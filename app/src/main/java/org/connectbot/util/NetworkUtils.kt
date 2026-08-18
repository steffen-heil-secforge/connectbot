/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2025 The ConnectBot Contributors
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

import android.content.Context
import java.net.NetworkInterface

object NetworkUtils {

    const val BIND_LOCALHOST = "localhost"
    const val BIND_ALL_INTERFACES = "0.0.0.0"
    const val BIND_HOTSPOT = "wifi_hotspot"

    private val HOTSPOT_INTERFACE_PREFIXES = listOf("ap", "wlan1", "p2p", "hotspot", "softap", "wifi_ap")
    private val CELLULAR_INTERFACE_PREFIXES = listOf("rmnet", "ccmni", "pdp", "ppp", "cellular", "mobile", "radio", "baseband")

    private var lastKnownApIP: String? = null

    /** Pure logic: resolve symbolic bind address to actual IP string. Returns null if hotspot unavailable. */
    fun resolveBindAddress(bindAddress: String, apIP: String?): String? = when (bindAddress) {
        BIND_ALL_INTERFACES -> BIND_ALL_INTERFACES
        BIND_HOTSPOT -> apIP  // intentional null — caller must handle unavailability
        else -> "127.0.0.1"
    }

    /** Returns the device's WiFi hotspot interface IP, or null if hotspot is off. */
    fun getAccessPointIP(context: Context): String? =
        getHotspotInterfaceIP(NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList())

    /** Pure-logic overload (testable without Context). */
    fun getHotspotInterfaceIP(interfaces: List<NetworkInterface>): String? {
        for (iface in interfaces) {
            if (!isHotspotInterfaceName(iface.name)) continue
            if (isCellularInterface(iface.name)) continue
            for (addr in iface.inetAddresses) {
                val ip = addr.hostAddress ?: continue
                if (!addr.isLoopbackAddress && isPrivateIPv4(ip)) return ip
            }
        }
        return null
    }

    fun isHotspotInterfaceName(name: String): Boolean =
        HOTSPOT_INTERFACE_PREFIXES.any { prefix ->
            when (prefix) {
                "wlan1" -> name == "wlan1" || (name.startsWith("wlan1") && name.length > 5 && !name[5].isDigit())
                else -> name.startsWith(prefix)
            }
        }

    private fun isCellularInterface(name: String): Boolean =
        CELLULAR_INTERFACE_PREFIXES.any { name.startsWith(it) }

    fun isPrivateIPv4(ip: String): Boolean {
        if (ip.contains(':')) return false  // IPv6
        val parts = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        return when (parts[0]) {
            10 -> true
            172 -> parts[1] in 16..31
            192 -> parts[1] == 168
            else -> false
        }
    }

    /** Detects whether the AP state has changed since the last check. Thread-safe. */
    fun hasAccessPointStateChanged(context: Context): Boolean =
        synchronized(this) {
            val current = getAccessPointIP(context)
            if (current != lastKnownApIP) {
                lastKnownApIP = current
                true
            } else false
        }

    /** Display string for use in UI and notifications. */
    fun getBindAddressDisplayName(bindAddress: String, apIP: String?): String = when (bindAddress) {
        BIND_ALL_INTERFACES -> "All interfaces (0.0.0.0)"
        BIND_HOTSPOT -> if (apIP != null) "WiFi hotspot ($apIP)" else "WiFi hotspot (unavailable)"
        else -> "Localhost"
    }
}
