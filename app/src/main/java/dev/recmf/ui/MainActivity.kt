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
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.recmf.health.HealthConnectSync
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestBluetooth.launch(bluetoothPermissions)

        setContent {
            ReCmfTheme {
                val model: HomeViewModel = viewModel()
                val state by model.uiState.collectAsStateWithLifecycle()
                val discovered by model.discovered.collectAsStateWithLifecycle()
                val scanError by model.scanError.collectAsStateWithLifecycle()
                val protocolLog by model.protocolLog.collectAsStateWithLifecycle()
                val watchPreferences by model.watchPreferences.collectAsStateWithLifecycle()
                val cityLookup by model.cityLookup.collectAsStateWithLifecycle()

                // Re-read on every composition rather than caching: the user grants this
                // in system settings and comes straight back to this screen.
                val hasNotificationAccess = model.hasNotificationAccess()
                val isBatteryExempt = model.isExemptFromBatteryOptimisation()

                HomeScreen(
                    state = state,
                    discovered = discovered,
                    scanError = scanError,
                    healthConnectAvailability = model.healthConnectAvailability,
                    protocolLog = protocolLog,
                    watchPreferences = watchPreferences,
                    onWatchPreferences = model::updateWatchPreferences,
                    hasNotificationAccess = hasNotificationAccess,
                    isBatteryExempt = isBatteryExempt,
                    onClearLog = model::clearLog,
                    onNotificationsEnabled = model::setNotificationsEnabled,
                    onScreenOffOnlyEnabled = model::setNotifyOnlyWhenScreenOff,
                    cityLookup = cityLookup,
                    onWeatherEnabled = model::setWeatherEnabled,
                    onFindCity = model::findCity,
                    onGrantNotificationAccess = {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                    onAllowBackgroundWork = {
                        // The targeted dialog rather than the whole-app list: the
                        // permission is declared, and making the user find reCMF among
                        // every installed app is how a setting that matters goes unset.
                        startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                "package:$packageName".toUri(),
                            ),
                        )
                    },
                    onScan = model::startScan,
                    onPair = model::pair,
                    onForget = model::forget,
                    onSyncNow = model::syncNow,
                    onAutoSyncSeconds = model::setAutoSyncSeconds,
                    onHealthConnectEnabled = { enabled ->
                        model.setHealthConnectEnabled(enabled)
                        if (enabled) requestHealthConnect.launch(HealthConnectSync.REQUIRED_PERMISSIONS)
                    },
                )
            }
        }
    }
}
