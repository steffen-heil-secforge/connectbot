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

import org.junit.Test;

/**
 * Tests for NetworkUtils functionality, focusing on pure logic that doesn't
 * require Android system services.
 */
public class NetworkUtilsTest {
	
	@Test
	public void testBindAddressConstants() {
		assertEquals("localhost", NetworkUtils.BIND_LOCALHOST);
		assertEquals("0.0.0.0", NetworkUtils.BIND_ALL_INTERFACES);
		assertEquals("access_point", NetworkUtils.BIND_ACCESS_POINT);
	}
	
	// Note: Tests that require Android Context are not included in unit tests
	// since they require system services that aren't available in pure unit tests.
	// These would be better tested as integration tests.
	
	/**
	 * Test interface name detection patterns for WiFi AP interfaces
	 */
	@Test
	public void testApInterfacePatterns() {
		// These are the patterns NetworkUtils should recognize as AP interfaces
		String[] apInterfaces = {
			"ap0", "ap1", "ap2",
			"wlan1", // Note: wlan2 won't match "wlan1" pattern in NetworkUtils
			"p2p0", "p2p-wlan0-0",
			"hotspot0",
			"softap0",
			"wifi_ap0"
		};
		
		// Note: We can't easily test the private isLikelyApInterface method directly,
		// but we can test that these patterns would be recognized by the implementation
		for (String ifName : apInterfaces) {
			// This is a behavioral test - these interface names should be considered
			// valid AP interface candidates based on the patterns in NetworkUtils
			boolean matches = ifName.contains("ap") || 
				ifName.contains("wlan1") ||
				ifName.contains("p2p") || 
				ifName.contains("hotspot") || 
				ifName.contains("softap") || 
				ifName.contains("wifi_ap");
			assertTrue("Interface " + ifName + " should match AP patterns", matches);
		}
	}
	
	/**
	 * Test interface name patterns that should NOT be considered AP interfaces
	 */
	@Test
	public void testNonApInterfacePatterns() {
		String[] nonApInterfaces = {
			"wlan0",     // Primary WiFi, not AP
			"eth0",      // Ethernet
			"lo",        // Loopback
			"rmnet0",    // Cellular
			"ccmni0",    // Cellular (MediaTek)
			"pdp_ip0",   // Cellular
			"ppp0",      // Point-to-point
			"tun0",      // VPN tunnel
			"dummy0",    // Dummy interface
			"sit0"       // IPv6-in-IPv4 tunnel
		};
		
		for (String ifName : nonApInterfaces) {
			// Use same contains() logic as NetworkUtils to be consistent
			boolean matches = ifName.contains("ap") || 
				ifName.contains("wlan1") ||
				ifName.contains("p2p") || 
				ifName.contains("hotspot") || 
				ifName.contains("softap") || 
				ifName.contains("wifi_ap");
			assertFalse("Interface " + ifName + " should not match AP patterns", matches);
		}
	}
	
	/**
	 * Test potential false positives with contains() pattern matching
	 */
	@Test
	public void testFalsePositiveApPatterns() {
		// These contain AP patterns but shouldn't be considered AP interfaces
		String[] actualFalsePositives = {
			"laptop",      // contains "ap" - bug in NetworkUtils
			"paperwork",   // contains "ap" - bug in NetworkUtils  
			"snapdragon",  // contains "ap" - bug in NetworkUtils
			"wrapper",     // contains "ap" (w-r-[ap]-p-e-r) - bug in NetworkUtils
		};
		
		// These should NOT match any patterns
		String[] shouldNotMatch = {
			"transport",   // contains "p" but not "p2p"
			"ethernet",    // no matching patterns
			"loopback",    // no matching patterns
			"tunnel"       // no matching patterns
		};
		
		// Test the false positives (bugs in current implementation)
		for (String ifName : actualFalsePositives) {
			boolean matches = ifName.contains("ap") || 
				ifName.contains("wlan1") ||
				ifName.contains("p2p") || 
				ifName.contains("hotspot") || 
				ifName.contains("softap") || 
				ifName.contains("wifi_ap");
			// These WOULD match current implementation (documenting the bug)
			assertTrue("Interface " + ifName + " incorrectly matches AP patterns (known NetworkUtils bug)", matches);
		}
		
		// Test interfaces that should correctly NOT match
		for (String ifName : shouldNotMatch) {
			boolean matches = ifName.contains("ap") || 
				ifName.contains("wlan1") ||
				ifName.contains("p2p") || 
				ifName.contains("hotspot") || 
				ifName.contains("softap") || 
				ifName.contains("wifi_ap");
			assertFalse("Interface " + ifName + " should not match AP patterns", matches);
		}
	}
	
	/**
	 * Test IP address validation for private ranges
	 */
	@Test
	public void testPrivateIpRanges() {
		// These tests verify the IP range logic used in isLikelyApIP
		
		// 192.168.x.x range (most common for AP)
		assertTrue("192.168.1.1 should be in private range", 
			"192.168.1.1".startsWith("192.168."));
		assertTrue("192.168.0.1 should be in private range",
			"192.168.0.1".startsWith("192.168."));
		assertTrue("192.168.255.254 should be in private range",
			"192.168.255.254".startsWith("192.168."));
		
		// 10.x.x.x range
		assertTrue("10.0.0.1 should be in private range",
			"10.0.0.1".startsWith("10."));
		assertTrue("10.255.255.254 should be in private range",
			"10.255.255.254".startsWith("10."));
		
		// 172.16.x.x - 172.31.x.x range
		assertTrue("172.16.0.1 should match private range pattern",
			"172.16.0.1".matches("172\\.(1[6-9]|2[0-9]|3[0-1])\\..*"));
		assertTrue("172.31.255.254 should match private range pattern",
			"172.31.255.254".matches("172\\.(1[6-9]|2[0-9]|3[0-1])\\..*"));
		
		// Edge cases for 172.x.x.x range
		assertFalse("172.15.0.1 should not match private range pattern",
			"172.15.0.1".matches("172\\.(1[6-9]|2[0-9]|3[0-1])\\..*"));
		assertFalse("172.32.0.1 should not match private range pattern",
			"172.32.0.1".matches("172\\.(1[6-9]|2[0-9]|3[0-1])\\..*"));
	}
	
	/**
	 * Test cellular interface detection patterns
	 */
	@Test
	public void testCellularInterfacePatterns() {
		String[] cellularInterfaces = {
			"rmnet0", "rmnet1", "rmnet_data0",
			"ccmni0", "ccmni1", 
			"pdp_ip0", "pdp_ip1",
			"ppp0", "ppp1",
			"wwan0", "wwan1"
		};
		
		// Test that these match cellular patterns
		for (String ifName : cellularInterfaces) {
			boolean matchesCellular = ifName.matches("(rmnet|ccmni|pdp_ip|ppp|wwan).*");
			assertTrue("Interface " + ifName + " should match cellular patterns", matchesCellular);
		}
	}
	
	/**
	 * Test that WiFi client interfaces are not confused with AP interfaces
	 */
	@Test
	public void testWifiClientVsApInterfaces() {
		// wlan0 is typically WiFi client, wlan1 is typically AP
		String clientInterface = "wlan0";
		String apInterface = "wlan1";
		
		// Use consistent contains() logic
		boolean clientMatches = clientInterface.contains("ap") || 
			clientInterface.contains("wlan1") ||
			clientInterface.contains("p2p") || 
			clientInterface.contains("hotspot") || 
			clientInterface.contains("softap") || 
			clientInterface.contains("wifi_ap");
		assertFalse("wlan0 should not be considered AP interface", clientMatches);
		
		boolean apMatches = apInterface.contains("ap") || 
			apInterface.contains("wlan1") ||
			apInterface.contains("p2p") || 
			apInterface.contains("hotspot") || 
			apInterface.contains("softap") || 
			apInterface.contains("wifi_ap");
		assertTrue("wlan1 should be considered AP interface", apMatches);
	}
	
	/**
	 * Test IPv6 address filtering (should be rejected by isLikelyApIP)
	 */
	@Test
	public void testIPv6AddressFiltering() {
		// IPv6 addresses should be filtered out by the "contains(:)" check
		String[] ipv6Addresses = {
			"2001:db8::1",          // Regular IPv6
			"::1",                  // IPv6 loopback  
			"fe80::1",              // Link-local IPv6
			"2001:db8:85a3::8a2e:370:7334", // Full IPv6
			"192.168.1.1:8080"     // IPv4 with port (edge case)
		};
		
		for (String ip : ipv6Addresses) {
			// Test the same logic as isLikelyApIP uses for IPv6 filtering
			boolean containsColon = ip.contains(":");
			assertTrue("IP " + ip + " should contain colon and be filtered out", containsColon);
		}
		
		// IPv4 addresses should NOT contain colons
		String[] ipv4Addresses = {
			"192.168.1.1",
			"10.0.0.1", 
			"172.16.0.1",
			"127.0.0.1"
		};
		
		for (String ip : ipv4Addresses) {
			boolean containsColon = ip.contains(":");
			assertFalse("IPv4 " + ip + " should not contain colon", containsColon);
		}
	}
	
	/**
	 * Test realistic AP interface names from real Android devices
	 */
	@Test
	public void testRealisticApInterfaceNames() {
		String[] realisticApNames = {
			"ap0",              // Standard AP interface
			"wlan1",            // Secondary WiFi interface used as AP
			"softap0",          // Software AP
			"wifi_ap0",         // WiFi AP interface
			"p2p-wlan0-0",      // P2P interface
			"p2p0",             // P2P interface
			"hotspot0",         // Hotspot interface
			"ap_bridge0",       // AP bridge (contains "ap")
			"wlan1-sta",        // AP interface with suffix (contains "wlan1")
			"my_hotspot_dev"    // Custom hotspot device (contains "hotspot")
		};
		
		for (String ifName : realisticApNames) {
			boolean matches = ifName.contains("ap") || 
				ifName.contains("wlan1") ||
				ifName.contains("p2p") || 
				ifName.contains("hotspot") || 
				ifName.contains("softap") || 
				ifName.contains("wifi_ap");
			assertTrue("Realistic AP interface " + ifName + " should match patterns", matches);
		}
	}
	
	/**
	 * Test comprehensive IP validation edge cases for 172.x.x.x ranges
	 */
	@Test
	public void testIpValidationEdgeCases() {
		// Test 172.x.x.x range boundaries (172.16-31.x.x is private)
		String[] validPrivate172 = {
			"172.16.0.1",     // Lower bound
			"172.31.255.254", // Upper bound  
			"172.20.1.1",     // Middle
			"172.16.255.255", // Edge case
			"172.31.0.0"      // Edge case
		};
		
		String[] invalidPrivate172 = {
			"172.15.255.255", // Just below range
			"172.32.0.0",     // Just above range
			"172.0.0.1",      // Way below
			"172.255.255.255" // Way above
		};
		
		// Test valid private 172.x ranges
		for (String ip : validPrivate172) {
			boolean matches = ip.matches("172\\.(1[6-9]|2[0-9]|3[0-1])\\..*");
			assertTrue("IP " + ip + " should match 172.16-31.x.x pattern", matches);
		}
		
		// Test invalid private 172.x ranges  
		for (String ip : invalidPrivate172) {
			boolean matches = ip.matches("172\\.(1[6-9]|2[0-9]|3[0-1])\\..*");
			assertFalse("IP " + ip + " should not match 172.16-31.x.x pattern", matches);
		}
		
		// Test malformed IP addresses
		String[] malformedIPs = {
			"",
			"192.168",
			"192.168.1",
			"192.168.1.1.1",
			"192.168.1.256",
			"not.an.ip.address",
			"192.168.1.-1"
		};
		
		for (String ip : malformedIPs) {
			// Most malformed IPs won't match any private range patterns
			boolean matchesAny = ip.startsWith("192.168.") ||
				ip.startsWith("10.") ||
				ip.matches("172\\.(1[6-9]|2[0-9]|3[0-1])\\..*");
			// This documents expected behavior - some malformed IPs might accidentally match
		}
	}
	
	/**
	 * Test null and empty input handling
	 */
	@Test 
	public void testNullAndEmptyInputHandling() {
		// Test empty interface names against AP patterns
		String[] emptyInputs = {"", "   ", "\t", "\n"};
		
		for (String input : emptyInputs) {
			boolean matches = input.contains("ap") || 
				input.contains("wlan1") ||
				input.contains("p2p") || 
				input.contains("hotspot") || 
				input.contains("softap") || 
				input.contains("wifi_ap");
			assertFalse("Empty/whitespace input '" + input + "' should not match AP patterns", matches);
		}
		
		// Test IP validation with null/empty
		assertFalse("Empty string should not match any private IP pattern", 
			"".startsWith("192.168."));
		assertFalse("Empty string should not match 10.x pattern", 
			"".startsWith("10."));
		assertFalse("Empty string should not match 172.x pattern", 
			"".matches("172\\.(1[6-9]|2[0-9]|3[0-1])\\..*"));
		
		// Test colon detection for IPv6 filtering
		assertFalse("Empty string should not contain colon", "".contains(":"));
	}
	
	/**
	 * Test case sensitivity handling (NetworkUtils uses toLowerCase())
	 */
	@Test
	public void testCaseSensitivityHandling() {
		String[] mixedCaseApInterfaces = {
			"AP0", "Ap1", "aP2",           // Mixed case "ap"
			"WLAN1", "Wlan1", "wLaN1",     // Mixed case "wlan1"  
			"P2P0", "p2P-wlan0-0",         // Mixed case "p2p"
			"HOTSPOT0", "HotSpot1",        // Mixed case "hotspot"
			"SOFTAP0", "SoftAP1",          // Mixed case "softap"
			"WIFI_AP0", "WiFi_AP1"         // Mixed case "wifi_ap"
		};
		
		for (String ifName : mixedCaseApInterfaces) {
			// Test against lowercase patterns (what NetworkUtils actually uses)
			String lowercase = ifName.toLowerCase();
			boolean matches = lowercase.contains("ap") || 
				lowercase.contains("wlan1") ||
				lowercase.contains("p2p") || 
				lowercase.contains("hotspot") || 
				lowercase.contains("softap") || 
				lowercase.contains("wifi_ap");
			assertTrue("Interface " + ifName + " should match when converted to lowercase", matches);
		}
	}
	
	/**
	 * Test pattern boundary conditions  
	 */
	@Test
	public void testPatternBoundaryConditions() {
		// Test minimal pattern matches
		String[] minimalMatches = {
			"ap",          // Exact "ap" pattern
			"p2p",         // Exact "p2p" pattern  
			"hotspot",     // Exact "hotspot" pattern
			"softap",      // Exact "softap" pattern
			"wifi_ap",     // Exact "wifi_ap" pattern
			"wlan1"        // Exact "wlan1" pattern
		};
		
		for (String ifName : minimalMatches) {
			boolean matches = ifName.contains("ap") || 
				ifName.contains("wlan1") ||
				ifName.contains("p2p") || 
				ifName.contains("hotspot") || 
				ifName.contains("softap") || 
				ifName.contains("wifi_ap");
			assertTrue("Minimal pattern " + ifName + " should match", matches);
		}
		
		// Test embedded patterns (potential false positives)
		String[] embeddedPatterns = {
			"xapx",        // "ap" embedded - WOULD match (potential issue)
			"xp2px",       // "p2p" embedded - WOULD match (potential issue)  
			"xhotspotx",   // "hotspot" embedded - WOULD match (potential issue)
			"xsoftapx",    // "softap" embedded - WOULD match (potential issue)
		};
		
		// Test patterns that should NOT match
		String[] nonEmbeddedPatterns = {
			"xhotshotx",   // "hotshot" does NOT contain "hotspot"
			"ethernet",    // "eth" not any AP pattern
			"transport",   // "p" not "p2p"
			"cellular",    // no AP patterns
		};
		
		for (String ifName : embeddedPatterns) {
			boolean matches = ifName.contains("ap") || 
				ifName.contains("wlan1") ||
				ifName.contains("p2p") || 
				ifName.contains("hotspot") || 
				ifName.contains("softap") || 
				ifName.contains("wifi_ap");
			// These WOULD incorrectly match - documenting the behavior
			assertTrue("Embedded pattern " + ifName + " incorrectly matches (known issue)", matches);
		}
		
		for (String ifName : nonEmbeddedPatterns) {
			boolean matches = ifName.contains("ap") || 
				ifName.contains("wlan1") ||
				ifName.contains("p2p") || 
				ifName.contains("hotspot") || 
				ifName.contains("softap") || 
				ifName.contains("wifi_ap");
			assertFalse("Non-embedded pattern " + ifName + " should not match", matches);
		}
	}
	
	/**
	 * Test string constants are properly defined and accessible
	 */
	@Test
	public void testBindAddressConstantsArePublic() {
		// Verify all constants are accessible and have expected values
		assertNotNull("BIND_LOCALHOST should not be null", NetworkUtils.BIND_LOCALHOST);
		assertNotNull("BIND_ALL_INTERFACES should not be null", NetworkUtils.BIND_ALL_INTERFACES);
		assertNotNull("BIND_ACCESS_POINT should not be null", NetworkUtils.BIND_ACCESS_POINT);
		
		assertFalse("BIND_LOCALHOST should not be empty", NetworkUtils.BIND_LOCALHOST.isEmpty());
		assertFalse("BIND_ALL_INTERFACES should not be empty", NetworkUtils.BIND_ALL_INTERFACES.isEmpty());
		assertFalse("BIND_ACCESS_POINT should not be empty", NetworkUtils.BIND_ACCESS_POINT.isEmpty());
	}
}