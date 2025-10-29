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

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import org.connectbot.PuttyImportDialog;
import org.connectbot.R;
import org.connectbot.bean.HostBean;

import java.io.InputStream;
import java.util.List;
import java.util.Objects;

import androidx.fragment.app.FragmentManager;

/**
 * Utility class for handling PuTTY import operations and host comparisons.
 */
public class PuttyImportHandler {
    private static final String TAG = "CB.PuttyImportHandler";
    
    private final Context context;
    
    public PuttyImportHandler(Context context) {
        this.context = context;
    }
    
    /**
     * Handle selected PuTTY registry file.
     */
    public void handlePuttyImportFile(Uri fileUri, FragmentManager fragmentManager, 
                                     ImportErrorCallback errorCallback) {
        try {
            // Get file size
            long fileSize = getFileSize(fileUri);
            
            // Parse the file
            InputStream inputStream = context.getContentResolver().openInputStream(fileUri);
            if (inputStream == null) {
                errorCallback.onError(context.getString(R.string.putty_import_error_invalid_file));
                return;
            }
            
            PuttyRegistryParser parser = new PuttyRegistryParser();
            PuttyRegistryParser.ParseResult result = parser.parseRegistryFile(inputStream, fileSize);
            inputStream.close();
            
            // Check for errors
            if (!result.getErrors().isEmpty()) {
                String error = result.getErrors().get(0);
                String errorMessage = getErrorMessage(error);
                errorCallback.onError(errorMessage);
                return;
            }
            
            // Check if there are any sessions that need import/update
            if (result.getValidSessions().isEmpty()) {
                errorCallback.onError(context.getString(R.string.putty_import_no_sessions_to_import));
                return;
            }
            
            // Perform filtering check to see if any sessions actually need import
            int importableCount = getImportableSessionsCount(result);
            if (importableCount == 0) {
                errorCallback.onError(context.getString(R.string.putty_import_all_sessions_exist));
                return;
            }
            
            // Show import dialog
            PuttyImportDialog dialog = PuttyImportDialog.newInstance(result);
            dialog.show(fragmentManager, "putty_import");
            
        } catch (Exception e) {
            Log.e(TAG, "Error handling PuTTY import file", e);
            errorCallback.onError(context.getString(R.string.putty_import_error_generic));
        }
    }
    
    /**
     * Get file size from URI.
     */
    private long getFileSize(Uri fileUri) {
        long fileSize = 0;
        try {
            android.database.Cursor cursor = context.getContentResolver().query(
                fileUri, null, null, null, null);
            if (cursor != null) {
                int sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE);
                if (sizeIndex != -1 && cursor.moveToFirst()) {
                    fileSize = cursor.getLong(sizeIndex);
                }
                cursor.close();
            }
        } catch (Exception e) {
            // Ignore, we'll check during parsing
        }
        return fileSize;
    }
    
    /**
     * Convert parser error to user-friendly message.
     */
    private String getErrorMessage(String error) {
        if (error.contains("too large")) {
            return context.getString(R.string.putty_import_error_file_too_large);
        } else if (error.contains("encoding")) {
            return context.getString(R.string.putty_import_error_encoding);
        } else if (error.contains("no sessions") || error.contains("No PuTTY")) {
            return context.getString(R.string.putty_import_error_no_sessions);
        } else {
            return context.getString(R.string.putty_import_error_invalid_file);
        }
    }
    
    /**
     * Count how many sessions from the parse result would actually be imported.
     */
    public int getImportableSessionsCount(PuttyRegistryParser.ParseResult result) {
        int importableCount = 0;
        HostDatabase hostDatabase = HostDatabase.get(context);
        List<HostBean> existingHosts = hostDatabase.getHosts(false);
        
        for (HostBean session : result.getValidSessions()) {
            HostBean existingHost = findExistingHost(existingHosts, session.getNickname());
            if (existingHost == null || hasSignificantDifferences(existingHost, session)) {
                importableCount++;
            }
        }
        
        return importableCount;
    }
    
    /**
     * Find an existing host by nickname.
     */
    public HostBean findExistingHost(List<HostBean> hosts, String nickname) {
        for (HostBean host : hosts) {
            if (host.getNickname().equals(nickname)) {
                return host;
            }
        }
        return null;
    }
    
    /**
     * Check if there are significant differences between two hosts.
     */
    public boolean hasSignificantDifferences(HostBean existingHost, HostBean session) {
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
        if (existingHost.getCompression() != session.getCompression()) {
            return true;
        }
        
        // For simplicity, skip detailed port forward comparison here
        // The PuttyImportDialog will do the full comparison
        return false;
    }
    
    /**
     * Callback interface for import errors.
     */
    public interface ImportErrorCallback {
        void onError(String message);
    }
}