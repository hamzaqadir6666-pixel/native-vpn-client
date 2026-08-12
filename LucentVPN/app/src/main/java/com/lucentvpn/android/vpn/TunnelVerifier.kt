package com.lucentvpn.android.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Confirms Android has an active VPN network before the UI claims protection. */
class TunnelVerifier(context: Context) {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

    suspend fun awaitVerified(timeoutMs: Long = VERIFY_TIMEOUT_MS): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (!isVerified()) delay(POLL_INTERVAL_MS)
            true
        } ?: false

    fun isVerified(): Boolean = connectivityManager.allNetworks.any { network ->
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return@any false
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        ) return@any false

        val properties = connectivityManager.getLinkProperties(network) ?: return@any false
        properties.interfaceName?.isNotBlank() == true &&
            properties.routes.any { route -> route.isDefaultRoute }
    }

    private companion object {
        const val VERIFY_TIMEOUT_MS = 8_000L
        const val POLL_INTERVAL_MS = 250L
    }
}
