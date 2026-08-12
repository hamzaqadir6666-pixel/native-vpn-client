package com.lucentvpn.android

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lucentvpn.android.ui.LucentRoot
import com.lucentvpn.android.ui.LucentViewModel
import com.lucentvpn.android.ui.theme.LucentTheme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: LucentViewModel

    /**
     * The system VPN consent dialog.
     *
     * This must be launched from an Activity, which is why the connection
     * manager emits the Intent rather than launching it: the manager outlives
     * this Activity and has no window of its own.
     */
    private val consentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onConsentResult(result.resultCode == RESULT_OK)
    }

    /**
     * Notification permission, API 33+.
     *
     * A VPN's foreground-service notification is how the user retains control
     * of the tunnel, so we ask -- but the tunnel is not gated on the answer,
     * because VpnService is exempt from the foreground-service type rules and
     * refusing to connect over a denied notification would be user-hostile.
     */
    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Either answer is acceptable; nothing to do. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val vm: LucentViewModel = viewModel(factory = LucentViewModel.Factory)
            viewModel = vm

            val settings by vm.settings.collectAsState()

            LucentTheme(themeMode = settings.themeMode) {
                LucentRoot(viewModel = vm)
            }
        }

        observeConsentRequests()
        requestNotificationPermissionIfNeeded()
    }

    /**
     * Forwards consent Intents from the connection manager into the Activity
     * result API.
     *
     * [flowWithLifecycle] at STARTED matters: launching the dialog while this
     * Activity is stopped throws, and a queued request would otherwise fire at
     * exactly the wrong moment.
     */
    private fun observeConsentRequests() {
        lifecycleScope.launch {
            viewModel.consentRequests
                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collectLatest { intent ->
                    runCatching { consentLauncher.launch(intent) }
                        .onFailure { viewModel.onConsentResult(false) }
                }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!granted) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
