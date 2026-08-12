package com.lucentvpn.android.data.source

import android.util.Log
import com.lucentvpn.android.data.model.VpnServer
import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Downloads the public VPN Gate relay feed from HTTPS endpoints only. */
class VpnGateServerSource : ServerSource {
    override val displayName = "VPN Gate Public Relay Network"
    override val infoUrl = "https://www.vpngate.net/en/"
    override val trustNotice =
        "VPN Gate relays are operated by unpaid volunteers, not by this app. " +
            "Each operator sets their own logging policy, which is shown per server."

    private val mirrors = listOf(
        "https://www.vpngate.net/api/iphone/",
        "https://api.vpngate.net/api/iphone/",
    )

    override suspend fun fetchServers(): List<VpnServer> = withContext(Dispatchers.IO) {
        var lastFailure: ServerSourceException? = null
        for (mirror in mirrors) {
            ensureActive()
            try {
                val servers = VpnGateCsvParser.parse(download(mirror))
                if (servers.isNotEmpty()) {
                    Log.i(TAG, "Loaded ${servers.size} relays from $mirror")
                    return@withContext servers
                }
                lastFailure = ServerSourceException(
                    "$mirror returned no usable relays",
                    ServerSourceException.Reason.NO_USABLE_SERVERS,
                )
            } catch (cancelled: InterruptedIOException) {
                throw cancelled
            } catch (failure: Throwable) {
                Log.w(TAG, "Mirror failed: $mirror (${failure.message})")
                lastFailure = failure as? ServerSourceException ?: ServerSourceException(
                    "Could not reach $mirror",
                    ServerSourceException.Reason.ENDPOINT_ERROR,
                    failure,
                )
            }
        }
        throw lastFailure ?: ServerSourceException(
            "No VPN Gate mirror could be reached",
            ServerSourceException.Reason.ENDPOINT_ERROR,
        )
    }

    private fun download(initialUrl: String): String {
        var current = URL(initialUrl)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            require(current.protocol == "https") { "Refusing non-HTTPS relay feed" }
            val connection = (current.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("Accept-Encoding", "gzip")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                val status = connection.responseCode
                if (status in REDIRECT_CODES) {
                    if (redirectCount == MAX_REDIRECTS) throw endpoint("Too many redirects")
                    val location = connection.getHeaderField("Location") ?: throw endpoint("Redirect without Location")
                    val next = URL(current, location)
                    if (next.protocol != "https") throw endpoint("Refusing redirect to non-HTTPS feed")
                    current = next
                    return@repeat
                }
                if (status !in 200..299) throw endpoint("VPN Gate mirror answered HTTP $status")
                val declaredLength = connection.contentLengthLong
                if (declaredLength > MAX_DOWNLOAD_BYTES) throw endpoint("Relay feed is too large")

                val input = if (connection.contentEncoding?.contains("gzip", true) == true) {
                    GZIPInputStream(connection.inputStream)
                } else {
                    connection.inputStream
                }
                val output = ByteArrayOutputStream()
                input.use { stream ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > MAX_DOWNLOAD_BYTES) throw endpoint("Relay feed is too large")
                        output.write(buffer, 0, count)
                    }
                }
                return output.toString(Charsets.UTF_8.name())
            } finally {
                connection.disconnect()
            }
        }
        throw endpoint("Could not resolve relay feed redirect")
    }

    private fun endpoint(message: String) = ServerSourceException(
        message,
        ServerSourceException.Reason.ENDPOINT_ERROR,
    )

    private companion object {
        const val TAG = "VpnGateSource"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val MAX_DOWNLOAD_BYTES = 8 * 1024 * 1024
        const val MAX_REDIRECTS = 3
        const val USER_AGENT = "LucentVPN/1.0 (Android)"
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
