package com.lucentvpn.android.data

import com.lucentvpn.android.data.model.VpnServer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Measures real reachability by timing a TCP handshake against the relay's own
 * OpenVPN port.
 *
 * This doubles as the health check the connect path relies on: a relay that
 * will not complete a TCP handshake is not going to complete a VPN handshake
 * either, so probing first lets us skip dead volunteers before spending the
 * user's time on a full connection attempt.
 *
 * ICMP ping is deliberately not used -- it needs a raw socket, is blocked by
 * many of these hosts, and says nothing about whether the VPN port is open.
 */
object LatencyProbe {

    private const val PROBE_TIMEOUT_MS = 1_500
    private const val MAX_PARALLEL_PROBES = 24

    /** Result of probing one relay. */
    data class Probe(val server: VpnServer, val latencyMs: Int?)

    /**
     * Probes [servers] concurrently, in bounded batches so we neither stall on
     * a slow relay nor open hundreds of sockets at once.
     *
     * @return the same servers, with [VpnServer.measuredPingMs] populated where
     *   the handshake succeeded. Unreachable relays come back with null.
     */
    suspend fun probeAll(servers: List<VpnServer>): List<VpnServer> = coroutineScope {
        servers.chunked(MAX_PARALLEL_PROBES).flatMap { batch ->
            batch.map { server -> async { probe(server) } }.awaitAll()
        }.map { probe ->
            probe.server.copy(measuredPingMs = probe.latencyMs)
        }
    }

    /** Times a single TCP handshake. Never throws. */
    suspend fun probe(server: VpnServer): Probe = withContext(Dispatchers.IO) {
        val port = remotePortOf(server.openVpnConfig)
        val start = System.nanoTime()
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(server.ipAddress, port), PROBE_TIMEOUT_MS)
            }
            val elapsedMs = ((System.nanoTime() - start) / 1_000_000L).toInt()
            Probe(server, elapsedMs.coerceAtLeast(1))
        } catch (_: IOException) {
            Probe(server, null)
        } catch (_: SecurityException) {
            Probe(server, null)
        }
    }

    /**
     * Reads the port off the profile's `remote` line so we probe the port the
     * tunnel will actually dial. Falls back to OpenVPN's default of 1194.
     */
    fun remotePortOf(config: String): Int {
        config.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("remote ", ignoreCase = true)) {
                val parts = line.split(Regex("\\s+"))
                parts.getOrNull(2)?.toIntOrNull()?.let { port ->
                    if (port in 1..65535) return port
                }
            }
        }
        return 1194
    }
}
