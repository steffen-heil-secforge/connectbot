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
	private String bindAddress;
	private ProgressDialog progressDialog;

	public static class ImportResult {
		public int imported = 0;
		public int skipped = 0;
		public boolean hasError = false;
		public String errorMessage;
	}

	public PuttyImportTask(Context context, List<HostBean> sessions, String bindAddress, HostListActivity activity) {
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

		// Use the session as HostBean directly
		HostBean hostBean = session;
		if (hostBean == null) {
			return false;
		}

		// Check if host already exists
		HostBean existingHost = existingHosts.get(session.getNickname());
		if (existingHost != null) {
			// Update existing host
			hostBean.setId(existingHost.getId());
		}

		// Save host
		hostBean = hostdb.saveHost(hostBean);
		if (hostBean == null) {
			return false;
		}

		// Note: Port forwards are not available in HostBean directly
		// This would need to be handled separately by parsing the original registry data

		return true;
	}
}