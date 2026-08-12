package com.lucentvpn.android.ui

import androidx.annotation.StringRes
import com.lucentvpn.android.R
import com.lucentvpn.android.vpn.VpnError

/**
 * Maps each failure onto the sentence the user sees.
 *
 * Kept in one place so no composable invents its own wording, and so every
 * error is guaranteed to have copy -- the `when` is exhaustive, which means
 * adding a [VpnError] without user-facing text will not compile.
 */
@StringRes
fun VpnError.messageRes(): Int = when (this) {
    VpnError.PERMISSION_DENIED -> R.string.error_permission_denied
    VpnError.PERMISSION_REVOKED -> R.string.error_permission_revoked
    VpnError.NO_SERVERS_AVAILABLE -> R.string.error_no_servers
    VpnError.ALL_SERVERS_FAILED -> R.string.error_all_failed
    VpnError.TIMEOUT -> R.string.error_timeout
    VpnError.AUTH_FAILED -> R.string.error_auth_failed
    VpnError.UNSUPPORTED_CONFIG -> R.string.error_unsupported_config
    VpnError.TUNNEL_INIT_FAILED -> R.string.error_tunnel_init
    VpnError.CONNECTION_DROPPED -> R.string.error_dropped
    VpnError.NO_NETWORK -> R.string.error_no_network
    VpnError.BACKGROUND_RESTRICTED -> R.string.error_background_restricted
    VpnError.SERVER_LIST_UNAVAILABLE -> R.string.error_server_list
    VpnError.UNKNOWN -> R.string.error_unknown
}
