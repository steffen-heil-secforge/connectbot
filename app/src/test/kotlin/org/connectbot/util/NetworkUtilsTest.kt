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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkUtilsTest {

    // --- resolveBindAddress ---

    @Test
    fun resolveBindAddress_localhost_returns127() {
        val result = NetworkUtils.resolveBindAddress(NetworkUtils.BIND_LOCALHOST, apIP = null)
        assertEquals("127.0.0.1", result)
    }

    @Test
    fun resolveBindAddress_allInterfaces_returns0000() {
        val result = NetworkUtils.resolveBindAddress(NetworkUtils.BIND_ALL_INTERFACES, apIP = null)
        assertEquals("0.0.0.0", result)
    }

    @Test
    fun resolveBindAddress_hotspot_withApIP_returnsApIP() {
        val result = NetworkUtils.resolveBindAddress(NetworkUtils.BIND_HOTSPOT, apIP = "192.168.43.1")
        assertEquals("192.168.43.1", result)
    }

    @Test
    fun resolveBindAddress_hotspot_withNullApIP_returnsNull() {
        val result = NetworkUtils.resolveBindAddress(NetworkUtils.BIND_HOTSPOT, apIP = null)
        assertNull(result)
    }

    // --- isHotspotInterfaceName ---

    @Test
    fun isHotspotInterfaceName_acceptsAp0() {
        assertTrue(NetworkUtils.isHotspotInterfaceName("ap0"))
    }

    @Test
    fun isHotspotInterfaceName_acceptsWlan1() {
        assertTrue(NetworkUtils.isHotspotInterfaceName("wlan1"))
    }

    @Test
    fun isHotspotInterfaceName_acceptsP2p0() {
        assertTrue(NetworkUtils.isHotspotInterfaceName("p2p0"))
    }

    @Test
    fun isHotspotInterfaceName_acceptsHotspot0() {
        assertTrue(NetworkUtils.isHotspotInterfaceName("hotspot0"))
    }

    @Test
    fun isHotspotInterfaceName_acceptsSoftap0() {
        assertTrue(NetworkUtils.isHotspotInterfaceName("softap0"))
    }

    @Test
    fun isHotspotInterfaceName_acceptsWifiAp0() {
        assertTrue(NetworkUtils.isHotspotInterfaceName("wifi_ap0"))
    }

    @Test
    fun isHotspotInterfaceName_rejectsWlan0() {
        assertFalse(NetworkUtils.isHotspotInterfaceName("wlan0"))
    }

    @Test
    fun isHotspotInterfaceName_rejectsRmnet0() {
        assertFalse(NetworkUtils.isHotspotInterfaceName("rmnet0"))
    }

    @Test
    fun isHotspotInterfaceName_rejectsEth0() {
        assertFalse(NetworkUtils.isHotspotInterfaceName("eth0"))
    }

    // --- isPrivateIPv4 ---

    @Test
    fun isPrivateIPv4_accepts192_168() {
        assertTrue(NetworkUtils.isPrivateIPv4("192.168.43.1"))
    }

    @Test
    fun isPrivateIPv4_accepts10_0_0_1() {
        assertTrue(NetworkUtils.isPrivateIPv4("10.0.0.1"))
    }

    @Test
    fun isPrivateIPv4_accepts172_16_0_1() {
        assertTrue(NetworkUtils.isPrivateIPv4("172.16.0.1"))
    }

    @Test
    fun isPrivateIPv4_rejectsPublicIP() {
        assertFalse(NetworkUtils.isPrivateIPv4("8.8.8.8"))
    }

    @Test
    fun isPrivateIPv4_rejectsIPv6() {
        assertFalse(NetworkUtils.isPrivateIPv4("fe80::1"))
        assertFalse(NetworkUtils.isPrivateIPv4("2001:db8::1"))
    }

    @Test
    fun isPrivateIPv4_rejectsMalformed() {
        assertFalse(NetworkUtils.isPrivateIPv4("not.an.ip"))
        assertFalse(NetworkUtils.isPrivateIPv4(""))
        assertFalse(NetworkUtils.isPrivateIPv4("192.168"))
    }

    // --- getBindAddressDisplayName ---

    @Test
    fun getBindAddressDisplayName_localhost_returnsLocalhostLabel() {
        val result = NetworkUtils.getBindAddressDisplayName(NetworkUtils.BIND_LOCALHOST, apIP = null)
        assertEquals("Localhost", result)
    }

    @Test
    fun getBindAddressDisplayName_allInterfaces_returnsAllInterfacesLabel() {
        val result = NetworkUtils.getBindAddressDisplayName(NetworkUtils.BIND_ALL_INTERFACES, apIP = null)
        assertEquals("All interfaces (0.0.0.0)", result)
    }

    @Test
    fun getBindAddressDisplayName_hotspot_withApIP_includesIP() {
        val result = NetworkUtils.getBindAddressDisplayName(NetworkUtils.BIND_HOTSPOT, apIP = "192.168.43.1")
        assertEquals("WiFi hotspot (192.168.43.1)", result)
    }

    @Test
    fun getBindAddressDisplayName_hotspot_withNullApIP_showsUnavailable() {
        val result = NetworkUtils.getBindAddressDisplayName(NetworkUtils.BIND_HOTSPOT, apIP = null)
        assertEquals("WiFi hotspot (unavailable)", result)
    }
}
