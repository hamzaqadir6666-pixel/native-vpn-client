package com.lucentvpn.android.data.source

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnGateCsvParserTest {
    private val header =
        "#HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,Uptime,LogType,Operator,OpenVPN_ConfigData_Base64"

    @Test
    fun parsesValidProfileAndQuotedFields() {
        val config = """
            client
            proto tcp-client
            remote 198.51.100.8 443
            <ca>
            -----BEGIN CERTIFICATE-----
            test
            -----END CERTIFICATE-----
            </ca>
        """.trimIndent()
        val csv = "$header\nrelay1,198.51.100.8,10,20,1000000,\"Korea, Republic\",KR,3,1000,2weeks,Volunteer,${encode(config)}\n*"

        val servers = VpnGateCsvParser.parse(csv)

        assertEquals(1, servers.size)
        assertEquals("Korea, Republic", servers.single().countryName)
        assertEquals(com.lucentvpn.android.data.model.VpnServer.Transport.TCP, servers.single().transport)
    }

    @Test
    fun rejectsInvalidBase64RemotePortAndExternalCertificate() {
        val invalidPort = "remote example.com 70000\n<ca>\nx\n</ca>"
        val externalCert = "remote example.com 443\nca ca.crt\n<ca>\nx\n</ca>"
        val csv = buildString {
            appendLine(header)
            appendLine("bad1,192.0.2.1,1,1,1,Test,US,0,0,x,x,not-base64")
            appendLine("bad2,192.0.2.2,1,1,1,Test,US,0,0,x,x,${encode(invalidPort)}")
            appendLine("bad3,192.0.2.3,1,1,1,Test,US,0,0,x,x,${encode(externalCert)}")
        }

        assertTrue(VpnGateCsvParser.parse(csv).isEmpty())
    }

    @Test
    fun deduplicatesHostNamesCaseInsensitively() {
        val config = "remote example.com 443\n<ca>\nx\n</ca>"
        val row = ",192.0.2.1,1,1,1,Test,US,0,0,x,x,${encode(config)}"
        val parsed = VpnGateCsvParser.parse("$header\nRelay$row\nrelay$row")
        assertEquals(1, parsed.size)
    }

    private fun encode(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray())
}
