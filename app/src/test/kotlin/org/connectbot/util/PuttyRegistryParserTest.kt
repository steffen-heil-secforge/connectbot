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

import org.connectbot.util.HostConstants.PORTFORWARD_DYNAMIC5
import org.connectbot.util.HostConstants.PORTFORWARD_LOCAL
import org.connectbot.util.HostConstants.PORTFORWARD_REMOTE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class PuttyRegistryParserTest {

    private val parser = PuttyRegistryParser()

    // -------------------------------------------------------------------------
    // Helper: build a minimal valid .reg file string for a single SSH session
    // -------------------------------------------------------------------------

    private fun regFile(
        sessionName: String = "MyServer",
        hostname: String = "example.com",
        username: String = "admin",
        port: String = """dword:00000016""",   // 22 decimal
        protocol: String = "ssh",
        compression: String = """dword:00000000""",
        extras: String = "",
    ): String = """
        Windows Registry Editor Version 5.00

        [HKEY_CURRENT_USER\Software\SimonTatham\PuTTY\Sessions\$sessionName]
        "HostName"="$hostname"
        "UserName"="$username"
        "PortNumber"=$port
        "Protocol"="$protocol"
        "Compression"=$compression
        $extras
    """.trimIndent()

    private fun stream(text: String): ByteArrayInputStream =
        ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))

    // =========================================================================
    // Test 1: Basic SSH session parsed correctly (host, port, user)
    // =========================================================================
    @Test
    fun basicSshSession_parsesHostPortUser() {
        val result = parser.parse(stream(regFile()))

        assertEquals("Expected exactly 1 host", 1, result.hosts.size)
        assertTrue("Expected no errors but got: ${result.errors}", result.errors.isEmpty())

        val host = result.hosts[0]
        assertEquals("example.com", host.hostname)
        assertEquals("admin", host.username)
        assertEquals(22, host.port)
        assertEquals("ssh", host.protocol)
        assertEquals("MyServer", host.nickname)
        assertFalse(host.compression)
    }

    // =========================================================================
    // Test 2: URL-decoded session name
    // =========================================================================
    @Test
    fun urlEncodedSessionName_isDecoded() {
        // PuTTY URL-encodes spaces and special chars in the registry key
        val result = parser.parse(stream(regFile(sessionName = "My%20Server%21")))

        assertEquals(1, result.hosts.size)
        assertEquals("My Server!", result.hosts[0].nickname)
    }

    // =========================================================================
    // Test 3: Non-SSH session rejected (telnet, raw, serial …)
    // =========================================================================
    @Test
    fun nonSshSession_isSkipped() {
        val result = parser.parse(stream(regFile(protocol = "telnet")))

        assertEquals("Non-SSH session should be skipped", 0, result.hosts.size)
    }

    // =========================================================================
    // Test 4: DWORD port number parsed from hex (0x00000539 = 1337)
    // =========================================================================
    @Test
    fun dwordPortHex_parsedCorrectly() {
        val result = parser.parse(stream(regFile(port = "dword:00000539")))

        assertEquals(1, result.hosts.size)
        assertEquals(1337, result.hosts[0].port)
    }

    // =========================================================================
    // Test 5: Local port forward (L8080=localhost:80)
    // =========================================================================
    @Test
    fun localPortForward_parsedCorrectly() {
        val reg = regFile(extras = """"PortForwardings"="L8080=localhost:80"""")
        val result = parser.parse(stream(reg))

        assertEquals(1, result.hosts.size)
        val pfs = result.portForwards[result.hosts[0].nickname].orEmpty()
        assertEquals(1, pfs.size)
        val pf = pfs[0]
        assertEquals(PORTFORWARD_LOCAL, pf.type)
        assertEquals(8080, pf.sourcePort)
        assertEquals("localhost", pf.destAddr)
        assertEquals(80, pf.destPort)
    }

    // =========================================================================
    // Test 6: Dynamic SOCKS forward (D1080)
    // =========================================================================
    @Test
    fun dynamicSocksForward_parsedCorrectly() {
        val reg = regFile(extras = """"PortForwardings"="D1080"""")
        val result = parser.parse(stream(reg))

        assertEquals(1, result.hosts.size)
        val pfs = result.portForwards[result.hosts[0].nickname].orEmpty()
        assertEquals(1, pfs.size)
        val pf = pfs[0]
        assertEquals(PORTFORWARD_DYNAMIC5, pf.type)
        assertEquals(1080, pf.sourcePort)
    }

    // =========================================================================
    // Test 7: Remote port forward (R9090=127.0.0.1:9090)
    // =========================================================================
    @Test
    fun remotePortForward_parsedCorrectly() {
        val reg = regFile(extras = """"PortForwardings"="R9090=127.0.0.1:9090"""")
        val result = parser.parse(stream(reg))

        assertEquals(1, result.hosts.size)
        val pfs = result.portForwards[result.hosts[0].nickname].orEmpty()
        assertEquals(1, pfs.size)
        val pf = pfs[0]
        assertEquals(PORTFORWARD_REMOTE, pf.type)
        assertEquals(9090, pf.sourcePort)
        assertEquals("127.0.0.1", pf.destAddr)
        assertEquals(9090, pf.destPort)
    }

    // =========================================================================
    // Test 8: Empty / invalid content returns error and no hosts
    // =========================================================================
    @Test
    fun emptyContent_returnsError() {
        val result = parser.parse(stream(""))

        assertTrue("Expected at least one error for empty input", result.errors.isNotEmpty())
        assertTrue("Expected no hosts for empty input", result.hosts.isEmpty())
    }

    @Test
    fun invalidContent_returnsError() {
        val result = parser.parse(stream("This is not a registry file"))

        assertTrue("Expected at least one error for invalid input", result.errors.isNotEmpty())
        assertTrue("Expected no hosts for invalid input", result.hosts.isEmpty())
    }

    // =========================================================================
    // Test 9: UTF-8 BOM handled transparently
    // =========================================================================
    @Test
    fun utf8BomContent_handledTransparently() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val body = regFile().toByteArray(Charsets.UTF_8)
        val withBom = ByteArrayInputStream(bom + body)

        val result = parser.parse(withBom)

        assertEquals("BOM should not cause parse failure", 1, result.hosts.size)
        assertTrue("Unexpected errors: ${result.errors}", result.errors.isEmpty())
    }

    // =========================================================================
    // Test 10: 100-session cap with truncated flag
    // =========================================================================
    @Test
    fun hundredAndOneSessions_capped_truncatedFlagSet() {
        val sb = StringBuilder("Windows Registry Editor Version 5.00\n\n")
        for (i in 1..101) {
            sb.append("[HKEY_CURRENT_USER\\Software\\SimonTatham\\PuTTY\\Sessions\\Session$i]\n")
            sb.append("\"HostName\"=\"host$i.example.com\"\n")
            sb.append("\"Protocol\"=\"ssh\"\n")
            sb.append("\"UserName\"=\"user\"\n")
            sb.append("\"PortNumber\"=dword:00000016\n")
            sb.append("\n")
        }
        val result = parser.parse(stream(sb.toString()))

        assertEquals("Should cap at 100", 100, result.hosts.size)
        assertTrue("truncated flag should be set", result.truncated)
    }

    // =========================================================================
    // Test 11: "Default Settings" session skipped
    // =========================================================================
    @Test
    fun defaultSettings_isSkipped() {
        val reg = """
            Windows Registry Editor Version 5.00

            [HKEY_CURRENT_USER\Software\SimonTatham\PuTTY\Sessions\Default%20Settings]
            "HostName"="example.com"
            "Protocol"="ssh"
            "UserName"="user"
            "PortNumber"=dword:00000016
        """.trimIndent()

        val result = parser.parse(stream(reg))

        assertEquals("Default Settings should be skipped", 0, result.hosts.size)
    }

    // =========================================================================
    // Test 12: Duplicate NFC-normalized names deduplicated
    // =========================================================================
    @Test
    fun duplicateNfcNames_deduplicated() {
        // Both names normalize to the same NFC form — second one is a duplicate
        // U+00E9 (é precomposed) vs U+0065 U+0301 (e + combining accent)
        val name1 = "café"          // precomposed é
        val name2 = "café"         // decomposed é (NFC → same as name1)

        val reg = """
            Windows Registry Editor Version 5.00

            [HKEY_CURRENT_USER\Software\SimonTatham\PuTTY\Sessions\$name1]
            "HostName"="server1.example.com"
            "Protocol"="ssh"
            "UserName"="user"
            "PortNumber"=dword:00000016

            [HKEY_CURRENT_USER\Software\SimonTatham\PuTTY\Sessions\$name2]
            "HostName"="server2.example.com"
            "Protocol"="ssh"
            "UserName"="user"
            "PortNumber"=dword:00000016
        """.trimIndent()

        val result = parser.parse(stream(reg))

        assertEquals("Duplicate NFC names should be deduplicated to 1 host", 1, result.hosts.size)
        assertTrue("Expected a warning about duplicate", result.warnings.isNotEmpty())
    }

    // =========================================================================
    // Test 13: Compression flag read from dword (0=false, 1=true)
    // =========================================================================
    @Test
    fun compressionEnabled_parsedFromDword() {
        val result = parser.parse(stream(regFile(compression = "dword:00000001")))

        assertEquals(1, result.hosts.size)
        assertTrue(result.hosts[0].compression)
    }

    // =========================================================================
    // Test 14: Session with blank hostname is skipped
    // =========================================================================
    @Test
    fun blankHostname_sessionSkipped() {
        val result = parser.parse(stream(regFile(hostname = "")))

        assertEquals("Session with blank hostname should be skipped", 0, result.hosts.size)
    }

    // =========================================================================
    // Test 15: Multiple port forwards (comma-separated) parsed correctly
    // =========================================================================
    @Test
    fun multiplePortForwards_parsedCorrectly() {
        val reg = regFile(extras = """"PortForwardings"="L8080=localhost:80,D1080,R9090=127.0.0.1:9090"""")
        val result = parser.parse(stream(reg))

        assertEquals(1, result.hosts.size)
        val pfs = result.portForwards[result.hosts[0].nickname].orEmpty()
        assertEquals(3, pfs.size)
    }

    // =========================================================================
    // Test 16: parsePortForwards public helper — direct unit test
    // =========================================================================
    @Test
    fun parsePortForwards_dynamicWithAddressFamily() {
        // 6D1080 — address-family=6 (IPv6) prefix before direction, still parses as dynamic5
        val pfs = parser.parsePortForwards("6D1080")
        assertEquals(1, pfs.size)
        assertEquals(PORTFORWARD_DYNAMIC5, pfs[0].type)
        assertEquals(1080, pfs[0].sourcePort)
    }

    @Test
    fun parsePortForwards_localWithAddressFamily4() {
        // 4L8080=localhost:80 — address-family=4 (IPv4) prefix before direction
        val pfs = parser.parsePortForwards("4L8080=localhost:80")
        assertEquals(1, pfs.size)
        assertEquals(PORTFORWARD_LOCAL, pfs[0].type)
        assertEquals(8080, pfs[0].sourcePort)
        assertEquals("localhost", pfs[0].destAddr)
        assertEquals(80, pfs[0].destPort)
    }

    @Test
    fun parsePortForwards_nullSpec_returnsEmptyList() {
        val pfs = parser.parsePortForwards(null)
        assertTrue(pfs.isEmpty())
    }

    @Test
    fun parsePortForwards_emptySpec_returnsEmptyList() {
        val pfs = parser.parsePortForwards("")
        assertTrue(pfs.isEmpty())
    }

    // =========================================================================
    // Test 17: Out-of-range source port rejected
    // =========================================================================
    @Test
    fun `parseSinglePortForward skips out-of-range source port`() {
        val result = parser.parsePortForwards("L0=host:80")
        assertTrue(result.isEmpty())
    }

    // =========================================================================
    // Test 18: Out-of-range destination port rejected
    // =========================================================================
    @Test
    fun `parseSinglePortForward skips out-of-range dest port`() {
        val result = parser.parsePortForwards("L8080=host:99999")
        assertTrue(result.isEmpty())
    }
}
