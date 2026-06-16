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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Constructs a [NetworkInterface] with the given [name] and [addresses] using the
 * package-private JDK constructor (available since Java 6, stable through Java 21).
 * This avoids the need to mock a final JDK class.
 */
private fun fakeNetworkInterface(name: String, vararg addresses: InetAddress): NetworkInterface {
    val ctor = NetworkInterface::class.java.getDeclaredConstructor(
        String::class.java, Int::class.javaPrimitiveType, Array<InetAddress>::class.java
    )
    ctor.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    return ctor.newInstance(name, 0, addresses) as NetworkInterface
}

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

    // --- getHotspotInterfaceIP(List<NetworkInterface>) ---

    @Test
    fun getHotspotInterfaceIP_hotspotInterface_returnsPrivateIP() {
        // "ap0" matches the "ap" prefix; 192.168.43.1 is private IPv4
        val addr = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 43, 1))
        val iface = fakeNetworkInterface("ap0", addr)
        val result = NetworkUtils.getHotspotInterfaceIP(listOf(iface))
        assertEquals("192.168.43.1", result)
    }

    @Test
    fun getHotspotInterfaceIP_emptyList_returnsNull() {
        val result = NetworkUtils.getHotspotInterfaceIP(emptyList())
        assertNull(result)
    }

    @Test
    fun getHotspotInterfaceIP_cellularInterface_returnsNull() {
        // "rmnet0" is a cellular interface — must be rejected even with a private IP
        val addr = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1))
        val iface = fakeNetworkInterface("rmnet0", addr)
        val result = NetworkUtils.getHotspotInterfaceIP(listOf(iface))
        assertNull(result)
    }

    @Test
    fun getHotspotInterfaceIP_loopbackAddress_returnsNull() {
        // 127.0.0.1 is a loopback — must be rejected even on a hotspot-named interface
        val addr = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val iface = fakeNetworkInterface("ap0", addr)
        val result = NetworkUtils.getHotspotInterfaceIP(listOf(iface))
        assertNull(result)
    }

    @Test
    fun getHotspotInterfaceIP_ipv6Address_returnsNull() {
        // An IPv6 address contains ':' and must be rejected
        val addr = InetAddress.getByName("fe80::1")
        val iface = fakeNetworkInterface("ap0", addr)
        val result = NetworkUtils.getHotspotInterfaceIP(listOf(iface))
        assertNull(result)
    }

    @Test
    fun getHotspotInterfaceIP_multipleInterfaces_returnsFirstMatch() {
        // First interface is non-hotspot, second is hotspot; should return second's IP
        val nonHotspotAddr = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 2))
        val nonHotspot = fakeNetworkInterface("wlan0", nonHotspotAddr)

        val hotspotAddr1 = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 43, 1))
        val hotspot1 = fakeNetworkInterface("ap0", hotspotAddr1)

        val hotspotAddr2 = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 43, 2))
        val hotspot2 = fakeNetworkInterface("softap0", hotspotAddr2)

        val result = NetworkUtils.getHotspotInterfaceIP(listOf(nonHotspot, hotspot1, hotspot2))
        // First matching hotspot interface wins
        assertEquals("192.168.43.1", result)
    }

    // --- isHotspotInterfaceName: wlan1 prefix ambiguity ---

    @Test
    fun isHotspotInterfaceName_rejectsWlan10() {
        assertFalse(NetworkUtils.isHotspotInterfaceName("wlan10"))
    }

    @Test
    fun isHotspotInterfaceName_rejectsWlan11() {
        assertFalse(NetworkUtils.isHotspotInterfaceName("wlan11"))
    }

    @Test
    fun isHotspotInterfaceName_acceptsWlan1Exactly() {
        assertTrue(NetworkUtils.isHotspotInterfaceName("wlan1"))
    }

    // --- hasAccessPointStateChanged ---

    @Test
    fun hasAccessPointStateChanged_returnsTrueWhenApStateChanges() {
        // Use reflection to set lastKnownApIP to a known value (simulating "AP was on")
        val field = NetworkUtils::class.java.getDeclaredField("lastKnownApIP")
        field.isAccessible = true
        field.set(NetworkUtils, "192.168.43.1")

        // In test env, getAccessPointIP returns null (no real hotspot interfaces).
        // State changes from "192.168.43.1" to null → should return true.
        val mockContext = mock<Context>()
        val result = NetworkUtils.hasAccessPointStateChanged(mockContext)
        assertTrue(result)
    }

    @Test
    fun hasAccessPointStateChanged_returnsFalseWhenApStateUnchanged() {
        // Reset lastKnownApIP to null (matches what getAccessPointIP returns in test env)
        val field = NetworkUtils::class.java.getDeclaredField("lastKnownApIP")
        field.isAccessible = true
        field.set(NetworkUtils, null)

        // First call: null → null, no change → false
        val mockContext = mock<Context>()
        val result1 = NetworkUtils.hasAccessPointStateChanged(mockContext)
        assertFalse(result1)

        // Second call: still null → null → false
        val result2 = NetworkUtils.hasAccessPointStateChanged(mockContext)
        assertFalse(result2)
    }
}
