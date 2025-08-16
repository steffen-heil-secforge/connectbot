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

package org.connectbot;

import android.app.ProgressDialog;
import android.content.Context;
import android.os.AsyncTask;
import android.util.Log;
import android.widget.Toast;

import org.connectbot.bean.HostBean;
import org.connectbot.bean.PortForwardBean;
import org.connectbot.util.HostDatabase;
import org.connectbot.util.PuttySession;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AsyncTask to import PuTTY sessions in the background.
 * 
 * @author ConnectBot Team
 */
public class PuttyImportTask extends AsyncTask<Void, Void, PuttyImportTask.ImportResult> {
	private static final String TAG = "CB.PuttyImportTask";
	
	private WeakReference<Context> contextRef;
	private WeakReference<HostListActivity> activityRef;
	private List<PuttySession> sessions;
	private String bindAddress;
	private ProgressDialog progressDialog;
	
	public static class ImportResult {
		public int imported = 0;
		public int skipped = 0;
		public boolean hasError = false;
		public String errorMessage;
	}
	
	public PuttyImportTask(Context context, List<PuttySession> sessions, String bindAddress, HostListActivity activity) {
		this.contextRef = new WeakReference<>(context);
		this.activityRef = new WeakReference<>(activity);
		this.sessions = sessions;
		this.bindAddress = bindAddress;
	}
	
	@Override
	protected void onPreExecute() {
		Context context = contextRef.get();
		if (context != null) {
			progressDialog = new ProgressDialog(context);
			progressDialog.setMessage("Importing PuTTY sessions...");
			progressDialog.setIndeterminate(true);
			progressDialog.setCancelable(false);
			progressDialog.show();
		}
	}
	
	@Override
	protected ImportResult doInBackground(Void... voids) {
		ImportResult result = new ImportResult();
		Context context = contextRef.get();
		
		if (context == null) {
			result.hasError = true;
			result.errorMessage = "Context lost";
			return result;
		}
		
		try {
			HostDatabase hostdb = HostDatabase.get(context);
			
			// Get existing hosts for lookup
			Map<String, HostBean> existingHosts = new HashMap<>();
			List<HostBean> hosts = hostdb.getHosts(false);
			for (HostBean host : hosts) {
				existingHosts.put(host.getNickname(), host);
			}
			
			// Import each selected session
			for (PuttySession session : sessions) {
				try {
					if (importSession(hostdb, session, existingHosts)) {
						result.imported++;
					} else {
						result.skipped++;
					}
				} catch (Exception e) {
					Log.w(TAG, "Failed to import session: " + session.getSessionName(), e);
					result.skipped++;
				}
			}
			
		} catch (Exception e) {
			Log.e(TAG, "Error during import", e);
			result.hasError = true;
			result.errorMessage = context.getString(R.string.putty_import_error_generic);
		}
		
		return result;
	}
	
	@Override
	protected void onPostExecute(ImportResult result) {
		if (progressDialog != null && progressDialog.isShowing()) {
			progressDialog.dismiss();
		}
		
		Context context = contextRef.get();
		HostListActivity activity = activityRef.get();
		
		if (context == null) return;
		
		// Show result message
		if (result.hasError) {
			Toast.makeText(context, result.errorMessage, Toast.LENGTH_LONG).show();
		} else if (result.skipped > 0) {
			String message = context.getString(R.string.putty_import_partial_success, 
				result.imported, result.skipped);
			Toast.makeText(context, message, Toast.LENGTH_LONG).show();
		} else {
			String message = context.getString(R.string.putty_import_success, result.imported);
			Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
		}
		
		// Refresh host list
		if (activity != null && result.imported > 0) {
			activity.updateList();
		}
	}
	
	/**
	 * Import a single PuTTY session.
	 */
	private boolean importSession(HostDatabase hostdb, PuttySession session, 
			Map<String, HostBean> existingHosts) {
		
		// Convert PuttySession to HostBean
		HostBean hostBean = convertToHostBean(session);
		if (hostBean == null) {
			return false;
		}
		
		// Check if host already exists
		HostBean existingHost = existingHosts.get(session.getSessionName());
		if (existingHost != null) {
			// Update existing host
			hostBean.setId(existingHost.getId());
		}
		
		// Save host
		hostBean = hostdb.saveHost(hostBean);
		if (hostBean == null) {
			return false;
		}
		
		// Import port forwards
		if (!session.getPortForwards().isEmpty()) {
			importPortForwards(hostdb, hostBean, session.getPortForwards());
		}
		
		return true;
	}
	
	/**
	 * Convert PuttySession to HostBean.
	 */
	private HostBean convertToHostBean(PuttySession session) {
		if (session.getHostname() == null || session.getHostname().trim().isEmpty()) {
			return null;
		}
		
		HostBean host = new HostBean();
		host.setNickname(session.getSessionName());
		host.setProtocol("ssh");
		host.setHostname(session.getHostname());
		host.setUsername(session.getUsername());
		host.setPort(session.getPort());
		host.setCompression(session.isCompression());
		host.setUseKeys(true); // Default to using keys
		
		// Set auth agent based on PuTTY setting
		if (session.isTryAgent()) {
			host.setUseAuthAgent(HostDatabase.AUTHAGENT_YES);
		} else {
			host.setUseAuthAgent(HostDatabase.AUTHAGENT_NO);
		}
		
		// Set defaults for ConnectBot-specific settings
		host.setWantSession(true);
		host.setDelKey(HostDatabase.DELKEY_DEL);
		host.setFontSize(HostBean.DEFAULT_FONT_SIZE);
		host.setEncoding(HostDatabase.ENCODING_DEFAULT);
		host.setStayConnected(false);
		host.setQuickDisconnect(false);
		
		// Handle public key file reference
		if (session.getPublicKeyFile() != null && !session.getPublicKeyFile().isEmpty()) {
			// For now, just set to use any available key
			// In a full implementation, we'd need to import the .ppk file
			host.setPubkeyId(HostDatabase.PUBKEYID_ANY);
		} else {
			host.setPubkeyId(HostDatabase.PUBKEYID_ANY);
		}
		
		return host;
	}
	
	/**
	 * Import port forwards for a host.
	 */
	private void importPortForwards(HostDatabase hostdb, HostBean host, 
			List<PuttySession.PortForward> portForwards) {
		
		// Delete existing port forwards for this host
		List<PortForwardBean> existingForwards = hostdb.getPortForwardsForHost(host);
		for (PortForwardBean existing : existingForwards) {
			hostdb.deletePortForward(existing);
		}
		
		// Create new port forwards
		for (PuttySession.PortForward pf : portForwards) {
			try {
				PortForwardBean pfBean = convertToPortForwardBean(host, pf);
				if (pfBean != null) {
					hostdb.savePortForward(pfBean);
				}
			} catch (Exception e) {
				Log.w(TAG, "Failed to import port forward", e);
			}
		}
	}
	
	/**
	 * Convert PuTTY port forward to PortForwardBean.
	 */
	private PortForwardBean convertToPortForwardBean(HostBean host, PuttySession.PortForward pf) {
		// Map PuTTY types to ConnectBot types
		String type;
		switch (pf.getType()) {
			case "local":
				type = HostDatabase.PORTFORWARD_LOCAL;
				break;
			case "remote":
				type = HostDatabase.PORTFORWARD_REMOTE;
				break;
			case "dynamic5":
				type = HostDatabase.PORTFORWARD_DYNAMIC5;
				break;
			default:
				return null;
		}
		
		// Determine bind address: use PuTTY's bind IP if specified, otherwise use user-selected default
		String actualBindAddress = bindAddress;
		if (pf.getBindIP() != null && !pf.getBindIP().isEmpty()) {
			// PuTTY specified a bind IP, use it directly
			actualBindAddress = pf.getBindIP();
		}
		
		// Generate nickname for port forward
		String nickname = type + "_" + pf.getSourcePort();
		if (pf.getDestHost() != null) {
			nickname += "_" + pf.getDestHost() + "_" + pf.getDestPort();
		}
		
		PortForwardBean pfBean = new PortForwardBean(-1, host.getId(), nickname, type, 
			pf.getSourcePort(), pf.getDestHost(), pf.getDestPort(), actualBindAddress);
		
		return pfBean;
	}
}