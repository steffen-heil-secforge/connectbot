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

import android.util.Log;

import org.connectbot.bean.HostBean;
import org.connectbot.bean.PortForwardBean;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for PuTTY Windows Registry export files.
 *
 * @author ConnectBot Team
 */
public class PuttyRegistryParser {
	private static final String TAG = "CB.PuttyRegistryParser";

	private static final long MAX_FILE_SIZE = 1024 * 1024; // 1MB
	private static final int MAX_SESSIONS = 100;
	private static final String PUTTY_SESSIONS_PATH = "HKEY_CURRENT_USER\\Software\\SimonTatham\\PuTTY\\Sessions\\";

	// Error and warning message constants
	private static final String ERROR_FILE_TOO_LARGE = "File too large (max 1MB)";
	private static final String ERROR_INVALID_REGISTRY_FILE = "Invalid or corrupted registry file";
	private static final String ERROR_FILE_READING = "File reading error";
	private static final String ERROR_NO_VALID_SESSIONS = "No valid SSH sessions found";
	private static final String WARNING_TOO_MANY_SESSIONS = "Too many sessions, imported first " + MAX_SESSIONS;
	private static final String WARNING_INVALID_SESSION_SKIPPED = "Invalid session data skipped: ";

	// Regex patterns for parsing
	private static final Pattern SECTION_PATTERN = Pattern.compile("^\\[(.+)\\]$");
	private static final Pattern VALUE_PATTERN = Pattern.compile("^\"([^\"]+)\"=(.+)$");
	private static final Pattern DWORD_PATTERN = Pattern.compile("^dword:([0-9a-fA-F]{8})$");
	private static final Pattern STRING_PATTERN = Pattern.compile("^\"(.*)\"$");

	// Cached regex patterns for validation
	private static final Pattern IPV4_PATTERN = Pattern.compile("^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$");
	private static final Pattern IPV6_PATTERN = Pattern.compile("^([0-9a-fA-F]{0,4}:){1,7}[0-9a-fA-F]{0,4}$");
	private static final Pattern HOSTNAME_ALPHANUMERIC_PATTERN = Pattern.compile("^[a-zA-Z0-9.-]+$");
	private static final Pattern HOSTNAME_IPV6_PATTERN = Pattern.compile("^\\[?[0-9a-fA-F:]+\\]?$");
	private static final Pattern FQDN_PATTERN = Pattern.compile("^[a-zA-Z0-9]([a-zA-Z0-9\\.-]*[a-zA-Z0-9])?$");

	/**
	 * Result of parsing a PuTTY registry file.
	 */
	public static class ParseResult implements Serializable {
		private List<HostBean> validSessions = new ArrayList<>();
		private List<String> errors = new ArrayList<>();
		private List<String> warnings = new ArrayList<>();
		private boolean truncated = false;
		private Map<String, List<PortForwardBean>> portForwards = new HashMap<>();

		public List<HostBean> getValidSessions() {
			return validSessions;
		}

		public List<String> getErrors() {
			return errors;
		}

		public List<String> getWarnings() {
			return warnings;
		}

		public boolean isTruncated() {
			return truncated;
		}

		public void addError(String error) {
			errors.add(error);
		}

		public void addWarning(String warning) {
			warnings.add(warning);
		}

		public void setTruncated(boolean truncated) {
			this.truncated = truncated;
		}

		public Map<String, List<PortForwardBean>> getPortForwards() {
			return portForwards;
		}

		public void addPortForwards(String sessionName, List<PortForwardBean> forwards) {
			if (forwards != null && !forwards.isEmpty()) {
				portForwards.put(sessionName, forwards);
			}
		}
	}

	/**
	 * Parse a PuTTY registry file from an InputStream.
	 */
	public ParseResult parseRegistryFile(InputStream inputStream, long fileSize) {
		ParseResult result = new ParseResult();

		if (fileSize > MAX_FILE_SIZE) {
			result.addError(ERROR_FILE_TOO_LARGE);
			return result;
		}

		try {
			String content = readFileContent(inputStream);
			if (content == null || !isValidContent(content)) {
				result.addError(ERROR_INVALID_REGISTRY_FILE);
				return result;
			}

			parseSessions(content, result);

		} catch (IOException e) {
			Log.e(TAG, "Error reading registry file", e);
			result.addError(ERROR_FILE_READING);
		} catch (Exception e) {
			Log.e(TAG, "Unexpected error parsing registry file", e);
			result.addError(ERROR_INVALID_REGISTRY_FILE);
		}

		return result;
	}

	/**
	 * Read file content with encoding detection and validation.
	 */
	private String readFileContent(InputStream inputStream) throws IOException {
		// Read all bytes first to avoid mark/reset issues with ContentResolver
		byte[] allBytes = readAllBytes(inputStream);
		if (allBytes.length == 0) {
			return "";
		}

		// Check for BOM
		Charset charset = Charset.forName("UTF-8"); // Default
		int bomLength = 0;

		if (allBytes.length >= 3 &&
			allBytes[0] == (byte) 0xEF && allBytes[1] == (byte) 0xBB && allBytes[2] == (byte) 0xBF) {
			// UTF-8 BOM (EF BB BF)
			charset = Charset.forName("UTF-8");
			bomLength = 3;
		} else if (allBytes.length >= 2) {
			if (allBytes[0] == (byte) 0xFF && allBytes[1] == (byte) 0xFE) {
				// UTF-16 Little Endian BOM (FF FE)
				charset = Charset.forName("UTF-16LE");
				bomLength = 2;
			} else if (allBytes[0] == (byte) 0xFE && allBytes[1] == (byte) 0xFF) {
				// UTF-16 Big Endian BOM (FE FF)
				charset = Charset.forName("UTF-16BE");
				bomLength = 2;
			}
		}

		// Decode with detected charset, skipping BOM
		try {
			byte[] contentBytes = new byte[allBytes.length - bomLength];
			System.arraycopy(allBytes, bomLength, contentBytes, 0, contentBytes.length);
			return new String(contentBytes, charset);
		} catch (Exception e) {
			// Fallback to UTF-8 without BOM
			try {
				return new String(allBytes, Charset.forName("UTF-8"));
			} catch (Exception e2) {
				// Last resort - system default
				return new String(allBytes);
			}
		}
	}

	/**
	 * Read all bytes from InputStream.
	 */
	private byte[] readAllBytes(InputStream inputStream) throws IOException {
		java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
		byte[] data = new byte[1024];
		int bytesRead;
		while ((bytesRead = inputStream.read(data, 0, data.length)) != -1) {
			buffer.write(data, 0, bytesRead);
		}
		return buffer.toByteArray();
	}

	/**
	 * Validate content and check for registry structure.
	 */
	private boolean isValidContent(String content) {
		return content != null && !content.trim().isEmpty() &&
			(content.contains("Windows Registry Editor") || content.contains("[HKEY_"));
	}

	/**
	 * Parse all sessions from content and add to result.
	 */
	private void parseSessions(String content, ParseResult result) {
		String[] lines = content.split("\n");
		String currentSection = null;
		Map<String, String> currentValues = null;
		Set<String> seenNames = new HashSet<>();
		int sessionCount = 0;

		for (String line : lines) {
			line = line.trim();
			if (line.isEmpty()) continue;

			// Check for section header
			Matcher sectionMatcher = SECTION_PATTERN.matcher(line);
			if (sectionMatcher.matches()) {
				// Process previous session if complete
				if (currentSection != null && currentValues != null) {
					processSession(currentSection, currentValues, result, seenNames, sessionCount);
					sessionCount++;
					if (sessionCount >= MAX_SESSIONS) {
						result.setTruncated(true);
						result.addWarning(WARNING_TOO_MANY_SESSIONS);
						break;
					}
				}

				// Start new session
				String section = sectionMatcher.group(1);
				if (section.startsWith(PUTTY_SESSIONS_PATH)) {
					String sessionName = section.substring(PUTTY_SESSIONS_PATH.length());
					try {
						currentSection = URLDecoder.decode(sessionName, "UTF-8");
						currentValues = new HashMap<>();
					} catch (Exception e) {
						Log.w(TAG, "Failed to decode session name: " + sessionName, e);
						currentSection = null;
						currentValues = null;
					}
				} else {
					currentSection = null;
					currentValues = null;
				}
				continue;
			}

			// Parse key-value pairs
			if (currentSection != null && currentValues != null) {
				Matcher valueMatcher = VALUE_PATTERN.matcher(line);
				if (valueMatcher.matches()) {
					String key = valueMatcher.group(1);
					String value = parseRegistryValue(valueMatcher.group(2));
					if (value != null) {
						currentValues.put(key, value);
					}
				}
			}
		}

		// Process final session
		if (currentSection != null && currentValues != null) {
			processSession(currentSection, currentValues, result, seenNames, sessionCount);
		}

		if (result.getValidSessions().isEmpty()) {
			result.addError(ERROR_NO_VALID_SESSIONS);
		}
	}

	/**
	 * Process a single session and add to result if valid.
	 */
	private void processSession(String sessionName, Map<String, String> values,
			ParseResult result, Set<String> seenNames, int sessionCount) {
		try {
			// Check for SSH protocol
			String protocol = values.get("Protocol");
			if (!"ssh".equals(protocol)) {
				return; // Skip non-SSH sessions
			}

			HostBean session = createHostBean(sessionName, values);
			if (session != null && !isDuplicate(session, seenNames)) {
				result.getValidSessions().add(session);

				// Parse and store port forwards for this session
				String portForwardings = values.get("PortForwardings");
				if (portForwardings != null && !portForwardings.isEmpty()) {
					List<PortForwardBean> forwards = parsePortForwardsForSession(sessionName, portForwardings);
					result.addPortForwards(sessionName, forwards);
				}
			}
		} catch (Exception e) {
			Log.w(TAG, "Failed to parse session: " + sessionName, e);
			result.addWarning(WARNING_INVALID_SESSION_SKIPPED + sessionName);
		}
	}

	/**
	 * Check for duplicate session names.
	 */
	private boolean isDuplicate(HostBean session, Set<String> seenNames) {
		String normalizedName = Normalizer.normalize(session.getNickname(), Normalizer.Form.NFC);
		if (seenNames.contains(normalizedName)) {
			return true;
		}
		seenNames.add(normalizedName);
		return false;
	}

	/**
	 * Parse a registry value (DWORD or string).
	 */
	private String parseRegistryValue(String value) {
		String dwordResult = parseDwordValue(value);
		if (dwordResult != null) {
			return dwordResult;
		}

		return parseStringValue(value);
	}

	/**
	 * Parse a DWORD registry value.
	 * Returns the decimal string representation or null if not a DWORD.
	 */
	private String parseDwordValue(String value) {
		Matcher dwordMatcher = DWORD_PATTERN.matcher(value);
		if (dwordMatcher.matches()) {
			try {
				long dwordValue = Long.parseLong(dwordMatcher.group(1), 16);
				return String.valueOf(dwordValue);
			} catch (NumberFormatException e) {
				return null;
			}
		}
		return null;
	}

	/**
	 * Parse a string registry value.
	 * Returns the unquoted string or null if not a quoted string.
	 */
	private String parseStringValue(String value) {
		Matcher stringMatcher = STRING_PATTERN.matcher(value);
		if (stringMatcher.matches()) {
			return stringMatcher.group(1);
		}
		return null;
	}

	/**
	 * Create a HostBean from session data.
	 */
	private HostBean createHostBean(String sessionName, Map<String, String> values) {
		// Validate session name
		if (!isValidSessionName(sessionName)) {
			return null;
		}

		HostBean session = new HostBean();
		session.setNickname(sessionName);
		session.setProtocol("ssh");

		// Parse basic connection info
		String hostname = values.get("HostName");
		if (!isValidHostname(hostname)) {
			return null;
		}
		session.setHostname(hostname);

		String username = values.get("UserName");
		if (username != null && isValidUsername(username)) {
			session.setUsername(username);
		}

		String portStr = values.get("PortNumber");
		if (portStr != null) {
			Integer port = safeParseInt(portStr);
			if (port != null && isValidPort(port)) {
				session.setPort(port);
			}
		}

		// Parse compression
		String compressionStr = values.get("Compression");
		if ("1".equals(compressionStr)) {
			session.setCompression(true);
		}

		// Parse authentication (store auth agent preference in useAuthAgent field)
		String tryAgentStr = values.get("TryAgent");
		if ("0".equals(tryAgentStr)) {
			session.setUseAuthAgent("no");
		} else {
			session.setUseAuthAgent("confirm");
		}

		// Note: PublicKeyFile and PortForwardings are not directly supported by HostBean
		// Port forwards would need to be created as separate PortForwardBean instances
		// and associated with the host after creation

		return session;
	}

	/**
	 * Parse port forwards from a port forwarding string (for testing and external use).
	 * @param hostId The host ID to associate with the port forwards
	 * @param portForwardings The port forwarding string to parse
	 * @return List of PortForwardBean objects
	 */
	public List<PortForwardBean> parsePortForwards(long hostId, String portForwardings) {
		List<PortForwardBean> portForwards = new ArrayList<>();
		if (portForwardings == null || portForwardings.isEmpty()) {
			return portForwards;
		}

		String[] forwards = portForwardings.split(",");
		for (String forward : forwards) {
			forward = forward.trim();
			if (forward.isEmpty()) continue;

			try {
				PortForwardBean pf = parsePortForward(hostId, forward);
				if (pf != null) {
					portForwards.add(pf);
				}
			} catch (Exception e) {
				Log.w(TAG, "Failed to parse port forward: " + forward, e);
			}
		}
		return portForwards;
	}

	/**
	 * Parse port forwards for a session during initial parsing.
	 * This version doesn't require a host ID since the session isn't saved yet.
	 */
	private List<PortForwardBean> parsePortForwardsForSession(String sessionName, String portForwardings) {
		List<PortForwardBean> portForwards = new ArrayList<>();
		if (portForwardings == null || portForwardings.isEmpty()) {
			return portForwards;
		}

		String[] forwards = portForwardings.split(",");
		for (String forward : forwards) {
			forward = forward.trim();
			if (forward.isEmpty()) continue;

			try {
				PortForwardBean pf = parsePortForward(-1L, forward); // Use -1 as placeholder
				if (pf != null) {
					portForwards.add(pf);
				}
			} catch (Exception e) {
				Log.w(TAG, "Failed to parse port forward for session " + sessionName + ": " + forward, e);
			}
		}
		return portForwards;
	}


	/**
	 * Parse a single port forward entry.
	 */
	private PortForwardBean parsePortForward(long hostId, String forward) {
		if (forward == null || forward.trim().isEmpty()) {
			return null;
		}

		forward = forward.trim();

		// Extract IPv6 preference and port forward type
		PortForwardConfig config = parsePortForwardPrefix(forward);
		if (config == null) {
			return null;
		}

		// Parse based on forward type
		if ("Dynamic (SOCKS)".equals(config.typeStr)) {
			return parseDynamicPortForward(hostId, config.forwardSpec, config.typeStr, config.ipv6Preferred);
		} else {
			return parseLocalRemotePortForward(hostId, config.forwardSpec, config.typeStr, config.ipv6Preferred);
		}
	}

	/**
	 * Configuration extracted from port forward prefix.
	 */
	private static class PortForwardConfig {
		final String forwardSpec;
		final String typeStr;
		final boolean ipv6Preferred;

		PortForwardConfig(String forwardSpec, String typeStr, boolean ipv6Preferred) {
			this.forwardSpec = forwardSpec;
			this.typeStr = typeStr;
			this.ipv6Preferred = ipv6Preferred;
		}
	}

	/**
	 * Parse the IPv6 preference and port forward type from the prefix.
	 */
	private PortForwardConfig parsePortForwardPrefix(String forward) {
		// Extract and validate IPv4/IPv6 preference
		boolean ipv6Preferred = false;
		if (forward.startsWith("4") || forward.startsWith("6")) {
			ipv6Preferred = forward.startsWith("6");
			forward = forward.substring(1);
		}

		if (forward.length() == 0) {
			return null;
		}

		char type = forward.charAt(0);
		String typeStr;
		switch (type) {
			case 'L':
				typeStr = "Local";
				break;
			case 'R':
				typeStr = "Remote";
				break;
			case 'D':
				typeStr = "Dynamic (SOCKS)";
				break;
			default:
				return null;
		}

		String forwardSpec = forward.substring(1); // Remove type character
		return new PortForwardConfig(forwardSpec, typeStr, ipv6Preferred);
	}

	/**
	 * Parse a dynamic port forward (SOCKS proxy).
	 * Format: D[<bindIP>:]<port>
	 * Examples: D1080, D1.2.3.4:1080, D[ff::1]:1080
	 */
	private PortForwardBean parseDynamicPortForward(long hostId, String forwardSpec, String typeStr, boolean ipv6Preferred) {
		String bindIP = null;
		int sourcePort;

		String[] bindParts = parseBindIPAndPort(forwardSpec);
		if (bindParts != null && bindParts.length == 2) {
			bindIP = bindParts[0];
			Integer port = safeParseInt(bindParts[1]);
			if (port != null) {
				sourcePort = port;
				if (!isValidHostname(bindIP)) {
					bindIP = null; // Invalid bind IP, ignore
				}
			} else {
				return null;
			}
		} else {
			Integer port = safeParseInt(forwardSpec);
			if (port != null) {
				sourcePort = port;
			} else {
				return null;
			}
		}

		if (isValidPort(sourcePort)) {
			String bindAddress = validateAndNormalizeBindAddress(bindIP, ipv6Preferred);
			return new PortForwardBean(-1, hostId, "Dynamic SOCKS " + sourcePort, typeStr, sourcePort, null, 0, bindAddress);
		}

		return null;
	}

	/**
	 * Parse a local or remote port forward.
	 * Format: [<bindIP>:]<port>=<host>:<port>
	 * Examples: 8080=localhost:80, 1.2.3.4:8080=localhost:80, [ff::2]:8080=[ff::1]:80
	 */
	private PortForwardBean parseLocalRemotePortForward(long hostId, String forwardSpec, String typeStr, boolean ipv6Preferred) {
		String[] parts = forwardSpec.split("=", 2);
		if (parts.length != 2) return null;

		String sourceSpec = parts[0];
		String bindIP = null;
		int sourcePort;

		String[] bindParts = parseBindIPAndPort(sourceSpec);
		if (bindParts != null && bindParts.length == 2) {
			bindIP = bindParts[0];
			Integer port = safeParseInt(bindParts[1]);
			if (port != null) {
				sourcePort = port;
				if (!isValidHostname(bindIP)) {
					bindIP = null; // Invalid bind IP, ignore
				}
			} else {
				return null;
			}
		} else {
			Integer port = safeParseInt(sourceSpec);
			if (port != null) {
				sourcePort = port;
			} else {
				return null;
			}
		}

		if (!isValidPort(sourcePort)) return null;

		String[] destParts = parseHostAndPort(parts[1]);
		if (destParts == null || destParts.length != 2) return null;

		String destHost = destParts[0];
		Integer destPort = safeParseInt(destParts[1]);
		if (destPort == null) return null;

		if (isValidHostname(destHost) && isValidPort(destPort)) {
			String bindAddress = validateAndNormalizeBindAddress(bindIP, ipv6Preferred);
			String nickname = typeStr + " " + sourcePort + " -> " + destHost + ":" + destPort;
			return new PortForwardBean(-1, hostId, nickname, typeStr, sourcePort, destHost, destPort, bindAddress);
		}

		return null;
	}

	/**
	 * Safely parse an integer with error handling.
	 * Returns the parsed integer or null if parsing fails.
	 */
	private Integer safeParseInt(String str) {
		if (str == null || str.trim().isEmpty()) {
			return null;
		}
		try {
			return Integer.parseInt(str.trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/**
	 * Parse IPv6 address and port from bracketed format.
	 * Returns [address, port] or null if invalid format.
	 * Format: [ipv6]:port
	 */
	private String[] parseIPv6WithBrackets(String spec, boolean validateAddress) {
		if (!spec.startsWith("[")) {
			return null;
		}

		int closeBracket = spec.indexOf(']');
		if (closeBracket == -1 || closeBracket >= spec.length() - 1 || spec.charAt(closeBracket + 1) != ':') {
			return null;
		}

		String ipv6 = spec.substring(1, closeBracket);
		String port = spec.substring(closeBracket + 2);

		// Validate IPv6 address format if requested
		if (!validateAddress || isValidBindAddress(ipv6)) {
			return new String[]{ipv6, port};
		}

		return null;
	}

	/**
	 * Parse address and port from string, handling IPv6 addresses properly.
	 * Returns [address, port] or null if invalid format.
	 */
	private String[] parseAddressAndPort(String spec, boolean validateAddress) {
		if (spec == null || spec.isEmpty()) {
			return null;
		}

		spec = spec.trim();

		// IPv6 with brackets: [ff::1]:8080 or [::]:8080
		String[] ipv6Result = parseIPv6WithBrackets(spec, validateAddress);
		if (ipv6Result != null) {
			return ipv6Result;
		}

		// IPv4 or hostname: 192.168.1.1:8080 or hostname:8080
		// Must be careful not to split IPv6 without brackets
		if (!spec.contains(":")) {
			return null; // No port separator
		}

		// Count colons - if more than 1, likely IPv6 without brackets
		long colonCount = spec.chars().filter(ch -> ch == ':').count();
		if (colonCount > 1) {
			// IPv6 without brackets - only allow if it's a known safe pattern for bind addresses
			if (validateAddress && (spec.equals("::1") || spec.equals("::"))) {
				// Special case for localhost/wildcard IPv6 without port
				return null;
			}
			// Complex IPv6 without brackets not supported
			return null;
		}

		// Single colon, split normally for IPv4/hostname
		int lastColon = spec.lastIndexOf(':');
		if (lastColon > 0 && lastColon < spec.length() - 1) {
			String address = spec.substring(0, lastColon);
			String port = spec.substring(lastColon + 1);
			// Validate address format if requested
			if (!validateAddress || isValidBindAddress(address)) {
				return new String[]{address, port};
			}
		}

		return null;
	}

	/**
	 * Parse bind IP and port from string, handling IPv6 addresses properly.
	 * Returns [bindIP, port] or null if no bind IP specified.
	 */
	private String[] parseBindIPAndPort(String spec) {
		return parseAddressAndPort(spec, true);
	}

	/**
	 * Parse host and port from string, handling IPv6 addresses properly.
	 * Returns [host, port] or null if invalid format.
	 */
	private String[] parseHostAndPort(String spec) {
		return parseAddressAndPort(spec, false);
	}

	/**
	 * Helper method for null and empty string validation.
	 */
	private boolean isNullOrEmpty(String str) {
		return str == null || str.trim().isEmpty();
	}

	/**
	 * Helper method for string length validation with null/empty check.
	 */
	private boolean isValidStringLength(String str, int maxLength) {
		return !isNullOrEmpty(str) && str.length() <= maxLength;
	}

	/**
	 * Helper method for range validation.
	 */
	private boolean isInRange(int value, int min, int max) {
		return value >= min && value <= max;
	}

	/**
	 * Validate input values for session creation.
	 */
	private boolean isValidSessionName(String name) {
		return isValidStringLength(name, 64) &&
			!name.chars().anyMatch(c -> Character.isISOControl(c) || "/:*?\"<>|".indexOf(c) >= 0);
	}

	private boolean isValidHostname(String hostname) {
		return isValidStringLength(hostname, 253) &&
			(HOSTNAME_ALPHANUMERIC_PATTERN.matcher(hostname).matches() ||
			HOSTNAME_IPV6_PATTERN.matcher(hostname).matches());
	}

	private boolean isValidUsername(String username) {
		return username == null || (username.length() <= 32 &&
			username.chars().allMatch(c -> c >= 32 && c <= 126));
	}

	private boolean isValidPort(int port) {
		return isInRange(port, 1, 65535);
	}

	/**
	 * Validate and normalize bind address, with IPv6 support.
	 */
	private String validateAndNormalizeBindAddress(String bindIP, boolean ipv6Preferred) {
		if (bindIP == null || bindIP.trim().isEmpty()) {
			return ipv6Preferred ? "::1" : "localhost";
		}

		bindIP = bindIP.trim();

		// Validate the bind address
		if (!isValidBindAddress(bindIP)) {
			// Invalid bind address, use safe default
			return ipv6Preferred ? "::1" : "localhost";
		}

		// Normalize common addresses
		switch (bindIP.toLowerCase()) {
			case "0.0.0.0":
				return ipv6Preferred ? "::" : "0.0.0.0";
			case "127.0.0.1":
			case "localhost":
				return ipv6Preferred ? "::1" : "localhost";
			case "::":
				return ipv6Preferred ? "::" : "0.0.0.0";
			case "::1":
				return ipv6Preferred ? "::1" : "localhost";
			default:
				return bindIP;
		}
	}

	/**
	 * Validate bind address format (IPv4, IPv6, or hostname).
	 */
	private boolean isValidBindAddress(String address) {
		if (isNullOrEmpty(address)) {
			return false;
		}

		address = address.trim();

		// Check for IPv4 (including 0.0.0.0)
		if (IPV4_PATTERN.matcher(address).matches()) {
			return true;
		}

		// Check for IPv6 (basic validation)
		if (IPV6_PATTERN.matcher(address).matches() ||
			address.equals("::") || address.equals("::1")) {
			return true;
		}

		// Check for hostname/FQDN
		if (FQDN_PATTERN.matcher(address).matches() && address.length() <= 253) {
			return true;
		}

		return false;
	}

	/**
	 * Update port forward host IDs after sessions have been saved.
	 * This should be called after importing sessions to link port forwards to their hosts.
	 * Since PortForwardBean doesn't have a setHostId method, this creates new instances.
	 */
	public void updatePortForwardHostIds(ParseResult result, Map<String, Long> sessionNameToHostId) {
		for (Map.Entry<String, List<PortForwardBean>> entry : result.getPortForwards().entrySet()) {
			String sessionName = entry.getKey();
			List<PortForwardBean> forwards = entry.getValue();
			Long hostId = sessionNameToHostId.get(sessionName);

			if (hostId != null) {
				// Replace with new instances that have correct host ID
				List<PortForwardBean> updatedForwards = new ArrayList<>();
				for (PortForwardBean forward : forwards) {
					PortForwardBean updatedForward = new PortForwardBean(
						-1, // Let database assign ID
						hostId,
						forward.getNickname(),
						forward.getType(),
						forward.getSourcePort(),
						forward.getDestAddr(),
						forward.getDestPort(),
						forward.getBindAddress()
					);
					updatedForwards.add(updatedForward);
				}
				entry.setValue(updatedForwards);
			}
		}
	}

	/**
	 * Get port forwards for a specific session from parse results.
	 */
	public List<PortForwardBean> getPortForwardsForSession(ParseResult result, String sessionName) {
		List<PortForwardBean> forwards = result.getPortForwards().get(sessionName);
		return forwards != null ? new ArrayList<>(forwards) : new ArrayList<>();
	}

	/**
	 * Create port forward beans with proper host ID after session import.
	 * This is a utility method for the import process.
	 */
	public static class PortForwardCreationResult {
		public List<PortForwardBean> created = new ArrayList<>();
		public List<String> errors = new ArrayList<>();

		public boolean hasErrors() {
			return !errors.isEmpty();
		}
	}

	/**
	 * Create port forwards for a saved host from parse result.
	 */
	public PortForwardCreationResult createPortForwardsForHost(ParseResult parseResult,
			String sessionName, long hostId) {
		PortForwardCreationResult result = new PortForwardCreationResult();

		List<PortForwardBean> forwards = parseResult.getPortForwards().get(sessionName);
		if (forwards == null || forwards.isEmpty()) {
			return result;
		}

		for (PortForwardBean forward : forwards) {
			try {
				// Create new instance with proper host ID
				PortForwardBean newForward = new PortForwardBean(
					-1, // Let database assign ID
					hostId,
					forward.getNickname(),
					forward.getType(),
					forward.getSourcePort(),
					forward.getDestAddr(),
					forward.getDestPort(),
					forward.getBindAddress()
				);
				result.created.add(newForward);
			} catch (Exception e) {
				Log.w(TAG, "Failed to create port forward for host " + hostId, e);
				result.errors.add("Failed to create port forward: " + forward.getNickname());
			}
		}

		return result;
	}
}