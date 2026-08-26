/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "recmf-settings")

/** What the user paired with, and how far the sync has got. */
data class WatchSettings(
    val address: String?,
    val name: String?,
    val healthConnectEnabled: Boolean,
    val notificationsEnabled: Boolean,
    val lastSyncEpochSeconds: Long,
) {
    val isPaired: Boolean get() = address != null
}

class SettingsStore(private val context: Context) {
    val settings: Flow<WatchSettings> = context.dataStore.data.map { prefs ->
        WatchSettings(
            address = prefs[KEY_ADDRESS],
            name = prefs[KEY_NAME],
            healthConnectEnabled = prefs[KEY_HEALTH_CONNECT] ?: false,
            notificationsEnabled = prefs[KEY_NOTIFICATIONS] ?: false,
            lastSyncEpochSeconds = prefs[KEY_LAST_SYNC] ?: 0L,
        )
    }

    suspend fun current(): WatchSettings = settings.first()

    suspend fun setWatch(address: String, name: String?) {
        context.dataStore.edit { prefs ->
            if (prefs[KEY_ADDRESS] != address) {
                // A different watch means the stored key is meaningless — drop it so we
                // pair from scratch instead of failing the handshake in a loop.
                prefs.remove(KEY_AUTH_KEY)
                prefs.remove(KEY_LAST_SYNC)
            }
            prefs[KEY_ADDRESS] = address
            if (name != null) prefs[KEY_NAME] = name
        }
    }

    suspend fun forgetWatch() {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_ADDRESS)
            prefs.remove(KEY_NAME)
            prefs.remove(KEY_AUTH_KEY)
            prefs.remove(KEY_LAST_SYNC)
        }
    }

    /** Returns the stored pairing key, or null if there is none we can still read. */
    suspend fun authKey(): ByteArray? =
        context.dataStore.data.first()[KEY_AUTH_KEY]?.let(SecretVault::unseal)

    suspend fun setAuthKey(key: ByteArray) {
        val sealed = SecretVault.seal(key) ?: return
        context.dataStore.edit { it[KEY_AUTH_KEY] = sealed }
    }

    suspend fun setHealthConnectEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_HEALTH_CONNECT] = enabled }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_NOTIFICATIONS] = enabled }
    }

    suspend fun setLastSync(epochSeconds: Long) {
        context.dataStore.edit { it[KEY_LAST_SYNC] = epochSeconds }
    }

    private companion object {
        val KEY_ADDRESS = stringPreferencesKey("watch_address")
        val KEY_NAME = stringPreferencesKey("watch_name")
        val KEY_AUTH_KEY = stringPreferencesKey("watch_auth_key_sealed")
        val KEY_HEALTH_CONNECT = booleanPreferencesKey("health_connect_enabled")
        val KEY_NOTIFICATIONS = booleanPreferencesKey("notifications_enabled")
        val KEY_LAST_SYNC = longPreferencesKey("last_sync_epoch_seconds")
    }
}
