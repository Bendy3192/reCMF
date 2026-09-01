/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import android.annotation.SuppressLint
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.viewmodel.compose.viewModel
import android.content.Context
import dev.recmf.health.HealthConnectSync
import dev.recmf.service.WatchService
import dev.recmf.ui.theme.ReCmfTheme

class MainActivity : ComponentActivity() {

    /**
     * Bluetooth is useless without these, so they are requested up front rather than at
     * the moment of first use — a scan that silently returns nothing is far more
     * confusing than a permission dialog.
     */
    private val bluetoothPermissions = buildList {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)

        // Notifications only became a runtime permission in API 33; below that the
        // foreground service's notification appears without being asked for.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val requestBluetooth =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    private val requestHealthConnect =
        registerForActivityResult(PermissionControllerContract()) { }

    /**
     * Opens the exemption dialog for reCMF specifically.
     *
     * Lint objects that this permission is restricted on the Play Store to a list of
     * acceptable use cases. reCMF is not distributed there — it is a sideloaded companion
     * app for a watch, which is the shape of app the exemption exists for — so the
     * objection is noted rather than obeyed. Anyone taking this to Play should send the
     * user to the system list via ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS instead,
     * which asks for nothing but makes the user find reCMF among every installed app.
     */
    @SuppressLint("BatteryLife")
    private fun requestBackgroundExemption() {
        startActivity(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                "package:$packageName".toUri(),
            ),
        )
    }

    /**
     * Stops the find-phone ringing when this was opened from its notification.
     *
     * The notification cannot stop it directly — a notification that starts a service is
     * a trampoline, which Android 12 forbids — so the tap comes here and here asks the
     * service. Whoever tapped is holding the phone; the ringing has done its job.
     */
    private fun silenceIfAsked(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_SILENCE, false) != true) return

        // Cleared so returning to this activity later — from Recents, or a rotation —
        // does not re-run this on an intent that has already been acted on.
        intent.removeExtra(EXTRA_SILENCE)
        startService(WatchService.stopRingingIntent(this))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        silenceIfAsked(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        silenceIfAsked(intent)
        requestBluetooth.launch(bluetoothPermissions)

        setContent {
            ReCmfTheme {
                val model: HomeViewModel = viewModel()
                val state by model.uiState.collectAsStateWithLifecycle()
                val discovered by model.discovered.collectAsStateWithLifecycle()
                val scanError by model.scanError.collectAsStateWithLifecycle()
                val watchPreferences by model.watchPreferences.collectAsStateWithLifecycle()
                val cityLookup by model.cityLookup.collectAsStateWithLifecycle()
                val updateState by model.updateState.collectAsStateWithLifecycle()
                val notificationApps by model.notificationApps.collectAsStateWithLifecycle()
                val lastSleep by model.lastSleep.collectAsStateWithLifecycle()
                val charts by model.charts.collectAsStateWithLifecycle()
                val weekly by model.weekly.collectAsStateWithLifecycle()
                val sleepSession by model.lastSleepSession.collectAsStateWithLifecycle()
                val watchfaces by model.watchfaces.collectAsStateWithLifecycle()
                val watchfaceInstall by model.watchfaceInstall.collectAsStateWithLifecycle()

                // Which face the chosen file will displace, remembered across the trip out
                // to the system picker — the activity can be recreated while it is open.
                var replacing by rememberSaveable { mutableIntStateOf(-1) }

                // OpenDocument rather than GetContent: this reads one file once and wants
                // no storage permission for it, and the picker is the system's, so reCMF
                // never sees anything the person did not hand it.
                val chooseFace = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    if (uri != null && replacing >= 0) {
                        model.installWatchface(uri.toString(), replacing)
                    }
                }

                // Its own launcher rather than sharing the face one: the two hand their
                // result to different places, and a shared launcher would need a mode flag
                // that survives the trip out to the picker for no gain.
                val chooseGpsData = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
                ) { uri -> if (uri != null) model.installAgps(uri.toString()) }

                // Re-read on every composition rather than caching: the user grants this
                // in system settings and comes straight back to this screen.
                val hasNotificationAccess = model.hasNotificationAccess()
                val isBatteryExempt = model.isExemptFromBatteryOptimisation()

                HomeScreen(
                    state = state,
                    discovered = discovered,
                    scanError = scanError,
                    healthConnectAvailability = model.healthConnectAvailability,
                    watchPreferences = watchPreferences,
                    onWatchPreferences = model::updateWatchPreferences,
                    hasNotificationAccess = hasNotificationAccess,
                    notificationApps = notificationApps,
                    lastSleep = lastSleep,
                    sleepSession = sleepSession,
                    charts = charts,
                    weekly = weekly,
                    watchfaces = watchfaces,
                    watchfaceInstall = watchfaceInstall,
                    onNotificationAppBlocked = model::setNotificationBlocked,
                    onNotificationAppsBlocked = model::setNotificationBlocked,
                    isBatteryExempt = isBatteryExempt,
                    onNotificationsEnabled = model::setNotificationsEnabled,
                    onScreenOffOnlyEnabled = model::setNotifyOnlyWhenScreenOff,
                    cityLookup = cityLookup,
                    onWeatherEnabled = model::setWeatherEnabled,
                    onFindCity = model::findCity,
                    onGrantNotificationAccess = {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                    onAllowBackgroundWork = ::requestBackgroundExemption,
                    onScan = model::startScan,
                    onPair = model::pair,
                    onForget = model::forget,
                    onSyncNow = model::syncNow,
                    onFindWatch = model::findWatch,
                    onSelectWatchface = model::selectWatchface,
                    onGpsAlmanacAuto = model::setGpsAlmanacAuto,
                    onInstallAgps = {
                        // EPO files have no registered type either, so anything is offered
                        // and the file is checked before a byte goes to the watch.
                        chooseGpsData.launch(arrayOf("*/*"))
                    },
                    onInstallWatchface = { slot ->
                        replacing = slot
                        // Watchfaces have no registered type, so anything is offered and
                        // the file itself is checked before a byte goes to the watch.
                        chooseFace.launch(arrayOf("*/*"))
                    },
                    updateState = updateState,
                    onCheckForUpdate = model::checkForUpdate,
                    onInstallUpdate = model::installUpdate,
                    onAutoSyncSeconds = model::setAutoSyncSeconds,
                    onHealthConnectEnabled = { enabled ->
                        model.setHealthConnectEnabled(enabled)
                        if (enabled) requestHealthConnect.launch(HealthConnectSync.REQUIRED_PERMISSIONS)
                    },
                    onPhoneAlarmsEnabled = model::setPhoneAlarmsEnabled,
                )
            }
        }
    }

    companion object {
        private const val EXTRA_SILENCE = "dev.recmf.extra.SILENCE"

        /** What the find-phone notification opens when its body is tapped. */
        fun silenceIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_SILENCE, true)
                // Reuse the activity if it is already on top rather than stacking a
                // second copy of the app behind the first.
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
