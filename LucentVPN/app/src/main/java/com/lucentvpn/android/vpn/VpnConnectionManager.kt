package com.lucentvpn.android.vpn

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.lucentvpn.android.data.NetworkMonitor
import com.lucentvpn.android.data.ServerRepository
import com.lucentvpn.android.data.SettingsStore
import com.lucentvpn.android.data.model.VpnServer
import com.lucentvpn.android.data.source.ServerSourceException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns connection *policy*: which relay to dial, how long to wait, when to fall
 * back, when to give up, and when to re-dial without being asked.
 *
 * Process-scoped: exactly one instance lives in [com.lucentvpn.android.LucentApp]
 * so that the tunnel outlives any Activity and survives configuration changes.
 */
class VpnConnectionManager(
    private val scope: CoroutineScope,
    private val engine: VpnEngine,
    private val repository: ServerRepository,
    private val settings: SettingsStore,
    private val networkMonitor: NetworkMonitor,
) {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    val stats: StateFlow<TunnelStats> = engine.stats

    /**
     * Emits the system VPN consent Intent when it must be shown. The Activity
     * observes this, launches it, and reports back via [onConsentResult].
     */
    private val _consentRequests = MutableSharedFlow<Intent>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val consentRequests: SharedFlow<Intent> = _consentRequests.asSharedFlow()

    private var pendingConsent: CompletableDeferred<Boolean>? = null

    /** Guards against two overlapping connect attempts. */
    private val connectMutex = Mutex()

    private var activeJob: Job? = null

    /** The relay we are currently on or were last on, for reconnects. */
    @Volatile
    private var currentServer: VpnServer? = null

    /** True when the user asked to be disconnected; suppresses auto-reconnect. */
    @Volatile
    private var userInitiatedDisconnect = false

    init {
        observeDrops()
        observeNetworkChanges()
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Starts a connection attempt.
     *
     * @param pinnedServerId a relay the user explicitly chose, or null for
     *   automatic/fastest selection.
     */
    fun connect(pinnedServerId: String? = null) {
        activeJob?.cancel()
        activeJob = scope.launch {
            connectMutex.withLock { runConnect(pinnedServerId) }
        }
    }

    fun disconnect() {
        userInitiatedDisconnect = true
        activeJob?.cancel()
        activeJob = scope.launch {
            _state.value = ConnectionState.Disconnecting
            engine.stop()
            // Wait for the engine to actually report it is down, but never hang
            // the UI on a wedged engine.
            withTimeoutOrNull(DISCONNECT_TIMEOUT_MS) {
                engine.engineState.first { it is VpnEngine.EngineState.Stopped }
            }
            currentServer = null
            _state.value = ConnectionState.Idle
        }
    }

    /** Toggle used by the single big button. */
    fun toggle(pinnedServerId: String? = null) {
        when (val current = _state.value) {
            is ConnectionState.Connected -> disconnect()
            is ConnectionState.Connecting -> disconnect()
            is ConnectionState.Disconnecting -> Unit
            is ConnectionState.Idle, is ConnectionState.Failed -> {
                if (current is ConnectionState.Failed) _state.value = ConnectionState.Idle
                connect(pinnedServerId)
            }
        }
    }

    /** Called by the Activity after the system consent dialog closes. */
    fun onConsentResult(granted: Boolean) {
        pendingConsent?.complete(granted)
        pendingConsent = null
    }

    /** Clears a [ConnectionState.Failed] so the UI returns to Ready. */
    fun dismissError() {
        if (_state.value is ConnectionState.Failed) _state.value = ConnectionState.Idle
    }

    // -----------------------------------------------------------------------
    // Attempt sequence
    // -----------------------------------------------------------------------

    private suspend fun runConnect(pinnedServerId: String?) {
        userInitiatedDisconnect = false

        // 1. Refuse up front rather than failing three relays deep.
        if (!networkMonitor.queryOnline()) {
            fail(VpnError.NO_NETWORK, null)
            return
        }

        // 2. Make sure we have relays to choose from.
        _state.value = ConnectionState.Connecting(
            server = null,
            stage = ConnectionState.Connecting.Stage.SELECTING_SERVER,
        )

        if (repository.servers.value.isEmpty() || repository.isStale) {
            try {
                repository.refresh(probeLatency = repository.servers.value.isEmpty())
            } catch (e: ServerSourceException) {
                // A stale-but-present cache is better than no connection.
                if (repository.servers.value.isEmpty()) {
                    fail(
                        when (e.reason) {
                            ServerSourceException.Reason.NO_NETWORK -> VpnError.NO_NETWORK
                            ServerSourceException.Reason.NO_USABLE_SERVERS ->
                                VpnError.NO_SERVERS_AVAILABLE

                            else -> VpnError.SERVER_LIST_UNAVAILABLE
                        },
                        null,
                    )
                    return
                }
                Log.w(TAG, "Refresh failed; continuing with cached relays", e)
            }
        }

        val preferred = repository.serverById(pinnedServerId)
        val candidates = repository.connectionCandidates(preferred)

        if (candidates.isEmpty()) {
            fail(VpnError.NO_SERVERS_AVAILABLE, null)
            return
        }

        // 3. System consent, once, before touching the engine.
        if (!ensureConsent()) {
            fail(VpnError.PERMISSION_DENIED, null)
            return
        }

        // 4. Try relays in order until one gives us a real tunnel.
        val attempts = minOf(candidates.size, MAX_ATTEMPTS)

        for ((index, server) in candidates.take(attempts).withIndex()) {
            val error = attemptOne(server, index + 1, attempts)

            if (error == null) {
                // Tunnel is genuinely up.
                repository.markSucceeded(server)
                currentServer = server
                _state.value = ConnectionState.Connected(
                    server = server,
                    connectedAtElapsedRealtime = SystemClock.elapsedRealtime(),
                )
                return
            }

            Log.w(TAG, "Relay ${server.hostName} failed: $error")
            repository.markFailed(server)

            // Some failures are about us, not the relay: trying another relay
            // would only repeat the same outcome.
            if (error == VpnError.PERMISSION_DENIED ||
                error == VpnError.PERMISSION_REVOKED ||
                error == VpnError.NO_NETWORK ||
                error == VpnError.BACKGROUND_RESTRICTED
            ) {
                fail(error, server)
                return
            }

            // Make sure the failed attempt is fully torn down before redialing,
            // otherwise the next start races the old session.
            engine.stop()
            withTimeoutOrNull(DISCONNECT_TIMEOUT_MS) {
                engine.engineState.first { it is VpnEngine.EngineState.Stopped }
            }
        }

        fail(VpnError.ALL_SERVERS_FAILED, candidates.first())
    }

    /**
     * Dials exactly one relay and waits for a verdict.
     *
     * @return null on success, or the error that ended the attempt.
     */
    private suspend fun attemptOne(
        server: VpnServer,
        attempt: Int,
        totalAttempts: Int,
    ): VpnError? {
        _state.value = ConnectionState.Connecting(
            server = server,
            stage = ConnectionState.Connecting.Stage.STARTING_ENGINE,
            attempt = attempt,
            totalAttempts = totalAttempts,
        )

        val current = settings.settings.first()

        try {
            engine.start(
                server = server,
                options = VpnEngine.TunnelOptions(
                    overrideDns = current.useCustomDns,
                    primaryDns = current.primaryDns,
                    secondaryDns = current.secondaryDns,
                    excludedPackages = current.excludedApps,
                ),
            )
        } catch (e: EngineStartException) {
            return e.error
        } catch (e: SecurityException) {
            // Thrown when the OS refuses to start our foreground service.
            Log.e(TAG, "Service start refused", e)
            return VpnError.BACKGROUND_RESTRICTED
        }

        // Follow the engine until it either connects or gives up. The timeout
        // is what stops a silently-black-holed relay from hanging the UI.
        val verdict = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            var result: VpnError? = null
            engine.engineState.first { engineState ->
                when (engineState) {
                    is VpnEngine.EngineState.Connected -> true

                    is VpnEngine.EngineState.Failed -> {
                        result = engineState.error
                        true
                    }

                    is VpnEngine.EngineState.NoNetwork -> {
                        result = VpnError.NO_NETWORK
                        true
                    }

                    // Still in progress: mirror the stage into the UI.
                    else -> {
                        publishProgress(engineState, server, attempt, totalAttempts)
                        false
                    }
                }
            }
            result
        }

        return when {
            // withTimeoutOrNull returned because time ran out.
            verdict == null && engine.engineState.value !is VpnEngine.EngineState.Connected ->
                VpnError.TIMEOUT

            else -> verdict
        }
    }

    private fun publishProgress(
        engineState: VpnEngine.EngineState,
        server: VpnServer,
        attempt: Int,
        totalAttempts: Int,
    ) {
        val stage = when (engineState) {
            is VpnEngine.EngineState.Starting ->
                ConnectionState.Connecting.Stage.STARTING_ENGINE

            is VpnEngine.EngineState.ContactingServer ->
                ConnectionState.Connecting.Stage.CONTACTING_SERVER

            is VpnEngine.EngineState.Authenticating ->
                ConnectionState.Connecting.Stage.AUTHENTICATING

            is VpnEngine.EngineState.ConfiguringInterface ->
                ConnectionState.Connecting.Stage.ESTABLISHING_TUNNEL

            else -> return
        }

        _state.value = ConnectionState.Connecting(
            server = server,
            stage = stage,
            attempt = attempt,
            totalAttempts = totalAttempts,
        )
    }

    /**
     * Shows the system VPN consent dialog if we do not already hold consent.
     *
     * @return true when we may create a tunnel.
     */
    private suspend fun ensureConsent(): Boolean {
        val intent = engine.consentIntent() ?: return true

        _state.value = ConnectionState.Connecting(
            server = null,
            stage = ConnectionState.Connecting.Stage.AWAITING_PERMISSION,
        )

        val deferred = CompletableDeferred<Boolean>()
        pendingConsent = deferred

        if (!_consentRequests.tryEmit(intent)) {
            // No Activity is listening, so we cannot obtain consent right now.
            pendingConsent = null
            return false
        }

        // If the user backgrounds the dialog forever, do not leak the attempt.
        return withTimeoutOrNull(CONSENT_TIMEOUT_MS) { deferred.await() } ?: false
    }

    private fun fail(error: VpnError, server: VpnServer?) {
        Log.w(TAG, "Connection failed: $error")
        _state.value = ConnectionState.Failed(error, server)
    }

    // -----------------------------------------------------------------------
    // Unsolicited events
    // -----------------------------------------------------------------------

    /**
     * Detects a tunnel that dies on its own -- relay went away, the OS revoked
     * our VPN slot, or the engine's process was killed.
     */
    private fun observeDrops() {
        scope.launch {
            engine.engineState.collect { engineState ->
                val connected = _state.value as? ConnectionState.Connected ?: return@collect

                val dropped = engineState is VpnEngine.EngineState.Stopped ||
                    engineState is VpnEngine.EngineState.Failed ||
                    engineState is VpnEngine.EngineState.NoNetwork

                if (!dropped || userInitiatedDisconnect) return@collect

                Log.w(TAG, "Tunnel dropped while connected: $engineState")

                val autoReconnect = settings.settings.first().autoReconnect
                if (autoReconnect && networkMonitor.queryOnline()) {
                    connect(connected.server.id)
                } else {
                    fail(
                        (engineState as? VpnEngine.EngineState.Failed)?.error
                            ?: VpnError.CONNECTION_DROPPED,
                        connected.server,
                    )
                }
            }
        }
    }

    /**
     * An OpenVPN session bound to a Wi-Fi interface does not survive a switch to
     * cellular; it stalls without reporting an error. Re-dialing on the change
     * is the only reliable recovery.
     */
    private fun observeNetworkChanges() {
        scope.launch {
            networkMonitor.underlyingNetworkChanges
                .drop(1) // ignore the initial value
                .collect {
                    val connected = _state.value as? ConnectionState.Connected ?: return@collect
                    if (!settings.settings.first().autoReconnect) return@collect

                    Log.i(TAG, "Re-dialing after underlying network change")
                    connect(connected.server.id)
                }
        }
    }

    private companion object {
        const val TAG = "VpnConnectionManager"

        /** Per-relay budget. Volunteer relays are slow but not this slow. */
        const val CONNECT_TIMEOUT_MS = 30_000L
        const val DISCONNECT_TIMEOUT_MS = 5_000L
        const val CONSENT_TIMEOUT_MS = 120_000L

        /** How many relays we will burn through before telling the user. */
        const val MAX_ATTEMPTS = 4
    }
}
