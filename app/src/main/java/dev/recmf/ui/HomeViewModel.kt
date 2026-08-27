/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.ui

import android.app.Application
import android.content.Context
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.recmf.ble.ConnectionState
import dev.recmf.ble.DiscoveredWatch
import dev.recmf.ble.WatchScanner
import dev.recmf.data.HeartRateSampleEntity
import dev.recmf.data.RecmfDatabase
import dev.recmf.data.SettingsStore
import dev.recmf.data.WatchPreferences
import dev.recmf.data.WatchSetting
import dev.recmf.data.WatchSettings
import dev.recmf.health.HealthConnectAvailability
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

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

    private val updater = Updater(application)

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    fun checkForUpdate() {
        if (_updateState.value is UpdateState.Checking) return

        viewModelScope.launch {
            _updateState.value = UpdateState.Checking
            _updateState.value = updater.check(BuildConfig.VERSION_CODE)
        }
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

        fun startOfToday(): Long =
            LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
    }
}
