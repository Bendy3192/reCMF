/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.ui

import android.content.Intent
import android.app.Application
import android.content.Context
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.recmf.ble.ConnectionState
import dev.recmf.ble.DiscoveredWatch
import dev.recmf.ble.WatchScanner
import dev.recmf.data.ActivitySampleEntity
import dev.recmf.data.DailyTotals
import dev.recmf.data.HeartRateSampleEntity
import dev.recmf.data.RecmfDatabase
import dev.recmf.data.SettingsStore
import dev.recmf.data.SleepSessionEntity
import dev.recmf.data.SleepSummary
import dev.recmf.data.WatchPreferences
import dev.recmf.data.WatchSetting
import dev.recmf.data.WatchSettings
import dev.recmf.health.CumulativeReading
import dev.recmf.health.HealthConnectAvailability
import dev.recmf.health.stepDeltas
import androidx.core.app.NotificationManagerCompat
import dev.recmf.health.HealthConnectSync
import dev.recmf.protocol.BatteryStatus
import dev.recmf.protocol.CmfParsers
import dev.recmf.protocol.SleepSession
import dev.recmf.protocol.WatchfaceList
import dev.recmf.protocol.hexToBytes
import dev.recmf.BuildConfig
import dev.recmf.service.WatchService
import dev.recmf.update.AvailableUpdate
import dev.recmf.update.UpdateState
import dev.recmf.update.Updater
import androidx.core.net.toUri
import kotlin.math.roundToInt
import dev.recmf.ai.AiClient
import dev.recmf.ai.AiChat
import dev.recmf.ai.AiContext
import dev.recmf.ai.AiEndpoint
import dev.recmf.data.AiInsightEntity
import dev.recmf.data.CoachMessageEntity
import dev.recmf.data.AiSettings
import dev.recmf.data.Backup
import dev.recmf.data.BackupStore
import dev.recmf.health.Night
import dev.recmf.health.Readiness
import dev.recmf.health.ReadinessSignal
import dev.recmf.health.SleepScore
import dev.recmf.health.comparable
import dev.recmf.health.onlyOneSource
import dev.recmf.health.preferMeasured
import dev.recmf.health.restingEnergy
import dev.recmf.health.readiness as scoreReadiness
import dev.recmf.health.sleepScore as scoreSleep
import dev.recmf.service.AlarmMirrorProblem
import dev.recmf.service.WeatherProblem
import dev.recmf.service.WatchStatus
import dev.recmf.service.WatchfaceInstall
import dev.recmf.service.WatchdogWorker
import dev.recmf.weather.WeatherClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import dev.recmf.health.workoutSessions
import dev.recmf.weather.PhoneLocation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.Year
import java.time.ZoneId
import java.util.Locale
import java.time.LocalDate

/** What the health screen draws for the day so far. */
data class HealthCharts(
    val heartRate: List<ChartPoint> = emptyList(),
    val stepsByHour: List<Float> = emptyList(),
    val spo2: List<ChartPoint> = emptyList(),
)

/**
 * One workout, as far as this watch will admit to one.
 *
 * There is no sport here and no distance, because the watch keeps neither where reCMF can
 * reach it: it answers a request for workout summaries with an empty payload and sends
 * only the pulse it took while the session ran. What is here is what that pulse says —
 * when it started, when it stopped, and how hard the wearer was working.
 */
data class WorkoutRow(
    val startSeconds: Long,
    val endSeconds: Long,
    val averageBpm: Int,
    val maxBpm: Int,
    val pulse: List<ChartPoint>,
) {
    val seconds: Long get() = endSeconds - startSeconds
}

/**
 * One day of one measurement.
 *
 * [value] is null for a day the watch reported nothing — not zero. A day not worn and a
 * day spent still are different facts, and a bar of height zero says the second.
 */
data class DayValue(val date: LocalDate, val value: Float?)

/** A cumulative counter's figure for a day: the highest reading, never the sum. */
private fun highest(values: List<Float>): Float = values.max()

/** A measurement's figure for a day, where there is no total to speak of. */
private fun mean(values: List<Float>): Float = values.average().toFloat()

/**
 * The last seven days of each measurement, one figure per day, for the strips under the
 * tiles. Always seven entries, oldest first, whether or not the watch has anything for
 * them.
 */
data class WeeklySeries(
    val heartRate: List<DayValue> = emptyList(),
    val steps: List<DayValue> = emptyList(),
    val distanceMeters: List<DayValue> = emptyList(),
    val calories: List<DayValue> = emptyList(),
    val climbs: List<DayValue> = emptyList(),
    val restingHeartRate: List<DayValue> = emptyList(),
    val spo2: List<DayValue> = emptyList(),
    val stress: List<DayValue> = emptyList(),
)

/**
 * Where an export or an import has got to, in the words the card uses.
 *
 * [NotOurs] is kept apart from [Failed] on purpose: a file that is simply not a reCMF
 * backup is the commonest way this goes wrong and wants a different sentence from a disk
 * that would not open.
 */
sealed interface BackupState {
    data object Working : BackupState
    data class Exported(val settings: Int, val rows: Int) : BackupState
    data class Imported(val settings: Int, val rows: Int) : BackupState
    data object NotOurs : BackupState
    data class Failed(val reason: String) : BackupState
}

/** Something the assistant said about a measurement, and when. */
data class AiInsight(
    val text: String,
    val sources: List<String>,
    val atSeconds: Long,
    /** What it was about: the figure explained, and the last day it could see. */
    val basis: String,
)

/** One app in the notification list, as the settings screen shows it. */
data class NotificationApp(
    val packageName: String,
    val label: String,
    val blocked: Boolean,
)

data class HomeUiState(
    val connection: ConnectionState = ConnectionState.IDLE,
    val settings: WatchSettings = WatchSettings(),
    val watch: WatchInfo = WatchInfo(),
    /** Steps, distance, calories and climbs since midnight, as the watch counts them. */
    val today: DailyTotals = DailyTotals(),
    val latestHeartRate: HeartRateSampleEntity? = null,
    val weather: WeatherStatus = WeatherStatus(),
    /** Percent, from the day's newest stored reading. */
    val spo2: Int? = null,

    /** The watch's own 0-100 scale, from the day's newest stored reading. */
    val stress: Int? = null,

    /** Beats per minute, from the day's newest stored reading. */
    val restingHeartRate: Int? = null,
)

/** How the search for a place is going. */
sealed interface CityLookup {
    data object Idle : CityLookup
    data object Searching : CityLookup
    data class Found(val name: String) : CityLookup
    data object NotFound : CityLookup

    /** Asked where the phone is and got no answer — no permission, or nothing knows. */
    data object NoPosition : CityLookup
}

/** What the watch itself has told us this session. */
data class WatchInfo(
    val battery: BatteryStatus? = null,
    val firmware: String? = null,
    val serialNumber: String? = null,
    /** The newest record the watch handed over, timestamped by the watch's own clock. */
    val lastRecordEpochSeconds: Long? = null,
    val lastRecordCount: Int? = null,
    /** When the watch last finished answering, new data or not. */
    val lastExchangeAtMillis: Long? = null,
)

/**
 * The tail of [HomeUiState], bundled only because `combine` takes five flows and the
 * state needs more than five sources. Not a concept — just the overflow.
 */
private data class Extras(
    val heartRate: HeartRateSampleEntity?,
    val weather: WeatherStatus,
    val spo2: Int?,
    val stress: Int?,
    val restingHeartRate: Int?,
)

/** What has become of the forecast, so the card can say rather than sit blank. */
data class WeatherStatus(
    val sentAtMillis: Long? = null,
    val temperatureC: Int? = null,
    val problem: WeatherProblem? = null,
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = SettingsStore(application)
    private val dao = RecmfDatabase.get(application).sampleDao()
    private val scanner = WatchScanner(application)
    private val healthConnect = HealthConnectSync(application)
    private val weatherClient = WeatherClient()

    private val _discovered = MutableStateFlow<List<DiscoveredWatch>>(emptyList())
    val discovered: StateFlow<List<DiscoveredWatch>> = _discovered.asStateFlow()

    private val _scanError = MutableStateFlow<String?>(null)
    val scanError: StateFlow<String?> = _scanError.asStateFlow()

    private var scanJob: Job? = null

    private val watchInfo = combine(
        combine(WatchStatus.battery, WatchStatus.firmware, WatchStatus.serialNumber, ::Triple),
        WatchStatus.lastRecordEpochSeconds,
        WatchStatus.lastRecordCount,
        WatchStatus.lastExchangeAtMillis,
    ) { (battery, firmware, serial), recordAt, recordCount, exchangeAt ->
        WatchInfo(battery, firmware, serial, recordAt, recordCount, exchangeAt)
    }

    private val weatherStatus = combine(
        WatchStatus.weatherSentAtMillis,
        WatchStatus.weatherTemperatureC,
        WatchStatus.weatherProblem,
    ) { sentAt, temperature, problem -> WeatherStatus(sentAt, temperature, problem) }

    val uiState: StateFlow<HomeUiState> = combine(
        WatchStatus.state,
        settingsStore.settings,
        watchInfo,
        // Bound at construction, so a screen left open across midnight keeps counting
        // into yesterday until it is recreated.
        dao.totalsSince(startOfToday()),
        combine(
            dao.latestHeartRate(),
            weatherStatus,
            // From the table, not from the service's memory. The in-memory value is empty
            // until a sample happens to arrive, so after every restart the card read "—"
            // for blood oxygen above a chart that was full of it. Stress now reads the
            // same way: Health Connect still has no record type for it, so reCMF's own
            // table is the only place it survives a restart.
            dao.latestSpo2Since(startOfToday()).map { it?.percent },
            dao.latestStressSince(startOfToday()).map { it?.level },
            dao.latestRestingHeartRateSince(startOfToday()).map { it?.bpm },
            ::Extras,
        ),
    ) { connection, settings, watch, today, extras ->
        HomeUiState(
            connection = connection,
            settings = settings,
            watch = watch,
            today = today,
            latestHeartRate = extras.heartRate,
            weather = extras.weather,
            spo2 = extras.spo2,
            stress = extras.stress,
            restingHeartRate = extras.restingHeartRate,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), HomeUiState())

    val watchPreferences: StateFlow<WatchPreferences> = settingsStore.watchPreferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), WatchPreferences())

    /** Applied to the watch by the service, which is watching the same flow. */
    fun updateWatchPreferences(
        setting: WatchSetting,
        transform: (WatchPreferences) -> WatchPreferences,
    ) {
        viewModelScope.launch { settingsStore.updateWatchPreferences(setting, transform) }
    }

    val healthConnectAvailability: HealthConnectAvailability get() = healthConnect.availability()

    /**
     * Whether distance and calories may be written.
     *
     * True when Health Connect is not usable at all, because there is then nothing to ask
     * for and a permission dialog that cannot be answered is worse than none.
     */
    suspend fun hasExtraHealthPermissions(): Boolean =
        healthConnectAvailability != HealthConnectAvailability.AVAILABLE ||
            healthConnect.hasExtraPermissions()

    /**
     * Whether the user has granted notification access. It is granted in system settings
     * rather than by a permission dialog, so there is nothing to request — only to check
     * and to link to.
     */
    /**
     * Whether the system will let reCMF work while the phone is idle.
     *
     * Without this the background refresh runs when Android feels like letting it, which
     * for a watch that is meant to stay in step is the difference between a companion app
     * and a widget you have to open.
     */
    fun isExemptFromBatteryOptimisation(): Boolean {
        val context = getApplication<Application>()
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun hasNotificationAccess(): Boolean {
        val context = getApplication<Application>()
        return context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setNotificationsEnabled(enabled) }
    }

    fun setNotifyOnlyWhenScreenOff(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setNotifyOnlyWhenScreenOff(enabled) }
    }

    fun setAutoSyncSeconds(seconds: Int) {
        viewModelScope.launch { settingsStore.setAutoSyncSeconds(seconds) }
    }

    fun setWeatherEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setWeatherEnabled(enabled) }
    }

    private val _cityLookup = MutableStateFlow<CityLookup>(CityLookup.Idle)
    val cityLookup: StateFlow<CityLookup> = _cityLookup.asStateFlow()

    /** Resolves a typed place to coordinates once, so nothing has to ask for location. */
    fun findCity(city: String) {
        if (city.isBlank()) return

        viewModelScope.launch {
            _cityLookup.value = CityLookup.Searching

            val found = weatherClient.geocode(city, java.util.Locale.getDefault().language)
            _cityLookup.value = if (found == null) {
                CityLookup.NotFound
            } else {
                settingsStore.setWeatherPlace(found.name, found.latitude, found.longitude)
                CityLookup.Found(found.name)
            }
        }
    }

    /**
     * Takes the place from the phone instead of from a typed name.
     *
     * Ends in the same place a typed city does — a name and a pair of coordinates in the
     * settings — so nothing downstream knows or cares which way the wearer chose.
     */
    fun useCurrentLocation() {
        viewModelScope.launch {
            _cityLookup.value = CityLookup.Searching

            val here = withContext(Dispatchers.IO) {
                PhoneLocation.current(getApplication())
            }

            _cityLookup.value = if (here == null) {
                CityLookup.NoPosition
            } else {
                settingsStore.setWeatherPlace(here.name, here.latitude, here.longitude)
                CityLookup.Found(here.name)
            }
        }
    }

    fun setWeatherAutoPlace(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setWeatherAutoPlace(enabled)
            if (enabled) useCurrentLocation()
        }
    }

    /**
     * Takes the place again when the app is opened, if that is how it is set up.
     *
     * This is the half of "automatic" that needs no special permission: opening the app
     * puts it in the foreground, where ordinary location is allowed. With "all the time"
     * granted the service does it too, and this becomes a formality; without it, this is
     * the whole feature — arrive somewhere, open reCMF once, and the watch has the right
     * forecast for as long as you stay.
     *
     * Silent about failure on purpose. Nobody opened the app to be told that the phone
     * has not worked out where it is yet.
     */
    fun refreshPlaceIfAutomatic() {
        viewModelScope.launch {
            val settings = settingsStore.current()
            if (!settings.weatherAutoPlace) return@launch
            if (!PhoneLocation.granted(getApplication())) return@launch

            val here = withContext(Dispatchers.IO) { PhoneLocation.current(getApplication()) }
                ?: return@launch

            settingsStore.setWeatherPlace(here.name, here.latitude, here.longitude)
        }
    }

    /** Restarts the scan. Cancelling the previous one stops the radio between presses. */
    fun startScan() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _scanError.value = null
            try {
                scanner.scan().collect { _discovered.value = it }
            } catch (e: IllegalStateException) {
                _scanError.value = e.message
            }
        }
    }

    fun pair(watch: DiscoveredWatch) {
        viewModelScope.launch {
            settingsStore.setWatch(watch.address, watch.name)
            val context = getApplication<Application>()
            WatchService.start(context)
            WatchdogWorker.schedule(context)
        }
    }

    fun forget() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            WatchService.stop(context)
            WatchdogWorker.cancel(context)
            settingsStore.forgetWatch()
        }
    }

    fun syncNow() {
        WatchService.syncNow(getApplication())
    }

    fun findWatch() {
        WatchService.findWatch(getApplication())
    }

    /**
     * Every app with a launcher icon, and whether each is silenced.
     *
     * Launchable apps rather than every installed package: the second is a list of
     * hundreds of components with no user-facing existence, and picking out of it is
     * worse than not having the list. This is the same set any launcher shows, which is
     * the set a person recognises.
     *
     * Read once, off the main thread. A phone holds a few hundred of these and each label
     * is a call into the package manager, which is not something to do while a frame is
     * waiting.
     */
    private val installedApps: Flow<List<NotificationApp>> = flow {
        emit(loadInstalledApps())
    }.flowOn(Dispatchers.IO)

    val notificationApps: StateFlow<List<NotificationApp>> = combine(
        installedApps,
        settingsStore.notificationBlockedPackages,
    ) { apps, blocked ->
        apps.map { it.copy(blocked = it.packageName in blocked) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    private fun loadInstalledApps(): List<NotificationApp> {
        val packages = getApplication<Application>().packageManager

        val launchable = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        return packages.queryIntentActivities(launchable, 0)
            .asSequence()
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .map { info ->
                NotificationApp(
                    packageName = info.packageName,
                    // An app with no label is unusual; showing its identifier beats
                    // showing a blank row nobody can act on.
                    label = packages.getApplicationLabel(info).toString()
                        .ifBlank { info.packageName },
                    blocked = false,
                )
            }
            // Case-insensitive, so "VK" does not sort miles from "Viber".
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    fun setNotificationBlocked(packageName: String, blocked: Boolean) {
        viewModelScope.launch { settingsStore.setNotificationBlocked(packageName, blocked) }
    }

    /** Everything at once, for the two buttons that save a hundred taps. */
    fun setNotificationBlocked(packageNames: List<String>, blocked: Boolean) {
        viewModelScope.launch { settingsStore.setNotificationBlocked(packageNames, blocked) }
    }

    /**
     * The day so far, as something to draw.
     *
     * All three come out of the staging table, which has been holding a week of readings
     * since the app was written and had never once been read back — the screen showed the
     * newest number of each kind and the rest sat there.
     */
    val charts: StateFlow<HealthCharts> = combine(
        dao.heartRateSince(startOfToday()),
        dao.activitySince(startOfToday()),
        dao.spo2Since(startOfToday()),
    ) { heartRate, activity, spo2 ->
        HealthCharts(
            heartRate = heartRate.map { ChartPoint(it.timestamp, it.bpm.toFloat()) },
            stepsByHour = stepsByHour(activity),
            spo2 = spo2.map { ChartPoint(it.timestamp, it.percent.toFloat()) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), HealthCharts())

    /**
     * The last seven days, one figure per measurement per day.
     *
     * Read from the same staging table as the day's charts, over a wider window. The
     * table keeps a week and no more, which is exactly what these strips draw — so this
     * asks for everything there is and nothing that has been pruned.
     *
     * Counters take the day's maximum, because the watch reports them cumulatively and a
     * sum would multiply the day by the number of syncs. Measurements take the day's
     * mean, because a heart rate has no daily total to speak of.
     */
    val weekly: StateFlow<WeeklySeries> = combine(
        dao.activitySince(startOfWeek()),
        dao.heartRateSince(startOfWeek()),
        dao.restingHeartRateSince(startOfWeek()),
        dao.spo2Since(startOfWeek()),
        dao.stressSince(startOfWeek()),
    ) { activity, heartRate, resting, spo2, stress ->
        WeeklySeries(
            heartRate = heartRate.byDay({ it.timestamp }, { it.bpm.toFloat() }, ::mean),
            steps = activity.byDay({ it.timestamp }, { it.steps.toFloat() }, ::highest),
            distanceMeters =
                activity.byDay({ it.timestamp }, { it.distanceMeters.toFloat() }, ::highest),
            calories = activity.byDay({ it.timestamp }, { it.calories.toFloat() }, ::highest),
            climbs = activity.byDay({ it.timestamp }, { it.climbs.toFloat() }, ::highest),
            restingHeartRate = resting.byDay({ it.timestamp }, { it.bpm.toFloat() }, ::mean),
            spo2 = spo2.byDay({ it.timestamp }, { it.percent.toFloat() }, ::mean),
            stress = stress.byDay({ it.timestamp }, { it.level.toFloat() }, ::mean),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), WeeklySeries())

    /**
     * How today sits against the days behind it.
     *
     * Assembled here rather than in [readiness] itself so the scoring stays a function of
     * numbers and can be tested without a database: this end knows about days, timezones
     * and which night belongs to which morning, and that end knows about none of it.
     *
     * A night is filed under the day it **ended**. Sleep that began at 23:40 is Tuesday's
     * sleep to the calendar and Wednesday's rest to the person waking up, and it is the
     * person the score is about.
     *
     * Null until today has something to say. Nothing is carried forward from yesterday to
     * fill the gap — a score labelled today that was computed from yesterday is worse than
     * no score, and the morning before the first sync of the day is exactly when somebody
     * would be looking.
     */
    /**
     * Variability by day, from whatever else on this phone measures it.
     *
     * A plain state rather than a flow off the database, because it is not ours: Health
     * Connect is read when asked and does not push.
     */
    private val _hrv = MutableStateFlow<Map<LocalDate, Float>>(emptyMap())

    /**
     * Last night as another wearable recorded it, by the date it was woken up on.
     *
     * Read for one thing above all: the waking stage, which this watch has no word for.
     */
    private val _nightsElsewhere = MutableStateFlow<Map<LocalDate, Night>>(emptyMap())

    /**
     * Resting pulse as another device has it, by day.
     *
     * The figure another wearable's own score named as the single cause of a low morning,
     * on a day this one called ordinary for want of any resting pulse with history behind
     * it.
     */
    private val _restingElsewhere = MutableStateFlow<Map<LocalDate, Float>>(emptyMap())

    /**
     * Reads what the other devices on this phone have, and reads it again after each sync.
     *
     * Watching the exchange clock rather than calling this from several places: every path
     * that brings new watch data ends there, and a StateFlow hands over its current value
     * on subscription, so the first reading happens on start without being asked for
     * separately.
     *
     * Both reads are as often as figures taken once a night can change, and both are
     * harmless when there is nothing to read — an empty answer is what a phone with one
     * wearable is meant to produce.
     */
    private fun watchOtherDevices() {
        viewModelScope.launch {
            WatchStatus.lastExchangeAtMillis.collect {
                val zone = ZoneId.systemDefault()
                _hrv.value = healthConnect.heartRateVariability(BASELINE_DAYS, zone)
                _nightsElsewhere.value = healthConnect.nightsElsewhere(BASELINE_DAYS, zone)
                _restingElsewhere.value =
                    healthConnect.restingHeartRateElsewhere(BASELINE_DAYS, zone)
            }
        }
    }

    /**
     * Last night, scored the way the published sleep scores are.
     *
     * Assembled here rather than in [SleepScore] because only this class knows where the
     * pieces live: the nights are reCMF's, the resting pulse is reCMF's, and the waking
     * stage is somebody else's. What to do when two devices both have a night is the one
     * decision worth keeping out of a view model, and [preferMeasured] holds it.
     *
     * The history handed to the score deliberately stops short of the night being scored.
     * A night included in its own baseline pulls the average towards itself and the score
     * towards the middle, which is how a scale quietly stops saying anything.
     */
    val sleepScore: StateFlow<SleepScore?> = combine(
        dao.sleepSince(startOfBaseline()),
        dao.restingHeartRateSince(startOfBaseline()),
        combine(_nightsElsewhere, _restingElsewhere, ::Pair),
        settingsStore.settings,
    ) { own, resting, (elsewhere, restingElsewhere), settings ->
        val zone = ZoneId.systemDefault()
        fun day(seconds: Long): LocalDate = Instant.ofEpochSecond(seconds).atZone(zone).toLocalDate()

        val nights = nightsByDay(own, elsewhere, zone)

        val ourResting = resting
            .groupBy { day(it.timestamp) }
            .mapValues { (_, day) -> day.map { it.bpm.toFloat() }.average().toFloat() }

        val last = nights.keys.maxOrNull()

        // The same one-source rule readiness uses, decided against the night being scored
        // rather than against today: a score written for last night should be judged by
        // whatever measured that morning.
        val restingByDay = last
            ?.let { onlyOneSource(ourResting, restingElsewhere, it, LEAST_DAYS).readings }
            .orEmpty()

        if (last == null) {
            null
        } else {
            val scored = nights.getValue(last)

            val restfulHistory = comparable(nights, scored)
                .filterKeys { it < last }
                .toSortedMap()
                .values
                .filter { it.staged && it.asleepSeconds > 0 }
                .map { it.restfulSeconds.toFloat() / it.asleepSeconds }

            val restingHistory = restingByDay.filterKeys { it < last }.values.toList()

            scored
                .copy(restingHeartRate = restingByDay[last])
                .let {
                    scoreSleep(
                        night = it,
                        restfulHistory = restfulHistory,
                        restingHistory = restingHistory,
                        targetSeconds = settings.sleepTargetMinutes * 60,
                    )
                }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    fun finishOnboarding() {
        viewModelScope.launch { settingsStore.setOnboardingDone() }
    }

    fun setSleepTargetMinutes(minutes: Int) {
        viewModelScope.launch { settingsStore.setSleepTargetMinutes(minutes) }
    }

    /**
     * One night per morning, from whichever device measured it.
     *
     * Shared by readiness and the sleep score because they must not disagree about what
     * last night was. They did: the score went through [preferMeasured] and readiness read
     * only reCMF's own table, so a night spent wearing the other device existed on one
     * screen and not on the other — and readiness quietly dropped two of its five signals
     * for a night that had been measured perfectly well.
     */
    private fun nightsByDay(
        own: List<SleepSessionEntity>,
        elsewhere: Map<LocalDate, Night>,
        zone: ZoneId,
    ): Map<LocalDate, Night> {
        val ours = own.associate { night ->
            Instant.ofEpochSecond(night.wakeTimestamp).atZone(zone).toLocalDate() to Night(
                asleepSeconds = night.asleepSeconds,
                restfulSeconds = night.deepSeconds + night.remSeconds,
                fromWatch = true,
            )
        }

        return (ours.keys + elsewhere.keys)
            .associateWith { preferMeasured(ours[it], elsewhere[it]) }
            .mapNotNull { (date, night) -> night?.let { date to it } }
            .toMap()
    }

    val readiness: StateFlow<Readiness?> = combine(
        dao.restingHeartRateSince(startOfBaseline()),
        dao.stressSince(startOfBaseline()),
        dao.sleepSince(startOfBaseline()),
        combine(_nightsElsewhere, _restingElsewhere, ::Pair),
        _hrv,
    ) { resting, stress, own, (elsewhere, restingElsewhere), hrv ->
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()

        val nights = nightsByDay(own, elsewhere, zone)

        // Last night decides which nights count as a baseline, so a phone that swapped
        // wearables partway through the fortnight compares like with like either way.
        val tonight = nights.keys.maxOrNull()?.let(nights::getValue)

        fun <T> daily(rows: List<T>, at: (T) -> Long, value: (T) -> Float?): Map<LocalDate, Float> =
            rows.groupBy { Instant.ofEpochSecond(at(it)).atZone(zone).toLocalDate() }
                .mapValues { (_, day) -> day.mapNotNull(value) }
                .filterValues { it.isNotEmpty() }
                .mapValues { (_, values) -> values.average().toFloat() }

        val sleepNights = tonight?.let { comparable(nights, it) }.orEmpty()

        // One device's resting pulse, used whole. Which one is decided by coverage rather
        // than by preference, and only once per computation: a source that changed
        // partway through the window would destroy the comparison it was chosen for.
        val pulse = onlyOneSource(
            own = daily(resting, { it.timestamp }, { it.bpm.toFloat() }),
            elsewhere = restingElsewhere,
            today = today,
            leastDays = LEAST_DAYS,
        )

        val byDay = mapOf(
            // Already a figure a day when it arrives, so it needs none of the averaging
            // the watch's own trickle does.
            ReadinessSignal.HEART_RATE_VARIABILITY to hrv,
            ReadinessSignal.RESTING_HEART_RATE to pulse.readings,
            ReadinessSignal.STRESS to
                daily(stress, { it.timestamp }, { it.level.toFloat() }),
            // Only nights the same device measured as last night's. Two wearables
            // disagree about how long somebody slept, and a run of one device's nights is
            // not a baseline for the other's.
            ReadinessSignal.SLEEP_DURATION to sleepNights
                .mapValues { (_, night) -> night.asleepSeconds.toFloat() / 60f },
            // Also only from a night something broke into stages. A session that is a
            // start and an end has no restful share to report, and reading its zero as
            // "none of it restored anything" would be the worst night this person ever had.
            ReadinessSignal.SLEEP_QUALITY to sleepNights
                .filterValues { it.staged && it.asleepSeconds > 0 }
                .mapValues { (_, night) -> night.restfulSeconds.toFloat() / night.asleepSeconds },
        )

        // Aliased on import: the property being initialised here is also called readiness,
        // and a reader should not have to work out which one a bare call resolves to.
        scoreReadiness(
            today = byDay.mapNotNull { (signal, days) -> days[today]?.let { signal to it } }.toMap(),
            history = byDay.mapValues { (_, days) ->
                days.filterKeys { it < today }.toSortedMap().values.toList()
            },
            // Variability is always another device's — this watch cannot produce it at all.
            elsewhere = buildSet {
                add(ReadinessSignal.HEART_RATE_VARIABILITY)
                if (!pulse.fromWatch) add(ReadinessSignal.RESTING_HEART_RATE)
            },
            leastDays = LEAST_DAYS,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    /**
     * The workouts, newest first.
     *
     * Built rather than read: this watch keeps no summary of a session and will not answer
     * a request for one, so what a workout *is* here is a run of pulse the watch marked as
     * taken during exercise. See `workoutSessions` for where the edges are drawn.
     *
     * Ninety days, which is as far back as the table is allowed to keep them — workout
     * pulse is the one thing in it that is not pruned once Health Connect has it.
     */
    val workouts: StateFlow<List<WorkoutRow>> = dao.workoutHeartRate(startOfWorkoutHistory())
        .map { samples ->
            val ordered = samples.sortedBy { it.timestamp }

            workoutSessions(ordered.map { it.timestamp })
                .map { session ->
                    val during = ordered.filter { it.timestamp in session }
                    WorkoutRow(
                        startSeconds = session.first,
                        endSeconds = session.last,
                        averageBpm = during.sumOf { it.bpm } / during.size,
                        maxBpm = during.maxOf { it.bpm },
                        pulse = during.map { ChartPoint(it.timestamp, it.bpm.toFloat()) },
                    )
                }
                .reversed()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    private fun startOfWorkoutHistory(): Long =
        java.time.Instant.now().epochSecond - WORKOUT_HISTORY_SECONDS

    /**
     * Groups readings into the seven days ending today, in the phone's own time zone.
     *
     * A day the watch said nothing about comes back null rather than absent, so the strip
     * keeps its seven columns and a gap stays a gap. Grouping is done here and not in SQL
     * because a local calendar day is not something epoch seconds know about.
     */
    private fun <T> List<T>.byDay(
        at: (T) -> Long,
        value: (T) -> Float,
        summarise: (List<Float>) -> Float,
    ): List<DayValue> {
        val zone = ZoneId.systemDefault()
        val byDate = groupBy { Instant.ofEpochSecond(at(it)).atZone(zone).toLocalDate() }
        val today = LocalDate.now()

        return (DAYS_IN_STRIP - 1L downTo 0L).map { back ->
            val date = today.minusDays(back)
            DayValue(date, byDate[date]?.map(value)?.takeIf { it.isNotEmpty() }?.let(summarise))
        }
    }

    /**
     * The day's steps, split into the hour each one was taken in.
     *
     * The table holds a cumulative counter, so this is the same differencing that feeds
     * Health Connect — the movement between readings — bucketed by when it happened. Bars
     * of a running total would be a staircase that only ever climbs, which says nothing
     * about when the walking was.
     */
    private fun stepsByHour(readings: List<ActivitySampleEntity>): List<Float> {
        val hours = FloatArray(HOURS_IN_DAY)
        val zone = ZoneId.systemDefault()

        stepDeltas(readings.map { CumulativeReading(it.timestamp, it.steps, it.distanceMeters, it.calories) })
            .forEach { delta ->
                // Attributed to the hour the interval ended in. Splitting an interval
                // across an hour boundary would be more honest and, at five minutes a
                // reading, entirely invisible.
                val hour = Instant.ofEpochSecond(delta.endSeconds).atZone(zone).hour
                hours[hour] += delta.steps.toFloat()
            }

        return hours.toList()
    }

    /**
     * The faces the watch listed this connection, or null before it has listed any.
     *
     * Straight from [WatchStatus] and nowhere else: this is the watch's current state, not
     * a record of anything, and it goes empty on a restart on purpose.
     */
    val watchfaces: StateFlow<WatchfaceList?> = WatchStatus.watchfaces

    /** How the current install is going, straight from the service. */
    val watchfaceInstall: StateFlow<WatchfaceInstall?> = WatchStatus.watchfaceInstall

    /** Why the phone's alarms are not reaching the watch, or null while they are. */
    val alarmMirrorProblem: StateFlow<AlarmMirrorProblem?> = WatchStatus.alarmMirrorProblem

    /** Sends a face file, displacing the one at [replacedIndex] in the watch's list. */
    fun setGpsAlmanacAuto(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setGpsAlmanacAuto(enabled) }
    }

    /** Hands the watch an EPO file, so its own GPS starts from something. */
    fun installAgps(uri: String) {
        WatchService.installAgps(getApplication(), uri)
    }

    fun installWatchface(uri: String, replacedIndex: Int) {
        WatchService.installWatchface(getApplication(), uri, replacedIndex)
    }

    /** Hands the watch its own list back with a different face marked active. */
    fun selectWatchface(index: Int) {
        WatchService.setWatchface(getApplication(), index)
    }

    /**
     * The days exactly as the assistant would be shown them.
     *
     * Built from the same tables the screen draws, so the preview is not a mock-up of what
     * would be sent — it is what would be sent. If this and the request ever disagreed, the
     * preview would be worse than not offering one.
     */
    val aiDays: StateFlow<List<AiContext.Day>> = combine(
        dao.restingHeartRateSince(startOfBaseline()),
        dao.stressSince(startOfBaseline()),
        dao.sleepSince(startOfBaseline()),
        // Paired rather than passed separately: combine takes five flows and this needs
        // six. The two that travel together are the two the watch sends in the same
        // exchange.
        combine(
            dao.activitySince(startOfBaseline()),
            dao.spo2Since(startOfBaseline()),
            dao.heartRateSince(startOfBaseline()),
            ::Triple,
        ),
        // The two readings that come from other devices, likewise paired.
        combine(_hrv, _nightsElsewhere, ::Pair),
    ) { resting, stress, own, (activity, spo2, pulse), (hrv, elsewhere) ->
        val zone = ZoneId.systemDefault()

        fun <T> mean(rows: List<T>, at: (T) -> Long, value: (T) -> Float?): Map<LocalDate, Float> =
            rows.groupBy { Instant.ofEpochSecond(at(it)).atZone(zone).toLocalDate() }
                .mapValues { (_, day) -> day.mapNotNull(value) }
                .filterValues { it.isNotEmpty() }
                .mapValues { (_, values) -> values.average().toFloat() }

        val restingByDay = mean(resting, { it.timestamp }, { it.bpm.toFloat() })
        val stressByDay = mean(stress, { it.timestamp }, { it.level.toFloat() })
        // Through the same assembly readiness and the score use, so a night spent wearing
        // the other device is not missing from the one place that is asked about it.
        val nights = nightsByDay(own, elsewhere, zone)
        val sleepByDay = nights.mapValues { (_, night) -> night.asleepSeconds / 60f }
        val restfulByDay = nights
            .filterValues { it.staged && it.asleepSeconds > 0 }
            .mapValues { (_, night) -> night.restfulSeconds * 100f / night.asleepSeconds }

        // Steps, distance, calories and climbs are all counters the watch resets at
        // midnight, so each day's figure is the highest reading of it and never the sum of
        // the readings.
        val byDay = activity.groupBy { Instant.ofEpochSecond(it.timestamp).atZone(zone).toLocalDate() }
        val stepsByDay = byDay.mapValues { (_, day) -> day.maxOf { it.steps } }
        val distanceByDay = byDay.mapValues { (_, day) -> day.maxOf { it.distanceMeters } }
        val caloriesByDay = byDay.mapValues { (_, day) -> day.maxOf { it.calories } }
        val climbsByDay = byDay.mapValues { (_, day) -> day.maxOf { it.climbs } }

        // Averaged, unlike the counters: blood oxygen is a reading rather than a running
        // total, and the day's average is what the seven-day strip already shows.
        val oxygenByDay = mean(spo2, { it.timestamp }, { it.percent.toFloat() })

        // Every beat the watch reported, workouts included, because that is what the tile
        // and the week strip above it already average. A column that quietly excluded
        // exercise would disagree with the screen it is meant to explain.
        val pulseByDay = mean(pulse, { it.timestamp }, { it.bpm.toFloat() })

        // Variability days count too, even though nothing else may have happened on them:
        // it is the one column here the watch did not produce, and a day it is the only
        // reading for is still a day worth showing.
        val dates = (
            restingByDay.keys + stressByDay.keys + sleepByDay.keys + stepsByDay.keys +
                oxygenByDay.keys + pulseByDay.keys + hrv.keys
            ).sorted()

        dates.map { date ->
            AiContext.Day(
                date = date.toString(),
                restingHeartRate = restingByDay[date]?.roundToInt(),
                sleepMinutes = sleepByDay[date]?.roundToInt(),
                restfulPercent = restfulByDay[date]?.roundToInt(),
                stress = stressByDay[date]?.roundToInt(),
                steps = stepsByDay[date],
                heartRateVariability = hrv[date]?.roundToInt(),
                bloodOxygen = oxygenByDay[date]?.roundToInt(),
                calories = caloriesByDay[date],
                distanceMeters = distanceByDay[date],
                climbs = climbsByDay[date],
                heartRate = pulseByDay[date]?.roundToInt(),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    /**
     * The figures reCMF worked out itself, exactly as the assistant would be told them.
     *
     * Assembled here, in one place, for the same reason [aiDays] is: the preview on the
     * settings screen and the request itself read this one flow, so a preview cannot show
     * one thing and a request send another.
     *
     * It exists at all because it did not, and the gap was visible on the screen. The
     * coach offers "why is my readiness like that" as a suggestion above the box, and the
     * answer came back — reasonably — that there is no readiness in the data and this
     * watch does not compute one. It is computed, by this app, two tabs away.
     */
    val aiWorked: StateFlow<AiContext.Worked> = combine(
        readiness,
        sleepScore,
        settingsStore.settings,
        settingsStore.watchPreferences,
        // Paired rather than passed separately: combine takes five flows and this needs
        // six.
        combine(settingsStore.ai, workouts, ::Pair),
    ) { scored, night, settings, watch, (aiSettings, sessions) ->
        val zone = ZoneId.systemDefault()
        AiContext.Worked(
            today = LocalDate.now().toString(),
            readiness = scored,
            sleep = night,
            sleepTargetMinutes = settings.sleepTargetMinutes,
            stepsGoal = watch.stepsGoal,
            // The most recent handful rather than the ninety days the tab holds. A
            // pattern is visible in ten sessions, and the rest is a month of rows bought
            // on every question somebody asks.
            workouts = sessions.take(WORKOUTS_SENT).map {
                AiContext.Session(
                    date = Instant.ofEpochSecond(it.startSeconds).atZone(zone)
                        .toLocalDate()
                        .toString(),
                    minutes = (it.seconds / 60).toInt(),
                    averageBpm = it.averageBpm,
                    maxBpm = it.maxBpm,
                )
            },
            // Only where the profile can carry it, and by the same call the wizard makes,
            // so the two screens quote one figure rather than two.
            restingEnergy = restingEnergy(
                sex = aiSettings.profile.sex,
                age = aiSettings.profile.birthYear
                    .takeIf { it in 1900..Year.now().value }
                    ?.let { Year.now().value - it }
                    ?: 0,
                heightCm = aiSettings.profile.heightCm,
                weightKg = aiSettings.profile.weightKg,
            ),
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        AiContext.Worked(),
    )

    /** What the assistant is allowed to do, and where it is pointed. */
    val ai: StateFlow<AiSettings> = settingsStore.ai
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), AiSettings())

    /**
     * Records "no thank you" to the assistant as a whole, or takes it back.
     *
     * Declining also clears the key, which the switches alone never do. Somebody who has
     * decided they want no language model near this should not have to trust that a key
     * sitting in the app is unused — and a key is a thing they can paste again in ten
     * seconds if they change their mind, which the wizard's own wording says.
     */
    fun setAiDeclined(declined: Boolean) {
        viewModelScope.launch {
            settingsStore.setAiDeclined(declined)
            if (declined) settingsStore.setAiKey(null)
        }
    }

    fun setAiInsightsEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setAiInsightsEnabled(enabled) }
    }

    fun setAiCoachEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setAiCoachEnabled(enabled) }
    }

    fun setAiEndpoint(baseUrl: String, model: String, wire: AiEndpoint.Wire) {
        viewModelScope.launch { settingsStore.setAiEndpoint(baseUrl, model, wire) }
    }

    private val _aiModels = MutableStateFlow<AiClient.Models?>(null)

    /** What the provider said it will answer for, or why it would not say. */
    val aiModels: StateFlow<AiClient.Models?> = _aiModels.asStateFlow()

    /**
     * Asks the provider what models it has.
     *
     * Offered rather than required: not every provider serves a list, and one that does not
     * is answered with a sentence saying so rather than an error. Typing the name stays
     * possible either way, which is the only thing that works everywhere.
     */
    fun fetchAiModels(baseUrl: String) {
        viewModelScope.launch {
            _aiModels.value = AiClient.Models.Asking
            _aiModels.value = aiClient.models(baseUrl, settingsStore.ai.first().key)
        }
    }

    fun setAiKey(key: String?) {
        viewModelScope.launch { settingsStore.setAiKey(key) }
    }

    fun setAiProfile(profile: AiContext.Profile) {
        viewModelScope.launch { settingsStore.setAiProfile(profile) }
    }

    fun setAiWebSearch(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setAiWebSearch(enabled) }
    }

    fun setAiSystemPrompt(prompt: String) {
        viewModelScope.launch { settingsStore.setAiSystemPrompt(prompt) }
    }

    private val aiClient = AiClient()

    private val _aiProbe = MutableStateFlow<AiClient.Answer?>(null)

    /** How the last "try it" went, or null until one is asked for. */
    val aiProbe: StateFlow<AiClient.Answer?> = _aiProbe.asStateFlow()

    /**
     * Sends one short question, to find out whether the key and the endpoint work.
     *
     * Deliberately not a health question: this is about whether the connection is right,
     * and somebody testing their settings should not have to spend their own data to do it.
     */
    fun probeAi() {
        viewModelScope.launch {
            _aiProbe.value = null
            val settings = settingsStore.ai.first()
            _aiProbe.value = aiClient.ask(
                settings = settings,
                system = AiContext.instructions("Answer in exactly three words.", readerLanguage()),
                user = "Say hello.",
            )
        }
    }

    /**
     * What the assistant has said about each metric, straight from the cache.
     *
     * Keyed by the metric's own string so the sheet can look one up without knowing
     * anything about how it was stored.
     */
    val aiInsights: StateFlow<Map<String, AiInsight>> = dao.insights()
        .map { rows ->
            rows.associate { row ->
                row.metric to AiInsight(
                    text = row.text,
                    sources = row.sources.lines().filter { it.isNotBlank() },
                    atSeconds = row.atSeconds,
                    basis = row.basis,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyMap())

    private val _aiAsking = MutableStateFlow<Set<String>>(emptySet())

    /** Which metrics have a question in flight, so the sheet can show it thinking. */
    val aiAsking: StateFlow<Set<String>> = _aiAsking.asStateFlow()

    /**
     * Asks about a metric, unless there is already a good answer or a question in flight.
     *
     * The cache is what keeps this from being expensive. An answer stays good until either
     * enough time passes or the data behind it moves — a figure that has not changed does
     * not need explaining again however old the explanation is, and one that has changed
     * needs it again however new.
     *
     * @param force ignores both of those, for somebody who simply wants another answer.
     */
    fun askAboutMetric(
        metric: String,
        todayValue: String,
        column: String,
        force: Boolean = false,
    ) {
        viewModelScope.launch {
            if (metric in _aiAsking.value) return@launch

            val settings = settingsStore.ai.first()
            if (!settings.insightsEnabled || !settings.usable) return@launch

            val days = aiDays.first()
            val now = Instant.now().epochSecond

            // What the answer would be about: the figure on the card, and the last day the
            // table can see. An answer is stale when that changes and not before.
            val basis = "$todayValue|${days.lastOrNull()?.date.orEmpty()}"

            val had = aiInsights.value[metric]

            // Two conditions, and both have to give way. Nothing has changed means nothing
            // to say differently — which is the whole of it for a night, since last night
            // does not move at lunchtime. And a figure that does move all day, like a step
            // count, would otherwise buy a paid answer on every glance, so it still waits
            // out the interval.
            val settled = had != null &&
                (had.basis == basis || now - had.atSeconds < INSIGHT_GOOD_FOR_SECONDS)
            if (!force && settled) return@launch

            _aiAsking.value = _aiAsking.value + metric
            try {
                val answer = aiClient.ask(
                    settings = settings,
                    system = AiContext.instructions(settings.systemPrompt, readerLanguage()),
                    user = AiContext.user(
                        question = AiContext.aboutMetric(metric, todayValue, column),
                        days = days,
                        // The other opt-in sends numbers with nobody's name against them,
                        // and this is the line that keeps that true.
                        about = if (settings.coachEnabled) settings.aboutMe() else "",
                        worked = aiWorked.first(),
                    ),
                )

                if (answer is AiClient.Answer.Said) {
                    dao.insertInsight(
                        AiInsightEntity(
                            metric = metric,
                            text = answer.text,
                            sources = answer.sources.joinToString("\n"),
                            atSeconds = now,
                            basis = basis,
                        ),
                    )
                }
            } finally {
                _aiAsking.value = _aiAsking.value - metric
            }
        }
    }

    /**
     * The conversation with the coach, oldest first.
     *
     * Straight off the table rather than held in memory: it is the conversation, not a
     * cache of one, and the only copy there is.
     */
    val coach: StateFlow<List<CoachMessageEntity>> = dao.coachMessages()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    private val _coachThinking = MutableStateFlow(false)
    val coachThinking: StateFlow<Boolean> = _coachThinking.asStateFlow()

    /** What went wrong with the last thing sent, until something else is sent. */
    private val _coachProblem = MutableStateFlow<String?>(null)
    val coachProblem: StateFlow<String?> = _coachProblem.asStateFlow()

    /**
     * Says something to the coach and keeps both halves.
     *
     * The wearer's message is written down before the request goes out, so it is on the
     * screen while the answer is being waited for and survives the app being closed
     * mid-wait. A failure leaves it there too: what somebody typed is theirs, and deleting
     * it because a server was unreachable would be the app throwing away their words to
     * tidy up after itself.
     *
     * The figures ride in the standing instructions rather than in a turn — they are
     * context, not something either side said — which also means every question is
     * answered against today's numbers rather than against whatever they were when the
     * conversation started.
     */
    fun sendToCoach(text: String) {
        val said = text.trim()
        if (said.isEmpty()) return

        viewModelScope.launch {
            if (_coachThinking.value) return@launch

            val settings = settingsStore.ai.first()
            if (!settings.coachEnabled || !settings.usable) return@launch

            _coachProblem.value = null
            dao.insertCoachMessage(
                CoachMessageEntity(
                    fromUser = true,
                    text = said,
                    atSeconds = Instant.now().epochSecond,
                ),
            )

            _coachThinking.value = true
            try {
                // Read back rather than appended to a list held here: the message just
                // written is in it, and so is anything restored from a backup.
                val whole = dao.allCoachMessages().map { AiChat.Turn(it.fromUser, it.text) }

                // Every request resends the conversation, because the far side keeps
                // nothing. Unbounded, that is a bill that grows with every question and a
                // request that eventually stops fitting at all — and the only cure left to
                // somebody then is to clear the conversation and lose it.
                val sent = AiContext.lastWithin(whole, CONVERSATION_BUDGET) { it.text.length }

                val answer = aiClient.converse(
                    settings = settings,
                    system = AiContext.coaching(
                        prompt = settings.systemPrompt,
                        language = readerLanguage(),
                        days = aiDays.first(),
                        about = settings.aboutMe(),
                        worked = aiWorked.first(),
                        trimmed = sent.size < whole.size,
                    ),
                    turns = sent,
                )

                when (answer) {
                    is AiClient.Answer.Said -> dao.insertCoachMessage(
                        CoachMessageEntity(
                            fromUser = false,
                            text = answer.text,
                            atSeconds = Instant.now().epochSecond,
                        ),
                    )

                    else -> _coachProblem.value = answer.wording()
                }
            } finally {
                _coachThinking.value = false
            }
        }
    }

    /** Forgets the conversation. Nothing is kept anywhere else, so this is the whole of it. */
    fun clearCoach() {
        viewModelScope.launch {
            dao.clearCoachMessages()
            _coachProblem.value = null
        }
    }

    /**
     * A failure in words, for the line under the conversation.
     *
     * Deliberately short and deliberately not the provider's own message verbatim in every
     * case: a refusal can quote back what was sent, and what was sent is somebody's health
     * data.
     */
    private fun AiClient.Answer.wording(): String = when (this) {
        is AiClient.Answer.Refused -> reason?.takeIf { it.isNotBlank() }?.let { "$code: $it" }
            ?: code.toString()

        is AiClient.Answer.Unreachable -> why
        AiClient.Answer.Unreadable -> "empty answer"
        AiClient.Answer.NotConfigured -> "not configured"
        is AiClient.Answer.Said -> ""
    }

    /**
     * The language the phone is being read in, named in English for the instruction.
     *
     * Taken from the phone rather than stored as a setting: somebody who switched Android
     * to a language has already said which one they read, and asking again would be a
     * second place for the same answer to be wrong.
     */
    /** The profile as the request will carry it, or nothing when there is none. */
    private fun AiSettings.aboutMe(): String =
        AiContext.about(profile, Year.now().value)

    private fun readerLanguage(): String =
        Locale.getDefault().getDisplayLanguage(Locale.ENGLISH)

    private val _held = MutableStateFlow<List<HealthConnectSync.Held>?>(null)

    /** What a look inside Health Connect found, or null until one is asked for. */
    val held: StateFlow<List<HealthConnectSync.Held>?> = _held.asStateFlow()

    /**
     * Reads what Health Connect is holding and from whom.
     *
     * On demand rather than on every launch: it is a diagnostic somebody presses when they
     * want to know whether a second device is feeding the phone, not a thing worth doing
     * behind their back on a schedule.
     */
    fun surveyHealthConnect() {
        viewModelScope.launch { _held.value = healthConnect.survey() }
    }

    /** Drops every answer the assistant has given, for somebody who wants them gone. */
    fun forgetAiInsights() {
        viewModelScope.launch { dao.clearInsights() }
    }

    private val backupStore = BackupStore(application, dao)

    private val _backup = MutableStateFlow<BackupState?>(null)

    /** How the last export or import went, or null when neither has been asked for. */
    val backup: StateFlow<BackupState?> = _backup.asStateFlow()

    /**
     * Writes everything worth carrying to a file the wearer picked.
     *
     * Done here rather than in the service: an export reads the database and the settings
     * and touches the watch not at all, so there is nothing for a connection to be in the
     * middle of.
     */
    fun exportBackup(uri: String) {
        viewModelScope.launch {
            _backup.value = BackupState.Working
            _backup.value = withContext(Dispatchers.IO) {
                runCatching {
                    val contents = backupStore.collect(
                        versionCode = BuildConfig.VERSION_CODE,
                        nowSeconds = Instant.now().epochSecond,
                    )
                    val text = Backup.write(contents)
                    getApplication<Application>().contentResolver
                        .openOutputStream(uri.toUri(), "wt")
                        ?.use { it.write(text.toByteArray()) }
                        ?: error("could not open the file for writing")
                    BackupState.Exported(contents.settings.size, contents.tables.values.sumOf { it.rows.size })
                }.getOrElse { BackupState.Failed(it.message ?: it.javaClass.simpleName) }
            }
        }
    }

    /** Reads a file back over what is here, merging rather than replacing. */
    fun importBackup(uri: String) {
        viewModelScope.launch {
            _backup.value = BackupState.Working
            _backup.value = withContext(Dispatchers.IO) {
                runCatching {
                    val text = getApplication<Application>().contentResolver
                        .openInputStream(uri.toUri())
                        ?.use { it.readBytes().decodeToString() }
                        ?: error("could not open the file")

                    val contents = Backup.read(text)
                        ?: return@runCatching BackupState.NotOurs

                    val put = backupStore.restore(contents)
                    BackupState.Imported(put.settings, put.rows)
                }.getOrElse { BackupState.Failed(it.message ?: it.javaClass.simpleName) }
            }
        }
    }

    /** The last night the watch reported, or null until it reports one. */
    val lastSleep: StateFlow<SleepSummary?> = settingsStore.lastSleep
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    /**
     * The same night with its stages, read back out of the bytes that were kept.
     *
     * The stages were never stored as stages — only the frame was, on the reasoning that
     * a parse can be wrong and bytes cannot. That turns out to be all the sleep screen
     * needs: it re-reads the frame it was given. A frame that no longer parses gives
     * nothing rather than crashing the screen it is drawn on.
     */
    val lastSleepSession: StateFlow<SleepSession?> = settingsStore.lastSleep
        .map { summary ->
            summary?.raw
                ?.takeIf { it.isNotEmpty() }
                ?.let { runCatching { CmfParsers.parseSleep(it.hexToBytes()) }.getOrNull() }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    private val updater = Updater(application)

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    fun checkForUpdate() {
        if (_updateState.value is UpdateState.Checking) return

        viewModelScope.launch { check() }
    }

    /**
     * Looks for a newer build on opening the app, but not on every opening.
     *
     * The daily background check is what catches a build while the app is closed; this is
     * for the other half of the same wish — opening reCMF and seeing that something is
     * waiting, without pressing anything. Throttled, because an app that is opened eight
     * times an hour should not ask GitHub eight times an hour, and because the answer does
     * not change that fast.
     *
     * Silent about failure by design. Nobody opened the app to be told the network is
     * down; the button is there for anyone who wants an answer now.
     */
    private fun checkForUpdateOnOpen() {
        viewModelScope.launch {
            val since = Instant.now().epochSecond - settingsStore.lastUpdateCheckSeconds.first()
            if (since < CHECK_ON_OPEN_INTERVAL_SECONDS) return@launch

            val state = check()
            if (state is UpdateState.Failed) _updateState.value = UpdateState.Idle
        }
    }

    private suspend fun check(): UpdateState {
        _updateState.value = UpdateState.Checking

        val state = updater.check(BuildConfig.VERSION_CODE)
        _updateState.value = state

        // Recorded whatever the answer was, including a failure: a phone with no signal
        // would otherwise retry on every single opening.
        settingsStore.setLastUpdateCheck(Instant.now().epochSecond)

        return state
    }

    fun installUpdate(update: AvailableUpdate) {
        viewModelScope.launch {
            _updateState.value = UpdateState.Downloading(null)
            _updateState.value = updater.install(update) { percent ->
                _updateState.value = UpdateState.Downloading(percent)
            }
        }
    }

    fun setPhoneAlarmsEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setPhoneAlarmsEnabled(enabled) }
    }

    fun setHealthConnectEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setHealthConnectEnabled(enabled) }
    }

    /**
     * Everything that has to happen once, when the screen is first built.
     *
     * Last in the class on purpose, and it has to stay last. Property initialisers and
     * init blocks run in declaration order, so an init block placed at the top — where it
     * reads best — runs while every property below it is still null. Nothing warns about
     * it: the calls here are ordinary methods, and the property they touch is only read
     * inside them. The variability read did exactly that and crashed the app on
     * opening, every time, because the flow it writes to is declared six hundred lines
     * further down. `checkForUpdateOnOpen` had the same fault and survived it only by
     * suspending on a disk read before it reached its own state.
     *
     * So: new work goes at the end of this block, and this block stays at the end of the
     * class.
     */
    init {
        watchOtherDevices()

        // A watch that has been used with the stock app is already paired at the OS
        // level, and may not advertise at all while it is connected to something else.
        // Offer it before the user asks for a scan.
        _discovered.value = scanner.bonded()

        checkForUpdateOnOpen()
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L

        /** Often enough to be useful on opening, rare enough not to be a poll. */
        const val CHECK_ON_OPEN_INTERVAL_SECONDS = 6L * 60 * 60

        const val HOURS_IN_DAY = 24

        /** A week, which is also everything the staging table keeps. */
        const val DAYS_IN_STRIP = 7

        /**
         * How many past days a signal needs before it counts, and before another device
         * may take a signal over.
         *
         * Named here rather than left to the scoring's own default because two places now
         * depend on the same number: a signal with less than this behind it is left out,
         * and a second device with less than this behind it does not get to replace the
         * watch. Those have to agree, or a source could be adopted and then dropped in the
         * same breath.
         */
        const val LEAST_DAYS = 4

        /**
         * How much of a conversation is resent with each question, in characters.
         *
         * Roughly three thousand tokens of talk, which is a long exchange and still small
         * beside the standing instructions it travels with. What falls off the front stays
         * on the screen and in the backup; it is only no longer paid for on every turn.
         */
        const val CONVERSATION_BUDGET = 12_000

        /** How many sessions travel with a question. Enough for a pattern, not a month. */
        const val WORKOUTS_SENT = 10

        /** How many days back readiness reads. The nights table keeps thirty. */
        const val BASELINE_DAYS = 30L

        /**
         * The soonest a changed figure may buy another answer.
         *
         * A floor rather than an expiry: an answer goes stale when what it was about
         * changes, and this only stops the figures that change constantly from turning
         * every glance into a request. A step count moves every minute; six hours is long
         * enough that opening the tile through the day costs one answer rather than ten,
         * and short enough that the morning's reading is not still being explained in the
         * evening.
         *
         * Nothing expires on this alone. A night that has not changed is never re-asked,
         * however long ago it was explained.
         */
        const val INSIGHT_GOOD_FOR_SECONDS = 6L * 60 * 60

        /**
         * How far back the workouts screen looks.
         *
         * Longer than the week the rest of the table keeps, because workout pulse is not
         * pruned: it is the only record of a session there is.
         */
        const val WORKOUT_HISTORY_SECONDS = 90L * 24 * 60 * 60

        fun startOfToday(): Long =
            LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toEpochSecond()

        /** Midnight at the start of the oldest day the seven-day strips show. */
        fun startOfWeek(): Long = LocalDate.now()
            .minusDays(DAYS_IN_STRIP - 1L)
            .atStartOfDay(ZoneId.systemDefault())
            .toEpochSecond()

        /**
         * As far back as readiness looks.
         *
         * Longer than the strips, because a baseline of six days plus today is thin and
         * the nights table is kept for a month anyway. The sample tables hold a week, so
         * in practice the pulse and stress baselines are a week and sleep's is a month —
         * which is fine: each signal is judged against its own history, not a shared one.
         */
        fun startOfBaseline(): Long = LocalDate.now()
            .minusDays(BASELINE_DAYS)
            .atStartOfDay(ZoneId.systemDefault())
            .toEpochSecond()
    }
}
