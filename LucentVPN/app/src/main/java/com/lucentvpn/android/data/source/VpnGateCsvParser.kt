package com.lucentvpn.android.data.source

import com.lucentvpn.android.data.model.VpnServer
import java.util.Base64

/** Strict, row-tolerant parser for VPN Gate's public relay CSV. */
object VpnGateCsvParser {
    private const val MAX_CONFIG_BYTES = 512 * 1024

    fun parse(csv: String): List<VpnServer> {
        val records = parseCsv(csv)
        val headerIndex = records.indexOfFirst { it.firstOrNull()?.trim()?.startsWith("#HostName") == true }
        if (headerIndex < 0) throw malformed("VPN Gate response contained no column header")

        val names = records[headerIndex].mapIndexed { index, value ->
            (if (index == 0) value.removePrefix("#") else value).trim().lowercase()
        }
        val columns = names.withIndex().associate { it.value to it.index }
        if (columns["hostname"] == null || columns["openvpn_configdata_base64"] == null) {
            throw malformed("VPN Gate header is missing the host or config column")
        }

        return records.asSequence()
            .drop(headerIndex + 1)
            .mapNotNull { parseRow(it, columns) }
            .distinctBy { it.id.lowercase() }
            .toList()
    }

    private fun parseRow(fields: List<String>, columns: Map<String, Int>): VpnServer? {
        fun field(name: String) = columns[name]?.let(fields::getOrNull)?.trim()?.takeIf(String::isNotEmpty)

        val host = field("hostname") ?: return null
        val ip = field("ip") ?: return null
        if (!HOST_PATTERN.matches(host) || !IPV4_PATTERN.matches(ip)) return null

        val config = decodeConfig(field("openvpn_configdata_base64") ?: return null) ?: return null
        val remote = validatedRemote(config) ?: return null
        val countryCode = field("countryshort")?.uppercase()?.takeIf { it.matches(Regex("[A-Z]{2}")) }.orEmpty()

        return VpnServer(
            id = host.lowercase(),
            hostName = host,
            ipAddress = ip,
            countryName = field("countrylong") ?: countryCode.ifEmpty { "Unknown" },
            countryCode = countryCode,
            reportedPingMs = field("ping")?.toIntOrNull()?.takeIf { it >= 0 } ?: -1,
            speedBps = field("speed")?.toLongOrNull()?.coerceAtLeast(0) ?: 0,
            sessions = field("numvpnsessions")?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
            uptimeMs = field("uptime")?.toLongOrNull()?.coerceAtLeast(0) ?: 0,
            logPolicy = field("logtype") ?: "unknown",
            operator = field("operator") ?: "unknown",
            score = field("score")?.toLongOrNull()?.coerceAtLeast(0) ?: 0,
            openVpnConfig = config,
            transport = remote.transport,
        )
    }

    private fun decodeConfig(encoded: String): String? = runCatching {
        val compact = encoded.filterNot(Char::isWhitespace)
        val bytes = Base64.getDecoder().decode(compact)
        if (bytes.isEmpty() || bytes.size > MAX_CONFIG_BYTES) return null
        bytes.toString(Charsets.UTF_8).takeIf { '\u0000' !in it }
    }.getOrNull()

    private data class Remote(val transport: VpnServer.Transport)

    private fun validatedRemote(config: String): Remote? {
        val directives = config.lineSequence()
            .map { it.substringBefore('#').substringBefore(';').trim() }
            .filter(String::isNotEmpty)
            .toList()
        val remoteParts = directives.firstOrNull { it.startsWith("remote ", ignoreCase = true) }
            ?.split(Regex("\\s+")) ?: return null
        if (remoteParts.size < 3) return null
        val remoteHost = remoteParts[1]
        val port = remoteParts[2].toIntOrNull() ?: return null
        if (!HOST_PATTERN.matches(remoteHost) || port !in 1..65535) return null

        val lower = config.lowercase()
        if (!lower.contains("<ca>") || !lower.contains("</ca>")) return null
        if (directives.any { line ->
                val normalized = line.lowercase()
                listOf("ca ", "cert ", "key ", "tls-auth ", "tls-crypt ").any(normalized::startsWith) &&
                    !normalized.contains("[[inline]]")
            }
        ) return null

        val proto = remoteParts.getOrNull(3)
            ?: directives.firstOrNull { it.startsWith("proto ", true) }?.substringAfter(' ')
        return Remote(if (proto?.contains("tcp", true) == true) VpnServer.Transport.TCP else VpnServer.Transport.UDP)
    }

    /** Handles quoted fields, escaped quotes, CRLF, and newlines inside quoted fields. */
    private fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                char == '"' && quoted && text.getOrNull(index + 1) == '"' -> {
                    field.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    row += field.toString()
                    field.clear()
                }
                (char == '\n' || char == '\r') && !quoted -> {
                    if (char == '\r' && text.getOrNull(index + 1) == '\n') index++
                    row += field.toString()
                    field.clear()
                    if (row.any(String::isNotBlank)) rows += row.toList()
                    row.clear()
                }
                else -> field.append(char)
            }
            index++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row += field.toString()
            if (row.any(String::isNotBlank)) rows += row
        }
        return rows
    }

    private fun malformed(message: String) = ServerSourceException(
        message,
        ServerSourceException.Reason.MALFORMED_RESPONSE,
    )

    private val HOST_PATTERN = Regex("[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?")
    private val IPV4_PATTERN = Regex("(?:\\d{1,3}\\.){3}\\d{1,3}")
}
