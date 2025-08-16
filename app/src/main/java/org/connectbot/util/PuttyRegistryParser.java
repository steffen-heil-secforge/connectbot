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
	
	// Regex patterns for parsing
	private static final Pattern SECTION_PATTERN = Pattern.compile("^\\[(.+)\\]$");
	private static final Pattern VALUE_PATTERN = Pattern.compile("^\"([^\"]+)\"=(.+)$");
	private static final Pattern DWORD_PATTERN = Pattern.compile("^dword:([0-9a-fA-F]{8})$");
	private static final Pattern STRING_PATTERN = Pattern.compile("^\"(.*)\"$");
	
	/**
	 * Result of parsing a PuTTY registry file.
	 */
	public static class ParseResult implements Serializable {
		private List<PuttySession> validSessions = new ArrayList<>();
		private List<String> errors = new ArrayList<>();
		private List<String> warnings = new ArrayList<>();
		private boolean truncated = false;
		
		public List<PuttySession> getValidSessions() {
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
	}
	
	/**
	 * Parse a PuTTY registry file from an InputStream.
	 */
	public ParseResult parseRegistryFile(InputStream inputStream, long fileSize) {
		ParseResult result = new ParseResult();
		
		// Check file size
		if (fileSize > MAX_FILE_SIZE) {
			result.addError("File too large (max 1MB)");
			return result;
		}
		
		try {
			// Try different encodings
			String content = readWithEncodingDetection(inputStream);
			if (content == null) {
				result.addError("File encoding error");
				return result;
			}
			
			// Validate basic registry structure
			if (!isValidRegistryFile(content)) {
				result.addError("Invalid or corrupted registry file");
				return result;
			}
			
			// Parse sessions
			Map<String, Map<String, String>> sessions = parseRegistrySections(content);
			if (sessions.isEmpty()) {
				result.addError("No PuTTY SSH sessions found");
				return result;
			}
			
			// Convert to PuttySession objects
			Set<String> seenNames = new HashSet<>();
			int sessionCount = 0;
			
			for (Map.Entry<String, Map<String, String>> entry : sessions.entrySet()) {
				if (sessionCount >= MAX_SESSIONS) {
					result.setTruncated(true);
					result.addWarning("Too many sessions, imported first " + MAX_SESSIONS);
					break;
				}
				
				try {
					String sessionName = entry.getKey();
					Map<String, String> values = entry.getValue();
					
					// Check for SSH protocol
					String protocol = values.get("Protocol");
					if (!"ssh".equals(protocol)) {
						continue; // Skip non-SSH sessions
					}
					
					PuttySession session = parseSessionSection(sessionName, values);
					if (session != null) {
						// Check for duplicates (after Unicode normalization)
						String normalizedName = Normalizer.normalize(session.getSessionName(), 
							Normalizer.Form.NFC);
						if (seenNames.contains(normalizedName)) {
							result.addWarning("Duplicate session name skipped: " + sessionName);
							continue;
						}
						seenNames.add(normalizedName);
						
						result.getValidSessions().add(session);
						sessionCount++;
					}
				} catch (Exception e) {
					Log.w(TAG, "Failed to parse session: " + entry.getKey(), e);
					result.addWarning("Invalid session data skipped: " + entry.getKey());
				}
			}
			
			if (result.getValidSessions().isEmpty()) {
				result.addError("No valid SSH sessions found");
			}
			
		} catch (IOException e) {
			Log.e(TAG, "Error reading registry file", e);
			result.addError("File reading error");
		} catch (Exception e) {
			Log.e(TAG, "Unexpected error parsing registry file", e);
			result.addError("Invalid or corrupted registry file");
		}
		
		return result;
	}
	
	/**
	 * Try to read file with encoding detection.
	 * Supports UTF-8 BOM (EF BB BF), UTF-16 LE BOM (FF FE), and UTF-16 BE BOM (FE FF).
	 */
	private String readWithEncodingDetection(InputStream inputStream) throws IOException {
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
	 * Check if the content looks like a valid registry file.
	 */
	private boolean isValidRegistryFile(String content) {
		return content.contains("Windows Registry Editor") || 
			content.contains("[HKEY_");
	}
	
	/**
	 * Parse registry sections from file content.
	 */
	private Map<String, Map<String, String>> parseRegistrySections(String content) {
		Map<String, Map<String, String>> sessions = new HashMap<>();
		String[] lines = content.split("\n");
		
		String currentSection = null;
		Map<String, String> currentValues = null;
		
		for (String line : lines) {
			line = line.trim();
			if (line.isEmpty()) continue;
			
			// Check for section header
			Matcher sectionMatcher = SECTION_PATTERN.matcher(line);
			if (sectionMatcher.matches()) {
				String section = sectionMatcher.group(1);
				
				// Only process PuTTY sessions
				if (section.startsWith(PUTTY_SESSIONS_PATH)) {
					String sessionName = section.substring(PUTTY_SESSIONS_PATH.length());
					try {
						// URL decode session name
						sessionName = URLDecoder.decode(sessionName, "UTF-8");
						
						currentSection = sessionName;
						currentValues = new HashMap<>();
						sessions.put(currentSection, currentValues);
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
					String value = valueMatcher.group(2);
					
					// Parse different value types
					String parsedValue = parseRegistryValue(value);
					if (parsedValue != null) {
						currentValues.put(key, parsedValue);
					}
				}
			}
		}
		
		return sessions;
	}
	
	/**
	 * Parse a registry value (DWORD or string).
	 */
	private String parseRegistryValue(String value) {
		// Check for DWORD
		Matcher dwordMatcher = DWORD_PATTERN.matcher(value);
		if (dwordMatcher.matches()) {
			try {
				long dwordValue = Long.parseLong(dwordMatcher.group(1), 16);
				return String.valueOf(dwordValue);
			} catch (NumberFormatException e) {
				return null;
			}
		}
		
		// Check for string
		Matcher stringMatcher = STRING_PATTERN.matcher(value);
		if (stringMatcher.matches()) {
			return stringMatcher.group(1);
		}
		
		return null;
	}
	
	/**
	 * Parse a single session section into a PuttySession object.
	 */
	private PuttySession parseSessionSection(String sessionName, Map<String, String> values) {
		// Validate session name
		if (!isValidSessionName(sessionName)) {
			return null;
		}
		
		PuttySession session = new PuttySession(sessionName);
		
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
			try {
				int port = Integer.parseInt(portStr);
				if (isValidPort(port)) {
					session.setPort(port);
				}
			} catch (NumberFormatException e) {
				// Use default port
			}
		}
		
		// Parse compression
		String compressionStr = values.get("Compression");
		if ("1".equals(compressionStr)) {
			session.setCompression(true);
		}
		
		// Parse authentication
		String tryAgentStr = values.get("TryAgent");
		if ("0".equals(tryAgentStr)) {
			session.setTryAgent(false);
		}
		
		String publicKeyFile = values.get("PublicKeyFile");
		if (publicKeyFile != null && !publicKeyFile.isEmpty()) {
			session.setPublicKeyFile(publicKeyFile);
		}
		
		// Parse port forwards
		String portForwardings = values.get("PortForwardings");
		if (portForwardings != null && !portForwardings.isEmpty()) {
			parsePortForwards(session, portForwardings);
		}
		
		return session;
	}
	
	/**
	 * Parse port forwarding string.
	 */
	private void parsePortForwards(PuttySession session, String portForwardings) {
		String[] forwards = portForwardings.split(",");
		for (String forward : forwards) {
			forward = forward.trim();
			if (forward.isEmpty()) continue;
			
			try {
				PuttySession.PortForward pf = parsePortForward(forward);
				if (pf != null) {
					session.addPortForward(pf);
				}
			} catch (Exception e) {
				Log.w(TAG, "Failed to parse port forward: " + forward, e);
			}
		}
	}
	
	/**
	 * Parse a single port forward entry.
	 */
	private PuttySession.PortForward parsePortForward(String forward) {
		// Format: [4|6]<type>[<bindIP>:]<port>=<host>:<port>
		// Examples: L8080=localhost:80, 4L1.2.3.4:8080=localhost:80, 6R[ff::2]:11=[ff::1]:12
		
		// Remove IPv4/IPv6 prefix if present
		if (forward.startsWith("4") || forward.startsWith("6")) {
			forward = forward.substring(1);
		}
		
		char type = forward.charAt(0);
		String typeStr;
		switch (type) {
			case 'L':
				typeStr = "local";
				break;
			case 'R':
				typeStr = "remote";
				break;
			case 'D':
				typeStr = "dynamic5";
				break;
			default:
				return null;
		}
		
		forward = forward.substring(1); // Remove type character
		
		if ("dynamic5".equals(typeStr)) {
			// Dynamic forwards: D[<bindIP>:]<port>
			// Examples: D1080, D1.2.3.4:1080, D[ff::1]:1080
			String bindIP = null;
			int sourcePort;
			
			try {
				String[] bindParts = parseBindIPAndPort(forward);
				if (bindParts != null && bindParts.length == 2) {
					bindIP = bindParts[0];
					sourcePort = Integer.parseInt(bindParts[1]);
					if (!isValidHostname(bindIP)) {
						bindIP = null; // Invalid bind IP, ignore
					}
				} else {
					sourcePort = Integer.parseInt(forward);
				}
				
				if (isValidPort(sourcePort)) {
					return new PuttySession.PortForward(typeStr, sourcePort, null, 0, bindIP);
				}
			} catch (NumberFormatException e) {
				return null;
			}
		} else {
			// Local/Remote forwards: [<bindIP>:]<port>=<host>:<port>
			// Examples: 8080=localhost:80, 1.2.3.4:8080=localhost:80, [ff::2]:8080=[ff::1]:80
			String[] parts = forward.split("=", 2);
			if (parts.length != 2) return null;
			
			try {
				String sourceSpec = parts[0];
				String bindIP = null;
				int sourcePort;
				
				String[] bindParts = parseBindIPAndPort(sourceSpec);
				if (bindParts != null && bindParts.length == 2) {
					bindIP = bindParts[0];
					sourcePort = Integer.parseInt(bindParts[1]);
					if (!isValidHostname(bindIP)) {
						bindIP = null; // Invalid bind IP, ignore
					}
				} else {
					sourcePort = Integer.parseInt(sourceSpec);
				}
				
				if (!isValidPort(sourcePort)) return null;
				
				String[] destParts = parseHostAndPort(parts[1]);
				if (destParts == null || destParts.length != 2) return null;
				
				String destHost = destParts[0];
				int destPort = Integer.parseInt(destParts[1]);
				
				if (isValidHostname(destHost) && isValidPort(destPort)) {
					return new PuttySession.PortForward(typeStr, sourcePort, destHost, destPort, bindIP);
				}
			} catch (NumberFormatException e) {
				return null;
			}
		}
		
		return null;
	}
	
	/**
	 * Parse bind IP and port from string, handling IPv6 addresses properly.
	 * Returns [bindIP, port] or null if no bind IP specified.
	 */
	private String[] parseBindIPAndPort(String spec) {
		if (spec == null || spec.isEmpty()) {
			return null;
		}
		
		// IPv6 with brackets: [ff::1]:8080
		if (spec.startsWith("[")) {
			int closeBracket = spec.indexOf(']');
			if (closeBracket != -1 && closeBracket < spec.length() - 1 && spec.charAt(closeBracket + 1) == ':') {
				String ipv6 = spec.substring(1, closeBracket);
				String port = spec.substring(closeBracket + 2);
				return new String[]{ipv6, port};
			}
			return null;
		}
		
		// IPv4 or hostname: 192.168.1.1:8080 or hostname:8080
		// Must be careful not to split IPv6 without brackets
		if (!spec.contains(":")) {
			return null; // No port separator
		}
		
		// Count colons - if more than 1, likely IPv6 without brackets
		long colonCount = spec.chars().filter(ch -> ch == ':').count();
		if (colonCount > 1) {
			// Likely IPv6 without brackets, not supported in this context
			return null;
		}
		
		// Single colon, split normally for IPv4/hostname
		int lastColon = spec.lastIndexOf(':');
		if (lastColon > 0 && lastColon < spec.length() - 1) {
			String ip = spec.substring(0, lastColon);
			String port = spec.substring(lastColon + 1);
			return new String[]{ip, port};
		}
		
		return null;
	}
	
	/**
	 * Parse host and port from string, handling IPv6 addresses properly.
	 * Returns [host, port] or null if invalid format.
	 */
	private String[] parseHostAndPort(String spec) {
		if (spec == null || spec.isEmpty()) {
			return null;
		}
		
		// IPv6 with brackets: [ff::1]:80
		if (spec.startsWith("[")) {
			int closeBracket = spec.indexOf(']');
			if (closeBracket != -1 && closeBracket < spec.length() - 1 && spec.charAt(closeBracket + 1) == ':') {
				String ipv6 = spec.substring(1, closeBracket);
				String port = spec.substring(closeBracket + 2);
				return new String[]{ipv6, port};
			}
			return null;
		}
		
		// IPv4 or hostname: 192.168.1.1:80 or hostname:80
		if (!spec.contains(":")) {
			return null; // No port separator
		}
		
		// Count colons - if more than 1, likely IPv6 without brackets
		long colonCount = spec.chars().filter(ch -> ch == ':').count();
		if (colonCount > 1) {
			// Likely IPv6 without brackets, not supported in destination context
			return null;
		}
		
		// Single colon, split normally
		int lastColon = spec.lastIndexOf(':');
		if (lastColon > 0 && lastColon < spec.length() - 1) {
			String host = spec.substring(0, lastColon);
			String port = spec.substring(lastColon + 1);
			return new String[]{host, port};
		}
		
		return null;
	}
	
	/**
	 * Validate session name.
	 */
	private boolean isValidSessionName(String name) {
		if (name == null || name.trim().isEmpty() || name.length() > 64) {
			return false;
		}
		
		// Check for control characters and path separators
		for (char c : name.toCharArray()) {
			if (Character.isISOControl(c) || c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|') {
				return false;
			}
		}
		
		return true;
	}
	
	/**
	 * Validate hostname.
	 */
	private boolean isValidHostname(String hostname) {
		if (hostname == null || hostname.trim().isEmpty() || hostname.length() > 253) {
			return false;
		}
		
		// Basic hostname validation - accept FQDN, IPv4, IPv6
		return hostname.matches("^[a-zA-Z0-9.-]+$") || 
			hostname.matches("^\\[?[0-9a-fA-F:]+\\]?$"); // Simple IPv6 check
	}
	
	/**
	 * Validate username.
	 */
	private boolean isValidUsername(String username) {
		if (username == null || username.length() > 32) {
			return false;
		}
		
		// Printable ASCII only
		for (char c : username.toCharArray()) {
			if (c < 32 || c > 126) {
				return false;
			}
		}
		
		return true;
	}
	
	/**
	 * Validate port number.
	 */
	private boolean isValidPort(int port) {
		return port >= 1 && port <= 65535;
	}
}