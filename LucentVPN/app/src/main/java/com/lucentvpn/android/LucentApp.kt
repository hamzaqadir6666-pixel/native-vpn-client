package com.lucentvpn.android

import android.app.Application
import android.util.Log
import com.lucentvpn.android.data.NetworkMonitor
import com.lucentvpn.android.data.ServerRepository
import com.lucentvpn.android.data.SettingsStore
import com.lucentvpn.android.vpn.OpenVpnEngine
import com.lucentvpn.android.vpn.VpnConnectionManager
import de.blinkt.openvpn.core.PRNGFixes
import de.blinkt.openvpn.core.StatusListener
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Manual dependency container.
 *
 * The graph here is small and entirely process-scoped, so a DI framework would
 * add a compile step without removing any code. The important property is that
 * [connectionManager] lives on the Application: the tunnel must outlive every
 * Activity, or rotating the device would drop the user's connection.
 */
class LucentApp : Application() {

    /**
     * Survives Activity death. [SupervisorJob] keeps one failed child -- say a
     * relay refresh -- from cancelling the connection manager's own coroutines.
     */
    val appScope: CoroutineScope by lazy {
        CoroutineScope(
            SupervisorJob() +
                CoroutineExceptionHandler { _, t ->
                    Log.e(TAG, "Unhandled coroutine failure", t)
                }
        )
    }

    val settingsStore: SettingsStore by lazy { SettingsStore(this) }

    val serverRepository: ServerRepository by lazy { ServerRepository(this) }

    val networkMonitor: NetworkMonitor by lazy { NetworkMonitor(this) }

    private val vpnEngine: OpenVpnEngine by lazy { OpenVpnEngine(this) }

    val connectionManager: VpnConnectionManager by lazy {
        VpnConnectionManager(
            scope = appScope,
            engine = vpnEngine,
            repository = serverRepository,
            settings = settingsStore,
            networkMonitor = networkMonitor,
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // ics-openvpn requires both of these before a tunnel is started.
        //
        // PRNGFixes patches the OpenSSL PRNG seeding bug present on older
        // Android releases; upstream calls it from its own Application.onCreate
        // and the native core assumes it has run.
        PRNGFixes.apply()

        // StatusListener binds the engine's status broadcaster to this process.
        // Without it VpnStatus never receives updates from the service, so our
        // listeners in OpenVpnEngine would stay silent and every connection
        // would look like a timeout.
        StatusListener().init(this)

        networkMonitor.start()

        warmUpRelayList()
    }

    /**
     * Gets a relay list in place before the user reaches for Connect.
     *
     * Cache first so the server list screen is never empty on a cold start,
     * then a network refresh that is allowed to fail silently -- pressing
     * Connect performs its own blocking refresh when it needs one.
     */
    private fun warmUpRelayList() {
        appScope.launch {
            val hadCache = serverRepository.loadCache()

            runCatching { serverRepository.refresh(probeLatency = !hadCache) }
                .onFailure { Log.w(TAG, "Initial relay refresh failed", it) }

            maybeAutoConnect()
        }
    }

    /** Honours the auto-connect-on-launch setting once relays are available. */
    private suspend fun maybeAutoConnect() {
        val settings = settingsStore.settings.first()
        if (!settings.autoConnectOnLaunch) return
        if (serverRepository.servers.value.isEmpty()) return

        Log.i(TAG, "Auto-connecting on launch")
        connectionManager.connect(settings.pinnedServerId)
    }

    companion object {
        private const val TAG = "LucentApp"

        /**
         * Set in [onCreate], which the framework guarantees runs before any
         * component of this process. Used only by [com.lucentvpn.android.vpn.BootReceiver],
         * which has no other handle on the graph.
         */
        @Volatile
        lateinit var instance: LucentApp
            private set
    }
}
