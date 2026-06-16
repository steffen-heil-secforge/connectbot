/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2025-2026 Kenny Root
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

package org.connectbot.ui.screens.puttyimport

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import org.connectbot.R
import org.connectbot.util.NetworkUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PuttyImportScreen(
    onNavigateBack: () -> Unit,
    onImportDone: () -> Unit,
    viewModel: PuttyImportViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    // Navigate away when import completes
    LaunchedEffect(uiState.importResult) {
        if (uiState.importResult != null) {
            onImportDone()
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            viewModel.loadFile(uri)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.list_menu_import_putty)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        if (!uiState.isParsed) {
            // State 1: File not yet picked
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator()
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        uiState.error?.let { error ->
                            Text(
                                text = error,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                        Button(onClick = { filePicker.launch("*/*") }) {
                            Text(stringResource(R.string.putty_import_choose_file))
                        }
                    }
                }
            }
        } else {
            // State 2: File parsed — show session list + options
            val anySelected = uiState.sessions.any { it.selected }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Truncation warning
                if (uiState.truncated) {
                    item {
                        Text(
                            text = stringResource(R.string.putty_import_truncated),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }

                // Select All / Deselect All buttons
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(vertical = 4.dp),
                    ) {
                        OutlinedButton(onClick = { viewModel.selectAll(true) }) {
                            Text(stringResource(R.string.putty_import_select_all))
                        }
                        OutlinedButton(onClick = { viewModel.selectAll(false) }) {
                            Text(stringResource(R.string.putty_import_deselect_all))
                        }
                    }
                }

                // Session rows
                itemsIndexed(uiState.sessions) { index, session ->
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Checkbox(
                            checked = session.selected,
                            onCheckedChange = { viewModel.toggleSession(index) },
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(top = 12.dp),
                        ) {
                            Text(
                                text = session.host.nickname,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            if (session.existsAlready) {
                                Text(
                                    text = stringResource(R.string.putty_import_session_exists),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                )
                            }
                            val host = session.host
                            val userAtHost = buildString {
                                if (host.username.isNotEmpty()) {
                                    append(host.username)
                                    append("@")
                                }
                                append(host.hostname)
                                append(":")
                                append(host.port)
                            }
                            Text(
                                text = userAtHost,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (session.portForwards.isNotEmpty()) {
                                Text(
                                    text = stringResource(
                                        R.string.putty_import_port_forwards,
                                        session.portForwards.size,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                // Bind address section
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.putty_import_bind_address),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )

                    val bindOptions = listOf(
                        NetworkUtils.BIND_LOCALHOST to stringResource(R.string.bind_localhost),
                        NetworkUtils.BIND_ALL_INTERFACES to stringResource(R.string.bind_all_interfaces),
                        NetworkUtils.BIND_HOTSPOT to stringResource(R.string.bind_hotspot),
                    )

                    Column {
                        bindOptions.forEach { (value, label) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                RadioButton(
                                    selected = uiState.bindAddress == value,
                                    onClick = { viewModel.setBindAddress(value) },
                                )
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }

                        if (uiState.bindAddress != NetworkUtils.BIND_LOCALHOST) {
                            Text(
                                text = stringResource(R.string.security_warning_network_exposure),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }

                // Import button
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.importSelected() },
                        enabled = anySelected && !uiState.isLoading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (uiState.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .height(20.dp)
                                    .padding(end = 8.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                        Text(stringResource(R.string.putty_import_import))
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}
