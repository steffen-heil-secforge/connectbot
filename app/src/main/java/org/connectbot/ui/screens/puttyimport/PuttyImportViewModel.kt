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

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.connectbot.data.HostRepository
import org.connectbot.data.entity.Host
import org.connectbot.data.entity.PortForward
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.util.NetworkUtils
import org.connectbot.util.PuttyRegistryParser
import javax.inject.Inject

data class SessionItem(
    val host: Host,
    val portForwards: List<PortForward>,
    val existsAlready: Boolean,
    val selected: Boolean = true,
)

data class PuttyImportUiState(
    val sessions: List<SessionItem> = emptyList(),
    val bindAddress: String = NetworkUtils.BIND_LOCALHOST,
    val isLoading: Boolean = false,
    val isParsed: Boolean = false,
    val importResult: String? = null,
    val error: String? = null,
    val truncated: Boolean = false,
)

@HiltViewModel
class PuttyImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: HostRepository,
    private val dispatchers: CoroutineDispatchers,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PuttyImportUiState())
    val uiState: StateFlow<PuttyImportUiState> = _uiState.asStateFlow()

    fun loadFile(uri: Uri) {
        _uiState.update { it.copy(isLoading = true, error = null) }

        viewModelScope.launch {
            val result = withContext(dispatchers.io) {
                runCatching {
                    val stream = context.contentResolver.openInputStream(uri)
                        ?: return@runCatching null
                    stream.use { PuttyRegistryParser().parse(it) }
                }
            }

            result.fold(
                onSuccess = { parseResult ->
                    if (parseResult == null) {
                        _uiState.update {
                            it.copy(isLoading = false, error = "Could not open file")
                        }
                        return@fold
                    }

                    if (parseResult.errors.isNotEmpty() && parseResult.hosts.isEmpty()) {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = parseResult.errors.joinToString("\n"),
                            )
                        }
                        return@fold
                    }

                    val existingHosts = withContext(dispatchers.io) {
                        repository.getHosts()
                    }
                    val existingNicknames = existingHosts.map { it.nickname }.toSet()

                    val sessionItems = parseResult.hosts.map { host ->
                        SessionItem(
                            host = host,
                            portForwards = parseResult.portForwards[host.nickname] ?: emptyList(),
                            existsAlready = host.nickname in existingNicknames,
                        )
                    }

                    _uiState.update {
                        it.copy(
                            sessions = sessionItems,
                            isLoading = false,
                            isParsed = true,
                            truncated = parseResult.truncated,
                            error = null,
                        )
                    }
                },
                onFailure = { throwable ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = throwable.localizedMessage ?: "Unknown error reading file",
                        )
                    }
                },
            )
        }
    }

    fun toggleSession(index: Int) {
        _uiState.update { state ->
            val sessions = state.sessions.toMutableList()
            if (index in sessions.indices) {
                sessions[index] = sessions[index].copy(selected = !sessions[index].selected)
            }
            state.copy(sessions = sessions)
        }
    }

    fun selectAll(selected: Boolean) {
        _uiState.update { state ->
            state.copy(sessions = state.sessions.map { it.copy(selected = selected) })
        }
    }

    fun setBindAddress(addr: String) {
        _uiState.update { it.copy(bindAddress = addr) }
    }

    fun importSelected() {
        val currentState = _uiState.value
        val selectedSessions = currentState.sessions.filter { it.selected }
        if (selectedSessions.isEmpty()) return

        viewModelScope.launch {
            val bindAddress = currentState.bindAddress

            withContext(dispatchers.io) {
                for (sessionItem in selectedSessions) {
                    val parsedHost = sessionItem.host

                    // Check if a host with the same nickname already exists
                    val existingHost = repository.findHost(mapOf("nickname" to parsedHost.nickname))

                    val hostToSave = if (existingHost != null) {
                        // Update only connection-related fields; preserve user preferences
                        existingHost.copy(
                            hostname = parsedHost.hostname,
                            port = parsedHost.port,
                            username = parsedHost.username,
                            compression = parsedHost.compression,
                            protocol = parsedHost.protocol,
                        )
                    } else {
                        parsedHost
                    }

                    val savedHost = repository.saveHost(hostToSave)

                    // Delete existing port forwards for this host and re-insert parsed ones
                    val existingPortForwards = repository.getPortForwardsForHost(savedHost.id)
                    for (pf in existingPortForwards) {
                        repository.deletePortForward(pf)
                    }

                    for (pf in sessionItem.portForwards) {
                        repository.savePortForward(
                            pf.copy(hostId = savedHost.id, sourceAddr = bindAddress),
                        )
                    }
                }
            }

            val count = selectedSessions.size
            val message = if (count == 1) {
                "Imported 1 session"
            } else {
                "Imported $count sessions"
            }

            _uiState.update { it.copy(importResult = message) }
        }
    }
}
