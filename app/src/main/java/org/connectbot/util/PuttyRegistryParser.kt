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

package org.connectbot.util

import org.connectbot.data.entity.Host
import org.connectbot.data.entity.PortForward
import java.io.InputStream
import java.net.URLDecoder
import java.text.Normalizer
import java.text.Normalizer.Form

/**
 * Parses Windows Registry Editor (.reg) files exported by PuTTY and extracts
 * SSH sessions as [Host] and [PortForward] objects.
 *
 * Parsing follows the PuTTY session registry layout under:
 *   HKEY_CURRENT_USER\Software\SimonTatham\PuTTY\Sessions\<name>
 *
 * Only sessions with Protocol = "ssh" and a non-blank HostName are imported.
 * A maximum of 100 sessions is returned; if more are present [ParseResult.truncated]
 * is set to `true`.
 */
class PuttyRegistryParser {

    companion object {
        private const val MAX_SESSIONS = 100
        private val SECTION_PATTERN = Regex(
            """^\[HKEY_CURRENT_USER\\Software\\SimonTatham\\PuTTY\\Sessions\\([^\]]+)\]$"""
        )
        private val STRING_VALUE_PATTERN = Regex("""^"([^"]+)"="(.*)"$""")
        private val DWORD_VALUE_PATTERN = Regex("""^"([^"]+)"=dword:([0-9A-Fa-f]+)$""")

        // Port forward entry: optional address-family digit, then L/R/D, then the rest
        private val PF_PATTERN = Regex(
            """^([46]?)([LRD])(\d+)(?:=([^:]+):(\d+))?$"""
        )

        private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        private val UTF16_LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        private val UTF16_BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
    }

    /**
     * Result returned by [parse].
     *
     * @param hosts          parsed SSH hosts (at most 100)
     * @param portForwards   port-forward lists keyed by session nickname
     * @param errors         fatal or session-level error messages
     * @param warnings       non-fatal warnings (e.g. duplicate names, bad port forwards)
     * @param truncated      true when the file contained more than 100 sessions
     */
    data class ParseResult(
        val hosts: List<Host>,
        val portForwards: Map<String, List<PortForward>>,
        val errors: List<String>,
        val warnings: List<String>,
        val truncated: Boolean,
    )

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Parse a .reg file from [stream] and return all discovered SSH sessions.
     */
    fun parse(stream: InputStream): ParseResult {
        val bytes = stream.readBytes()
        if (bytes.isEmpty()) {
            return ParseResult(
                hosts = emptyList(),
                portForwards = emptyMap(),
                errors = listOf("Input is empty"),
                warnings = emptyList(),
                truncated = false,
            )
        }

        val content = decodeContent(bytes)

        // Validate file header
        if (!content.contains("Windows Registry Editor") && !content.contains("[HKEY_")) {
            return ParseResult(
                hosts = emptyList(),
                portForwards = emptyMap(),
                errors = listOf("Not a valid Windows Registry Editor file"),
                warnings = emptyList(),
                truncated = false,
            )
        }

        return parseSections(content)
    }

    /**
     * Parse a PuTTY `PortForwardings` value string into a list of [PortForward] objects.
     *
     * Entries are comma-separated, each matching the pattern:
     *   `[4|6]L<srcPort>=<destHost>:<destPort>`  (local)
     *   `[4|6]R<srcPort>=<destHost>:<destPort>`  (remote)
     *   `[4|6]D<srcPort>`                         (dynamic SOCKS5)
     *
     * Invalid entries are silently skipped.
     *
     * @param spec the raw value string from the registry, or null
     * @return list of parsed port forwards; empty if spec is null or blank
     */
    fun parsePortForwards(spec: String?): List<PortForward> {
        if (spec.isNullOrBlank()) return emptyList()

        val result = mutableListOf<PortForward>()
        for (entry in spec.split(",")) {
            val trimmed = entry.trim()
            if (trimmed.isEmpty()) continue
            parseSinglePortForward(trimmed)?.let { result.add(it) }
        }
        return result
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /** Detect BOM and decode bytes to a String. Defaults to UTF-8. */
    private fun decodeContent(bytes: ByteArray): String {
        return when {
            bytes.startsWith(UTF8_BOM) ->
                String(bytes, UTF8_BOM.size, bytes.size - UTF8_BOM.size, Charsets.UTF_8)

            bytes.startsWith(UTF16_LE_BOM) ->
                String(bytes, UTF16_LE_BOM.size, bytes.size - UTF16_LE_BOM.size, Charsets.UTF_16LE)

            bytes.startsWith(UTF16_BE_BOM) ->
                String(bytes, UTF16_BE_BOM.size, bytes.size - UTF16_BE_BOM.size, Charsets.UTF_16BE)

            else ->
                String(bytes, Charsets.UTF_8)
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (this.size < prefix.size) return false
        for (i in prefix.indices) {
            if (this[i] != prefix[i]) return false
        }
        return true
    }

    /** Parse all PuTTY session sections found in [content]. */
    private fun parseSections(content: String): ParseResult {
        val hosts = mutableListOf<Host>()
        val portForwards = mutableMapOf<String, List<PortForward>>()
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        var truncated = false

        // Track NFC-normalized names to detect duplicates
        val seenNames = mutableSetOf<String>()

        // Split on section headers, keeping the delimiter so we can iterate
        val lines = content.lines()
        var currentSection: String? = null
        val currentKV = mutableMapOf<String, String>()

        fun processCurrentSection() {
            val sectionName = currentSection ?: return
            val rawName = try {
                URLDecoder.decode(sectionName, "UTF-8")
            } catch (_: Exception) {
                sectionName
            }

            // Skip "Default Settings"
            if (rawName == "Default Settings") {
                currentSection = null
                currentKV.clear()
                return
            }

            val nfcName = Normalizer.normalize(rawName, Form.NFC)

            // Deduplicate by NFC-normalized name
            if (!seenNames.add(nfcName)) {
                warnings.add("Duplicate session name skipped: $rawName")
                currentSection = null
                currentKV.clear()
                return
            }

            // Only import SSH sessions
            val protocol = currentKV["Protocol"]?.lowercase()
            if (protocol != "ssh") {
                currentSection = null
                currentKV.clear()
                return
            }

            val hostname = currentKV["HostName"] ?: ""
            if (hostname.isBlank()) {
                currentSection = null
                currentKV.clear()
                return
            }

            // Parse port (DWORD or default 22)
            val port = currentKV["PortNumber"]?.toIntOrNull()?.coerceIn(1, 65535) ?: 22

            // Parse compression (DWORD 0/1)
            val compression = currentKV["Compression"] == "1"

            val username = currentKV["UserName"] ?: ""

            if (hosts.size >= MAX_SESSIONS) {
                truncated = true
                currentSection = null
                currentKV.clear()
                return
            }

            val host = Host(
                nickname = nfcName,
                protocol = "ssh",
                hostname = hostname,
                port = port,
                username = username,
                compression = compression,
            )
            hosts.add(host)

            // Parse port forwards
            val pfSpec = currentKV["PortForwardings"]
            val pfs = parsePortForwards(pfSpec)
            if (pfs.isNotEmpty()) {
                portForwards[nfcName] = pfs
            }

            currentSection = null
            currentKV.clear()
        }

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            // Check for a new section header
            val sectionMatch = SECTION_PATTERN.matchEntire(line)
            if (sectionMatch != null) {
                processCurrentSection()
                currentSection = sectionMatch.groupValues[1]
                currentKV.clear()
                continue
            }

            // Parse key-value pairs inside the current section
            if (currentSection != null) {
                val stringMatch = STRING_VALUE_PATTERN.matchEntire(line)
                if (stringMatch != null) {
                    currentKV[stringMatch.groupValues[1]] = stringMatch.groupValues[2]
                    continue
                }

                val dwordMatch = DWORD_VALUE_PATTERN.matchEntire(line)
                if (dwordMatch != null) {
                    val hexValue = dwordMatch.groupValues[2]
                    val intValue = hexValue.toLongOrNull(16)?.toInt()
                    if (intValue != null) {
                        currentKV[dwordMatch.groupValues[1]] = intValue.toString()
                    }
                }
            }
        }

        // Process the last section (no trailing section header to trigger flush)
        processCurrentSection()

        return ParseResult(
            hosts = hosts,
            portForwards = portForwards,
            errors = errors,
            warnings = warnings,
            truncated = truncated,
        )
    }

    /**
     * Parse a single port-forward entry string (no commas).
     * Returns null if the entry cannot be parsed.
     */
    private fun parseSinglePortForward(entry: String): PortForward? {
        val match = PF_PATTERN.matchEntire(entry) ?: return null

        // group 1: optional address family (4 or 6), group 2: type letter (L/R/D)
        // group 3: source port, group 4: dest host (L/R only), group 5: dest port (L/R only)
        val typeChar = match.groupValues[2].uppercase()
        val srcPort = match.groupValues[3].toIntOrNull() ?: return null

        return when (typeChar) {
            "D" -> PortForward(
                hostId = 0L,
                nickname = "D$srcPort",
                type = HostConstants.PORTFORWARD_DYNAMIC5,
                sourceAddr = "localhost",
                sourcePort = srcPort,
                destAddr = null,
                destPort = 0,
            )

            "L", "R" -> {
                val destHost = match.groupValues[4].takeIf { it.isNotEmpty() } ?: return null
                val destPort = match.groupValues[5].toIntOrNull() ?: return null
                val pfType = if (typeChar == "L") {
                    HostConstants.PORTFORWARD_LOCAL
                } else {
                    HostConstants.PORTFORWARD_REMOTE
                }
                PortForward(
                    hostId = 0L,
                    nickname = "$typeChar${srcPort}=${destHost}:${destPort}",
                    type = pfType,
                    sourceAddr = "localhost",
                    sourcePort = srcPort,
                    destAddr = destHost,
                    destPort = destPort,
                )
            }

            else -> null
        }
    }
}
