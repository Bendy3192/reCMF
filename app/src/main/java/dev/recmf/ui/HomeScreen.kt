/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.recmf.R
import dev.recmf.ble.ConnectionState
import dev.recmf.ble.DiscoveredWatch
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import dev.recmf.ble.ProtocolLog
import dev.recmf.data.WatchPreferences
import dev.recmf.protocol.CmfActivityType
import dev.recmf.protocol.CmfSettings
import dev.recmf.health.HealthConnectAvailability

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    discovered: List<DiscoveredWatch>,
    scanError: String?,
    healthConnectAvailability: HealthConnectAvailability,
    protocolLog: List<ProtocolLog.Entry>,
    watchPreferences: WatchPreferences,
    onWatchPreferences: ((WatchPreferences) -> WatchPreferences) -> Unit,
    hasNotificationAccess: Boolean,
    onClearLog: () -> Unit,
    onNotificationsEnabled: (Boolean) -> Unit,
    onScreenOffOnlyEnabled: (Boolean) -> Unit,
    onGrantNotificationAccess: () -> Unit,
    onScan: () -> Unit,
    onPair: (DiscoveredWatch) -> Unit,
    onForget: () -> Unit,
    onSyncNow: () -> Unit,
    onAutoSyncSeconds: (Int) -> Unit,
    onHealthConnectEnabled: (Boolean) -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
    ) { insets ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ConnectionCard(state, onSyncNow, onForget) }

            if (state.settings.isPaired) {
                item { TodayCard(state, onAutoSyncSeconds) }
                item { WatchSettingsCard(watchPreferences, state.connection.isUsable, onWatchPreferences) }
                item { HealthConnectCard(state, healthConnectAvailability, onHealthConnectEnabled) }
                item {
                    NotificationsCard(
                        state = state,
                        hasAccess = hasNotificationAccess,
                        onEnabled = onNotificationsEnabled,
                        onScreenOffOnly = onScreenOffOnlyEnabled,
                        onGrantAccess = onGrantNotificationAccess,
                    )
                }
            } else {
                item { PairingHeader(scanError, onScan) }
                items(discovered, key = { it.address }) { watch ->
                    ListItem(
                        headlineContent = { Text(watch.name ?: stringResource(R.string.unnamed_watch)) },
                        supportingContent = {
                            Text(
                                if (watch.isBonded) {
                                    stringResource(R.string.device_paired, watch.address)
                                } else {
                                    watch.address
                                },
                            )
                        },
                        trailingContent = {
                            FilledTonalButton(onClick = { onPair(watch) }) {
                                Text(stringResource(R.string.action_pair))
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                    )
                }
            }

            item { ProtocolLogCard(protocolLog, onClearLog) }

            item { Spacer(Modifier.padding(8.dp)) }
        }
    }
}

@Composable
private fun ConnectionCard(
    state: HomeUiState,
    onSyncNow: () -> Unit,
    onForget: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = state.settings.name ?: stringResource(R.string.no_watch_paired),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(state.connection.labelRes()),
                style = MaterialTheme.typography.bodyMedium,
            )

            state.watch.battery?.let { battery ->
                Text(
                    text = if (battery.isCharging) {
                        stringResource(R.string.battery_charging, battery.levelPercent)
                    } else {
                        stringResource(R.string.battery_level, battery.levelPercent)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            state.watch.firmware?.let { firmware ->
                Text(
                    text = stringResource(R.string.firmware_version, firmware),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // Only shown while something is genuinely in flight; a permanently
            // animating bar teaches people to ignore it.
            if (state.connection.isSettling()) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (state.settings.isPaired) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = onSyncNow, enabled = state.connection.isUsable) {
                        Text(stringResource(R.string.action_sync_now))
                    }
                    OutlinedButton(onClick = onForget) {
                        Text(stringResource(R.string.action_forget))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TodayCard(state: HomeUiState, onAutoSyncSeconds: (Int) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.today), style = MaterialTheme.typography.titleMedium)

            Row(verticalAlignment = Alignment.Bottom) {
                Metric(
                    value = state.stepsToday.toString(),
                    label = stringResource(R.string.metric_steps),
                )
                Spacer(Modifier.width(32.dp))
                Metric(
                    value = state.latestHeartRate?.bpm?.toString() ?: "—",
                    label = stringResource(R.string.metric_heart_rate),
                )
            }

            HorizontalDivider()

            Text(stringResource(R.string.auto_sync), style = MaterialTheme.typography.labelLarge)

            // Each poll is radio time on both sides, so how often is the user's call —
            // including not at all.
            // FlowRow, not Row: five chips do not fit a narrow screen, and a Row squeezes
            // the last one into a one-character-wide column rather than wrapping it.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AUTO_SYNC_CHOICES.forEach { (seconds, labelRes) ->
                    FilterChip(
                        selected = state.settings.autoSyncSeconds == seconds,
                        onClick = { onAutoSyncSeconds(seconds) },
                        label = { Text(stringResource(labelRes)) },
                    )
                }
            }
        }
    }
}

private val AUTO_SYNC_CHOICES = listOf(
    0 to R.string.auto_sync_off,
    30 to R.string.auto_sync_30s,
    60 to R.string.auto_sync_1m,
    300 to R.string.auto_sync_5m,
    900 to R.string.auto_sync_15m,
)

@Composable
private fun Metric(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.displaySmall)
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun HealthConnectCard(
    state: HomeUiState,
    availability: HealthConnectAvailability,
    onEnabled: (Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.health_connect),
                    style = MaterialTheme.typography.titleMedium,
                )
                Switch(
                    checked = state.settings.healthConnectEnabled,
                    onCheckedChange = onEnabled,
                    enabled = availability == HealthConnectAvailability.AVAILABLE,
                )
            }

            HorizontalDivider()

            Text(
                text = when (availability) {
                    HealthConnectAvailability.AVAILABLE ->
                        stringResource(R.string.health_connect_available)

                    HealthConnectAvailability.UPDATE_REQUIRED ->
                        stringResource(R.string.health_connect_update_required)

                    HealthConnectAvailability.NOT_INSTALLED ->
                        stringResource(R.string.health_connect_not_installed)
                },
                style = MaterialTheme.typography.bodyMedium,
            )

            if (state.settings.lastSyncEpochSeconds > 0) {
                Text(
                    text = stringResource(
                        R.string.last_sync,
                        java.text.DateFormat.getDateTimeInstance()
                            .format(java.util.Date(state.settings.lastSyncEpochSeconds * 1000)),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun PairingHeader(scanError: String?, onScan: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.pair_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.pair_explainer), style = MaterialTheme.typography.bodyMedium)

            scanError?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }

            Button(onClick = onScan) { Text(stringResource(R.string.action_scan)) }
        }
    }
}

private fun ConnectionState.labelRes(): Int = when (this) {
    ConnectionState.IDLE -> R.string.status_idle
    ConnectionState.WAITING -> R.string.status_waiting
    ConnectionState.CONNECTING -> R.string.status_connecting
    ConnectionState.INITIALIZING -> R.string.status_initializing
    ConnectionState.AUTHENTICATING -> R.string.status_authenticating
    ConnectionState.READY -> R.string.status_ready
}

/** True while the link is actively working towards being usable. */
private fun ConnectionState.isSettling(): Boolean = when (this) {
    ConnectionState.CONNECTING,
    ConnectionState.INITIALIZING,
    ConnectionState.AUTHENTICATING,
    -> true

    ConnectionState.IDLE, ConnectionState.WAITING, ConnectionState.READY -> false
}

/**
 * The watch's own configuration.
 *
 * Every change is written to the watch immediately, and the whole set is written again on
 * each connection — so a setting made while the watch was away is not lost, it is applied
 * when it comes back.
 */
@Composable
private fun WatchSettingsCard(
    preferences: WatchPreferences,
    connected: Boolean,
    onChange: ((WatchPreferences) -> WatchPreferences) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.watch_settings), style = MaterialTheme.typography.titleMedium)

            if (!connected) {
                Text(
                    stringResource(R.string.watch_settings_offline),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.padding(4.dp))

            SettingSwitch(stringResource(R.string.setting_hr_monitoring), preferences.heartRateMonitoring) {
                onChange { current -> current.copy(heartRateMonitoring = it) }
            }
            SettingSwitch(stringResource(R.string.setting_spo2_monitoring), preferences.spo2Monitoring) {
                onChange { current -> current.copy(spo2Monitoring = it) }
            }
            SettingSwitch(stringResource(R.string.setting_stress_monitoring), preferences.stressMonitoring) {
                onChange { current -> current.copy(stressMonitoring = it) }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SettingSwitch(stringResource(R.string.setting_raise_to_wake), preferences.raiseToWake) {
                onChange { current -> current.copy(raiseToWake = it) }
            }
            SettingSwitch(stringResource(R.string.setting_24_hour), preferences.use24Hour) {
                onChange { current -> current.copy(use24Hour = it) }
            }
            SettingSwitch(stringResource(R.string.setting_metric), preferences.metric) {
                onChange { current -> current.copy(metric = it) }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text(stringResource(R.string.daily_goals), style = MaterialTheme.typography.titleSmall)

            GoalField(stringResource(R.string.goal_steps), preferences.stepsGoal) {
                onChange { current -> current.copy(stepsGoal = it) }
            }
            GoalField(stringResource(R.string.goal_distance), preferences.distanceGoalMeters) {
                onChange { current -> current.copy(distanceGoalMeters = it) }
            }
            GoalField(stringResource(R.string.goal_calories), preferences.caloriesGoal) {
                onChange { current -> current.copy(caloriesGoal = it) }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text(stringResource(R.string.reminders), style = MaterialTheme.typography.titleSmall)

            SettingSwitch(stringResource(R.string.reminder_stand), preferences.standReminder) {
                onChange { current -> current.copy(standReminder = it) }
            }
            if (preferences.standReminder) {
                GoalField(stringResource(R.string.reminder_interval), preferences.standIntervalMinutes) {
                    onChange { current -> current.copy(standIntervalMinutes = it) }
                }
                QuietHoursRow(
                    startMinutes = preferences.standQuietStartMinutes,
                    endMinutes = preferences.standQuietEndMinutes,
                ) { start, end ->
                    onChange { current ->
                        current.copy(standQuietStartMinutes = start, standQuietEndMinutes = end)
                    }
                }
            }

            SettingSwitch(stringResource(R.string.reminder_drink), preferences.drinkReminder) {
                onChange { current -> current.copy(drinkReminder = it) }
            }
            if (preferences.drinkReminder) {
                GoalField(stringResource(R.string.reminder_interval), preferences.drinkIntervalMinutes) {
                    onChange { current -> current.copy(drinkIntervalMinutes = it) }
                }
                QuietHoursRow(
                    startMinutes = preferences.drinkQuietStartMinutes,
                    endMinutes = preferences.drinkQuietEndMinutes,
                ) { start, end ->
                    onChange { current ->
                        current.copy(drinkQuietStartMinutes = start, drinkQuietEndMinutes = end)
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text(stringResource(R.string.alerts), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.alerts_explainer),
                style = MaterialTheme.typography.bodySmall,
            )

            GoalField(stringResource(R.string.alert_hr_low), preferences.heartRateAlertLow) {
                onChange { current -> current.copy(heartRateAlertLow = it) }
            }
            GoalField(stringResource(R.string.alert_hr_resting_high), preferences.heartRateAlertRestingHigh) {
                onChange { current -> current.copy(heartRateAlertRestingHigh = it) }
            }
            GoalField(stringResource(R.string.alert_hr_active_high), preferences.heartRateAlertActiveHigh) {
                onChange { current -> current.copy(heartRateAlertActiveHigh = it) }
            }
            GoalField(stringResource(R.string.alert_spo2_low), preferences.spo2AlertLow) {
                onChange { current -> current.copy(spo2AlertLow = it) }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SportTypesSection(preferences.sportTypes) { types ->
                onChange { current -> current.copy(sportTypes = types) }
            }
        }
    }
}

/** A quiet window during which the watch stays silent. Equal times mean no window. */
@Composable
private fun QuietHoursRow(startMinutes: Int, endMinutes: Int, onChange: (Int, Int) -> Unit) {
    var editing by remember { mutableStateOf<Boolean?>(null) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.quiet_hours), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { editing = true }) { Text(startMinutes.asClockTime()) }
        Text("–")
        TextButton(onClick = { editing = false }) { Text(endMinutes.asClockTime()) }
    }

    val isStart = editing ?: return
    val initial = if (isStart) startMinutes else endMinutes

    TimePickerDialog(
        initialMinutes = initial,
        onDismiss = { editing = null },
        onConfirm = { picked ->
            if (isStart) onChange(picked, endMinutes) else onChange(startMinutes, picked)
            editing = null
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(initialMinutes: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val state = rememberTimePickerState(
        initialHour = initialMinutes / 60,
        initialMinute = initialMinutes % 60,
        is24Hour = true,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
        text = { TimeInput(state = state) },
    )
}

private fun Int.asClockTime(): String = "%02d:%02d".format(this / 60, this % 60)

/**
 * Which exercises the watch offers in its own sport menu.
 *
 * Collapsed by default: there are over a hundred and almost nobody changes them, but the
 * ones that are on should be visible without unfolding anything.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SportTypesSection(selected: List<CmfActivityType>, onChange: (List<CmfActivityType>) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.sport_types, selected.size, CmfSettings.MAX_SPORT_TYPES),
            style = MaterialTheme.typography.titleSmall,
        )
        TextButton(onClick = { expanded = !expanded }) {
            Text(stringResource(if (expanded) R.string.action_hide else R.string.action_show))
        }
    }

    val shown = if (expanded) CmfActivityType.entries else selected

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        shown.forEach { type ->
            val isSelected = type in selected
            FilterChip(
                selected = isSelected,
                // The watch will not accept more than it has room for, so refuse the tap
                // rather than silently dropping the choice when the list is sent.
                enabled = isSelected || selected.size < CmfSettings.MAX_SPORT_TYPES,
                onClick = {
                    onChange(if (isSelected) selected - type else selected + type)
                },
                label = { Text(type.readableName()) },
            )
        }
    }
}

private fun CmfActivityType.readableName(): String =
    name.split("_").joinToString(" ") { word -> word.lowercase().replaceFirstChar { it.uppercase() } }

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun GoalField(label: String, value: Int, onValue: (Int) -> Unit) {
    // Edited as text so a half-typed number does not momentarily become a goal of 8 —
    // only a parsable value is committed.
    var text by remember(value) { mutableStateOf(value.toString()) }

    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            text = typed.filter(Char::isDigit).take(5)
            text.toIntOrNull()?.let(onValue)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NotificationsCard(
    state: HomeUiState,
    hasAccess: Boolean,
    onEnabled: (Boolean) -> Unit,
    onScreenOffOnly: (Boolean) -> Unit,
    onGrantAccess: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.notifications),
                    style = MaterialTheme.typography.titleMedium,
                )
                Switch(
                    checked = state.settings.notificationsEnabled && hasAccess,
                    onCheckedChange = onEnabled,
                    enabled = hasAccess,
                )
            }

            HorizontalDivider()

            if (hasAccess) {
                Text(
                    stringResource(R.string.notifications_explainer),
                    style = MaterialTheme.typography.bodyMedium,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.notifications_screen_off_only),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(
                        checked = state.settings.notifyOnlyWhenScreenOff,
                        onCheckedChange = onScreenOffOnly,
                        enabled = state.settings.notificationsEnabled,
                    )
                }
            } else {
                Text(
                    stringResource(R.string.notifications_need_access),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onGrantAccess) {
                    Text(stringResource(R.string.action_grant_access))
                }
            }
        }
    }
}

/**
 * The recent protocol exchange.
 *
 * "Connected, but nothing arrives" is the failure this app is most likely to hit against
 * an incompletely understood protocol, and it looks identical to working from the outside.
 * Showing the traffic is what makes it reportable without a cable and logcat.
 */
@Composable
private fun ProtocolLogCard(entries: List<ProtocolLog.Entry>, onClear: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.protocol_log, entries.size),
                    style = MaterialTheme.typography.titleMedium,
                )
                TextButton(onClick = { expanded = !expanded }) {
                    Text(stringResource(if (expanded) R.string.action_hide else R.string.action_show))
                }
            }

            if (!expanded) return@Column

            HorizontalDivider()

            if (entries.isEmpty()) {
                Text(stringResource(R.string.protocol_log_empty), style = MaterialTheme.typography.bodyMedium)
                return@Column
            }

            // Newest first: the interesting event is always the most recent one.
            entries.asReversed().take(LOG_LINES).forEach { entry ->
                Text(
                    text = buildString {
                        append(TIME_FORMAT.format(java.util.Date(entry.atMillis)))
                        append("  ")
                        append(
                            when (entry.direction) {
                                ProtocolLog.Direction.OUT -> "→"
                                ProtocolLog.Direction.IN -> "←"
                                ProtocolLog.Direction.ACK -> "✓"
                                ProtocolLog.Direction.DROP -> "✕"
                                ProtocolLog.Direction.NOTE -> "·"
                            },
                        )
                        append(" ")
                        append(entry.label)
                        entry.detail?.let { append("\n        ").append(it) }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = when (entry.direction) {
                        ProtocolLog.Direction.DROP -> MaterialTheme.colorScheme.error
                        ProtocolLog.Direction.ACK -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            TextButton(onClick = onClear) { Text(stringResource(R.string.action_clear)) }
        }
    }
}

/** Enough to see a whole handshake, few enough to scroll past. */
private const val LOG_LINES = 60

private val TIME_FORMAT = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT)
