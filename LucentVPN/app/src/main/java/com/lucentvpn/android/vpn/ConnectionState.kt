package com.lucentvpn.android.vpn

import com.lucentvpn.android.data.model.VpnServer

/**
 * The single connection state the whole UI renders from.
 *
 * [Connected] is only ever emitted after the engine reports a fully
 * established tunnel. There is no path in this app that sets it optimistically.
 */
sealed interface ConnectionState {

    /** Nothing running. */
    data object Idle : ConnectionState

    /**
     * A tunnel attempt is in flight.
     *
     * @param attempt 1-based index of the current relay attempt.
     * @param totalAttempts how many relays we are willing to try.
     */
    data class Connecting(
        val server: VpnServer?,
        val stage: Stage,
        val attempt: Int = 1,
        val totalAttempts: Int = 1,
    ) : ConnectionState {

        enum class Stage {
            /** Fetching or ranking relays. */
            SELECTING_SERVER,

            /** Waiting on the system VPN consent dialog. */
            AWAITING_PERMISSION,

            /** Handing the profile to the tunnel engine. */
            STARTING_ENGINE,

            /** TCP/UDP session opened, no OpenVPN reply yet. */
            CONTACTING_SERVER,

            /** Server replied; TLS and key negotiation in progress. */
            AUTHENTICATING,

            /** Interface configured, routes being applied. */
            ESTABLISHING_TUNNEL,
        }

        /** Coarse progress for the ring animation, 0f..1f. */
        val progress: Float
            get() = when (stage) {
                Stage.SELECTING_SERVER -> 0.10f
                Stage.AWAITING_PERMISSION -> 0.20f
                Stage.STARTING_ENGINE -> 0.35f
                Stage.CONTACTING_SERVER -> 0.55f
                Stage.AUTHENTICATING -> 0.75f
                Stage.ESTABLISHING_TUNNEL -> 0.92f
            }
    }

    /** The tunnel is up and carrying traffic. */
    data class Connected(
        val server: VpnServer,
        val connectedAtElapsedRealtime: Long,
    ) : ConnectionState

    data object Disconnecting : ConnectionState

    /** The attempt gave up. [error] is already user-facing. */
    data class Failed(
        val error: VpnError,
        val server: VpnServer?,
    ) : ConnectionState

    val isBusy: Boolean
        get() = this is Connecting || this is Disconnecting

    val isConnected: Boolean
        get() = this is Connected
}

/** Live counters for the tunnel. All zero when disconnected. */
data class TunnelStats(
    val bytesIn: Long = 0,
    val bytesOut: Long = 0,
    val downstreamBps: Long = 0,
    val upstreamBps: Long = 0,
) {
    val totalBytes: Long
        get() = if (Long.MAX_VALUE - bytesIn.coerceAtLeast(0) < bytesOut.coerceAtLeast(0)) {
            Long.MAX_VALUE
        } else {
            bytesIn.coerceAtLeast(0) + bytesOut.coerceAtLeast(0)
        }
}

/**
 * Every failure the user can actually be shown, with the recovery hint that
 * belongs to it. Keeping these as data rather than raw strings is what lets the
 * UI decide between "Retry" and "Open settings".
 */
enum class VpnError(val recoverable: Boolean) {
    /** User dismissed or denied the system VPN consent dialog. */
    PERMISSION_DENIED(recoverable = true),

    /** Another app currently owns the VPN slot. */
    PERMISSION_REVOKED(recoverable = true),

    /** No relay list at all. */
    NO_SERVERS_AVAILABLE(recoverable = true),

    /** We had relays but every one we tried refused us. */
    ALL_SERVERS_FAILED(recoverable = true),

    /** Handshake did not complete in time. */
    TIMEOUT(recoverable = true),

    /** Relay rejected our credentials or certificate. */
    AUTH_FAILED(recoverable = true),

    /** The bundled profile used something the engine cannot do. */
    UNSUPPORTED_CONFIG(recoverable = true),

    /** VpnService.Builder could not create the interface. */
    TUNNEL_INIT_FAILED(recoverable = true),

    /** Tunnel was up and then died. */
    CONNECTION_DROPPED(recoverable = true),

    /** Device has no internet at all. */
    NO_NETWORK(recoverable = true),

    /** OS killed our service or blocked the background start. */
    BACKGROUND_RESTRICTED(recoverable = false),

    /** Could not reach the relay directory. */
    SERVER_LIST_UNAVAILABLE(recoverable = true),

    UNKNOWN(recoverable = true),
}
