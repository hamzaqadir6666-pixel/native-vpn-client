package com.lucentvpn.android.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lucentvpn.android.LucentApp
import com.lucentvpn.android.data.SettingsStore
import com.lucentvpn.android.data.model.VpnServer
import com.lucentvpn.android.data.source.ServerSourceException
import com.lucentvpn.android.vpn.ConnectionState
import com.lucentvpn.android.vpn.TunnelStats
import com.lucentvpn.android.vpn.VpnError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

/**
 * Presentation state for the whole app.
 *
 * Holds no tunnel state of its own -- everything observable here is derived
 * from the process-scoped [com.lucentvpn.android.vpn.VpnConnectionManager], so
 * a rotation or a return from the background renders the true connection rather
 * than a stale snapshot.
 */
class LucentViewModel(application: Application) : AndroidViewModel(application) {

    private val app: LucentApp get() = getApplication()

    private val manager get() = app.connectionManager
    private val repository get() = app.serverRepository
    private val settingsStore get() = app.settingsStore

    val connectionState: StateFlow<ConnectionState> get() = manager.state
    val stats: StateFlow<TunnelStats> get() = manager.stats

    /** The system consent Intent, forwarded to the Activity to launch. */
    val consentRequests: SharedFlow<Intent> get() = manager.consentRequests

    val settings: StateFlow<SettingsStore.Settings> = settingsStore.settings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = SettingsStore.Settings(),
        )

    val servers: StateFlow<List<VpnServer>> get() = repository.servers
    val lastRefreshAt: StateFlow<Long> get() = repository.lastRefreshAt

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /** A relay-list error, separate from connection errors. */
    private val _listError = MutableStateFlow<VpnError?>(null)
    val listError: StateFlow<VpnError?> = _listError.asStateFlow()

    val isOnline: StateFlow<Boolean> get() = app.networkMonitor.isOnline

    /**
     * Relays ranked for display, with favourites hoisted to the top.
     *
     * Ranking happens here rather than in the list composable so the order is
     * stable across recompositions.
     */
    val rankedServers: StateFlow<List<VpnServer>> =
        combine(repository.servers, settingsStore.settings) { list, prefs ->
            val ranked = repository.rank(list)
            val favourites = ranked.filter { it.id in prefs.favouriteServerIds }
            val rest = ranked.filterNot { it.id in prefs.favouriteServerIds }
            favourites + rest
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    /**
     * The relay shown on the home screen: the pinned one when the user chose
     * one, otherwise whichever relay automatic mode would dial next.
     */
    val displayServer: StateFlow<VpnServer?> =
        combine(connectionState, repository.servers, settingsStore.settings) { state, _, prefs ->
            when (state) {
                is ConnectionState.Connected -> state.server
                is ConnectionState.Connecting -> state.server
                    ?: resolveTarget(prefs.pinnedServerId)

                else -> resolveTarget(prefs.pinnedServerId)
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )

    private fun resolveTarget(pinnedId: String?): VpnServer? =
        repository.serverById(pinnedId) ?: repository.fastestServer()

    // -----------------------------------------------------------------------
    // Intents from the UI
    // -----------------------------------------------------------------------

    /** The one big button. */
    fun onToggleConnection() {
        viewModelScope.launch {
            manager.toggle(settingsStore.settings.first().pinnedServerId)
        }
    }

    /**
     * User picked a relay from the list.
     *
     * @param serverId null selects automatic mode.
     */
    fun onSelectServer(serverId: String?) {
        viewModelScope.launch {
            settingsStore.setPinnedServer(serverId)

            // Switching relays while connected should move the tunnel, not
            // leave the user on the old relay with new-looking UI.
            if (connectionState.value.isConnected) {
                manager.connect(serverId)
            }
        }
    }

    fun onConsentResult(granted: Boolean) = manager.onConsentResult(granted)

    fun onDismissError() = manager.dismissError()

    fun onDismissListError() {
        _listError.value = null
    }

    fun onRefreshServers() {
        if (_isRefreshing.value) return

        viewModelScope.launch {
            _isRefreshing.value = true
            _listError.value = null
            try {
                repository.refresh(probeLatency = true)
            } catch (e: ServerSourceException) {
                _listError.value = when (e.reason) {
                    ServerSourceException.Reason.NO_NETWORK -> VpnError.NO_NETWORK
                    ServerSourceException.Reason.NO_USABLE_SERVERS ->
                        VpnError.NO_SERVERS_AVAILABLE

                    else -> VpnError.SERVER_LIST_UNAVAILABLE
                }
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun onToggleFavourite(serverId: String) {
        viewModelScope.launch { settingsStore.toggleFavourite(serverId) }
    }

    // ---- Settings ---------------------------------------------------------

    fun setAutoConnect(value: Boolean) =
        viewModelScope.launch { settingsStore.setAutoConnectOnLaunch(value) }.let {}

    fun setAutoReconnect(value: Boolean) =
        viewModelScope.launch { settingsStore.setAutoReconnect(value) }.let {}

    fun setStartOnBoot(value: Boolean) =
        viewModelScope.launch { settingsStore.setStartOnBoot(value) }.let {}

    fun setThemeMode(mode: SettingsStore.ThemeMode) =
        viewModelScope.launch { settingsStore.setThemeMode(mode) }.let {}

    fun setUseCustomDns(value: Boolean) =
        viewModelScope.launch { settingsStore.setUseCustomDns(value) }.let {}

    fun setDns(primary: String, secondary: String) =
        viewModelScope.launch { settingsStore.setDns(primary, secondary) }.let {}

    companion object {
        val Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(
                modelClass: Class<T>,
                extras: androidx.lifecycle.viewmodel.CreationExtras,
            ): T {
                val application = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    ?: error("Application missing from CreationExtras")
                return LucentViewModel(application) as T
            }
        }
    }
}
