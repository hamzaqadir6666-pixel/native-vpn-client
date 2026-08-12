package com.lucentvpn.android.data

import android.content.Context
import android.util.Log
import com.lucentvpn.android.data.model.VpnServer
import com.lucentvpn.android.data.source.ServerSource
import com.lucentvpn.android.data.source.ServerSourceException
import com.lucentvpn.android.data.source.VpnGateCsvParser
import com.lucentvpn.android.data.source.VpnGateServerSource
import com.lucentvpn.android.vpn.VpnError
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

    enum class CacheFreshness { EMPTY, FRESH, STALE, EXPIRED }

    data class RelayHealth(
        val successes: Int = 0,
        val failures: Int = 0,
        val consecutiveFailures: Int = 0,
        val lastAttemptAt: Long = 0,
        val lastSuccessAt: Long = 0,
        val cooldownUntil: Long = 0,
        val lastError: VpnError? = null,
    )

    private val healthPreferences =
        context.getSharedPreferences(HEALTH_PREFERENCES, Context.MODE_PRIVATE)
    private val relayHealth = mutableMapOf<String, RelayHealth>()

    private val _cacheFreshness = MutableStateFlow(CacheFreshness.EMPTY)
    val cacheFreshness: StateFlow<CacheFreshness> = _cacheFreshness.asStateFlow()

    private val refreshMutex = Mutex()

    init {
        loadHealth()
    }

    private val cacheFile: File
        get() = File(context.cacheDir, CACHE_FILE_NAME)

    val isStale: Boolean
        get() = cacheAgeMs() > STALE_AFTER_MS

    private fun cacheAgeMs(now: Long = System.currentTimeMillis()): Long =
        (now - _lastRefreshAt.value).coerceAtLeast(0L)

    private fun updateFreshness(now: Long = System.currentTimeMillis()) {
        _cacheFreshness.value = when {
            _servers.value.isEmpty() -> CacheFreshness.EMPTY
            cacheAgeMs(now) <= STALE_AFTER_MS -> CacheFreshness.FRESH
            cacheAgeMs(now) <= EXPIRE_AFTER_MS -> CacheFreshness.STALE
            else -> CacheFreshness.EXPIRED
        }
    }

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
            val age = (System.currentTimeMillis() - file.lastModified()).coerceAtLeast(0L)
            if (age > EXPIRE_AFTER_MS) {
                _cacheFreshness.value = CacheFreshness.EXPIRED
                return@withContext false
            }
            val parsed = VpnGateCsvParser.parse(file.readText())
            if (parsed.isEmpty()) return@withContext false
            _servers.value = parsed
            _lastRefreshAt.value = file.lastModified()
            updateFreshness()
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

            _servers.value = ranked
            _lastRefreshAt.value = System.currentTimeMillis()
            _cacheFreshness.value = CacheFreshness.FRESH
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
    fun rank(servers: List<VpnServer>): List<VpnServer> {
        val now = System.currentTimeMillis()
        return servers.sortedWith(
            compareBy<VpnServer> { (relayHealth[it.id]?.cooldownUntil ?: 0L) > now }
                .thenBy { relayHealth[it.id]?.consecutiveFailures ?: 0 }
                .thenByDescending {
                    relayHealth[it.id]?.let { health ->
                        (health.successes + 1.0) / (health.successes + health.failures + 2.0)
                    } ?: 0.5
                }
                .thenByDescending { it.measuredPingMs != null }
                .thenBy { it.measuredPingMs ?: Int.MAX_VALUE }
                .thenBy { it.load }
                .thenByDescending { it.speedMbps }
                .thenByDescending { it.uptimeMs }
                .thenByDescending { it.score }
                .thenBy { it.id }
        )
    }

    /**
     * The ordered list of relays the connection manager should try.
     *
     * @param preferred a relay the user pinned. It goes first when it is still
     *   present in the list and has not already failed this session.
     */
    fun connectionCandidates(preferred: VpnServer? = null): List<VpnServer> {
        val all = _servers.value
        if (all.isEmpty()) return emptyList()

        val now = System.currentTimeMillis()
        val eligible = all.filter { (relayHealth[it.id]?.cooldownUntil ?: 0L) <= now }
        // If every relay is cooling down, retain the full set but let ranking
        // put the soonest/healthiest candidates first rather than dead-ending.
        val pool = eligible.ifEmpty { all }

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

    /** Records relay outcomes across process restarts with an expiring cooldown. */
    fun markFailed(server: VpnServer, error: VpnError) {
        val now = System.currentTimeMillis()
        val previous = relayHealth[server.id] ?: RelayHealth()
        val consecutive = previous.consecutiveFailures + 1
        relayHealth[server.id] = previous.copy(
            failures = previous.failures + 1,
            consecutiveFailures = consecutive,
            lastAttemptAt = now,
            cooldownUntil = now + cooldownFor(consecutive),
            lastError = error,
        )
        persistHealth(server.id)
    }

    fun markSucceeded(server: VpnServer) {
        val now = System.currentTimeMillis()
        val previous = relayHealth[server.id] ?: RelayHealth()
        relayHealth[server.id] = previous.copy(
            successes = previous.successes + 1,
            consecutiveFailures = 0,
            lastAttemptAt = now,
            lastSuccessAt = now,
            cooldownUntil = 0,
            lastError = null,
        )
        persistHealth(server.id)
    }

    private fun cooldownFor(consecutiveFailures: Int): Long =
        (BASE_COOLDOWN_MS * (1L shl (consecutiveFailures - 1).coerceIn(0, 5)))
            .coerceAtMost(MAX_COOLDOWN_MS)

    private fun loadHealth() {
        healthPreferences.all.forEach { (id, value) ->
            val parts = (value as? String)?.split('|') ?: return@forEach
            if (parts.size != 7) return@forEach
            runCatching {
                relayHealth[id] = RelayHealth(
                    successes = parts[0].toInt(),
                    failures = parts[1].toInt(),
                    consecutiveFailures = parts[2].toInt(),
                    lastAttemptAt = parts[3].toLong(),
                    lastSuccessAt = parts[4].toLong(),
                    cooldownUntil = parts[5].toLong(),
                    lastError = parts[6].takeIf { it.isNotBlank() }?.let { VpnError.valueOf(it) },
                )
            }
        }
    }

    private fun persistHealth(id: String) {
        val health = relayHealth[id] ?: return
        val value = listOf(
            health.successes,
            health.failures,
            health.consecutiveFailures,
            health.lastAttemptAt,
            health.lastSuccessAt,
            health.cooldownUntil,
            health.lastError?.name.orEmpty(),
        ).joinToString("|")
        healthPreferences.edit().putString(id, value).apply()
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
        const val HEALTH_PREFERENCES = "relay_health_v1"
        const val STALE_AFTER_MS = 30 * 60 * 1000L
        const val EXPIRE_AFTER_MS = 7 * 24 * 60 * 60 * 1000L
        const val BASE_COOLDOWN_MS = 30_000L
        const val MAX_COOLDOWN_MS = 30 * 60 * 1000L
        const val PROBE_LIMIT = 48
        const val MAX_CANDIDATES = 8
    }
}
