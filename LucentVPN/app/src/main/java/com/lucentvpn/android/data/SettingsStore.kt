package com.lucentvpn.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("lucent_settings")

/**
 * Every setting persisted here is wired to behaviour somewhere in the app.
 * Nothing in this file is decorative.
 */
class SettingsStore(private val context: Context) {

    data class Settings(
        /** Connect as soon as the app finishes initialising. */
        val autoConnectOnLaunch: Boolean = false,
        /** Re-dial automatically when a tunnel drops or the network moves. */
        val autoReconnect: Boolean = true,
        /** Arm auto-connect after device boot. */
        val startOnBoot: Boolean = false,
        val themeMode: ThemeMode = ThemeMode.SYSTEM,
        /** Push custom resolvers into the tunnel instead of the server's. */
        val useCustomDns: Boolean = false,
        val primaryDns: String = DEFAULT_PRIMARY_DNS,
        val secondaryDns: String = DEFAULT_SECONDARY_DNS,
        /** Package names excluded from the tunnel (split tunnelling). */
        val excludedApps: Set<String> = emptySet(),
        /** Relay ids the user starred. */
        val favouriteServerIds: Set<String> = emptySet(),
        /** Last relay the user explicitly picked, or null for automatic. */
        val pinnedServerId: String? = null,
    )

    enum class ThemeMode { SYSTEM, LIGHT, DARK }

    val settings: Flow<Settings> = context.dataStore.data
        .catch { cause ->
            // A corrupt preferences file must not brick the app.
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }
        .map { prefs ->
            Settings(
                autoConnectOnLaunch = prefs[KEY_AUTO_CONNECT] ?: false,
                autoReconnect = prefs[KEY_AUTO_RECONNECT] ?: true,
                startOnBoot = prefs[KEY_START_ON_BOOT] ?: false,
                themeMode = prefs[KEY_THEME]
                    ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                    ?: ThemeMode.SYSTEM,
                useCustomDns = prefs[KEY_USE_CUSTOM_DNS] ?: false,
                primaryDns = prefs[KEY_PRIMARY_DNS] ?: DEFAULT_PRIMARY_DNS,
                secondaryDns = prefs[KEY_SECONDARY_DNS] ?: DEFAULT_SECONDARY_DNS,
                excludedApps = prefs[KEY_EXCLUDED_APPS] ?: emptySet(),
                favouriteServerIds = prefs[KEY_FAVOURITES] ?: emptySet(),
                pinnedServerId = prefs[KEY_PINNED_SERVER],
            )
        }

    suspend fun setAutoConnectOnLaunch(value: Boolean) = put(KEY_AUTO_CONNECT, value)

    suspend fun setAutoReconnect(value: Boolean) = put(KEY_AUTO_RECONNECT, value)

    suspend fun setStartOnBoot(value: Boolean) = put(KEY_START_ON_BOOT, value)

    suspend fun setThemeMode(mode: ThemeMode) = put(KEY_THEME, mode.name)

    suspend fun setUseCustomDns(value: Boolean) = put(KEY_USE_CUSTOM_DNS, value)

    suspend fun setDns(primary: String, secondary: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_PRIMARY_DNS] = primary
            prefs[KEY_SECONDARY_DNS] = secondary
        }
    }

    suspend fun setExcludedApps(packages: Set<String>) = put(KEY_EXCLUDED_APPS, packages)

    suspend fun toggleFavourite(serverId: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_FAVOURITES] ?: emptySet()
            prefs[KEY_FAVOURITES] = if (serverId in current) {
                current - serverId
            } else {
                current + serverId
            }
        }
    }

    /** Pass null to return to automatic selection. */
    suspend fun setPinnedServer(serverId: String?) {
        context.dataStore.edit { prefs ->
            if (serverId == null) {
                prefs.remove(KEY_PINNED_SERVER)
            } else {
                prefs[KEY_PINNED_SERVER] = serverId
            }
        }
    }

    private suspend fun put(key: Preferences.Key<Boolean>, value: Boolean) {
        context.dataStore.edit { it[key] = value }
    }

    private suspend fun put(key: Preferences.Key<String>, value: String) {
        context.dataStore.edit { it[key] = value }
    }

    private suspend fun put(key: Preferences.Key<Set<String>>, value: Set<String>) {
        context.dataStore.edit { it[key] = value }
    }

    companion object {
        /** Cloudflare's resolvers: no-logging policy, widely reachable. */
        const val DEFAULT_PRIMARY_DNS = "1.1.1.1"
        const val DEFAULT_SECONDARY_DNS = "1.0.0.1"

        private val KEY_AUTO_CONNECT = booleanPreferencesKey("auto_connect_on_launch")
        private val KEY_AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        private val KEY_START_ON_BOOT = booleanPreferencesKey("start_on_boot")
        private val KEY_THEME = stringPreferencesKey("theme_mode")
        private val KEY_USE_CUSTOM_DNS = booleanPreferencesKey("use_custom_dns")
        private val KEY_PRIMARY_DNS = stringPreferencesKey("primary_dns")
        private val KEY_SECONDARY_DNS = stringPreferencesKey("secondary_dns")
        private val KEY_EXCLUDED_APPS = stringSetPreferencesKey("excluded_apps")
        private val KEY_FAVOURITES = stringSetPreferencesKey("favourite_servers")
        private val KEY_PINNED_SERVER = stringPreferencesKey("pinned_server")
    }
}
