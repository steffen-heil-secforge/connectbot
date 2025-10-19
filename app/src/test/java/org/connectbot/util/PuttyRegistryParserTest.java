/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2007 Kenny Root, Jeffrey Sharkey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.connectbot.util;

import static org.junit.Assert.*;

import org.connectbot.bean.HostBean;
import org.connectbot.bean.PortForwardBean;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests for PuttyRegistryParser functionality.
 */
public class PuttyRegistryParserTest {

	// Helper methods for test setup
	private String createRegistryContent(String sessionEntries) {
		return "Windows Registry Editor Version 5.00\n\n" + sessionEntries;
	}
	
	private String createSessionEntry(String sessionName, String hostname, String username, String port, String protocol) {
		StringBuilder entry = new StringBuilder();
		entry.append("[HKEY_CURRENT_USER\\Software\\SimonTatham\\PuTTY\\Sessions\\").append(sessionName).append("]\n");
		if (hostname != null) entry.append("\"HostName\"=\"").append(hostname).append("\"\n");
		if (username != null) entry.append("\"UserName\"=\"").append(username).append("\"\n");
		if (port != null) entry.append("\"PortNumber\"=").append(port).append("\n");
		if (protocol != null) entry.append("\"Protocol\"=\"").append(protocol).append("\"\n");
		return entry.toString();
	}
	
	private String createSessionEntry(String sessionName, String hostname, String protocol) {
		return createSessionEntry(sessionName, hostname, null, null, protocol);
	}
	
	private PuttyRegistryParser.ParseResult parseRegistry(String registryContent) {
		PuttyRegistryParser parser = new PuttyRegistryParser();
		ByteArrayInputStream inputStream = new ByteArrayInputStream(registryContent.getBytes(StandardCharsets.UTF_8));
		return parser.parseRegistryFile(inputStream, registryContent.length());
	}
	
	private void assertValidSession(HostBean session, String nickname, String hostname, String username, int port, String protocol) {
		assertEquals(nickname, session.getNickname());
		assertEquals(hostname, session.getHostname());
		if (username != null) {
			assertEquals(username, session.getUsername());
		}
		assertEquals(port, session.getPort());
		assertEquals(protocol, session.getProtocol());
	}
	
	private void assertSuccessfulParse(PuttyRegistryParser.ParseResult result, int expectedSessions) {
		assertNotNull(result);
		assertEquals(0, result.getErrors().size());
		assertEquals(expectedSessions, result.getValidSessions().size());
	}

	@Test
	public void testParseBasicSession() throws Exception {
		String sessionEntry = createSessionEntry("test-session", "example.com", "testuser", "dword:00000016", "ssh");
		String registryContent = createRegistryContent(sessionEntry);
		PuttyRegistryParser.ParseResult result = parseRegistry(registryContent);

		assertSuccessfulParse(result, 1);
		assertValidSession(result.getValidSessions().get(0), "test-session", "example.com", "testuser", 22, "ssh");
	}

	@Test
	public void testParseSessionWithSpacesInName() throws Exception {
		String sessionEntry = createSessionEntry("My%20Server", "myserver.com", "ssh");
		String registryContent = createRegistryContent(sessionEntry);
		PuttyRegistryParser.ParseResult result = parseRegistry(registryContent);

		assertSuccessfulParse(result, 1);
		assertEquals("My Server", result.getValidSessions().get(0).getNickname());
	}

	@Test
	public void testParseMultipleSessions() throws Exception {
		String server1Entry = createSessionEntry("server1", "server1.com", "ssh");
		String server2Entry = createSessionEntry("server2", "server2.com", "ssh");
		String registryContent = createRegistryContent(server1Entry + "\n" + server2Entry);
		PuttyRegistryParser.ParseResult result = parseRegistry(registryContent);

		assertSuccessfulParse(result, 2);
		
		// Find sessions by name since order is not guaranteed
		HostBean server1 = findSessionByName(result.getValidSessions(), "server1");
		HostBean server2 = findSessionByName(result.getValidSessions(), "server2");
		
		assertNotNull("server1 session should be found", server1);
		assertNotNull("server2 session should be found", server2);
		
		assertValidSession(server1, "server1", "server1.com", null, 22, "ssh");
		assertValidSession(server2, "server2", "server2.com", null, 22, "ssh");
	}
	
	private HostBean findSessionByName(List<HostBean> sessions, String name) {
		return sessions.stream().filter(s -> name.equals(s.getNickname())).findFirst().orElse(null);
	}
	
	private long getHostId(PortForwardBean forward) {
		try {
			// Use reflection to access private hostId field since there's no getter in older versions
			java.lang.reflect.Field field = PortForwardBean.class.getDeclaredField("hostId");
			field.setAccessible(true);
			return field.getLong(forward);
		} catch (Exception e) {
			// Fallback - this should not happen in normal usage
			return -1;
		}
	}

	@Test
	public void testParsePortForwards() throws Exception {
		String sessionEntry = createSessionEntry("test", "example.com", null, null, "ssh") +
			"\"PortForwardings\"=\"L8080=localhost:80,R9090=0.0.0.0:443,D1080\"\n";
		String registryContent = createRegistryContent(sessionEntry);
		PuttyRegistryParser.ParseResult result = parseRegistry(registryContent);

		assertSuccessfulParse(result, 1);
		
		// Test port forward parsing separately since HostBean doesn't contain port forwards directly
		PuttyRegistryParser parser = new PuttyRegistryParser();
		List<PortForwardBean> portForwards = parser.parsePortForwards(1L, "L8080=localhost:80,R9090=0.0.0.0:443,D1080");
		assertEquals(3, portForwards.size());

		// Find each port forward by type since order may vary
		PortForwardBean local = findPortForwardByTypeAndPort(portForwards, HostDatabase.PORTFORWARD_LOCAL, 8080);
		PortForwardBean remote = findPortForwardByTypeAndPort(portForwards, HostDatabase.PORTFORWARD_REMOTE, 9090);
		PortForwardBean dynamic = findPortForwardByTypeAndPort(portForwards, HostDatabase.PORTFORWARD_DYNAMIC5, 1080);

		assertValidPortForward(local, HostDatabase.PORTFORWARD_LOCAL, 8080, "localhost", 80);
		assertValidPortForward(remote, HostDatabase.PORTFORWARD_REMOTE, 9090, "0.0.0.0", 443);
		assertValidDynamicPortForward(dynamic, 1080);
	}
	
	private PortForwardBean findPortForwardByTypeAndPort(List<PortForwardBean> portForwards, String type, int port) {
		return portForwards.stream()
			.filter(pf -> type.equals(pf.getType()) && pf.getSourcePort() == port)
			.findFirst().orElse(null);
	}
	
	private void assertValidPortForward(PortForwardBean pf, String type, int sourcePort, String destAddr, int destPort) {
		assertNotNull(pf);
		assertEquals(type, pf.getType());
		assertEquals(sourcePort, pf.getSourcePort());
		assertEquals(destAddr, pf.getDestAddr());
		assertEquals(destPort, pf.getDestPort());
	}
	
	private void assertValidDynamicPortForward(PortForwardBean pf, int sourcePort) {
		assertNotNull(pf);
		assertEquals(HostDatabase.PORTFORWARD_DYNAMIC5, pf.getType());
		assertEquals(sourcePort, pf.getSourcePort());
		assertNull(pf.getDestAddr());
		assertEquals(0, pf.getDestPort());
	}

	@Test
	public void testParseWithBOMUtf8() throws Exception {
		String sessionEntry = createSessionEntry("test", "example.com", "ssh");
		String content = createRegistryContent(sessionEntry);
		
		byte[] withBOM = addUtf8BOM(content.getBytes(StandardCharsets.UTF_8));

		PuttyRegistryParser parser = new PuttyRegistryParser();
		ByteArrayInputStream inputStream = new ByteArrayInputStream(withBOM);
		PuttyRegistryParser.ParseResult result = parser.parseRegistryFile(inputStream, withBOM.length);

		assertSuccessfulParse(result, 1);
		assertValidSession(result.getValidSessions().get(0), "test", "example.com", null, 22, "ssh");
	}
	
	private byte[] addUtf8BOM(byte[] contentBytes) {
		byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
		byte[] withBOM = new byte[bom.length + contentBytes.length];
		System.arraycopy(bom, 0, withBOM, 0, bom.length);
		System.arraycopy(contentBytes, 0, withBOM, bom.length, contentBytes.length);
		return withBOM;
	}

	@Test
	public void testParseCustomPortNumber() throws Exception {
		String sessionEntry = createSessionEntry("test", "example.com", null, "dword:00008b80", "ssh");
		String registryContent = createRegistryContent(sessionEntry);
		PuttyRegistryParser.ParseResult result = parseRegistry(registryContent);

		assertSuccessfulParse(result, 1);
		assertEquals(35712, result.getValidSessions().get(0).getPort());
	}

	@Test
	public void testParseEmptyFile() throws Exception {
		PuttyRegistryParser.ParseResult result = parseRegistry("");
		
		assertFailedParse(result, 0);
	}
	
	private void assertFailedParse(PuttyRegistryParser.ParseResult result, int expectedSessions) {
		assertNotNull(result);
		assertEquals(expectedSessions, result.getValidSessions().size());
		assertTrue("Should have errors", result.getErrors().size() > 0);
	}

	@Test
	public void testParseInvalidRegistryFile() throws Exception {
		PuttyRegistryParser.ParseResult result = parseRegistry("This is not a registry file\n");
		
		assertFailedParse(result, 0);
	}

	@Test
	public void testParseNonPuttyRegistry() throws Exception {
		String nonPuttyEntry = "[HKEY_CURRENT_USER\\Software\\SomeOtherApp\\Sessions\\test]\n" +
			"\"HostName\"=\"example.com\"\n";
		String registryContent = createRegistryContent(nonPuttyEntry);
		PuttyRegistryParser.ParseResult result = parseRegistry(registryContent);
		
		assertFailedParse(result, 0);
	}

	@Test
	public void testParseSessionWithMissingHostname() throws Exception {
		String sessionEntry = createSessionEntry("test", null, "testuser", null, "ssh");
		String registryContent = createRegistryContent(sessionEntry);
		PuttyRegistryParser.ParseResult result = parseRegistry(registryContent);
		
		assertFailedParse(result, 0);
	}

	@Test
	public void testParseSessionWithDefaultSession() throws Exception {
		String sessionEntry = createSessionEntry("Default%20Settings", "", "ssh");
		String registryContent = createRegistryContent(sessionEntry);
		PuttyRegistryParser.ParseResult result = parseRegistry(registryContent);
		
		assertFailedParse(result, 0);
	}

	@Test
	public void testURLDecoding() throws Exception {
		String sessionEntry = createSessionEntry("My%20Test%20Server%20%2B%20More", "example.com", "ssh");
		String registryContent = createRegistryContent(sessionEntry);
		PuttyRegistryParser.ParseResult result = parseRegistry(registryContent);

		assertSuccessfulParse(result, 1);
		assertEquals("My Test Server + More", result.getValidSessions().get(0).getNickname());
	}

	@Test
	public void testParseResultErrorHandling() throws Exception {
		PuttyRegistryParser.ParseResult result = new PuttyRegistryParser.ParseResult();
		
		assertEquals(0, result.getValidSessions().size());
		assertEquals(0, result.getErrors().size());
		assertEquals(0, result.getWarnings().size());
		assertFalse(result.isTruncated());
		
		result.addError("Test error");
		result.addWarning("Test warning");
		result.setTruncated(true);
		
		assertEquals(1, result.getErrors().size());
		assertEquals("Test error", result.getErrors().get(0));
		assertEquals(1, result.getWarnings().size());
		assertEquals("Test warning", result.getWarnings().get(0));
		assertTrue(result.isTruncated());
	}
	
	@Test
	public void testPortForwardIntegrationInParseResult() throws Exception {
		String sessionEntry = createSessionEntry("test-with-forwards", "example.com", "ssh") +
			"\"PortForwardings\"=\"L8080=localhost:80,R9090=0.0.0.0:443,D1080\"\n";
		String registryContent = createRegistryContent(sessionEntry);
		PuttyRegistryParser.ParseResult result = parseRegistry(registryContent);

		assertSuccessfulParse(result, 1);
		
		// Check that port forwards were parsed and stored in result
		assertEquals(1, result.getPortForwards().size());
		assertTrue(result.getPortForwards().containsKey("test-with-forwards"));
		
		PuttyRegistryParser parser = new PuttyRegistryParser();
		List<PortForwardBean> forwards = parser.getPortForwardsForSession(result, "test-with-forwards");
		assertEquals(3, forwards.size());
		
		// Verify each port forward type is present
		PortForwardBean local = findPortForwardByTypeAndPort(forwards, HostDatabase.PORTFORWARD_LOCAL, 8080);
		PortForwardBean remote = findPortForwardByTypeAndPort(forwards, HostDatabase.PORTFORWARD_REMOTE, 9090);
		PortForwardBean dynamic = findPortForwardByTypeAndPort(forwards, HostDatabase.PORTFORWARD_DYNAMIC5, 1080);
		
		assertNotNull(local);
		assertNotNull(remote);
		assertNotNull(dynamic);
	}
	
	@Test
	public void testPortForwardBindAddressValidation() throws Exception {
		PuttyRegistryParser parser = new PuttyRegistryParser();
		
		// Test IPv4 bind addresses
		List<PortForwardBean> forwards1 = parser.parsePortForwards(1L, "L192.168.1.1:8080=localhost:80");
		assertEquals(1, forwards1.size());
		assertEquals("192.168.1.1", forwards1.get(0).getBindAddress());
		
		// Test IPv6 preference with 6 prefix
		List<PortForwardBean> forwards2 = parser.parsePortForwards(1L, "6L[::1]:8080=localhost:80");
		assertEquals(1, forwards2.size());
		assertEquals("::1", forwards2.get(0).getBindAddress());
		
		// Test with no bind address specified (should default to localhost)
		List<PortForwardBean> forwards3 = parser.parsePortForwards(1L, "L8080=localhost:80");
		assertEquals(1, forwards3.size());
		assertEquals("localhost", forwards3.get(0).getBindAddress());
	}
	
	@Test
	public void testPortForwardCreationResult() throws Exception {
		PuttyRegistryParser parser = new PuttyRegistryParser();
		PuttyRegistryParser.ParseResult parseResult = new PuttyRegistryParser.ParseResult();
		
		// Create some test port forwards in the parse result
		List<PortForwardBean> testForwards = new ArrayList<>();
		testForwards.add(new PortForwardBean(-1, -1, "Test Local", "Local", 8080, "localhost", 80, "localhost"));
		testForwards.add(new PortForwardBean(-1, -1, "Test Remote", "Remote", 9090, "0.0.0.0", 443, "0.0.0.0"));
		parseResult.addPortForwards("test-session", testForwards);
		
		// Test creating port forwards for a saved host
		PuttyRegistryParser.PortForwardCreationResult result = parser.createPortForwardsForHost(parseResult, "test-session", 123L);
		
		assertFalse(result.hasErrors());
		assertEquals(2, result.created.size());
		
		// Verify the created port forwards have the correct host ID
		for (PortForwardBean forward : result.created) {
			assertEquals(123L, getHostId(forward));
			assertEquals(-1, forward.getId()); // Should be -1 to let database assign
		}
	}

	// Edge case tests for IPv6 port forward formats
	@Test
	public void testParsePortForwardsIPv6() throws Exception {
		PuttyRegistryParser parser = new PuttyRegistryParser();
		
		// Test IPv6 local port forward with brackets
		List<PortForwardBean> forwards1 = parser.parsePortForwards(1L, "6L[::1]:8080=localhost:80");
		assertEquals(1, forwards1.size());
		assertEquals("::1", forwards1.get(0).getBindAddress());
		assertEquals(HostDatabase.PORTFORWARD_LOCAL, forwards1.get(0).getType());
		assertEquals(8080, forwards1.get(0).getSourcePort());
		
		// Test IPv6 remote port forward destination with brackets
		List<PortForwardBean> forwards2 = parser.parsePortForwards(1L, "R9090=[::1]:443");
		assertEquals(1, forwards2.size());
		assertEquals("::1", forwards2.get(0).getDestAddr());
		assertEquals(443, forwards2.get(0).getDestPort());
		
		// Test IPv6 dynamic port forward
		List<PortForwardBean> forwards3 = parser.parsePortForwards(1L, "6D[ff::2]:1080");
		assertEquals(1, forwards3.size());
		assertEquals("ff::2", forwards3.get(0).getBindAddress());
		assertEquals(HostDatabase.PORTFORWARD_DYNAMIC5, forwards3.get(0).getType());
	}

	@Test
	public void testParsePortForwardsMalformed() throws Exception {
		PuttyRegistryParser parser = new PuttyRegistryParser();
		
		// Test malformed port forward entries
		List<PortForwardBean> forwards1 = parser.parsePortForwards(1L, "InvalidFormat");
		assertEquals(0, forwards1.size());
		
		// Test empty port forward
		List<PortForwardBean> forwards2 = parser.parsePortForwards(1L, "");
		assertEquals(0, forwards2.size());
		
		// Test invalid port numbers
		List<PortForwardBean> forwards3 = parser.parsePortForwards(1L, "L999999=localhost:80");
		assertEquals(0, forwards3.size());
		
		// Test missing destination in local/remote forward
		List<PortForwardBean> forwards4 = parser.parsePortForwards(1L, "L8080=");
		assertEquals(0, forwards4.size());
		
		// Test invalid IPv6 brackets
		List<PortForwardBean> forwards5 = parser.parsePortForwards(1L, "L[::1:8080=localhost:80");
		assertEquals(0, forwards5.size());
	}

	@Test
	public void testParsePortForwardsIPv6Complex() throws Exception {
		PuttyRegistryParser parser = new PuttyRegistryParser();
		
		// Test complex IPv6 addresses
		List<PortForwardBean> forwards1 = parser.parsePortForwards(1L, "6L[2001:db8::1]:8080=[2001:db8::2]:80");
		assertEquals(1, forwards1.size());
		assertEquals("2001:db8::1", forwards1.get(0).getBindAddress());
		assertEquals("2001:db8::2", forwards1.get(0).getDestAddr());
		
		// Test IPv6 wildcard binding
		List<PortForwardBean> forwards2 = parser.parsePortForwards(1L, "6L[::]:8080=localhost:80");
		assertEquals(1, forwards2.size());
		assertEquals("::", forwards2.get(0).getBindAddress());
	}

	// Edge case tests for file size limits and encoding
	@Test
	public void testParseFileSizeLimit() throws Exception {
		PuttyRegistryParser parser = new PuttyRegistryParser();
		
		// Test file size over 1MB limit
		long oversizeFile = 1024 * 1024 + 1; // 1MB + 1 byte
		ByteArrayInputStream inputStream = new ByteArrayInputStream(new byte[0]);
		PuttyRegistryParser.ParseResult result = parser.parseRegistryFile(inputStream, oversizeFile);
		
		assertFailedParse(result, 0);
		assertTrue(result.getErrors().get(0).contains("File too large"));
	}

	@Test
	public void testParseEncodingEdgeCases() throws Exception {
		PuttyRegistryParser parser = new PuttyRegistryParser();
		String sessionEntry = createSessionEntry("test", "example.com", "ssh");
		String content = createRegistryContent(sessionEntry);
		
		// Test UTF-16 Little Endian BOM
		byte[] utf16LE = addUtf16LEBOM(content.getBytes(StandardCharsets.UTF_16LE));
		ByteArrayInputStream inputStream1 = new ByteArrayInputStream(utf16LE);
		PuttyRegistryParser.ParseResult result1 = parser.parseRegistryFile(inputStream1, utf16LE.length);
		assertSuccessfulParse(result1, 1);
		
		// Test UTF-16 Big Endian BOM
		byte[] utf16BE = addUtf16BEBOM(content.getBytes(StandardCharsets.UTF_16BE));
		ByteArrayInputStream inputStream2 = new ByteArrayInputStream(utf16BE);
		PuttyRegistryParser.ParseResult result2 = parser.parseRegistryFile(inputStream2, utf16BE.length);
		assertSuccessfulParse(result2, 1);
	}

	private byte[] addUtf16LEBOM(byte[] contentBytes) {
		byte[] bom = {(byte) 0xFF, (byte) 0xFE};
		byte[] withBOM = new byte[bom.length + contentBytes.length];
		System.arraycopy(bom, 0, withBOM, 0, bom.length);
		System.arraycopy(contentBytes, 0, withBOM, bom.length, contentBytes.length);
		return withBOM;
	}

	private byte[] addUtf16BEBOM(byte[] contentBytes) {
		byte[] bom = {(byte) 0xFE, (byte) 0xFF};
		byte[] withBOM = new byte[bom.length + contentBytes.length];
		System.arraycopy(bom, 0, withBOM, 0, bom.length);
		System.arraycopy(contentBytes, 0, withBOM, bom.length, contentBytes.length);
		return withBOM;
	}

	// Edge case tests for session name validation
	@Test
	public void testParseSessionNameEdgeCases() throws Exception {
		// Test session names at length boundary (64 chars)
		String longValidName = "A".repeat(64);
		String sessionEntry1 = createSessionEntry(longValidName, "example.com", "ssh");
		String registryContent1 = createRegistryContent(sessionEntry1);
		PuttyRegistryParser.ParseResult result1 = parseRegistry(registryContent1);
		assertSuccessfulParse(result1, 1);
		assertEquals(longValidName, result1.getValidSessions().get(0).getNickname());
		
		// Test session name over 64 chars (should fail)
		String tooLongName = "A".repeat(65);
		String sessionEntry2 = createSessionEntry(tooLongName, "example.com", "ssh");
		String registryContent2 = createRegistryContent(sessionEntry2);
		PuttyRegistryParser.ParseResult result2 = parseRegistry(registryContent2);
		assertFailedParse(result2, 0);
		
		// Test session name with control characters (should fail)
		String sessionEntry3 = createSessionEntry("test\u0001control", "example.com", "ssh");
		String registryContent3 = createRegistryContent(sessionEntry3);
		PuttyRegistryParser.ParseResult result3 = parseRegistry(registryContent3);
		assertFailedParse(result3, 0);
		
		// Test session name with forbidden characters (should fail)
		String sessionEntry4 = createSessionEntry("test:forbidden", "example.com", "ssh");
		String registryContent4 = createRegistryContent(sessionEntry4);
		PuttyRegistryParser.ParseResult result4 = parseRegistry(registryContent4);
		assertFailedParse(result4, 0);
	}

	// Edge case tests for hostname validation
	@Test
	public void testParseHostnameEdgeCases() throws Exception {
		// Test hostname at length boundary (253 chars)
		String longValidHostname = "a".repeat(240) + ".example.com"; // 253 total
		String sessionEntry1 = createSessionEntry("test", longValidHostname, "ssh");
		String registryContent1 = createRegistryContent(sessionEntry1);
		PuttyRegistryParser.ParseResult result1 = parseRegistry(registryContent1);
		assertSuccessfulParse(result1, 1);
		
		// Test hostname over 253 chars (should fail)
		String tooLongHostname = "a".repeat(250) + ".example.com"; // 262 total
		String sessionEntry2 = createSessionEntry("test", tooLongHostname, "ssh");
		String registryContent2 = createRegistryContent(sessionEntry2);
		PuttyRegistryParser.ParseResult result2 = parseRegistry(registryContent2);
		assertFailedParse(result2, 0);
		
		// Test empty hostname (should fail)
		String sessionEntry3 = createSessionEntry("test", "", "ssh");
		String registryContent3 = createRegistryContent(sessionEntry3);
		PuttyRegistryParser.ParseResult result3 = parseRegistry(registryContent3);
		assertFailedParse(result3, 0);
		
		// Test IPv6 hostname with brackets
		String sessionEntry4 = createSessionEntry("test", "[::1]", "ssh");
		String registryContent4 = createRegistryContent(sessionEntry4);
		PuttyRegistryParser.ParseResult result4 = parseRegistry(registryContent4);
		assertSuccessfulParse(result4, 1);
	}

	// Edge case tests for error recovery scenarios
	@Test
	public void testParseErrorRecovery() throws Exception {
		// Test partially corrupted registry file that can still parse some sessions
		String validSession = createSessionEntry("valid", "example.com", "ssh");
		String corruptedSection = "[HKEY_CURRENT_USER\\Software\\SimonTatham\\PuTTY\\Sessions\\corrupted]\n" +
			"\"HostName\"=invalid_value_format\n" +
			"\"PortNumber\"=not_a_number\n";
		String anotherValidSession = createSessionEntry("valid2", "example2.com", "ssh");
		
		String registryContent = createRegistryContent(validSession + "\n" + corruptedSection + "\n" + anotherValidSession);
		PuttyRegistryParser.ParseResult result = parseRegistry(registryContent);
		
		// Should successfully parse the valid sessions despite corruption
		assertEquals(2, result.getValidSessions().size());
		assertTrue(result.getWarnings().size() > 0 || result.getErrors().size() == 0);
	}

	@Test
	public void testParseMaxSessionsLimit() throws Exception {
		// Test session limit enforcement (100 sessions max)
		StringBuilder manySessionsBuilder = new StringBuilder();
		for (int i = 1; i <= 105; i++) {
			manySessionsBuilder.append(createSessionEntry("session" + i, "example" + i + ".com", "ssh")).append("\n");
		}
		
		String registryContent = createRegistryContent(manySessionsBuilder.toString());
		PuttyRegistryParser.ParseResult result = parseRegistry(registryContent);
		
		// Should only parse first 100 sessions and be marked as truncated
		assertEquals(100, result.getValidSessions().size());
		assertTrue(result.isTruncated());
		assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("Too many sessions")));
	}

	@Test
	public void testParsePortEdgeCases() throws Exception {
		// Test port 1 (minimum valid)
		String sessionEntry1 = createSessionEntry("test1", "example.com", null, "dword:00000001", "ssh");
		String registryContent1 = createRegistryContent(sessionEntry1);
		PuttyRegistryParser.ParseResult result1 = parseRegistry(registryContent1);
		assertSuccessfulParse(result1, 1);
		assertEquals(1, result1.getValidSessions().get(0).getPort());
		
		// Test port 65535 (maximum valid)
		String sessionEntry2 = createSessionEntry("test2", "example.com", null, "dword:0000ffff", "ssh");
		String registryContent2 = createRegistryContent(sessionEntry2);
		PuttyRegistryParser.ParseResult result2 = parseRegistry(registryContent2);
		assertSuccessfulParse(result2, 1);
		assertEquals(65535, result2.getValidSessions().get(0).getPort());
		
		// Test port 0 (invalid - should use default)
		String sessionEntry3 = createSessionEntry("test3", "example.com", null, "dword:00000000", "ssh");
		String registryContent3 = createRegistryContent(sessionEntry3);
		PuttyRegistryParser.ParseResult result3 = parseRegistry(registryContent3);
		assertSuccessfulParse(result3, 1);
		assertEquals(22, result3.getValidSessions().get(0).getPort()); // Default SSH port
	}

	@Test
	public void testParseUsernameBoundaries() throws Exception {
		// Test username at 32 char boundary
		String validUsername = "A".repeat(32);
		String sessionEntry1 = createSessionEntry("test1", "example.com", validUsername, null, "ssh");
		String registryContent1 = createRegistryContent(sessionEntry1);
		PuttyRegistryParser.ParseResult result1 = parseRegistry(registryContent1);
		assertSuccessfulParse(result1, 1);
		assertEquals(validUsername, result1.getValidSessions().get(0).getUsername());
		
		// Test username over 32 chars (should be ignored)
		String tooLongUsername = "A".repeat(33);
		String sessionEntry2 = createSessionEntry("test2", "example.com", tooLongUsername, null, "ssh");
		String registryContent2 = createRegistryContent(sessionEntry2);
		PuttyRegistryParser.ParseResult result2 = parseRegistry(registryContent2);
		assertSuccessfulParse(result2, 1);
		assertNull(result2.getValidSessions().get(0).getUsername()); // Should be ignored
	}

	@Test
	public void testParseComplexPortForwardRule() throws Exception {
		// Test the specific failing case: L1022=192.168.168.128:22,L1023=192.168.168.128:5900,L64734=127.0.0.1:64734
		PuttyRegistryParser parser = new PuttyRegistryParser();
		List<PortForwardBean> portForwards = parser.parsePortForwards(1L, "L1022=192.168.168.128:22,L1023=192.168.168.128:5900,L64734=127.0.0.1:64734");

		// Should parse 3 port forwards
		assertEquals("Should parse 3 port forwards", 3, portForwards.size());

		// Check each port forward
		PortForwardBean pf1 = findPortForwardByTypeAndPort(portForwards, HostDatabase.PORTFORWARD_LOCAL, 1022);
		assertNotNull("Port forward 1022 should exist", pf1);
		assertValidPortForward(pf1, HostDatabase.PORTFORWARD_LOCAL, 1022, "192.168.168.128", 22);

		PortForwardBean pf2 = findPortForwardByTypeAndPort(portForwards, HostDatabase.PORTFORWARD_LOCAL, 1023);
		assertNotNull("Port forward 1023 should exist", pf2);
		assertValidPortForward(pf2, HostDatabase.PORTFORWARD_LOCAL, 1023, "192.168.168.128", 5900);

		PortForwardBean pf3 = findPortForwardByTypeAndPort(portForwards, HostDatabase.PORTFORWARD_LOCAL, 64734);
		assertNotNull("Port forward 64734 should exist", pf3);
		assertValidPortForward(pf3, HostDatabase.PORTFORWARD_LOCAL, 64734, "127.0.0.1", 64734);
	}
}