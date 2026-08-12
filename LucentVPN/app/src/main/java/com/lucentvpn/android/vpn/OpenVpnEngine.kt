package com.lucentvpn.android.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.IBinder
import android.util.Log
import com.lucentvpn.android.data.model.VpnServer
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.IOpenVPNServiceInternal
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VPNLaunchHelper
import de.blinkt.openvpn.core.VpnStatus
import java.io.StringReader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ============================================================================
 * THE ONLY FILE THAT TOUCHES ics-openvpn APIs.
 * ============================================================================
 *
 * The tunnel is established by the ics-openvpn `openvpn3` native core, compiled
 * into this APK from the `:openvpn` submodule. That core is what performs the
 * work Android's VpnService contract requires: it calls
 * `VpnService.Builder` to create the virtual interface, calls
 * `VpnService.protect()` on its own transport socket so tunnel traffic is not
 * routed back into itself, runs the OpenVPN control/data channels against the
 * remote relay, and keeps a foreground notification for the duration.
 *
 * This class is the adapter: it converts a [VpnServer] into a [VpnProfile],
 * starts/stops the engine's service, and translates the engine's callbacks into
 * our protocol-neutral [VpnEngine.EngineState].
 *
 * ---------------------------------------------------------------------------
 * VERSION SENSITIVITY -- READ BEFORE UPGRADING THE SUBMODULE
 * ---------------------------------------------------------------------------
 * ics-openvpn is an application, not a published library, so it makes no API
 * stability promises. The symbols used below are the ones that exist on the
 * pinned submodule commit recorded in git. If a submodule bump fails to
 * compile, the break will be in this file and nowhere else. The historically
 * unstable spots are called out with `API NOTE` comments.
 */
class OpenVpnEngine(private val appContext: Context) : VpnEngine {

    private val _engineState =
        MutableStateFlow<VpnEngine.EngineState>(VpnEngine.EngineState.Stopped)
    override val engineState: StateFlow<VpnEngine.EngineState> = _engineState.asStateFlow()

    private val _stats = MutableStateFlow(TunnelStats())
    override val stats: StateFlow<TunnelStats> = _stats.asStateFlow()

    /** Set while we are intentionally tearing the tunnel down. */
    @Volatile
    private var stopRequested = false

    private var lastByteCountAt = 0L

    @Volatile
    private var activeProfileUuid: String? = null

    @Volatile
    private var callbackProfileUuid: String? = null

    // -----------------------------------------------------------------------
    // Engine callbacks
    // -----------------------------------------------------------------------

    private val stateListener = object : VpnStatus.StateListener {

        override fun updateState(
            state: String?,
            logmessage: String?,
            localizedResId: Int,
            level: ConnectionStatus?,
            intent: Intent?,
        ) {
            val expected = activeProfileUuid
            val reported = callbackProfileUuid
            if (expected != null && reported != null && expected != reported) {
                Log.d(TAG, "Ignoring callback from superseded profile $reported")
                return
            }
            _engineState.value = translate(state, logmessage, level)
            if (_engineState.value is VpnEngine.EngineState.Stopped ||
                _engineState.value is VpnEngine.EngineState.Failed
            ) {
                _stats.value = TunnelStats()
                lastByteCountAt = 0L
            }
        }

        override fun setConnectedVPN(uuid: String?) {
            callbackProfileUuid = uuid
        }
    }

    private val byteCountListener = VpnStatus.ByteCountListener { inBytes, outBytes, diffIn, diffOut ->
        val now = System.currentTimeMillis()
        val previous = lastByteCountAt
        lastByteCountAt = now

        // ics-openvpn reports its diffs over its own polling interval. Deriving
        // the interval from wall-clock deltas keeps the speed readout honest
        // even when the engine's polling drifts or a poll is skipped.
        val intervalSeconds = if (previous == 0L) {
            DEFAULT_BYTECOUNT_INTERVAL_SECONDS
        } else {
            ((now - previous) / 1000.0).coerceAtLeast(0.25)
        }

        val safeIn = inBytes.coerceAtLeast(0)
        val safeOut = outBytes.coerceAtLeast(0)
        _stats.value = TunnelStats(
            bytesIn = safeIn,
            bytesOut = safeOut,
            downstreamBps = if (diffIn < 0) 0 else (diffIn / intervalSeconds).toLong().coerceAtLeast(0),
            upstreamBps = if (diffOut < 0) 0 else (diffOut / intervalSeconds).toLong().coerceAtLeast(0),
        )
    }

    init {
        VpnStatus.addStateListener(stateListener)
        VpnStatus.addByteCountListener(byteCountListener)
    }

    /**
     * Maps the engine's vocabulary onto ours.
     *
     * Both the coarse [level] and the fine-grained OpenVPN [state] string are
     * consulted: the level tells us roughly where we are, and the state string
     * distinguishes cases the level flattens together (notably a clean exit
     * versus a dropped tunnel).
     */
    private fun translate(
        state: String?,
        logMessage: String?,
        level: ConnectionStatus?,
    ): VpnEngine.EngineState {
        val stateName = state?.uppercase().orEmpty()

        // Terminal, unambiguous failures first.
        when (stateName) {
            "AUTH_FAILED" -> return VpnEngine.EngineState.Failed(
                VpnError.AUTH_FAILED,
                logMessage,
            )

            "PROCESS_KILLED", "NOPROCESS" -> {
                // Only a failure if we did not ask for it.
                return if (stopRequested) {
                    VpnEngine.EngineState.Stopped
                } else {
                    VpnEngine.EngineState.Failed(VpnError.CONNECTION_DROPPED, logMessage)
                }
            }
        }

        return when (level) {
            ConnectionStatus.LEVEL_CONNECTED -> VpnEngine.EngineState.Connected

            ConnectionStatus.LEVEL_START -> VpnEngine.EngineState.Starting

            ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET ->
                VpnEngine.EngineState.ContactingServer

            ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED -> when (stateName) {
                // The relay is talking to us; distinguish key exchange from
                // interface setup so the UI progress means something.
                "GET_CONFIG", "ASSIGN_IP", "ADD_ROUTES" ->
                    VpnEngine.EngineState.ConfiguringInterface

                else -> VpnEngine.EngineState.Authenticating
            }

            ConnectionStatus.LEVEL_NONETWORK -> VpnEngine.EngineState.NoNetwork

            ConnectionStatus.LEVEL_VPNPAUSED -> VpnEngine.EngineState.Paused

            ConnectionStatus.LEVEL_AUTH_FAILED ->
                VpnEngine.EngineState.Failed(VpnError.AUTH_FAILED, logMessage)

            ConnectionStatus.LEVEL_NOTCONNECTED -> {
                if (stopRequested) {
                    VpnEngine.EngineState.Stopped
                } else {
                    // Reached NOTCONNECTED without being asked to stop. If we
                    // had a live tunnel this is a drop; otherwise the attempt
                    // simply never got off the ground.
                    val wasConnected = _engineState.value == VpnEngine.EngineState.Connected
                    VpnEngine.EngineState.Failed(
                        if (wasConnected) {
                            VpnError.CONNECTION_DROPPED
                        } else {
                            VpnError.TUNNEL_INIT_FAILED
                        },
                        logMessage,
                    )
                }
            }

            ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT ->
                // Should be unreachable: we only ever load profiles whose
                // credentials are fully inline. Treat it as unsupported rather
                // than hanging forever waiting for a prompt we never show.
                VpnEngine.EngineState.Failed(VpnError.UNSUPPORTED_CONFIG, logMessage)

            else -> _engineState.value
        }
    }

    // -----------------------------------------------------------------------
    // VpnEngine
    // -----------------------------------------------------------------------

    /** Mirrors `VpnService.prepare()`; null means consent already granted. */
    override fun consentIntent(): Intent? = VpnService.prepare(appContext)

    override fun start(server: VpnServer, options: VpnEngine.TunnelOptions) {
        stopRequested = false
        lastByteCountAt = 0L
        _stats.value = TunnelStats()
        _engineState.value = VpnEngine.EngineState.Starting

        val profile = buildProfile(server, options)
        activeProfileUuid = profile.uuidString
        callbackProfileUuid = null

        // ProfileManager persists to the app's private storage; the engine's
        // service reads the profile back by UUID after a process restart.
        val profileManager = ProfileManager.getInstance(appContext)
        profileManager.addProfile(profile)
        ProfileManager.saveProfile(appContext, profile)
        profileManager.saveProfileList(appContext)

        Log.i(TAG, "Starting tunnel to ${server.hostName} (${server.transport})")

        // The final flag asks the pinned engine to replace any still-running
        // profile, preventing two VpnService sessions from racing for the TUN.
        VPNLaunchHelper.startOpenVpn(profile, appContext, START_REASON, true)
    }

    /**
     * Converts a relay's inline `.ovpn` text into a [VpnProfile].
     *
     * Nothing is written to external storage and the user is never asked to
     * import a file: the profile text arrives inside the relay list and is
     * parsed straight from memory.
     */
    private fun buildProfile(
        server: VpnServer,
        options: VpnEngine.TunnelOptions,
    ): VpnProfile {
        val parser = ConfigParser()
        try {
            parser.parseConfig(StringReader(server.openVpnConfig))
        } catch (t: Throwable) {
            throw EngineStartException(
                "Relay ${server.hostName} shipped a profile we cannot parse",
                VpnError.UNSUPPORTED_CONFIG,
                t,
            )
        }

        // API NOTE: named `getConfigfile(context, boolean)` on pins older than
        // mid-2020; `convertProfile()` on current master.
        val profile: VpnProfile = try {
            parser.convertProfile()
        } catch (t: Throwable) {
            throw EngineStartException(
                "Relay ${server.hostName} uses an unsupported OpenVPN option",
                VpnError.UNSUPPORTED_CONFIG,
                t,
            )
        }

        profile.mName = "${server.countryName} - ${server.hostName}"

        // VPN Gate relays that ask for user/pass accept the published public
        // placeholder pair. These are documented public values that gate
        // nothing -- not a secret, and deliberately not stored in secure
        // storage, because doing so would imply they protect something.
        if (profile.mAuthenticationType == VpnProfile.TYPE_USERPASS ||
            profile.mAuthenticationType == VpnProfile.TYPE_USERPASS_CERTIFICATES ||
            profile.mAuthenticationType == VpnProfile.TYPE_USERPASS_PKCS12
        ) {
            profile.mUsername = PUBLIC_RELAY_USERNAME
            profile.mPassword = PUBLIC_RELAY_PASSWORD
        }

        // ---- User settings that genuinely change the tunnel ---------------

        if (options.overrideDns &&
            !options.primaryDns.isNullOrBlank()
        ) {
            profile.mOverrideDNS = true
            profile.mDNS1 = options.primaryDns
            options.secondaryDns?.takeIf { it.isNotBlank() }?.let { profile.mDNS2 = it }
        }

        if (options.excludedPackages.isNotEmpty()) {
            // `mAllowedAppsVpnAreDisallowed = true` turns the set into an
            // exclusion list, which is what split tunnelling means here.
            profile.mAllowedAppsVpn = HashSet(options.excludedPackages)
            profile.mAllowedAppsVpnAreDisallowed = true
        }

        // Keep the engine from silently retrying forever: we own retry policy
        // in VpnConnectionManager so that we can move to a different relay
        // instead of hammering a dead one.
        profile.mConnectRetryMax = "2"

        return profile
    }

    override fun stop() {
        stopRequested = true
        activeProfileUuid = null
        callbackProfileUuid = null
        lastByteCountAt = 0L
        _stats.value = TunnelStats()
        _engineState.value = VpnEngine.EngineState.Stopped

        ProfileManager.setConntectedVpnProfileDisconnected(appContext)

        // The service exposes an AIDL binder for in-process control. Binding is
        // asynchronous, so the actual stopVPN call happens in the callback.
        val intent = Intent(appContext, OpenVPNService::class.java).apply {
            action = OpenVPNService.START_SERVICE
        }

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                try {
                    IOpenVPNServiceInternal.Stub.asInterface(binder)
                        // `false` = do not keep the notification around.
                        ?.stopVPN(false)
                } catch (t: Throwable) {
                    Log.w(TAG, "stopVPN failed", t)
                } finally {
                    runCatching { appContext.unbindService(this) }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }

        runCatching {
            appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.onFailure { Log.w(TAG, "Could not bind tunnel service to stop it", it) }
    }

    override fun release() {
        VpnStatus.removeStateListener(stateListener)
        VpnStatus.removeByteCountListener(byteCountListener)
    }

    private companion object {
        const val TAG = "OpenVpnEngine"
        const val START_REASON = "LucentVPN user request"
        const val DEFAULT_BYTECOUNT_INTERVAL_SECONDS = 2.0

        // Published by VPN Gate for its public relays; not a credential.
        const val PUBLIC_RELAY_USERNAME = "vpn"
        const val PUBLIC_RELAY_PASSWORD = "vpn"
    }
}
