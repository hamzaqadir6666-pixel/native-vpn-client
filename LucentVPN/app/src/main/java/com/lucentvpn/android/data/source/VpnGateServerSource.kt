package com.lucentvpn.android.data.source

import android.util.Log
import com.lucentvpn.android.data.model.VpnServer
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads the VPN Gate Public VPN Relay list.
 *
 * VPN Gate is an academic volunteer relay network run by the University of
 * Tsukuba. It publishes a machine-readable CSV specifically so that clients can
 * enumerate relays, and its relays need no account or registration.
 *
 * The list endpoint is frequently rate-limited or blocked at the network level,
 * so several published mirrors are tried in order before giving up.
 */
class VpnGateServerSource : ServerSource {

    override val displayName = "VPN Gate Public Relay Network"

    override val infoUrl = "https://www.vpngate.net/en/"

    override val trustNotice =
        "VPN Gate relays are operated by unpaid volunteers, not by this app. " +
            "Each operator sets their own logging policy, which is shown per " +
            "server. Treat these relays as protection against local network " +
            "snooping, not as anonymity from the relay operator."

    private val mirrors = listOf(
        "https://www.vpngate.net/api/iphone/",
        "http://www.vpngate.net/api/iphone/",
        "https://api.vpngate.net/api/iphone/",
    )

    override suspend fun fetchServers(): List<VpnServer> = withContext(Dispatchers.IO) {
        var lastFailure: ServerSourceException? = null

        for (mirror in mirrors) {
            try {
                val body = download(mirror)
                val servers = VpnGateCsvParser.parse(body)
                if (servers.isEmpty()) {
                    lastFailure = ServerSourceException(
                        "$mirror returned no usable relays",
                        ServerSourceException.Reason.NO_USABLE_SERVERS,
                    )
                    continue
                }
                Log.i(TAG, "Loaded ${servers.size} relays from $mirror")
                return@withContext servers
            } catch (io: InterruptedIOException) {
                // Cancellation or timeout: let cooperative cancellation win.
                throw io
            } catch (t: Throwable) {
                Log.w(TAG, "Mirror failed: $mirror (${t.message})")
                lastFailure = t as? ServerSourceException ?: ServerSourceException(
                    "Could not reach $mirror",
                    ServerSourceException.Reason.ENDPOINT_ERROR,
                    t,
                )
            }
        }

        throw lastFailure ?: ServerSourceException(
            "No VPN Gate mirror could be reached",
            ServerSourceException.Reason.ENDPOINT_ERROR,
        )
    }

    private fun download(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            // The CSV is ~1-3 MB uncompressed; always ask for gzip.
            setRequestProperty("Accept-Encoding", "gzip")
            setRequestProperty("User-Agent", USER_AGENT)
        }

        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw ServerSourceException(
                    "VPN Gate mirror answered HTTP $status",
                    ServerSourceException.Reason.ENDPOINT_ERROR,
                )
            }

            val raw = connection.inputStream
            val stream = if (
                connection.contentEncoding?.contains("gzip", ignoreCase = true) == true
            ) {
                GZIPInputStream(raw)
            } else {
                raw
            }

            return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TAG = "VpnGateSource"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val USER_AGENT = "LucentVPN/1.0 (Android)"
    }
}
