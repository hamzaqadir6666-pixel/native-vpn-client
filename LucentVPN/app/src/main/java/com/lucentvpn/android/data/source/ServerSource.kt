package com.lucentvpn.android.data.source

import com.lucentvpn.android.data.model.VpnServer

/**
 * A provider of free, public VPN relays.
 *
 * The rest of the app depends only on this interface, so a different free
 * provider can be added later by writing one new class and registering it in
 * [com.lucentvpn.android.data.ServerRepository] -- no UI, engine or
 * view-model changes required.
 *
 * Implementations must be safe to call from a background dispatcher and must
 * never return a server whose configuration is missing or unusable.
 */
interface ServerSource {

    /** Human-readable name, shown in Settings -> VPN source. */
    val displayName: String

    /** Where the data comes from, shown in Settings for transparency. */
    val infoUrl: String

    /** The relay operator's trust model, surfaced to the user verbatim. */
    val trustNotice: String

    /**
     * Fetches and parses the current relay list.
     *
     * @throws ServerSourceException when the list cannot be retrieved or
     *   contains no usable relay.
     */
    suspend fun fetchServers(): List<VpnServer>
}

/** Raised when a source cannot produce a usable relay list. */
class ServerSourceException(
    message: String,
    val reason: Reason,
    cause: Throwable? = null,
) : Exception(message, cause) {

    enum class Reason {
        /** Device has no working internet connection. */
        NO_NETWORK,

        /** Reached the endpoint but it answered with an error. */
        ENDPOINT_ERROR,

        /** Got a response we could not make sense of. */
        MALFORMED_RESPONSE,

        /** Parsed fine, but nothing in it was usable. */
        NO_USABLE_SERVERS,
    }
}
