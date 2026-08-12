package com.lucentvpn.android.data

import android.content.Context
import android.util.Log
import com.lucentvpn.android.data.model.VpnServer
import com.lucentvpn.android.data.source.ServerSource
import com.lucentvpn.android.data.source.ServerSourceException
import com.lucentvpn.android.data.source.VpnGateCsvParser
import com.lucentvpn.android.data.source.VpnGateServerSource
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Single source of truth for the relay list.
 *
 * Responsibilities:
 *  - fetch from the active [ServerSource] and cache the raw payload on disk so
 *    the UI has something to show while offline,
 *  - probe relays for real reachability,
 *  - rank them and pick one automatically,
 *  - hand the connection manager an ordered fallback list.
 *
 * Swapping providers means constructing this with a different [ServerSource];
 * nothing above this class knows VPN Gate exists.
 */
class ServerRepository(
    private val context: Context,
    val source: ServerSource = VpnGateServerSource(),
) {

    private val _servers = MutableStateFlow<List<VpnServer>>(emptyList())
    val servers: StateFlow<List<VpnServer>> = _servers.asStateFlow()

    private val _lastRefreshAt = MutableStateFlow(0L)
    val lastRefreshAt: StateFlow<Long> = _lastRefreshAt.asStateFlow()

    /** Relays that failed to establish a tunnel this session, newest last. */
    private val failedServerIds = LinkedHashSet<String>()

    private val refreshMutex = Mutex()

    private val cacheFile: File
        get() = File(context.cacheDir, CACHE_FILE_NAME)

    val isStale: Boolean
        get() = System.currentTimeMillis() - _lastRefreshAt.value > STALE_AFTER_MS

    /**
     * Loads whatever was cached on a previous run. Cheap, safe to call on
     * startup before any network is available.
     *
     * @return true when usable cached relays were restored.
     */
    suspend fun loadCache(): Boolean = withContext(Dispatchers.IO) {
        val file = cacheFile
        if (!file.exists() || file.length() == 0L) return@withContext false

        try {
            val parsed = VpnGateCsvParser.parse(file.readText())
            if (parsed.isEmpty()) return@withContext false
            _servers.value = parsed
            _lastRefreshAt.value = file.lastModified()
            Log.i(TAG, "Restored ${parsed.size} relays from cache")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Discarding unreadable relay cache", t)
            runCatching { file.delete() }
            false
        }
    }

    /**
     * Fetches a fresh list, measures latency, and publishes the ranked result.
     *
     * @param probeLatency when false, skips the TCP probe pass. Used for quick
     *   background refreshes where we only need the list to exist.
     * @throws ServerSourceException when nothing usable could be retrieved.
     */
    suspend fun refresh(probeLatency: Boolean = true): List<VpnServer> =
        refreshMutex.withLock {
            val fetched = source.fetchServers()

            // Persist the raw list before probing so an interrupted probe still
            // leaves us with a usable cache.
            persist(fetched)

            val ranked = if (probeLatency) {
                // Probing every relay is wasteful; probe the most promising
                // ones by the provider's own score and keep the rest unprobed.
                val candidates = fetched.sortedByDescending { it.score }.take(PROBE_LIMIT)
                val probed = LatencyProbe.probeAll(candidates)
                val probedById = probed.associateBy { it.id }
                fetched.map { probedById[it.id] ?: it }
            } else {
                fetched
            }

            failedServerIds.clear()
            _servers.value = ranked
            _lastRefreshAt.value = System.currentTimeMillis()
            ranked
        }

    private suspend fun persist(servers: List<VpnServer>) = withContext(Dispatchers.IO) {
        // Re-serialise to the source's own CSV shape so loadCache can reuse the
        // exact same parser. Only the columns the parser reads are written.
        runCatching {
            buildString {
                append("*vpn_servers\n")
                append(
                    "#HostName,IP,Score,Ping,Speed,CountryLong,CountryShort," +
                        "NumVpnSessions,Uptime,LogType,Operator," +
                        "OpenVPN_ConfigData_Base64\n"
                )
                servers.forEach { s ->
                    append(s.hostName).append(',')
                    append(s.ipAddress).append(',')
                    append(s.score).append(',')
                    append(s.reportedPingMs).append(',')
                    append(s.speedBps).append(',')
                    append(s.countryName.replace(',', ' ')).append(',')
                    append(s.countryCode).append(',')
                    append(s.sessions).append(',')
                    append(s.uptimeMs).append(',')
                    append(s.logPolicy.replace(',', ' ')).append(',')
                    append(s.operator.replace(',', ' ')).append(',')
                    append(
                        android.util.Base64.encodeToString(
                            s.openVpnConfig.toByteArray(Charsets.UTF_8),
                            android.util.Base64.NO_WRAP,
                        )
                    )
                    append('\n')
                }
                append("*\n")
            }.let { cacheFile.writeText(it) }
        }.onFailure { Log.w(TAG, "Could not write relay cache", it) }
    }

    /**
     * Ranks relays best-first.
     *
     * A relay that answered our TCP probe always outranks one that did not,
     * because an unprobed or unreachable relay is a coin flip. Within the
     * reachable set we weight latency most heavily, then throughput, then load.
     */
    fun rank(servers: List<VpnServer>): List<VpnServer> =
        servers.sortedWith(
            compareByDescending<VpnServer> { it.measuredPingMs != null }
                .thenBy { it.measuredPingMs ?: Int.MAX_VALUE }
                .thenByDescending { it.speedMbps }
                .thenBy { it.load }
                .thenByDescending { it.score }
        )

    /**
     * The ordered list of relays the connection manager should try.
     *
     * @param preferred a relay the user pinned. It goes first when it is still
     *   present in the list and has not already failed this session.
     */
    fun connectionCandidates(preferred: VpnServer? = null): List<VpnServer> {
        val all = _servers.value
        if (all.isEmpty()) return emptyList()

        val healthy = all.filter { it.id !in failedServerIds }
        // If every relay has failed, clear the blacklist rather than dead-end:
        // volunteer relays recover, and the user pressing Connect again should
        // mean a genuine retry.
        val pool = healthy.ifEmpty {
            failedServerIds.clear()
            all
        }

        val ranked = rank(pool)
        val pin = preferred?.let { p -> pool.firstOrNull { it.id == p.id } }

        return if (pin != null) {
            listOf(pin) + ranked.filter { it.id != pin.id }
        } else {
            ranked
        }.take(MAX_CANDIDATES)
    }

    /** The relay automatic mode would choose right now, or null if none. */
    fun fastestServer(): VpnServer? = connectionCandidates().firstOrNull()

    fun serverById(id: String?): VpnServer? =
        id?.let { wanted -> _servers.value.firstOrNull { it.id == wanted } }

    /** Records that a relay could not be dialed so we stop offering it. */
    fun markFailed(server: VpnServer) {
        failedServerIds.add(server.id)
        Log.i(TAG, "Blacklisted ${server.hostName} for this session")
    }

    fun markSucceeded(server: VpnServer) {
        failedServerIds.remove(server.id)
    }

    /** Refreshes only if the data is old enough to matter. Never throws. */
    suspend fun refreshIfStale() {
        if (!isStale) return
        runCatching { refresh(probeLatency = false) }
            .onFailure { Log.w(TAG, "Background refresh failed", it) }
    }

    private companion object {
        const val TAG = "ServerRepository"
        const val CACHE_FILE_NAME = "relays_cache.csv"
        const val STALE_AFTER_MS = 30 * 60 * 1000L
        const val PROBE_LIMIT = 48
        const val MAX_CANDIDATES = 8
    }
}
