package com.lucentvpn.android.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.lucentvpn.android.LucentApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Re-arms the tunnel after a reboot or an app update, when the user asked for
 * that in Settings.
 *
 * Deliberately conservative: a VPN that silently reconnects itself when the
 * user did not opt in is a privacy problem, not a feature.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val app = context.applicationContext as? LucentApp ?: run {
            Log.w(TAG, "Boot broadcast without our Application; ignoring")
            return
        }

        // We cannot show the consent dialog from a receiver. If consent is not
        // already held, starting a tunnel here is impossible -- so do nothing
        // and let the user open the app.
        if (VpnService.prepare(context) != null) {
            Log.i(TAG, "No VPN consent on record; skipping boot connect")
            return
        }

        // goAsync() is not used: the work below only needs to survive long
        // enough to read one preference and hand off to the app-scoped manager,
        // which owns its own lifetime.
        app.appScope.launch {
            val settings = app.settingsStore.settings.first()
            if (!settings.startOnBoot) return@launch

            Log.i(TAG, "Reconnecting after $action")

            // The relay list is almost certainly cold this early in boot.
            val hadCache = app.serverRepository.loadCache()
            if (!hadCache) {
                runCatching { app.serverRepository.refresh(probeLatency = false) }
                    .onFailure {
                        Log.w(TAG, "Could not load relays on boot", it)
                        return@launch
                    }
            }

            app.connectionManager.connect(settings.pinnedServerId)
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
