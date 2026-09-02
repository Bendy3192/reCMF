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
import dev.recmf.ai.AiContext
import dev.recmf.ai.AiEndpoint
import dev.recmf.data.AiSettings
import dev.recmf.data.Backup
import dev.recmf.data.BackupStore
import dev.recmf.health.Readiness
import dev.recmf.health.ReadinessSignal
import dev.recmf.health.readiness as scoreReadiness
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
import java.time.LocalDate
import java.time.ZoneId

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

    init {
        // A watch that has been used with the stock app is already paired at the OS
        // level, and may not advertise at all while it is connected to something else.
        // Offer it before the user asks for a scan.
        _discovered.value = scanner.bonded()

        checkForUpdateOnOpen()
    }

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
    val readiness: StateFlow<Readiness?> = combine(
        dao.restingHeartRateSince(startOfBaseline()),
        dao.stressSince(startOfBaseline()),
        dao.sleepSince(startOfBaseline()),
    ) { resting, stress, nights ->
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()

        fun <T> daily(rows: List<T>, at: (T) -> Long, value: (T) -> Float?): Map<LocalDate, Float> =
            rows.groupBy { Instant.ofEpochSecond(at(it)).atZone(zone).toLocalDate() }
                .mapValues { (_, day) -> day.mapNotNull(value) }
                .filterValues { it.isNotEmpty() }
                .mapValues { (_, values) -> values.average().toFloat() }

        val byDay = mapOf(
            ReadinessSignal.RESTING_HEART_RATE to
                daily(resting, { it.timestamp }, { it.bpm.toFloat() }),
            ReadinessSignal.STRESS to
                daily(stress, { it.timestamp }, { it.level.toFloat() }),
            ReadinessSignal.SLEEP_DURATION to
                daily(nights, { it.wakeTimestamp }, { it.asleepSeconds.toFloat() / 60f }),
            ReadinessSignal.SLEEP_QUALITY to
                daily(nights, { it.wakeTimestamp }, { it.restfulShare }),
        )

        // Aliased on import: the property being initialised here is also called readiness,
        // and a reader should not have to work out which one a bare call resolves to.
        scoreReadiness(
            today = byDay.mapNotNull { (signal, days) -> days[today]?.let { signal to it } }.toMap(),
            history = byDay.mapValues { (_, days) ->
                days.filterKeys { it < today }.toSortedMap().values.toList()
            },
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
        dao.activitySince(startOfBaseline()),
    ) { resting, stress, nights, activity ->
        val zone = ZoneId.systemDefault()

        fun <T> mean(rows: List<T>, at: (T) -> Long, value: (T) -> Float?): Map<LocalDate, Float> =
            rows.groupBy { Instant.ofEpochSecond(at(it)).atZone(zone).toLocalDate() }
                .mapValues { (_, day) -> day.mapNotNull(value) }
                .filterValues { it.isNotEmpty() }
                .mapValues { (_, values) -> values.average().toFloat() }

        val restingByDay = mean(resting, { it.timestamp }, { it.bpm.toFloat() })
        val stressByDay = mean(stress, { it.timestamp }, { it.level.toFloat() })
        val sleepByDay = mean(nights, { it.wakeTimestamp }, { it.asleepSeconds / 60f })
        val restfulByDay = mean(nights, { it.wakeTimestamp }, { it.restfulShare?.times(100f) })

        // Steps are a counter the watch resets at midnight, so the day's figure is the
        // highest reading of it and never the sum of the readings.
        val stepsByDay = activity
            .groupBy { Instant.ofEpochSecond(it.timestamp).atZone(zone).toLocalDate() }
            .mapValues { (_, day) -> day.maxOf { it.steps } }

        val dates = (restingByDay.keys + stressByDay.keys + sleepByDay.keys + stepsByDay.keys)
            .sorted()

        dates.map { date ->
            AiContext.Day(
                date = date.toString(),
                restingHeartRate = restingByDay[date]?.roundToInt(),
                sleepMinutes = sleepByDay[date]?.roundToInt(),
                restfulPercent = restfulByDay[date]?.roundToInt(),
                stress = stressByDay[date]?.roundToInt(),
                steps = stepsByDay[date],
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    /** What the assistant is allowed to do, and where it is pointed. */
    val ai: StateFlow<AiSettings> = settingsStore.ai
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), AiSettings())

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
                system = "Answer in exactly three words.",
                user = "Say hello.",
            )
        }
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

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L

        /** Often enough to be useful on opening, rare enough not to be a poll. */
        const val CHECK_ON_OPEN_INTERVAL_SECONDS = 6L * 60 * 60

        const val HOURS_IN_DAY = 24

        /** A week, which is also everything the staging table keeps. */
        const val DAYS_IN_STRIP = 7

        /** How many days back readiness reads. The nights table keeps thirty. */
        const val BASELINE_DAYS = 30L

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
