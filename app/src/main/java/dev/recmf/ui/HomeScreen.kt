/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Card
import androidx.compose.material3.TextButton
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import dev.recmf.ble.ProtocolLog
import dev.recmf.health.HealthConnectAvailability

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    discovered: List<DiscoveredWatch>,
    scanError: String?,
    healthConnectAvailability: HealthConnectAvailability,
    protocolLog: List<ProtocolLog.Entry>,
    hasNotificationAccess: Boolean,
    onClearLog: () -> Unit,
    onNotificationsEnabled: (Boolean) -> Unit,
    onGrantNotificationAccess: () -> Unit,
    onScan: () -> Unit,
    onPair: (DiscoveredWatch) -> Unit,
    onForget: () -> Unit,
    onSyncNow: () -> Unit,
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
                item { TodayCard(state) }
                item { HealthConnectCard(state, healthConnectAvailability, onHealthConnectEnabled) }
                item {
                    NotificationsCard(
                        state = state,
                        hasAccess = hasNotificationAccess,
                        onEnabled = onNotificationsEnabled,
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

@Composable
private fun TodayCard(state: HomeUiState) {
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
        }
    }
}

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

@Composable
private fun NotificationsCard(
    state: HomeUiState,
    hasAccess: Boolean,
    onEnabled: (Boolean) -> Unit,
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
                    color = if (entry.direction == ProtocolLog.Direction.DROP) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
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
