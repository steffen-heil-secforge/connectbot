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

import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.util.SparseBooleanArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.connectbot.bean.HostBean;
import org.connectbot.bean.PortForwardBean;
import org.connectbot.util.PuttySession.PortForward;
import org.connectbot.util.HostDatabase;
import org.connectbot.util.NetworkUtils;
import org.connectbot.util.PuttyRegistryParser;
import org.connectbot.util.PuttySession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Dialog for importing PuTTY sessions.
 * 
 * @author ConnectBot Team
 */
public class PuttyImportDialog extends DialogFragment {
	private static final String ARG_PARSE_RESULT = "parseResult";
	
	private PuttyRegistryParser.ParseResult parseResult;
	private PuttySessionAdapter adapter;
	private RadioGroup bindAddressGroup;
	private TextView warningText;
	private TextView securityWarning;
	private Map<String, Boolean> existingHosts;
	
	public static PuttyImportDialog newInstance(PuttyRegistryParser.ParseResult result) {
		PuttyImportDialog dialog = new PuttyImportDialog();
		Bundle args = new Bundle();
		args.putSerializable(ARG_PARSE_RESULT, result);
		dialog.setArguments(args);
		return dialog;
	}
	
	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		
		if (getArguments() != null) {
			parseResult = (PuttyRegistryParser.ParseResult) getArguments().getSerializable(ARG_PARSE_RESULT);
		}
		
		// Check which hosts already exist
		checkExistingHosts();
	}
	
	@NonNull
	@Override
	public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
		View view = LayoutInflater.from(getContext()).inflate(R.layout.dia_putty_import, null);
		
		// Initialize views
		RecyclerView sessionList = view.findViewById(R.id.session_list);
		Button selectAllButton = view.findViewById(R.id.button_select_all);
		Button deselectAllButton = view.findViewById(R.id.button_deselect_all);
		bindAddressGroup = view.findViewById(R.id.bind_address_group);
		securityWarning = view.findViewById(R.id.security_warning);
		warningText = view.findViewById(R.id.warning_text);
		
		// Setup RecyclerView
		sessionList.setLayoutManager(new LinearLayoutManager(getContext()));
		
		// Sort sessions lexically by name
		List<PuttySession> sortedSessions = new ArrayList<>(parseResult.getValidSessions());
		sortedSessions.sort((a, b) -> a.getSessionName().compareToIgnoreCase(b.getSessionName()));
		
		adapter = new PuttySessionAdapter(sortedSessions, existingHosts);
		sessionList.setAdapter(adapter);
		
		// Setup buttons
		selectAllButton.setOnClickListener(v -> adapter.selectAll(true));
		deselectAllButton.setOnClickListener(v -> adapter.selectAll(false));
		
		// Setup bind address warning
		bindAddressGroup.setOnCheckedChangeListener((group, checkedId) -> updateSecurityWarning());
		updateSecurityWarning(); // Initial update
		
		// Show warnings if any
		showWarnings();
		
		return new AlertDialog.Builder(getContext(), R.style.AlertDialogTheme)
			.setView(view)
			.setPositiveButton(android.R.string.ok, this::onImportClicked)
			.setNegativeButton(android.R.string.cancel, null)
			.create();
	}
	
	private void checkExistingHosts() {
		existingHosts = new HashMap<>();
		HostDatabase hostdb = HostDatabase.get(getContext());
		List<HostBean> hosts = hostdb.getHosts(false);
		
		// Filter out sessions that are identical to existing hosts
		List<PuttySession> filteredSessions = new ArrayList<>();
		for (PuttySession session : parseResult.getValidSessions()) {
			HostBean existingHost = findExistingHost(hosts, session.getSessionName());
			if (existingHost != null) {
				// Compare if there are actual differences
				if (hasSignificantDifferences(existingHost, session)) {
					existingHosts.put(session.getSessionName(), true);
					filteredSessions.add(session);
				}
				// If no differences, don't add to filtered list (exclude from dialog)
			} else {
				// New host, include in dialog
				filteredSessions.add(session);
			}
		}
		
		// Update parseResult with filtered sessions
		parseResult.getValidSessions().clear();
		parseResult.getValidSessions().addAll(filteredSessions);
	}
	
	private HostBean findExistingHost(List<HostBean> hosts, String nickname) {
		for (HostBean host : hosts) {
			if (host.getNickname().equals(nickname)) {
				return host;
			}
		}
		return null;
	}
	
	private boolean hasSignificantDifferences(HostBean existingHost, PuttySession session) {
		// Compare hostname
		if (!existingHost.getHostname().equals(session.getHostname())) {
			return true;
		}
		
		// Compare port
		if (existingHost.getPort() != session.getPort()) {
			return true;
		}
		
		// Compare username
		String existingUsername = existingHost.getUsername();
		String sessionUsername = session.getUsername();
		if (!Objects.equals(existingUsername, sessionUsername)) {
			return true;
		}
		
		// Compare protocol
		if (!existingHost.getProtocol().equals(session.getProtocol())) {
			return true;
		}
		
		// Compare compression
		if (existingHost.getCompression() != session.isCompression()) {
			return true;
		}
		
		// Compare port forwards - get existing forwards for this host
		HostDatabase hostdb = HostDatabase.get(getContext());
		List<PortForwardBean> existingForwards = hostdb.getPortForwardsForHost(existingHost);
		List<PortForward> sessionForwards = session.getPortForwards();
		
		if (existingForwards.size() != sessionForwards.size()) {
			return true;
		}
		
		// Compare each port forward (simplified comparison)
		for (PortForward sessionForward : sessionForwards) {
			boolean found = false;
			for (PortForwardBean existingForward : existingForwards) {
				if (portForwardsEqual(existingForward, sessionForward)) {
					found = true;
					break;
				}
			}
			if (!found) {
				return true;
			}
		}
		
		return false; // No significant differences found
	}
	
	private boolean portForwardsEqual(PortForwardBean existing, PortForward session) {
		return existing.getType().equals(session.getType()) &&
			existing.getSourcePort() == session.getSourcePort() &&
			Objects.equals(existing.getDestAddr(), session.getDestHost()) &&
			existing.getDestPort() == session.getDestPort();
	}
	
	private void showWarnings() {
		if (parseResult.isTruncated() || !parseResult.getWarnings().isEmpty()) {
			StringBuilder warnings = new StringBuilder();
			
			if (parseResult.isTruncated()) {
				warnings.append("Too many sessions found, showing first 100.\n");
			}
			
			for (String warning : parseResult.getWarnings()) {
				warnings.append(warning).append("\n");
			}
			
			warningText.setText(warnings.toString().trim());
			warningText.setVisibility(View.VISIBLE);
		}
	}
	
	private void updateSecurityWarning() {
		int checkedId = bindAddressGroup.getCheckedRadioButtonId();
		boolean showWarning = (checkedId == R.id.bind_all_interfaces || checkedId == R.id.bind_hotspot);
		securityWarning.setVisibility(showWarning ? View.VISIBLE : View.GONE);
	}
	
	private void onImportClicked(DialogInterface dialog, int which) {
		List<PuttySession> selectedSessions = adapter.getSelectedSessions();
		if (selectedSessions.isEmpty()) {
			return;
		}
		
		String bindAddress = getSelectedBindAddress();
		
		// Perform import in background
		new PuttyImportTask(getContext(), selectedSessions, bindAddress, 
			(HostListActivity) getActivity()).execute();
	}
	
	private String getSelectedBindAddress() {
		int selectedId = bindAddressGroup.getCheckedRadioButtonId();
		
		if (selectedId == R.id.bind_localhost) {
			return "localhost";
		} else if (selectedId == R.id.bind_all_interfaces) {
			return "0.0.0.0";
		} else {
			return NetworkUtils.BIND_ACCESS_POINT; // Default: WiFi Hotspot
		}
	}
	
	/**
	 * RecyclerView adapter for PuTTY sessions.
	 */
	private static class PuttySessionAdapter extends RecyclerView.Adapter<PuttySessionAdapter.SessionViewHolder> {
		private List<PuttySession> sessions;
		private Map<String, Boolean> existingHosts;
		private SparseBooleanArray selectedSessions;
		
		public PuttySessionAdapter(List<PuttySession> sessions, Map<String, Boolean> existingHosts) {
			this.sessions = sessions;
			this.existingHosts = existingHosts;
			this.selectedSessions = new SparseBooleanArray();
			
			// Select all by default
			for (int i = 0; i < sessions.size(); i++) {
				selectedSessions.put(i, true);
			}
		}
		
		@NonNull
		@Override
		public SessionViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			View view = LayoutInflater.from(parent.getContext())
				.inflate(R.layout.item_putty_session, parent, false);
			return new SessionViewHolder(view);
		}
		
		@Override
		public void onBindViewHolder(@NonNull SessionViewHolder holder, int position) {
			PuttySession session = sessions.get(position);
			
			holder.sessionName.setText(session.getSessionName());
			
			// Show connection details
			StringBuilder details = new StringBuilder();
			if (session.getUsername() != null) {
				details.append(session.getUsername()).append("@");
			}
			details.append(session.getHostname());
			if (session.getPort() != 22) {
				details.append(":").append(session.getPort());
			}
			holder.sessionDetails.setText(details.toString());
			
			// Show port forwards count
			int forwardCount = session.getPortForwards().size();
			if (forwardCount > 0) {
				holder.portForwards.setText(
					holder.itemView.getContext().getString(R.string.putty_import_port_forwards, forwardCount));
				holder.portForwards.setVisibility(View.VISIBLE);
			} else {
				holder.portForwards.setVisibility(View.GONE);
			}
			
			// Show if host already exists
			boolean exists = existingHosts.containsKey(session.getSessionName());
			holder.sessionExists.setVisibility(exists ? View.VISIBLE : View.GONE);
			
			// Set checkbox state
			holder.sessionCheckbox.setChecked(selectedSessions.get(position, false));
			holder.sessionCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> 
				selectedSessions.put(position, isChecked));
			
			// Make the whole item clickable
			holder.itemView.setOnClickListener(v -> {
				boolean newState = !selectedSessions.get(position, false);
				selectedSessions.put(position, newState);
				holder.sessionCheckbox.setChecked(newState);
			});
		}
		
		@Override
		public int getItemCount() {
			return sessions.size();
		}
		
		public void selectAll(boolean select) {
			for (int i = 0; i < sessions.size(); i++) {
				selectedSessions.put(i, select);
			}
			notifyDataSetChanged();
		}
		
		public List<PuttySession> getSelectedSessions() {
			List<PuttySession> selected = new ArrayList<>();
			for (int i = 0; i < sessions.size(); i++) {
				if (selectedSessions.get(i, false)) {
					selected.add(sessions.get(i));
				}
			}
			return selected;
		}
		
		static class SessionViewHolder extends RecyclerView.ViewHolder {
			CheckBox sessionCheckbox;
			TextView sessionName;
			TextView sessionDetails;
			TextView portForwards;
			TextView sessionExists;
			
			SessionViewHolder(@NonNull View itemView) {
				super(itemView);
				sessionCheckbox = itemView.findViewById(R.id.session_checkbox);
				sessionName = itemView.findViewById(R.id.session_name);
				sessionDetails = itemView.findViewById(R.id.session_details);
				portForwards = itemView.findViewById(R.id.port_forwards);
				sessionExists = itemView.findViewById(R.id.session_exists);
			}
		}
	}
}