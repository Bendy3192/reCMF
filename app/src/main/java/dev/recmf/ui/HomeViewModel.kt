/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.recmf.ble.ConnectionState
import dev.recmf.ble.DiscoveredWatch
import dev.recmf.ble.ProtocolLog
import dev.recmf.ble.WatchScanner
import dev.recmf.data.HeartRateSampleEntity
import dev.recmf.data.RecmfDatabase
import dev.recmf.data.SettingsStore
import dev.recmf.data.WatchSettings
import dev.recmf.health.HealthConnectAvailability
import androidx.core.app.NotificationManagerCompat
import dev.recmf.health.HealthConnectSync
import dev.recmf.protocol.BatteryStatus
import dev.recmf.service.WatchService
import dev.recmf.service.WatchStatus
import dev.recmf.service.WatchdogWorker
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
    val settings: WatchSettings = WatchSettings(null, null, false, 0),
    val watch: WatchInfo = WatchInfo(),
    val stepsToday: Int = 0,
    val latestHeartRate: HeartRateSampleEntity? = null,
)

/** What the watch itself has told us this session. */
data class WatchInfo(
    val battery: BatteryStatus? = null,
    val firmware: String? = null,
    val serialNumber: String? = null,
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = SettingsStore(application)
    private val dao = RecmfDatabase.get(application).sampleDao()
    private val scanner = WatchScanner(application)
    private val healthConnect = HealthConnectSync(application)

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
        WatchStatus.battery,
        WatchStatus.firmware,
        WatchStatus.serialNumber,
    ) { battery, firmware, serial -> WatchInfo(battery, firmware, serial) }

    val uiState: StateFlow<HomeUiState> = combine(
        WatchStatus.state,
        settingsStore.settings,
        watchInfo,
        // Bound at construction, so a screen left open across midnight keeps counting
        // into yesterday until it is recreated.
        dao.stepsSince(startOfToday()),
        dao.latestHeartRate(),
    ) { connection, settings, watch, steps, heartRate ->
        HomeUiState(connection, settings, watch, steps, heartRate)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), HomeUiState())

    /** The recent protocol exchange, for the in-app log. */
    val protocolLog: StateFlow<List<ProtocolLog.Entry>> = ProtocolLog.entries

    fun clearLog() = ProtocolLog.clear()

    val healthConnectAvailability: HealthConnectAvailability get() = healthConnect.availability()

    /**
     * Whether the user has granted notification access. It is granted in system settings
     * rather than by a permission dialog, so there is nothing to request — only to check
     * and to link to.
     */
    fun hasNotificationAccess(): Boolean {
        val context = getApplication<Application>()
        return context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setNotificationsEnabled(enabled) }
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

    fun setHealthConnectEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setHealthConnectEnabled(enabled) }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L

        fun startOfToday(): Long =
            LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
    }
}
