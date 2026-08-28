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
import dev.recmf.BuildConfig
import dev.recmf.service.WatchService
import dev.recmf.update.AvailableUpdate
import dev.recmf.update.UpdateState
import dev.recmf.update.Updater
import dev.recmf.service.WeatherProblem
import dev.recmf.protocol.HeartRateSample
import dev.recmf.protocol.Spo2Sample
import dev.recmf.protocol.StressSample
import dev.recmf.service.WatchStatus
import dev.recmf.service.WatchdogWorker
import dev.recmf.weather.WeatherClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.combine
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
    val stepsToday: Int = 0,
    val latestHeartRate: HeartRateSampleEntity? = null,
    val weather: WeatherStatus = WeatherStatus(),
    val spo2: Spo2Sample? = null,
    val stress: StressSample? = null,
    val restingHeartRate: HeartRateSample? = null,
)

/** How the search for a place is going. */
sealed interface CityLookup {
    data object Idle : CityLookup
    data object Searching : CityLookup
    data class Found(val name: String) : CityLookup
    data object NotFound : CityLookup
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
    val spo2: Spo2Sample?,
    val stress: StressSample?,
    val restingHeartRate: HeartRateSample?,
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
        dao.stepsSince(startOfToday()),
        combine(
            dao.latestHeartRate(),
            weatherStatus,
            WatchStatus.spo2,
            WatchStatus.stress,
            WatchStatus.restingHeartRate,
            ::Extras,
        ),
    ) { connection, settings, watch, steps, extras ->
        HomeUiState(
            connection = connection,
            settings = settings,
            watch = watch,
            stepsToday = steps,
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

    /** The last night the watch reported, or null until it reports one. */
    val lastSleep: StateFlow<SleepSummary?> = settingsStore.lastSleep
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

    fun setHealthConnectEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setHealthConnectEnabled(enabled) }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L

        /** Often enough to be useful on opening, rare enough not to be a poll. */
        const val CHECK_ON_OPEN_INTERVAL_SECONDS = 6L * 60 * 60

        const val HOURS_IN_DAY = 24

        fun startOfToday(): Long =
            LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
    }
}
