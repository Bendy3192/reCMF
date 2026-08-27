/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.recmf.protocol.CmfActivityType
import dev.recmf.protocol.CmfAlarm
import dev.recmf.protocol.CmfWeekday
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

    val weatherEnabled: Boolean = false,
    /** The place the user typed, as the provider resolved it. */
    val weatherCity: String? = null,
    val weatherLatitude: Double = 0.0,
    val weatherLongitude: Double = 0.0,
    val lastSyncEpochSeconds: Long = 0,
) {
    val isPaired: Boolean get() = address != null
}

/** A group of watch settings that travel together in one command. */
enum class WatchSetting {
    MONITORING,
    RAISE_TO_WAKE,
    TIME_FORMAT,
    UNITS,
    GOALS,
    ALERTS,
    STAND_REMINDER,
    DRINK_REMINDER,
    SPORTS,
    ALARMS,
}

/**
 * The watch's own configuration, as far as reCMF has been told.
 *
 * The values below are reCMF's defaults, **not** the watch's — most of these settings
 * have no read-back command, so the app has no idea what the watch already holds. Only
 * the groups named in [configured] are ever sent: pushing a default would silently
 * replace whatever the user set up in the official app, and for the exercise list that
 * means deleting most of the watch's sport menu.
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

    /**
     * The watch's alarms, in the order it numbers them.
     *
     * Empty is a real value here, not "unknown": a watch with no alarms answers with no
     * bytes. Whether reCMF may send this list at all is decided by [configured], as for
     * every other group — the watch keeps exactly what it is given.
     */
    val alarms: List<CmfAlarm> = emptyList(),

    /** The groups the user has actually changed here. Everything else is left alone. */
    val configured: Set<WatchSetting> = emptySet(),
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
            weatherEnabled = prefs[KEY_WEATHER_ENABLED] ?: false,
            weatherCity = prefs[KEY_WEATHER_CITY],
            weatherLatitude = prefs[KEY_WEATHER_LAT] ?: 0.0,
            weatherLongitude = prefs[KEY_WEATHER_LON] ?: 0.0,
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
            alarms = prefs[KEY_ALARMS]?.let(::decodeAlarms).orEmpty(),
            configured = prefs[KEY_CONFIGURED]
                ?.mapNotNull { name -> WatchSetting.entries.firstOrNull { it.name == name } }
                ?.toSet()
                .orEmpty(),
        )
    }

    /**
     * @param setting the group being changed, recorded so that from now on it is sent to
     *   the watch. Settings never touched here stay the watch's own business.
     */
    /**
     * Takes the alarms the watch reported, without treating that as the user asking for
     * them.
     *
     * Reading a setting and choosing one are different things, and only the second earns a
     * place in [WatchPreferences.configured]. Adopting silently would turn the first
     * connection into a write-back of what was just read, which is how a read-back turns
     * into the overwriting it exists to prevent. A group the user has already configured
     * is left alone: their choice outranks the watch's current state.
     */
    suspend fun adoptAlarmsFromWatch(alarms: List<CmfAlarm>) {
        val current = watchPreferences.first()
        if (WatchSetting.ALARMS in current.configured) return

        context.dataStore.edit { it[KEY_ALARMS] = encodeAlarms(alarms) }
    }

    suspend fun updateWatchPreferences(
        setting: WatchSetting,
        transform: (WatchPreferences) -> WatchPreferences,
    ) {
        val current = watchPreferences.first()
        val updated = transform(current).copy(configured = current.configured + setting)

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
            prefs[KEY_ALARMS] = encodeAlarms(updated.alarms)
            prefs[KEY_CONFIGURED] = updated.configured.map { it.name }.toSet()
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

    /**
     * Forgets the pairing key but keeps the watch.
     *
     * The watch stores one key, so pairing it with anything else — the stock app, or
     * Gadgetbridge — replaces ours. Dropping the stale key lets the next connection
     * negotiate a fresh one instead of failing forever with a key nothing accepts.
     */
    suspend fun clearAuthKey() {
        context.dataStore.edit { it.remove(KEY_AUTH_KEY) }
    }

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

    suspend fun setWeatherEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_WEATHER_ENABLED] = enabled }
    }

    suspend fun setWeatherPlace(name: String, latitude: Double, longitude: Double) {
        context.dataStore.edit { prefs ->
            prefs[KEY_WEATHER_CITY] = name
            prefs[KEY_WEATHER_LAT] = latitude
            prefs[KEY_WEATHER_LON] = longitude
        }
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
        val KEY_WEATHER_ENABLED = booleanPreferencesKey("weather_enabled")
        val KEY_WEATHER_CITY = stringPreferencesKey("weather_city")
        val KEY_WEATHER_LAT = doublePreferencesKey("weather_latitude")
        val KEY_WEATHER_LON = doublePreferencesKey("weather_longitude")
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
        val KEY_ALARMS = stringPreferencesKey("watch_alarms")

        /**
         * Alarms as `hour,minute,enabled,dayMask` joined by semicolons.
         *
         * A string rather than a string set, because a set has no order and would fold two
         * alarms set to the same time into one — and the watch numbers them by position.
         */
        internal fun encodeAlarms(alarms: List<CmfAlarm>): String =
            alarms.joinToString(";") { alarm ->
                val mask = alarm.days.fold(0) { acc, day -> acc or day.bit }
                "${alarm.hour},${alarm.minute},${if (alarm.enabled) 1 else 0},$mask"
            }

        internal fun decodeAlarms(text: String): List<CmfAlarm> =
            text.split(";").mapNotNull { entry ->
                val parts = entry.split(",")
                if (parts.size != 4) return@mapNotNull null

                val hour = parts[0].toIntOrNull() ?: return@mapNotNull null
                val minute = parts[1].toIntOrNull() ?: return@mapNotNull null
                val mask = parts[3].toIntOrNull() ?: return@mapNotNull null
                if (hour !in 0..23 || minute !in 0..59) return@mapNotNull null

                CmfAlarm(
                    hour = hour,
                    minute = minute,
                    enabled = parts[2] == "1",
                    days = CmfWeekday.entries.filter { it.bit and mask != 0 }.toSet(),
                )
            }
        val KEY_CONFIGURED = stringSetPreferencesKey("watch_configured_settings")
    }
}
