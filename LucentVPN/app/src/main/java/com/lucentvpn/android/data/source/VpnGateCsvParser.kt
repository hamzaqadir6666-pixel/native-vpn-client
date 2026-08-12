package com.lucentvpn.android.data.source

import android.util.Base64
import com.lucentvpn.android.data.model.VpnServer

/**
 * Parser for the VPN Gate public relay CSV.
 *
 * The document looks like this:
 *
 * ```
 * *vpn_servers
 * #HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,...,OpenVPN_ConfigData_Base64
 * vpn481923,219.100.37.1,21344,9,48211008,Japan,JP,...,<base64>
 * *
 * ```
 *
 * Columns are resolved **by header name**, never by fixed index, so the parser
 * keeps working if VPN Gate reorders or adds columns. Rows that are truncated,
 * unparseable, or missing a usable OpenVPN profile are skipped rather than
 * failing the whole refresh -- a malformed row is normal in a volunteer network.
 */
object VpnGateCsvParser {

    private const val COL_HOST = "hostname"
    private const val COL_IP = "ip"
    private const val COL_SCORE = "score"
    private const val COL_PING = "ping"
    private const val COL_SPEED = "speed"
    private const val COL_COUNTRY_LONG = "countrylong"
    private const val COL_COUNTRY_SHORT = "countryshort"
    private const val COL_SESSIONS = "numvpnsessions"
    private const val COL_UPTIME = "uptime"
    private const val COL_LOG_TYPE = "logtype"
    private const val COL_OPERATOR = "operator"
    private const val COL_CONFIG = "openvpn_configdata_base64"

    /**
     * @return every row that yielded a dialable relay. Never throws for bad
     *   rows; throws only when the document itself has no usable header.
     */
    fun parse(csv: String): List<VpnServer> {
        val lines = csv.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "*vpn_servers" && it != "*" }
            .toList()

        val headerLine = lines.firstOrNull { it.startsWith("#") }
            ?: throw ServerSourceException(
                "VPN Gate response contained no column header",
                ServerSourceException.Reason.MALFORMED_RESPONSE,
            )

        val columns = headerLine.removePrefix("#")
            .split(',')
            .map { it.trim().lowercase() }

        val index = columns.withIndex().associate { (i, name) -> name to i }

        // Without a host and a config there is nothing we could ever dial.
        if (!index.containsKey(COL_CONFIG) || !index.containsKey(COL_HOST)) {
            throw ServerSourceException(
                "VPN Gate header is missing the host or config column",
                ServerSourceException.Reason.MALFORMED_RESPONSE,
            )
        }

        val headerPosition = lines.indexOf(headerLine)
        val seenHosts = HashSet<String>()

        return lines.asSequence()
            .drop(headerPosition + 1)
            .filterNot { it.startsWith("#") || it.startsWith("*") }
            .mapNotNull { row -> parseRow(row, index) }
            // A relay occasionally appears twice in one document.
            .filter { seenHosts.add(it.id) }
            .toList()
    }

    private fun parseRow(row: String, index: Map<String, Int>): VpnServer? {
        val fields = row.split(',')

        fun field(name: String): String? {
            val i = index[name] ?: return null
            return fields.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() }
        }

        val host = field(COL_HOST) ?: return null
        val ip = field(COL_IP) ?: return null
        val rawConfig = field(COL_CONFIG) ?: return null

        val config = decodeConfig(rawConfig) ?: return null
        if (!isDialable(config)) return null

        val countryCode = field(COL_COUNTRY_SHORT)?.uppercase()?.take(2).orEmpty()

        return VpnServer(
            id = host,
            hostName = host,
            ipAddress = ip,
            countryName = field(COL_COUNTRY_LONG) ?: countryCode.ifEmpty { "Unknown" },
            countryCode = countryCode,
            reportedPingMs = field(COL_PING)?.toIntOrNull() ?: -1,
            speedBps = field(COL_SPEED)?.toLongOrNull() ?: 0L,
            sessions = field(COL_SESSIONS)?.toIntOrNull() ?: 0,
            uptimeMs = field(COL_UPTIME)?.toLongOrNull() ?: 0L,
            logPolicy = field(COL_LOG_TYPE) ?: "unknown",
            operator = field(COL_OPERATOR) ?: "unknown",
            score = field(COL_SCORE)?.toLongOrNull() ?: 0L,
            openVpnConfig = config,
            transport = detectTransport(config),
        )
    }

    private fun decodeConfig(base64: String): String? = try {
        // The column is standard base64, but tolerate URL-safe padding variants.
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        if (bytes.isEmpty()) null else String(bytes, Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        null
    }

    /**
     * A profile is only worth keeping if it names a remote and carries the CA
     * material inline. Anything that would make ics-openvpn prompt for an
     * external file is rejected here so it can never reach the connect path.
     */
    private fun isDialable(config: String): Boolean {
        val lower = config.lowercase()
        val hasRemote = lower.lineSequence().any { it.trimStart().startsWith("remote ") }
        val hasInlineCa = "<ca>" in lower
        val referencesExternalFile = lower.lineSequence().any {
            val line = it.trimStart()
            (line.startsWith("ca ") || line.startsWith("cert ") || line.startsWith("key ")) &&
                !line.contains("[[INLINE]]")
        }
        return hasRemote && hasInlineCa && !referencesExternalFile
    }

    private fun detectTransport(config: String): VpnServer.Transport {
        config.lineSequence().forEach { raw ->
            val line = raw.trim().lowercase()
            if (line.startsWith("proto ")) {
                return if (line.contains("tcp")) {
                    VpnServer.Transport.TCP
                } else {
                    VpnServer.Transport.UDP
                }
            }
            // "remote <host> <port> <proto>"
            if (line.startsWith("remote ")) {
                val parts = line.split(Regex("\\s+"))
                if (parts.size >= 4) {
                    return if (parts[3].startsWith("tcp")) {
                        VpnServer.Transport.TCP
                    } else {
                        VpnServer.Transport.UDP
                    }
                }
            }
        }
        // OpenVPN's own default when no proto is given.
        return VpnServer.Transport.UDP
    }
}
