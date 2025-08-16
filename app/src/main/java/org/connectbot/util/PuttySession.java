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

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Data class representing a PuTTY session configuration.
 * 
 * @author ConnectBot Team
 */
public class PuttySession implements Serializable {
	private String sessionName;
	private String hostname;
	private String username;
	private int port = 22;
	private String protocol = "ssh";
	private boolean compression = false;
	private String publicKeyFile;
	private boolean tryAgent = true;
	private List<PortForward> portForwards = new ArrayList<>();
	
	public PuttySession(String sessionName) {
		this.sessionName = sessionName;
	}
	
	public String getSessionName() {
		return sessionName;
	}
	
	public void setSessionName(String sessionName) {
		this.sessionName = sessionName;
	}
	
	public String getHostname() {
		return hostname;
	}
	
	public void setHostname(String hostname) {
		this.hostname = hostname;
	}
	
	public String getUsername() {
		return username;
	}
	
	public void setUsername(String username) {
		this.username = username;
	}
	
	public int getPort() {
		return port;
	}
	
	public void setPort(int port) {
		this.port = port;
	}
	
	public String getProtocol() {
		return protocol;
	}
	
	public void setProtocol(String protocol) {
		this.protocol = protocol;
	}
	
	public boolean isCompression() {
		return compression;
	}
	
	public void setCompression(boolean compression) {
		this.compression = compression;
	}
	
	public String getPublicKeyFile() {
		return publicKeyFile;
	}
	
	public void setPublicKeyFile(String publicKeyFile) {
		this.publicKeyFile = publicKeyFile;
	}
	
	public boolean isTryAgent() {
		return tryAgent;
	}
	
	public void setTryAgent(boolean tryAgent) {
		this.tryAgent = tryAgent;
	}
	
	public List<PortForward> getPortForwards() {
		return portForwards;
	}
	
	public void addPortForward(PortForward portForward) {
		this.portForwards.add(portForward);
	}
	
	/**
	 * Data class representing a PuTTY port forward configuration.
	 */
	public static class PortForward implements Serializable {
		private String type; // "local", "remote", "dynamic5"
		private int sourcePort;
		private String destHost;
		private int destPort;
		private String bindIP; // IP to bind to (for local/dynamic forwards)
		
		public PortForward(String type, int sourcePort, String destHost, int destPort) {
			this(type, sourcePort, destHost, destPort, null);
		}
		
		public PortForward(String type, int sourcePort, String destHost, int destPort, String bindIP) {
			this.type = type;
			this.sourcePort = sourcePort;
			this.destHost = destHost;
			this.destPort = destPort;
			this.bindIP = bindIP;
		}
		
		public String getType() {
			return type;
		}
		
		public int getSourcePort() {
			return sourcePort;
		}
		
		public String getDestHost() {
			return destHost;
		}
		
		public int getDestPort() {
			return destPort;
		}
		
		public String getBindIP() {
			return bindIP;
		}
	}
}