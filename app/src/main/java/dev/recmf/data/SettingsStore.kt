/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.recmf.ai.AiEndpoint
import dev.recmf.protocol.CmfActivityType
import dev.recmf.protocol.CmfAlarm
import dev.recmf.protocol.CmfWeekday
import dev.recmf.protocol.WatchGoals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Shared with [BackupStore], which walks the whole store rather than named keys. */
internal val Context.dataStore by preferencesDataStore(name = "recmf-settings")

/**
 * A night the watch reported.
 *
 * @param raw the frame as it arrived, so a reading that turns out wrong can be checked
 *   against the bytes rather than against another night's sleep.
 */
data class SleepSummary(
    val startSeconds: Long,
    val wakeSeconds: Long,
    val stages: Int,
    val raw: String,
)

/**
 * What the assistant is allowed to do, and what it is pointed at.
 *
 * Two switches rather than one, because they send different things and the difference
 * matters. [insightsEnabled] sends numbers and nothing that names anybody; [coachEnabled]
 * additionally sends the profile below, which is the part that is actually about a person.
 * Somebody may reasonably want the first and not the second, and one switch would take
 * that choice away.
 *
 * Both default to off. Until one is turned on deliberately, nothing about the wearer
 * leaves the phone — which is what this app promised before an assistant existed and is
 * not something a new feature gets to quietly revise.
 *
 * @param key the API key, sealed by [SecretVault], exactly as the watch's pairing key is.
 *   It is in [Backup.NEVER_LEAVES], so no export carries it.
 */
data class AiSettings(
    val insightsEnabled: Boolean = false,
    val coachEnabled: Boolean = false,
    val baseUrl: String = "",
    val model: String = "",
    val wire: AiEndpoint.Wire = AiEndpoint.Wire.CHAT,
    /**
     * Whether to let the assistant look things up.
     *
     * Only means anything in the Responses shape, where search is a tool that has to be
     * asked for. On by default, because looking things up is the reason somebody would
     * point this at Perplexity at all — and off is one tap away for somebody who would
     * rather their question did not reach a search engine.
     */
    val webSearch: Boolean = true,
    val key: String? = null,
    /** Editable, because a prompt somebody cannot read is a prompt they cannot trust. */
    val systemPrompt: String = "",
) {
    /** Whether anything can be asked at all: a switch on with no key is just an intention. */
    val usable: Boolean get() = (insightsEnabled || coachEnabled) &&
        !key.isNullOrBlank() && baseUrl.isNotBlank() && model.isNotBlank()
}

/** What the user paired with, and how far the sync has got. */
data class WatchSettings(
    val address: String? = null,
    val name: String? = null,
    val healthConnectEnabled: Boolean = false,
    val notificationsEnabled: Boolean = false,
    val notifyOnlyWhenScreenOff: Boolean = true,
    /** Seconds between automatic syncs while connected; zero means only on request. */
    val autoSyncSeconds: Int = 300,

    /**
     * Whether the watch's alarms mirror the phone's clock.
     *
     * Off by default and deliberately so: the watch keeps exactly the list it is sent, so
     * turning this on replaces whatever alarms were set on the watch itself.
     */
    val phoneAlarmsEnabled: Boolean = false,

    /**
     * Whether reCMF fetches satellite orbits for the watch's GPS by itself.
     *
     * On by default, because the alternative is a receiver that takes minutes to find
     * itself in the open and never does indoors. It is a switch rather than a given
     * because it is the one thing reCMF sends to a third party at all: a plain GET of a
     * public file from MediaTek, with nothing identifying attached, but a request the app
     * would not otherwise make.
     */
    val gpsAlmanacAuto: Boolean = true,

    /** When the watch was last given orbits, so they are refreshed before they run out. */
    val almanacSentAtMillis: Long = 0L,

    /** Which shape of almanac the watch was last given; see `CmfAgps.FORMAT`. */
    val almanacFormatSent: Int = 0,

    val weatherEnabled: Boolean = false,
    /** The place the user typed, as the provider resolved it. */
    val weatherCity: String? = null,
    /**
     * Whether the place is taken from the phone rather than typed.
     *
     * How often it is taken depends on what the wearer has granted: with ordinary
     * location, whenever the app is opened; with "all the time", before every forecast,
     * app open or not.
     */
    val weatherAutoPlace: Boolean = false,

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
            phoneAlarmsEnabled = prefs[KEY_PHONE_ALARMS] ?: false,
            gpsAlmanacAuto = prefs[KEY_ALMANAC_AUTO] ?: true,
            almanacSentAtMillis = prefs[KEY_ALMANAC_SENT_AT] ?: 0L,
            almanacFormatSent = prefs[KEY_ALMANAC_FORMAT] ?: 0,
            autoSyncSeconds = prefs[KEY_AUTO_SYNC] ?: 300,
            weatherEnabled = prefs[KEY_WEATHER_ENABLED] ?: false,
            weatherCity = prefs[KEY_WEATHER_CITY],
            weatherAutoPlace = prefs[KEY_WEATHER_AUTO_PLACE] ?: false,
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
     * Takes what the watch reported, without treating that as the user asking for it.
     *
     * Reading a setting and choosing one are different things, and only the second earns a
     * place in [WatchPreferences.configured]. Adopting silently would turn the first
     * connection into a write-back of what was just read, which is how a read-back turns
     * into the overwriting it exists to prevent. A group the user has already configured
     * is left alone: their choice outranks the watch's current state.
     *
     * @return whether the value was taken, so the caller can say which of the two happened
     *   rather than logging a change that did not occur.
     */
    suspend fun adoptFromWatch(
        setting: WatchSetting,
        write: (MutablePreferences) -> Unit,
    ): Boolean {
        if (setting in watchPreferences.first().configured) return false

        context.dataStore.edit(write)
        return true
    }

    suspend fun adoptAlarmsFromWatch(alarms: List<CmfAlarm>): Boolean =
        adoptFromWatch(WatchSetting.ALARMS) { it[KEY_ALARMS] = encodeAlarms(alarms) }

    suspend fun adoptRaiseToWakeFromWatch(enabled: Boolean): Boolean =
        adoptFromWatch(WatchSetting.RAISE_TO_WAKE) { it[KEY_RAISE_TO_WAKE] = enabled }

    suspend fun adoptTimeFormatFromWatch(use24Hour: Boolean): Boolean =
        adoptFromWatch(WatchSetting.TIME_FORMAT) { it[KEY_24_HOUR] = use24Hour }

    suspend fun adoptSportTypesFromWatch(types: List<CmfActivityType>): Boolean {
        // An empty menu is not something the watch reports, and storing it would leave the
        // list looking configured-but-blank. Nothing read, nothing taken.
        if (types.isEmpty()) return false

        return adoptFromWatch(WatchSetting.SPORTS) { prefs ->
            prefs[KEY_SPORT_TYPES] = types.map { it.name }.toSet()
        }
    }

    /**
     * Takes the three goals reCMF shows, always.
     *
     * No [adoptFromWatch] guard here, because there is nothing to guard: reCMF does not
     * write goals — the watch acknowledges a goal write and keeps what it had — so a
     * stored goal is a reading, never a choice, and a newer reading always wins.
     *
     * The watch keeps two more, active minutes and climbs, which this app has nowhere to
     * put.
     */
    suspend fun adoptGoalsFromWatch(goals: WatchGoals): Boolean {
        if (goals.steps <= 0) return false

        context.dataStore.edit { prefs ->
            prefs[KEY_STEPS_GOAL] = goals.steps
            if (goals.distanceMeters > 0) prefs[KEY_DISTANCE_GOAL] = goals.distanceMeters
            if (goals.calories > 0) prefs[KEY_CALORIES_GOAL] = goals.calories
        }

        return true
    }

    suspend fun adoptStandReminderFromWatch(enabled: Boolean, intervalMinutes: Int): Boolean =
        adoptFromWatch(WatchSetting.STAND_REMINDER) { prefs ->
            prefs[KEY_STAND_REMINDER] = enabled
            if (intervalMinutes > 0) prefs[KEY_STAND_INTERVAL] = intervalMinutes
        }

    suspend fun adoptDrinkReminderFromWatch(enabled: Boolean, intervalMinutes: Int): Boolean =
        adoptFromWatch(WatchSetting.DRINK_REMINDER) { prefs ->
            prefs[KEY_DRINK_REMINDER] = enabled
            if (intervalMinutes > 0) prefs[KEY_DRINK_INTERVAL] = intervalMinutes
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

    suspend fun setPhoneAlarmsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_PHONE_ALARMS] = enabled }
    }

    /**
     * The assistant's settings, with the key unsealed only here.
     *
     * Unsealing on read rather than holding it about the place: the Keystore is the only
     * copy, and the plaintext should exist for as long as one request needs it and no
     * longer.
     */
    val ai: Flow<AiSettings> = context.dataStore.data.map { prefs ->
        AiSettings(
            insightsEnabled = prefs[KEY_AI_INSIGHTS] ?: false,
            coachEnabled = prefs[KEY_AI_COACH] ?: false,
            baseUrl = prefs[KEY_AI_BASE_URL] ?: DEFAULT_AI_BASE_URL,
            model = prefs[KEY_AI_MODEL] ?: "",
            webSearch = prefs[KEY_AI_WEB_SEARCH] ?: true,
            wire = prefs[KEY_AI_WIRE]
                ?.let { name -> AiEndpoint.Wire.entries.firstOrNull { it.name == name } }
                ?: AiEndpoint.Wire.CHAT,
            key = prefs[KEY_AI_KEY]?.let { SecretVault.unseal(it)?.decodeToString() },
            systemPrompt = prefs[KEY_AI_PROMPT] ?: "",
        )
    }

    suspend fun setAiInsightsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_AI_INSIGHTS] = enabled }
    }

    suspend fun setAiCoachEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_AI_COACH] = enabled }
    }

    suspend fun setAiEndpoint(baseUrl: String, model: String, wire: AiEndpoint.Wire) {
        context.dataStore.edit {
            it[KEY_AI_BASE_URL] = baseUrl.trim()
            it[KEY_AI_MODEL] = model.trim()
            it[KEY_AI_WIRE] = wire.name
        }
    }

    /**
     * Stores the key sealed, or clears it when given nothing.
     *
     * A key the Keystore refuses to seal is not written in the clear as a fallback. It is
     * dropped, and the switch that needed it simply has no key — which the screen can say
     * plainly, where a silently-plaintext secret could not be noticed at all.
     */
    suspend fun setAiKey(key: String?) {
        val sealed = key?.trim()?.takeIf { it.isNotEmpty() }?.let { SecretVault.seal(it.toByteArray()) }
        context.dataStore.edit { prefs ->
            if (sealed == null) prefs.remove(KEY_AI_KEY) else prefs[KEY_AI_KEY] = sealed
        }
    }

    suspend fun setAiWebSearch(enabled: Boolean) {
        context.dataStore.edit { it[KEY_AI_WEB_SEARCH] = enabled }
    }

    suspend fun setAiSystemPrompt(prompt: String) {
        context.dataStore.edit { it[KEY_AI_PROMPT] = prompt }
    }

    suspend fun setGpsAlmanacAuto(enabled: Boolean) {
        context.dataStore.edit { it[KEY_ALMANAC_AUTO] = enabled }
    }

    suspend fun setAlmanacSentAt(millis: Long, format: Int) {
        context.dataStore.edit {
            it[KEY_ALMANAC_SENT_AT] = millis
            it[KEY_ALMANAC_FORMAT] = format
        }
    }

    suspend fun setWeatherEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_WEATHER_ENABLED] = enabled }
    }

    suspend fun setWeatherAutoPlace(enabled: Boolean) {
        context.dataStore.edit { it[KEY_WEATHER_AUTO_PLACE] = enabled }
    }

    suspend fun setWeatherPlace(name: String, latitude: Double, longitude: Double) {
        context.dataStore.edit { prefs ->
            prefs[KEY_WEATHER_CITY] = name
            prefs[KEY_WEATHER_LAT] = latitude
            prefs[KEY_WEATHER_LON] = longitude
        }
    }

    /**
     * The newest build the wearer has already been told about.
     *
     * Kept so the daily check can tell "there is an update" from "there is an update you
     * have not seen": the first is true every day until they install it, and a phone that
     * says so every day is a phone people learn to ignore.
     */
    val lastAnnouncedVersion: Flow<Int> =
        context.dataStore.data.map { it[KEY_LAST_ANNOUNCED_VERSION] ?: 0 }

    suspend fun setLastAnnouncedVersion(versionCode: Int) {
        context.dataStore.edit { it[KEY_LAST_ANNOUNCED_VERSION] = versionCode }
    }

    /**
     * Apps whose notifications the wearer has silenced.
     *
     * A blocklist rather than an allowlist, even though the picker lists everything: a
     * watch that goes quiet until apps have been ticked one by one looks broken, and
     * turning a hundred switches on to get back what already worked is not a setup anyone
     * asked for. Everything is forwarded until it is turned off — and the picker has a
     * "none" button for anyone who would rather start from silence.
     */
    val notificationBlockedPackages: Flow<Set<String>> =
        context.dataStore.data.map { it[KEY_NOTIFICATION_BLOCKED].orEmpty() }

    suspend fun setNotificationBlocked(packageName: String, blocked: Boolean) {
        setNotificationBlocked(listOf(packageName), blocked)
    }

    suspend fun setNotificationBlocked(packageNames: Collection<String>, blocked: Boolean) {
        if (packageNames.isEmpty()) return

        context.dataStore.edit { prefs ->
            val current = prefs[KEY_NOTIFICATION_BLOCKED].orEmpty()
            prefs[KEY_NOTIFICATION_BLOCKED] =
                if (blocked) current + packageNames else current - packageNames.toSet()
        }
    }

    /**
     * The last night the watch reported, kept across restarts.
     *
     * The protocol log is a ring buffer of a couple of hundred entries, which on a phone
     * syncing every five minutes is a couple of hours — so a sleep frame that arrives at
     * six in the morning is gone before anyone looks. This is not that: it is written down
     * and stays, and the raw bytes stay with it so a parse that turns out wrong can be
     * re-read against what the watch actually sent.
     */
    val lastSleep: Flow<SleepSummary?> = context.dataStore.data.map { prefs ->
        val start = prefs[KEY_SLEEP_START] ?: return@map null
        val wake = prefs[KEY_SLEEP_WAKE] ?: return@map null

        SleepSummary(
            startSeconds = start,
            wakeSeconds = wake,
            stages = prefs[KEY_SLEEP_STAGES] ?: 0,
            raw = prefs[KEY_SLEEP_RAW].orEmpty(),
        )
    }

    /**
     * The night already sent to Health Connect, by the second it started.
     *
     * The watch hands a night over exactly once, twenty minutes or so after the wearer
     * gets up, and never again — so a night that arrives while Health Connect is off, or
     * while the app is a version that could not write it yet, is a night nobody can ask
     * for a second time. The bytes are kept anyway; this is what says whether they still
     * need sending.
     */
    val lastSleepWrittenStart: Flow<Long> =
        context.dataStore.data.map { it[KEY_SLEEP_WRITTEN_START] ?: 0L }

    suspend fun setLastSleepWritten(startSeconds: Long) {
        context.dataStore.edit { it[KEY_SLEEP_WRITTEN_START] = startSeconds }
    }

    suspend fun setLastSleep(summary: SleepSummary) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SLEEP_START] = summary.startSeconds
            prefs[KEY_SLEEP_WAKE] = summary.wakeSeconds
            prefs[KEY_SLEEP_STAGES] = summary.stages
            prefs[KEY_SLEEP_RAW] = summary.raw
        }
    }

    /** When the app last asked GitHub anything, so opening it does not ask every time. */
    val lastUpdateCheckSeconds: Flow<Long> =
        context.dataStore.data.map { it[KEY_LAST_UPDATE_CHECK] ?: 0L }

    suspend fun setLastUpdateCheck(epochSeconds: Long) {
        context.dataStore.edit { it[KEY_LAST_UPDATE_CHECK] = epochSeconds }
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
        val KEY_PHONE_ALARMS = booleanPreferencesKey("phone_alarms_enabled")

        val KEY_AI_INSIGHTS = booleanPreferencesKey("ai_insights_enabled")
        val KEY_AI_COACH = booleanPreferencesKey("ai_coach_enabled")
        val KEY_AI_BASE_URL = stringPreferencesKey("ai_base_url")
        val KEY_AI_MODEL = stringPreferencesKey("ai_model")

        /** Sealed, and named in [Backup.NEVER_LEAVES] so no export can carry it. */
        val KEY_AI_KEY = stringPreferencesKey("ai_key_sealed")
        val KEY_AI_PROMPT = stringPreferencesKey("ai_system_prompt")
        val KEY_AI_WIRE = stringPreferencesKey("ai_wire")
        val KEY_AI_WEB_SEARCH = booleanPreferencesKey("ai_web_search")

        /**
         * Where requests go unless told otherwise.
         *
         * Perplexity because it is what this was first pointed at, and because the shape
         * its API speaks is one anything else speaks too — which is the actual decision
         * here. Anything is three fields away, so this is a starting point and not a
         * commitment.
         *
         * The `/v1` is part of it because Perplexity's Agent API lives there, and their
         * Sonar endpoints — which do not — retire on 27 September 2026.
         *
         * No default model, deliberately. The obvious one to have written here was `sonar`,
         * which stops answering that month; a default that expires is worse than a blank
         * field, because a blank field says plainly that something is needed.
         */
        const val DEFAULT_AI_BASE_URL = "https://api.perplexity.ai/v1"

        val KEY_ALMANAC_AUTO = booleanPreferencesKey("gps_almanac_auto")
        val KEY_ALMANAC_SENT_AT = longPreferencesKey("almanac_sent_at")
        val KEY_ALMANAC_FORMAT = intPreferencesKey("almanac_format")
        val KEY_WEATHER_ENABLED = booleanPreferencesKey("weather_enabled")
        val KEY_WEATHER_CITY = stringPreferencesKey("weather_city")
        val KEY_WEATHER_AUTO_PLACE = booleanPreferencesKey("weather_auto_place")
        val KEY_WEATHER_LAT = doublePreferencesKey("weather_latitude")
        val KEY_WEATHER_LON = doublePreferencesKey("weather_longitude")
        val KEY_LAST_SYNC = longPreferencesKey("last_sync_epoch_seconds")
        val KEY_LAST_ANNOUNCED_VERSION = intPreferencesKey("last_announced_version_code")
        val KEY_LAST_UPDATE_CHECK = longPreferencesKey("last_update_check_epoch_seconds")
        val KEY_NOTIFICATION_BLOCKED = stringSetPreferencesKey("notification_blocked_packages")
        val KEY_SLEEP_START = longPreferencesKey("last_sleep_start")
        val KEY_SLEEP_WAKE = longPreferencesKey("last_sleep_wake")
        val KEY_SLEEP_STAGES = intPreferencesKey("last_sleep_stages")
        val KEY_SLEEP_RAW = stringPreferencesKey("last_sleep_raw")
        val KEY_SLEEP_WRITTEN_START = longPreferencesKey("last_sleep_written_start")


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
