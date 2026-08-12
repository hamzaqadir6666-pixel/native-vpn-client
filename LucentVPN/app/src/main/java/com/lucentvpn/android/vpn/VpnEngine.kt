package com.lucentvpn.android.vpn

import android.content.Intent
import com.lucentvpn.android.data.model.VpnServer
import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction over the thing that actually moves packets.
 *
 * [VpnConnectionManager] owns policy (which relay, when to retry, when to give
 * up); an engine owns exactly one tunnel and reports what it observes. This
 * split is what keeps the protocol swappable.
 */
interface VpnEngine {

    /** Raw engine state. The manager translates this into [ConnectionState]. */
    val engineState: StateFlow<EngineState>

    val stats: StateFlow<TunnelStats>

    /**
     * The consent Intent that must be shown before a tunnel can be created, or
     * null when the user has already granted consent to this app.
     *
     * Mirrors `VpnService.prepare()`.
     */
    fun consentIntent(): Intent?

    /**
     * Hands a profile to the engine and returns immediately. Progress arrives
     * on [engineState].
     *
     * @throws EngineStartException when the profile cannot be loaded at all.
     */
    fun start(server: VpnServer, options: TunnelOptions)

    /** Tears the tunnel down. Safe to call when nothing is running. */
    fun stop()

    /** Releases listeners. Called when the process-scoped owner goes away. */
    fun release()

    /** Per-connection tunnel configuration coming from user settings. */
    data class TunnelOptions(
        val overrideDns: Boolean = false,
        val primaryDns: String? = null,
        val secondaryDns: String? = null,
        /** Packages to keep *outside* the tunnel. */
        val excludedPackages: Set<String> = emptySet(),
    )

    /**
     * What the engine currently observes about its tunnel. Intentionally
     * protocol-agnostic.
     */
    sealed interface EngineState {
        data object Stopped : EngineState
        data object Starting : EngineState
        data object ContactingServer : EngineState
        data object Authenticating : EngineState
        data object ConfiguringInterface : EngineState
        data object Connected : EngineState
        data object Paused : EngineState
        data object NoNetwork : EngineState

        /** Terminal failure for the current attempt. */
        data class Failed(val error: VpnError, val detail: String?) : EngineState
    }
}

/** The profile could not even be handed to the engine. */
class EngineStartException(
    message: String,
    val error: VpnError,
    cause: Throwable? = null,
) : Exception(message, cause)
