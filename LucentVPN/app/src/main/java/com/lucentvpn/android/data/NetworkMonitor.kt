package com.lucentvpn.android.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks whether the device has a usable internet path, and signals when that
 * path changes underneath an established tunnel.
 *
 * Two distinct facts matter to the connect path:
 *
 *  - [isOnline]: is there any validated internet transport at all. Used to
 *    refuse a connection attempt up front and to show the offline state.
 *  - [underlyingNetworkChanges]: the non-VPN network was swapped (Wi-Fi to
 *    cellular, for example). An OpenVPN tunnel pinned to the old interface
 *    will stall silently, so the connection manager reconnects on this signal.
 */
class NetworkMonitor(context: Context) {

    private val connectivityManager =
        context.getSystemService(ConnectivityManager::class.java)

    private val _isOnline = MutableStateFlow(queryOnline())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _underlyingNetworkChanges = MutableStateFlow(0L)

    /** Increments every time the underlying (non-VPN) network is replaced. */
    val underlyingNetworkChanges: StateFlow<Long> = _underlyingNetworkChanges.asStateFlow()

    private var lastUnderlyingNetwork: Network? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {

        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities,
        ) {
            val validated =
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val isVpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)

            if (validated) _isOnline.value = true

            // Only physical transports count as "underlying".
            if (!isVpn && validated) {
                val previous = lastUnderlyingNetwork
                if (previous != null && previous != network) {
                    Log.i(TAG, "Underlying network changed: $previous -> $network")
                    _underlyingNetworkChanges.value += 1
                }
                lastUnderlyingNetwork = network
            }
        }

        override fun onLost(network: Network) {
            if (network == lastUnderlyingNetwork) lastUnderlyingNetwork = null
            _isOnline.value = queryOnline()
        }

        override fun onAvailable(network: Network) {
            _isOnline.value = queryOnline()
        }
    }

    fun start() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching {
            connectivityManager.registerNetworkCallback(request, callback)
        }.onFailure { Log.w(TAG, "Could not register network callback", it) }
    }

    fun stop() {
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
    }

    /**
     * Point-in-time connectivity check that deliberately ignores VPN
     * transports, so it reports whether the *underlying* internet works.
     */
    fun queryOnline(): Boolean {
        val networks = connectivityManager.allNetworks
        return networks.any { network ->
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return@any false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }

    private companion object {
        const val TAG = "NetworkMonitor"
    }
}
