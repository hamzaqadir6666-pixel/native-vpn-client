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

/** Tracks validated, captive, unavailable, and changed non-VPN networks. */
class NetworkMonitor(context: Context) {

    sealed interface Status {
        data object Unavailable : Status
        data object Unvalidated : Status
        data class Available(val network: Network) : Status
    }

    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val _status = MutableStateFlow(queryStatus())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _isOnline = MutableStateFlow(_status.value is Status.Available)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _underlyingNetworkChanges = MutableStateFlow(0L)
    val underlyingNetworkChanges: StateFlow<Long> = _underlyingNetworkChanges.asStateFlow()

    private var lastUnderlyingNetwork: Network? =
        (_status.value as? Status.Available)?.network
    private var started = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
            val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (!hasInternet) return

            if (validated) {
                val previous = lastUnderlyingNetwork
                if (previous != null && previous != network) {
                    Log.i(TAG, "Underlying network changed")
                    _underlyingNetworkChanges.value += 1
                }
                lastUnderlyingNetwork = network
                publish(Status.Available(network))
            } else if (queryStatus() !is Status.Available) {
                publish(Status.Unvalidated)
            }
        }

        override fun onLost(network: Network) {
            if (network == lastUnderlyingNetwork) lastUnderlyingNetwork = null
            publish(queryStatus())
        }

        override fun onAvailable(network: Network) = publish(queryStatus())
    }

    @Synchronized
    fun start() {
        if (started) return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching {
            connectivityManager.registerNetworkCallback(request, callback)
            started = true
        }.onFailure { Log.w(TAG, "Could not register network callback", it) }
    }

    @Synchronized
    fun stop() {
        if (!started) return
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        started = false
    }

    fun queryOnline(): Boolean = queryStatus() is Status.Available

    private fun queryStatus(): Status {
        var unvalidated = false
        connectivityManager.allNetworks.forEach { network ->
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return@forEach
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            ) return@forEach
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                return Status.Available(network)
            }
            unvalidated = true
        }
        return if (unvalidated) Status.Unvalidated else Status.Unavailable
    }

    private fun publish(value: Status) {
        _status.value = value
        _isOnline.value = value is Status.Available
    }

    private companion object {
        const val TAG = "NetworkMonitor"
    }
}
