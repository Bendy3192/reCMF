/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.recmf.protocol.CmfActivityType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "recmf-settings")

/** What the user paired with, and how far the sync has got. */
data class WatchSettings(
    val address: String? = null,
    val name: String? = null,
    val healthConnectEnabled: Boolean = false,
    val notificationsEnabled: Boolean = false,
    val notifyOnlyWhenScreenOff: Boolean = true,
    /** Seconds between automatic syncs while connected; zero means only on request. */
    val autoSyncSeconds: Int = 300,
    val lastSyncEpochSeconds: Long = 0,
) {
    val isPaired: Boolean get() = address != null
}

/**
 * The watch's own configuration, as reCMF believes it to be.
 *
 * Held here rather than read from the watch because most of these have no read-back
 * command; the app is the source of truth and re-applies them on every connection, so a
 * watch that was reset or configured elsewhere converges back.
 */
data class WatchPreferences(
    val heartRateMonitoring: Boolean = true,
    val spo2Monitoring: Boolean = false,
    val stressMonitoring: Boolean = false,
    val raiseToWake: Boolean = true,
    val use24Hour: Boolean = true,
    val metric: Boolean = true,
    val stepsGoal: Int = 8_000,
    val distanceGoalMeters: Int = 5_000,
    val caloriesGoal: Int = 300,

    /** Alert thresholds; zero means the watch does not alert on that measure at all. */
    val heartRateAlertLow: Int = 0,
    val heartRateAlertRestingHigh: Int = 0,
    val heartRateAlertActiveHigh: Int = 0,
    val spo2AlertLow: Int = 0,

    val standReminder: Boolean = false,
    val standIntervalMinutes: Int = 60,
    val drinkReminder: Boolean = false,
    val drinkIntervalMinutes: Int = 60,

    /**
     * Quiet windows as minutes since midnight. Start equal to end means no quiet hours,
     * which is how the watch reads a window of zero length.
     */
    val standQuietStartMinutes: Int = 0,
    val standQuietEndMinutes: Int = 0,
    val drinkQuietStartMinutes: Int = 0,
    val drinkQuietEndMinutes: Int = 0,

    /** Which exercises the watch shows in its own sport menu. */
    val sportTypes: List<CmfActivityType> = CmfActivityType.DEFAULT,
)

class SettingsStore(private val context: Context) {
    val settings: Flow<WatchSettings> = context.dataStore.data.map { prefs ->
        WatchSettings(
            address = prefs[KEY_ADDRESS],
            name = prefs[KEY_NAME],
            healthConnectEnabled = prefs[KEY_HEALTH_CONNECT] ?: false,
            notificationsEnabled = prefs[KEY_NOTIFICATIONS] ?: false,
            notifyOnlyWhenScreenOff = prefs[KEY_SCREEN_OFF_ONLY] ?: true,
            autoSyncSeconds = prefs[KEY_AUTO_SYNC] ?: 300,
            lastSyncEpochSeconds = prefs[KEY_LAST_SYNC] ?: 0L,
        )
    }

    suspend fun current(): WatchSettings = settings.first()

    val watchPreferences: Flow<WatchPreferences> = context.dataStore.data.map { prefs ->
        WatchPreferences(
            heartRateMonitoring = prefs[KEY_HR_MONITORING] ?: true,
            spo2Monitoring = prefs[KEY_SPO2_MONITORING] ?: false,
            stressMonitoring = prefs[KEY_STRESS_MONITORING] ?: false,
            raiseToWake = prefs[KEY_RAISE_TO_WAKE] ?: true,
            use24Hour = prefs[KEY_24_HOUR] ?: true,
            metric = prefs[KEY_METRIC] ?: true,
            stepsGoal = prefs[KEY_STEPS_GOAL] ?: 8_000,
            distanceGoalMeters = prefs[KEY_DISTANCE_GOAL] ?: 5_000,
            caloriesGoal = prefs[KEY_CALORIES_GOAL] ?: 300,
            heartRateAlertLow = prefs[KEY_HR_ALERT_LOW] ?: 0,
            heartRateAlertRestingHigh = prefs[KEY_HR_ALERT_RESTING_HIGH] ?: 0,
            heartRateAlertActiveHigh = prefs[KEY_HR_ALERT_ACTIVE_HIGH] ?: 0,
            spo2AlertLow = prefs[KEY_SPO2_ALERT_LOW] ?: 0,
            standReminder = prefs[KEY_STAND_REMINDER] ?: false,
            standIntervalMinutes = prefs[KEY_STAND_INTERVAL] ?: 60,
            drinkReminder = prefs[KEY_DRINK_REMINDER] ?: false,
            drinkIntervalMinutes = prefs[KEY_DRINK_INTERVAL] ?: 60,
            standQuietStartMinutes = prefs[KEY_STAND_QUIET_START] ?: 0,
            standQuietEndMinutes = prefs[KEY_STAND_QUIET_END] ?: 0,
            drinkQuietStartMinutes = prefs[KEY_DRINK_QUIET_START] ?: 0,
            drinkQuietEndMinutes = prefs[KEY_DRINK_QUIET_END] ?: 0,
            sportTypes = prefs[KEY_SPORT_TYPES]
                // An unrecognised name means a downgrade or a renamed type; skipping it
                // is better than failing to read the whole preference.
                ?.mapNotNull { name -> CmfActivityType.entries.firstOrNull { it.name == name } }
                ?.takeIf { it.isNotEmpty() }
                ?: CmfActivityType.DEFAULT,
        )
    }

    suspend fun updateWatchPreferences(transform: (WatchPreferences) -> WatchPreferences) {
        val updated = transform(watchPreferences.first())

        context.dataStore.edit { prefs ->
            prefs[KEY_HR_MONITORING] = updated.heartRateMonitoring
            prefs[KEY_SPO2_MONITORING] = updated.spo2Monitoring
            prefs[KEY_STRESS_MONITORING] = updated.stressMonitoring
            prefs[KEY_RAISE_TO_WAKE] = updated.raiseToWake
            prefs[KEY_24_HOUR] = updated.use24Hour
            prefs[KEY_METRIC] = updated.metric
            prefs[KEY_STEPS_GOAL] = updated.stepsGoal
            prefs[KEY_DISTANCE_GOAL] = updated.distanceGoalMeters
            prefs[KEY_CALORIES_GOAL] = updated.caloriesGoal
            prefs[KEY_HR_ALERT_LOW] = updated.heartRateAlertLow
            prefs[KEY_HR_ALERT_RESTING_HIGH] = updated.heartRateAlertRestingHigh
            prefs[KEY_HR_ALERT_ACTIVE_HIGH] = updated.heartRateAlertActiveHigh
            prefs[KEY_SPO2_ALERT_LOW] = updated.spo2AlertLow
            prefs[KEY_STAND_REMINDER] = updated.standReminder
            prefs[KEY_STAND_INTERVAL] = updated.standIntervalMinutes
            prefs[KEY_DRINK_REMINDER] = updated.drinkReminder
            prefs[KEY_DRINK_INTERVAL] = updated.drinkIntervalMinutes
            prefs[KEY_STAND_QUIET_START] = updated.standQuietStartMinutes
            prefs[KEY_STAND_QUIET_END] = updated.standQuietEndMinutes
            prefs[KEY_DRINK_QUIET_START] = updated.drinkQuietStartMinutes
            prefs[KEY_DRINK_QUIET_END] = updated.drinkQuietEndMinutes
            // Stored by name, not by code: a name survives the codes being corrected.
            prefs[KEY_SPORT_TYPES] = updated.sportTypes.map { it.name }.toSet()
        }
    }

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

    suspend fun setNotifyOnlyWhenScreenOff(enabled: Boolean) {
        context.dataStore.edit { it[KEY_SCREEN_OFF_ONLY] = enabled }
    }

    suspend fun setAutoSyncSeconds(seconds: Int) {
        context.dataStore.edit { it[KEY_AUTO_SYNC] = seconds }
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
        val KEY_SCREEN_OFF_ONLY = booleanPreferencesKey("notify_only_when_screen_off")
        val KEY_AUTO_SYNC = intPreferencesKey("auto_sync_seconds")
        val KEY_LAST_SYNC = longPreferencesKey("last_sync_epoch_seconds")

        val KEY_HR_MONITORING = booleanPreferencesKey("watch_hr_monitoring")
        val KEY_SPO2_MONITORING = booleanPreferencesKey("watch_spo2_monitoring")
        val KEY_STRESS_MONITORING = booleanPreferencesKey("watch_stress_monitoring")
        val KEY_RAISE_TO_WAKE = booleanPreferencesKey("watch_raise_to_wake")
        val KEY_24_HOUR = booleanPreferencesKey("watch_24_hour")
        val KEY_METRIC = booleanPreferencesKey("watch_metric")
        val KEY_STEPS_GOAL = intPreferencesKey("watch_steps_goal")
        val KEY_DISTANCE_GOAL = intPreferencesKey("watch_distance_goal")
        val KEY_CALORIES_GOAL = intPreferencesKey("watch_calories_goal")
        val KEY_HR_ALERT_LOW = intPreferencesKey("watch_hr_alert_low")
        val KEY_HR_ALERT_RESTING_HIGH = intPreferencesKey("watch_hr_alert_resting_high")
        val KEY_HR_ALERT_ACTIVE_HIGH = intPreferencesKey("watch_hr_alert_active_high")
        val KEY_SPO2_ALERT_LOW = intPreferencesKey("watch_spo2_alert_low")
        val KEY_STAND_REMINDER = booleanPreferencesKey("watch_stand_reminder")
        val KEY_STAND_INTERVAL = intPreferencesKey("watch_stand_interval")
        val KEY_DRINK_REMINDER = booleanPreferencesKey("watch_drink_reminder")
        val KEY_DRINK_INTERVAL = intPreferencesKey("watch_drink_interval")
        val KEY_STAND_QUIET_START = intPreferencesKey("watch_stand_quiet_start")
        val KEY_STAND_QUIET_END = intPreferencesKey("watch_stand_quiet_end")
        val KEY_DRINK_QUIET_START = intPreferencesKey("watch_drink_quiet_start")
        val KEY_DRINK_QUIET_END = intPreferencesKey("watch_drink_quiet_end")
        val KEY_SPORT_TYPES = stringSetPreferencesKey("watch_sport_types")
    }
}
