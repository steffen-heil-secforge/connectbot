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
	private List<HostBean> sessions;
	private Map<String, List<PortForwardBean>> portForwards;
	private String bindAddress;
	private ProgressDialog progressDialog;

	public static class ImportResult {
		public int imported = 0;
		public int skipped = 0;
		public boolean hasError = false;
		public String errorMessage;
	}

	public PuttyImportTask(Context context, List<HostBean> sessions, Map<String, List<PortForwardBean>> portForwards, String bindAddress, HostListActivity activity) {
		this.contextRef = new WeakReference<>(context);
		this.activityRef = new WeakReference<>(activity);
		this.sessions = sessions;
		this.portForwards = portForwards;
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
			for (HostBean session : sessions) {
				try {
					if (importSession(hostdb, session, existingHosts)) {
						result.imported++;
					} else {
						result.skipped++;
					}
				} catch (Exception e) {
					Log.w(TAG, "Failed to import session: " + session.getNickname(), e);
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
	private boolean importSession(HostDatabase hostdb, HostBean session,
			Map<String, HostBean> existingHosts) {

		if (session == null) {
			return false;
		}

		HostBean hostBean;

		// Check if host already exists
		HostBean existingHost = existingHosts.get(session.getNickname());
		if (existingHost != null) {
			// Update ONLY PuTTY-related fields on existing host to preserve ConnectBot settings
			// (colors, key usage, font size, stay-connected, quick-disconnect, etc.)
			existingHost.setProtocol(session.getProtocol());
			existingHost.setHostname(session.getHostname());
			existingHost.setPort(session.getPort());
			existingHost.setUsername(session.getUsername());
			existingHost.setCompression(session.getCompression());
			// Note: We don't update lastConnect, color, fontsize, pubkeyid, delkey,
			// stayconnected, quickdisconnect, useauthagent, postlogin, wantsession, encoding
			hostBean = existingHost;
		} else {
			// New host - use parsed session as-is
			hostBean = session;
		}

		// Save host
		hostBean = hostdb.saveHost(hostBean);
		if (hostBean == null) {
			return false;
		}

		// Import port forwards for this session (replaces existing ones)
		importPortForwards(hostdb, hostBean);

		return true;
	}

	/**
	 * Import port forwards for a session.
	 * This replaces all existing port forwards for the host with the imported ones.
	 */
	private void importPortForwards(HostDatabase hostdb, HostBean hostBean) {
		if (portForwards == null || hostBean == null) {
			return;
		}

		List<PortForwardBean> sessionPortForwards = portForwards.get(hostBean.getNickname());
		if (sessionPortForwards == null || sessionPortForwards.isEmpty()) {
			return;
		}

		try {
			// Delete existing port forwards for this host to prevent duplicates on re-import
			List<PortForwardBean> existingPortForwards = hostdb.getPortForwardsForHost(hostBean);
			for (PortForwardBean existingPf : existingPortForwards) {
				hostdb.deletePortForward(existingPf);
				Log.d(TAG, "Deleted existing port forward: " + existingPf.getNickname() + " for host: " + hostBean.getNickname());
			}

			// Now import the new port forwards
			for (PortForwardBean pf : sessionPortForwards) {
				// Create new PortForwardBean with correct host ID
				String finalBindAddress = (bindAddress != null && !bindAddress.isEmpty())
					? bindAddress : pf.getBindAddress();

				PortForwardBean newPf = new PortForwardBean(
					-1, // New ID
					hostBean.getId(), // Correct host ID
					pf.getNickname(),
					pf.getType(),
					pf.getSourcePort(),
					pf.getDestAddr(),
					pf.getDestPort(),
					finalBindAddress
				);

				// Save the port forward
				hostdb.savePortForward(newPf);
				Log.d(TAG, "Imported port forward: " + newPf.getNickname() + " for host: " + hostBean.getNickname());
			}
		} catch (Exception e) {
			Log.w(TAG, "Failed to import port forwards for host: " + hostBean.getNickname(), e);
		}
	}
}