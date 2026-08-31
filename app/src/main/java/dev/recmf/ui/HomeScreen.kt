/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.ui

import androidx.compose.foundation.layout.Box
import android.content.Context
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.produceState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import dev.recmf.BuildConfig
import dev.recmf.R
import dev.recmf.ble.ConnectionState
import dev.recmf.ble.DiscoveredWatch
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import dev.recmf.ble.ProtocolLog
import dev.recmf.data.WatchPreferences
import dev.recmf.data.WatchSetting
import dev.recmf.protocol.CmfAlarm
import dev.recmf.protocol.CmfAlarms
import dev.recmf.protocol.CmfActivityType
import dev.recmf.protocol.CmfWeekday
import dev.recmf.protocol.CmfSettings
import android.content.ClipData
import android.os.Build
import androidx.annotation.StringRes
import java.util.Locale
import android.widget.Toast
import dev.recmf.health.HealthConnectAvailability
import dev.recmf.service.WeatherProblem
import dev.recmf.data.SleepSummary
import dev.recmf.protocol.SleepSession
import dev.recmf.protocol.WatchfaceList
import dev.recmf.service.WatchfaceInstall
import dev.recmf.update.AvailableUpdate
import dev.recmf.update.UpdateState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    discovered: List<DiscoveredWatch>,
    scanError: String?,
    healthConnectAvailability: HealthConnectAvailability,
    watchPreferences: WatchPreferences,
    onWatchPreferences: (WatchSetting, (WatchPreferences) -> WatchPreferences) -> Unit,
    hasNotificationAccess: Boolean,
    notificationApps: List<NotificationApp>,
    lastSleep: SleepSummary?,
    sleepSession: SleepSession?,
    charts: HealthCharts,
    weekly: WeeklySeries,
    watchfaces: WatchfaceList?,
    watchfaceInstall: WatchfaceInstall?,
    onNotificationAppBlocked: (String, Boolean) -> Unit,
    onNotificationAppsBlocked: (List<String>, Boolean) -> Unit,
    isBatteryExempt: Boolean,
    onNotificationsEnabled: (Boolean) -> Unit,
    onScreenOffOnlyEnabled: (Boolean) -> Unit,
    cityLookup: CityLookup,
    onWeatherEnabled: (Boolean) -> Unit,
    onFindCity: (String) -> Unit,
    onGrantNotificationAccess: () -> Unit,
    onAllowBackgroundWork: () -> Unit,
    onScan: () -> Unit,
    onPair: (DiscoveredWatch) -> Unit,
    onForget: () -> Unit,
    onSyncNow: () -> Unit,
    onFindWatch: () -> Unit,
    onSelectWatchface: (Int) -> Unit,
    onInstallWatchface: (Int) -> Unit,
    updateState: UpdateState,
    onCheckForUpdate: () -> Unit,
    onInstallUpdate: (AvailableUpdate) -> Unit,
    onAutoSyncSeconds: (Int) -> Unit,
    onHealthConnectEnabled: (Boolean) -> Unit,
) {
    val pager = rememberPagerState { HomeTab.entries.size }
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)) {
            if (state.settings.isPaired) {
                // A pager rather than a swapped body, so the tabs can be swiped as well as
                // tapped. Each page carries its own scroll, which is why the connection
                // card is repeated rather than hoisted: hoisting it would pin a card above
                // a horizontally sliding surface and the two would come apart mid-swipe.
                HorizontalPager(state = pager) { page ->
                    TabContent(
                        tab = HomeTab.entries[page],
                        state = state,
                        healthConnectAvailability = healthConnectAvailability,
                        watchPreferences = watchPreferences,
                        onWatchPreferences = onWatchPreferences,
                        hasNotificationAccess = hasNotificationAccess,
                        notificationApps = notificationApps,
                        lastSleep = lastSleep,
                        sleepSession = sleepSession,
                        charts = charts,
                        weekly = weekly,
                        watchfaces = watchfaces,
                        watchfaceInstall = watchfaceInstall,
                        onNotificationAppBlocked = onNotificationAppBlocked,
                        onNotificationAppsBlocked = onNotificationAppsBlocked,
                        isBatteryExempt = isBatteryExempt,
                        onNotificationsEnabled = onNotificationsEnabled,
                        onScreenOffOnlyEnabled = onScreenOffOnlyEnabled,
                        cityLookup = cityLookup,
                        onWeatherEnabled = onWeatherEnabled,
                        onFindCity = onFindCity,
                        onGrantNotificationAccess = onGrantNotificationAccess,
                        onAllowBackgroundWork = onAllowBackgroundWork,
                        onForget = onForget,
                        onSyncNow = onSyncNow,
                        onFindWatch = onFindWatch,
                        onSelectWatchface = onSelectWatchface,
                        onInstallWatchface = onInstallWatchface,
                        updateState = updateState,
                        onCheckForUpdate = onCheckForUpdate,
                        onInstallUpdate = onInstallUpdate,
                        onAutoSyncSeconds = onAutoSyncSeconds,
                        onHealthConnectEnabled = onHealthConnectEnabled,
                    )
                }

                FloatingTabDock(
                    selected = pager.currentPage,
                    onSelect = { scope.launch { pager.animateScrollToPage(it) } },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp),
                )
            } else {
                PairingList(discovered, scanError, onScan, onPair)
            }
        }
    }
}

/** One tab's worth of cards. */
@Composable
private fun TabContent(
    tab: HomeTab,
    state: HomeUiState,
    healthConnectAvailability: HealthConnectAvailability,
    watchPreferences: WatchPreferences,
    onWatchPreferences: (WatchSetting, (WatchPreferences) -> WatchPreferences) -> Unit,
    hasNotificationAccess: Boolean,
    notificationApps: List<NotificationApp>,
    lastSleep: SleepSummary?,
    sleepSession: SleepSession?,
    charts: HealthCharts,
    weekly: WeeklySeries,
    watchfaces: WatchfaceList?,
    watchfaceInstall: WatchfaceInstall?,
    onNotificationAppBlocked: (String, Boolean) -> Unit,
    onNotificationAppsBlocked: (List<String>, Boolean) -> Unit,
    isBatteryExempt: Boolean,
    onNotificationsEnabled: (Boolean) -> Unit,
    onScreenOffOnlyEnabled: (Boolean) -> Unit,
    cityLookup: CityLookup,
    onWeatherEnabled: (Boolean) -> Unit,
    onFindCity: (String) -> Unit,
    onGrantNotificationAccess: () -> Unit,
    onAllowBackgroundWork: () -> Unit,
    onForget: () -> Unit,
    onSyncNow: () -> Unit,
    onFindWatch: () -> Unit,
    onSelectWatchface: (Int) -> Unit,
    onInstallWatchface: (Int) -> Unit,
    updateState: UpdateState,
    onCheckForUpdate: () -> Unit,
    onInstallUpdate: (AvailableUpdate) -> Unit,
    onAutoSyncSeconds: (Int) -> Unit,
    onHealthConnectEnabled: (Boolean) -> Unit,
) {
    // Which measurement is open, if any. Held here rather than in the tile so that the
    // sheet outlives the row scrolling off the screen.
    var opened by remember { mutableStateOf<TileSpec?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        // Room for the floating dock, which sits over the content rather than beside it.
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        // On both tabs: it is the answer to "is any of this current?", and neither tab
        // means anything without it.
        item { ConnectionCard(state, onSyncNow, onForget) }

        when (tab) {
            HomeTab.HEALTH -> {
                item { TodayCard(state, lastSleep, watchPreferences.stepsGoal) }

                // Tiles come before the charts: a figure is what gets looked for, and the
                // shape of the day is what gets looked at once the figure has been read.
                metricTiles(state, weekly) { opened = it }

                if (charts.heartRate.size >= 2 || charts.stepsByHour.any { it > 0f } ||
                    charts.spo2.isNotEmpty()
                ) {
                    item { ChartsCard(charts) }
                }

                item { AutoSyncCard(state.settings.autoSyncSeconds, onAutoSyncSeconds) }
                item { HealthConnectCard(state, healthConnectAvailability, onHealthConnectEnabled) }
                if (!isBatteryExempt) {
                    item { BackgroundWorkCard(onAllowBackgroundWork) }
                }
            }

            HomeTab.SLEEP -> {
                item { SleepCard(sleepSession) }
            }

            HomeTab.FACES -> {
                item {
                    WatchfaceCard(
                        watchfaces = watchfaces,
                        install = watchfaceInstall,
                        connected = state.connection.isUsable,
                        onSelect = onSelectWatchface,
                        onInstall = onInstallWatchface,
                    )
                }
            }

            HomeTab.DEVICE -> {
                item {
                    WatchSettingsCard(watchPreferences, state.connection.isUsable, onWatchPreferences)
                }
                item { WeatherCard(state, cityLookup, onWeatherEnabled, onFindCity) }
                item {
                    NotificationsCard(
                        state = state,
                        hasAccess = hasNotificationAccess,
                        apps = notificationApps,
                        onEnabled = onNotificationsEnabled,
                        onAppBlocked = onNotificationAppBlocked,
                        onAllBlocked = onNotificationAppsBlocked,
                        onScreenOffOnly = onScreenOffOnlyEnabled,
                        onGrantAccess = onGrantNotificationAccess,
                    )
                }
                item { AlarmsCard(watchPreferences.alarms, onWatchPreferences) }
                item { FindWatchCard(state.connection.isUsable, onFindWatch) }
                item { UpdateCard(updateState, onCheckForUpdate, onInstallUpdate) }
                item { ProtocolLogCard() }
            }
        }
    }

    opened?.let { tile ->
        val context = LocalContext.current
        MetricDetailSheet(
            icon = tile.icon,
            label = stringResource(tile.label),
            value = tile.value(context),
            week = tile.week,
            format = { number -> tile.format(context, number) },
            onDismiss = { opened = null },
        )
    }
}

@Composable
private fun PairingList(
    discovered: List<DiscoveredWatch>,
    scanError: String?,
    onScan: () -> Unit,
    onPair: (DiscoveredWatch) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
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

        // Kept here too: a pairing that fails leaves nothing else to look at, and this is
        // the one place that says why.
        item { ProtocolLogCard() }
    }
}

/**
 * A floating pill rather than a bar across the screen.
 *
 * It hovers over the content instead of reserving a strip of it, which is why the pages
 * carry bottom padding. The selected entry's background follows the pager continuously,
 * so a half-finished swipe shows a half-moved highlight rather than snapping when the
 * gesture ends.
 */
@Composable
private fun FloatingTabDock(
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            HomeTab.entries.forEachIndexed { index, entry ->
                val isSelected = index == selected
                val background by animateColorAsState(
                    targetValue = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.Transparent
                    },
                    label = "dock background",
                )
                val content by animateColorAsState(
                    targetValue = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    label = "dock label",
                )

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .background(background)
                        .clickable { onSelect(index) }
                        // Four tabs now, one of them a long word in some languages, so the
                        // pills are a touch tighter than they were with three.
                        .padding(horizontal = 13.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(entry.labelRes),
                        color = content,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
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

            Text(
                text = stringResource(R.string.app_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall,
            )

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

/**
 * The day in one card: the one measurement with a target, and last night.
 *
 * Steps get the ring because steps are the only figure here with a goal behind them — a
 * number against a target is a fraction, and a fraction is a shape before it is a figure.
 * Everything else the watch counts is a tile below, where it comes with its own week.
 */
@Composable
private fun TodayCard(
    state: HomeUiState,
    sleep: SleepSummary?,
    goal: Int,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.today), style = MaterialTheme.typography.titleMedium)

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StepsRing(steps = state.today.steps, goal = goal)

                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // The name above the times rather than beside them. Squeezed into one
                    // row next to the ring, "Last night" wrapped mid-word, which is a
                    // layout deciding for itself that a two-word label is a paragraph.
                    sleep?.let {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_metric_sleep),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                stringResource(R.string.metric_sleep),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            stringResource(
                                R.string.metric_sleep_value,
                                CLOCK_TIME.format(Instant.ofEpochSecond(it.startSeconds)),
                                CLOCK_TIME.format(Instant.ofEpochSecond(it.wakeSeconds)),
                            ),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }

                    // Said once, in words, instead of a column of em dashes: the watch has
                    // connected and has not yet handed anything over.
                    if (state.today.steps == 0 && sleep == null) {
                        Text(
                            stringResource(R.string.metrics_waiting),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // The watch's own clock, as the app can best see it. A zero above means
            // "nothing recorded under today's date", which happens both when the watch
            // was not worn and when its calendar has drifted off the phone's — and those
            // want opposite responses, so the app should not make the user guess which.
            WatchClockNote(state.watch)
        }
    }
}

/**
 * The measurements, two to a row, each with the week behind it.
 *
 * A tile is only added when there is something to put in it. That is the whole rule, and
 * it is why the grid is built as a list rather than written out: a fixed grid with three
 * of its six cells empty is a screen full of things the app cannot tell you, which reads
 * as broken rather than as quiet.
 *
 * A counter survives a zero day — once the watch has reported climbs at any point this
 * week, the tile stays and honestly says none today. Vanishing on the flat days would make
 * the grid rearrange itself under the reader.
 */
private fun LazyListScope.metricTiles(
    state: HomeUiState,
    weekly: WeeklySeries,
    onOpen: (TileSpec) -> Unit,
) {
    val tiles = buildList {
        state.latestHeartRate?.let { latest ->
            add(
                TileSpec(
                    R.drawable.ic_metric_heart, R.string.metric_heart_rate, weekly.heartRate,
                    format = { context, bpm ->
                        context.getString(R.string.value_bpm, bpm.roundToInt())
                    },
                ) { it.getString(R.string.value_bpm, latest.bpm) },
            )
        }
        state.restingHeartRate?.let { bpm ->
            add(
                TileSpec(
                    R.drawable.ic_metric_heart, R.string.metric_resting_heart_rate,
                    weekly.restingHeartRate,
                    format = { context, beats ->
                        context.getString(R.string.value_bpm, beats.roundToInt())
                    },
                ) { it.getString(R.string.value_bpm, bpm) },
            )
        }
        state.spo2?.let { percent ->
            add(
                TileSpec(
                    R.drawable.ic_metric_oxygen, R.string.metric_spo2, weekly.spo2,
                    format = { context, share ->
                        context.getString(R.string.value_percent, share.roundToInt())
                    },
                ) { it.getString(R.string.value_percent, percent) },
            )
        }
        state.stress?.let { level ->
            add(
                TileSpec(R.drawable.ic_metric_stress, R.string.metric_stress, weekly.stress) {
                    level.toString()
                },
            )
        }
        if (state.today.distanceMeters > 0 || weekly.distanceMeters.hasReadings()) {
            add(
                TileSpec(
                    R.drawable.ic_metric_distance, R.string.metric_distance,
                    weekly.distanceMeters,
                    format = { context, metres -> context.readableDistance(metres.roundToInt()) },
                ) { it.readableDistance(state.today.distanceMeters) },
            )
        }
        if (state.today.calories > 0 || weekly.calories.hasReadings()) {
            add(
                TileSpec(
                    R.drawable.ic_metric_calories, R.string.metric_calories, weekly.calories,
                    format = { context, kcal ->
                        context.getString(R.string.value_kcal, kcal.roundToInt())
                    },
                ) { it.getString(R.string.value_kcal, state.today.calories) },
            )
        }
        if (state.today.climbs > 0 || weekly.climbs.hasReadings()) {
            add(
                TileSpec(R.drawable.ic_metric_climbs, R.string.metric_climbs, weekly.climbs) {
                    state.today.climbs.toString()
                },
            )
        }
    }

    // Chunked into rows rather than handed to a grid: this sits inside a LazyColumn, and
    // a lazy grid inside a lazy column is an unbounded height inside an infinite one.
    tiles.chunked(2).forEachIndexed { row, pair ->
        item(key = "tiles-$row") {
            // Both tiles take the height of the taller one. Without this a pair where only
            // one has a week strip sits with a step in its bottom edge, and the grid reads
            // as broken rather than as two cards of different content.
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val context = LocalContext.current

                pair.forEachIndexed { column, tile ->
                    MetricTile(
                        icon = tile.icon,
                        label = stringResource(tile.label),
                        value = tile.value(context),
                        accent = ACCENTS[(row * 2 + column) % ACCENTS.size],
                        week = tile.week,
                        onClick = { onOpen(tile) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }

                // An odd tile keeps its own column width instead of stretching across
                // the row, so the grid stays a grid down to its last row.
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * One tile before it knows what colour it is.
 *
 * [value] takes a context rather than being a string because these are built outside a
 * composition, where `stringResource` cannot be called. [format] is the same unit applied
 * to any number, which is what the detail screen needs for the week's high, low and average.
 */
private class TileSpec(
    @param:DrawableRes val icon: Int,
    @param:StringRes val label: Int,
    val week: List<DayValue> = emptyList(),
    // Ahead of [value] so the trailing lambda at every call site still binds to that one.
    // A metric whose unit is worth saying overrides it; a plain count does not need to.
    val format: (Context, Float) -> String = { _, number -> number.roundToInt().toString() },
    val value: (Context) -> String,
)

/** True once any day in the week carries a reading above zero. */
private fun List<DayValue>.hasReadings(): Boolean = any { (it.value ?: 0f) > 0f }

/**
 * Metres, in the unit that suits the number.
 *
 * Below a kilometre, "0,4 km" throws away the only digit that was doing any work.
 */
private fun Context.readableDistance(metres: Int): String = if (metres < 1000) {
    getString(R.string.value_metres, metres)
} else {
    getString(R.string.value_km, String.format(Locale.getDefault(), "%.1f", metres / 1000f))
}

/** Cycled down the grid so neighbours differ; see the note in MetricTiles.kt. */
private val ACCENTS = listOf(
    TileAccent.PRIMARY,
    TileAccent.SECONDARY,
    TileAccent.TERTIARY,
    TileAccent.NEUTRAL,
)

/**
 * The day itself, drawn.
 *
 * Nothing is drawn until there are at least two readings — one point is not a shape, and
 * an axis around it is furniture around a fact.
 */
@Composable
private fun ChartsCard(charts: HealthCharts) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                stringResource(R.string.charts_today),
                style = MaterialTheme.typography.titleMedium,
            )

            if (charts.heartRate.size >= 2) {
                ChartSection(stringResource(R.string.chart_heart_rate)) {
                    // Twenty beats is the narrowest spread worth the full height. A quiet
                    // hour otherwise gets drawn as a mountain range.
                    LineChart(
                        charts.heartRate,
                        MaterialTheme.colorScheme.primary,
                        minimumSpan = 20f,
                    )
                }
            }

            if (charts.stepsByHour.any { it > 0f }) {
                ChartSection(stringResource(R.string.chart_steps)) {
                    BarChart(charts.stepsByHour, MaterialTheme.colorScheme.primary)
                }
            }

            if (charts.spo2.isNotEmpty()) {
                val low = charts.spo2.minOf { it.value }.toInt()
                val high = charts.spo2.maxOf { it.value }.toInt()

                ChartSection(
                    stringResource(R.string.chart_spo2),
                    note = stringResource(R.string.chart_range_percent, low, high),
                ) {
                    // Four percent is the narrowest scale worth the full height: a day
                    // that never leaves 97-99 still shows which readings were the low
                    // ones. A fixed 90-100 was tried and drew that day as a flat row.
                    DotChart(
                        charts.spo2,
                        MaterialTheme.colorScheme.tertiary,
                        minimumSpan = 4f,
                    )
                }
            }
        }
    }
}

/**
 * How often to ask the watch for the backlog.
 *
 * Each poll is radio time on both sides, so how often is the user's call — including not
 * at all.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AutoSyncCard(selectedSeconds: Int, onAutoSyncSeconds: (Int) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.auto_sync), style = MaterialTheme.typography.titleMedium)

            // FlowRow, not Row: five chips do not fit a narrow screen, and a Row squeezes
            // the last one into a one-character-wide column rather than wrapping it.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AUTO_SYNC_CHOICES.forEach { (seconds, labelRes) ->
                    FilterChip(
                        selected = selectedSeconds == seconds,
                        onClick = { onAutoSyncSeconds(seconds) },
                        label = { Text(stringResource(labelRes)) },
                    )
                }
            }
        }
    }
}

/**
 * Reports the date on the newest record the watch handed over.
 *
 * Silent while nothing has been fetched — an empty line every launch would be noise. It
 * speaks up when the watch's date and the phone's disagree, because from that point on
 * every figure in this app is filtered against a midnight the watch does not share.
 */
@Composable
private fun WatchClockNote(watch: WatchInfo) {
    // Says when the watch last answered, whether or not it had anything new. The line
    // below reports the date on the data, which legitimately does not move between two
    // syncs a minute apart — so on its own it made a working Sync button look dead.
    watch.lastExchangeAtMillis?.let { exchangeAt ->
        Text(
            text = stringResource(
                R.string.watch_last_exchange,
                LOG_TIME.format(Instant.ofEpochMilli(exchangeAt)),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    val recordAt = watch.lastRecordEpochSeconds ?: return

    val watchDate = Instant.ofEpochSecond(recordAt).atZone(ZoneId.systemDefault()).toLocalDate()
    val agrees = watchDate == LocalDate.now()
    val stamp = RECORD_STAMP.format(Instant.ofEpochSecond(recordAt))

    val count = watch.lastRecordCount ?: 0

    Text(
        text = stringResource(
            if (agrees) R.string.watch_clock_agrees else R.string.watch_clock_differs,
            stamp,
            // A plural, not a formatted number: Russian picks a different ending for one,
            // for two through four, and for the rest.
            pluralStringResource(R.plurals.watch_clock_records, count, count),
        ),
        style = MaterialTheme.typography.bodySmall,
        color = if (agrees) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.error
        },
    )
}

private val AUTO_SYNC_CHOICES = listOf(
    0 to R.string.auto_sync_off,
    30 to R.string.auto_sync_30s,
    60 to R.string.auto_sync_1m,
    300 to R.string.auto_sync_5m,
    900 to R.string.auto_sync_15m,
)

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
    onChange: (WatchSetting, (WatchPreferences) -> WatchPreferences) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.watch_settings), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.watch_settings_explainer),
                style = MaterialTheme.typography.bodySmall,
            )

            if (!connected) {
                Text(
                    stringResource(R.string.watch_settings_offline),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.padding(4.dp))

            SettingSwitch(stringResource(R.string.setting_hr_monitoring), preferences.heartRateMonitoring) {
                onChange(WatchSetting.MONITORING) { current -> current.copy(heartRateMonitoring = it) }
            }
            SettingSwitch(stringResource(R.string.setting_spo2_monitoring), preferences.spo2Monitoring) {
                onChange(WatchSetting.MONITORING) { current -> current.copy(spo2Monitoring = it) }
            }
            SettingSwitch(stringResource(R.string.setting_stress_monitoring), preferences.stressMonitoring) {
                onChange(WatchSetting.MONITORING) { current -> current.copy(stressMonitoring = it) }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SettingSwitch(stringResource(R.string.setting_raise_to_wake), preferences.raiseToWake) {
                onChange(WatchSetting.RAISE_TO_WAKE) { current -> current.copy(raiseToWake = it) }
            }
            SettingSwitch(stringResource(R.string.setting_24_hour), preferences.use24Hour) {
                onChange(WatchSetting.TIME_FORMAT) { current -> current.copy(use24Hour = it) }
            }
            SettingSwitch(stringResource(R.string.setting_metric), preferences.metric) {
                onChange(WatchSetting.UNITS) { current -> current.copy(metric = it) }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text(stringResource(R.string.daily_goals), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.daily_goals_read_only),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Shown, not edited. The watch acknowledges a goal write and then keeps what
            // it had — proven twice, in two different payload shapes — so an editable
            // field here would be a control that does nothing, which is worse than a
            // number and an explanation.
            GoalReading(stringResource(R.string.goal_steps), preferences.stepsGoal)
            GoalReading(stringResource(R.string.goal_distance), preferences.distanceGoalMeters)
            GoalReading(stringResource(R.string.goal_calories), preferences.caloriesGoal)

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text(stringResource(R.string.reminders), style = MaterialTheme.typography.titleSmall)

            SettingSwitch(stringResource(R.string.reminder_stand), preferences.standReminder) {
                onChange(WatchSetting.STAND_REMINDER) { current -> current.copy(standReminder = it) }
            }
            if (preferences.standReminder) {
                NumberField(stringResource(R.string.reminder_interval), preferences.standIntervalMinutes) {
                    onChange(WatchSetting.STAND_REMINDER) { current -> current.copy(standIntervalMinutes = it) }
                }
                QuietHoursRow(
                    startMinutes = preferences.standQuietStartMinutes,
                    endMinutes = preferences.standQuietEndMinutes,
                ) { start, end ->
                    onChange(WatchSetting.STAND_REMINDER) { current ->
                        current.copy(standQuietStartMinutes = start, standQuietEndMinutes = end)
                    }
                }
            }

            SettingSwitch(stringResource(R.string.reminder_drink), preferences.drinkReminder) {
                onChange(WatchSetting.DRINK_REMINDER) { current -> current.copy(drinkReminder = it) }
            }
            if (preferences.drinkReminder) {
                NumberField(stringResource(R.string.reminder_interval), preferences.drinkIntervalMinutes) {
                    onChange(WatchSetting.DRINK_REMINDER) { current -> current.copy(drinkIntervalMinutes = it) }
                }
                QuietHoursRow(
                    startMinutes = preferences.drinkQuietStartMinutes,
                    endMinutes = preferences.drinkQuietEndMinutes,
                ) { start, end ->
                    onChange(WatchSetting.DRINK_REMINDER) { current ->
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

            NumberField(stringResource(R.string.alert_hr_low), preferences.heartRateAlertLow) {
                onChange(WatchSetting.ALERTS) { current -> current.copy(heartRateAlertLow = it) }
            }
            NumberField(stringResource(R.string.alert_hr_resting_high), preferences.heartRateAlertRestingHigh) {
                onChange(WatchSetting.ALERTS) { current -> current.copy(heartRateAlertRestingHigh = it) }
            }
            NumberField(stringResource(R.string.alert_hr_active_high), preferences.heartRateAlertActiveHigh) {
                onChange(WatchSetting.ALERTS) { current -> current.copy(heartRateAlertActiveHigh = it) }
            }
            NumberField(stringResource(R.string.alert_spo2_low), preferences.spo2AlertLow) {
                onChange(WatchSetting.ALERTS) { current -> current.copy(spo2AlertLow = it) }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SportTypesSection(preferences.sportTypes) { types ->
                onChange(WatchSetting.SPORTS) { current -> current.copy(sportTypes = types) }
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
private fun NumberField(label: String, value: Int, onValue: (Int) -> Unit) {
    // Edited as text so a half-typed number does not momentarily become a threshold of
    // 8 — only a parsable value is committed.
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

/** One goal as the watch reports it. */
@Composable
private fun GoalReading(label: String, value: Int) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value.toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Weather for the watch's own weather screen.
 *
 * A typed place, not a location permission: a city is all a watch face needs, and asking
 * for coordinates the phone knows would be asking for far more than the feature uses.
 */
@Composable
private fun WeatherCard(
    state: HomeUiState,
    lookup: CityLookup,
    onEnabled: (Boolean) -> Unit,
    onFindCity: (String) -> Unit,
) {
    var typed by remember(state.settings.weatherCity) {
        mutableStateOf(state.settings.weatherCity.orEmpty())
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.weather), style = MaterialTheme.typography.titleMedium)
                Switch(
                    checked = state.settings.weatherEnabled,
                    onCheckedChange = onEnabled,
                    enabled = state.settings.weatherCity != null,
                )
            }

            HorizontalDivider()

            Text(stringResource(R.string.weather_explainer), style = MaterialTheme.typography.bodyMedium)

            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                label = { Text(stringResource(R.string.weather_city)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { onFindCity(typed) },
                    enabled = typed.isNotBlank() && lookup != CityLookup.Searching,
                ) {
                    Text(stringResource(R.string.action_find))
                }

                when (lookup) {
                    CityLookup.Searching -> Text(
                        stringResource(R.string.weather_searching),
                        style = MaterialTheme.typography.bodySmall,
                    )

                    CityLookup.NotFound -> Text(
                        stringResource(R.string.weather_not_found),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    is CityLookup.Found -> Text(
                        stringResource(R.string.weather_found, lookup.name),
                        style = MaterialTheme.typography.bodySmall,
                    )

                    CityLookup.Idle -> state.settings.weatherCity?.let {
                        Text(
                            stringResource(R.string.weather_found, it),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            if (state.settings.weatherEnabled) {
                HorizontalDivider()
                WeatherStatusLine(state.weather)
            }
        }
    }
}

/**
 * Says what became of the forecast.
 *
 * Every way this can fail used to be a silent return, so "the weather is not updating"
 * looked the same whether no place had been resolved, the provider could not be reached,
 * or the watch simply had not been asked yet.
 */
@Composable
private fun WeatherStatusLine(weather: WeatherStatus) {
    val (textRes, isError) = when {
        weather.problem == WeatherProblem.NO_CITY -> R.string.weather_status_no_city to true
        weather.problem == WeatherProblem.UNREACHABLE -> R.string.weather_status_unreachable to true
        weather.sentAtMillis == null -> R.string.weather_status_waiting to false
        else -> R.string.weather_status_sent to false
    }

    val detail = weather.sentAtMillis?.let { CLOCK_TIME.format(Instant.ofEpochMilli(it)) }

    Text(
        text = if (textRes == R.string.weather_status_sent) {
            stringResource(textRes, detail.orEmpty(), weather.temperatureC ?: 0)
        } else {
            stringResource(textRes)
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

/**
 * Asks for the one system setting that decides whether reCMF keeps up while the phone is
 * idle.
 *
 * Shown only while the exemption is missing, and it is not decoration: the background
 * refresh is scheduled work, and Android defers scheduled work on an optimised app until
 * it next feels like running it — which is how a forecast ends up hours stale.
 */
@Composable
private fun BackgroundWorkCard(onAllow: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.background_work),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.background_work_explainer),
                style = MaterialTheme.typography.bodyMedium,
            )
            FilledTonalButton(onClick = onAllow) {
                Text(stringResource(R.string.action_allow_background))
            }
        }
    }
}

@Composable
private fun NotificationsCard(
    state: HomeUiState,
    hasAccess: Boolean,
    apps: List<NotificationApp>,
    onEnabled: (Boolean) -> Unit,
    onScreenOffOnly: (Boolean) -> Unit,
    onAppBlocked: (String, Boolean) -> Unit,
    onAllBlocked: (List<String>, Boolean) -> Unit,
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

                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                var picking by rememberSaveable { mutableStateOf(false) }
                val silenced = apps.count { it.blocked }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = apps.isNotEmpty()) { picking = true },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.notification_apps),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            when {
                                apps.isEmpty() -> stringResource(R.string.notification_apps_loading)
                                silenced == 0 -> stringResource(R.string.notification_apps_all)
                                else -> pluralStringResource(
                                    R.plurals.notification_apps_silenced,
                                    silenced,
                                    silenced,
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (apps.isNotEmpty()) {
                        Text(
                            stringResource(R.string.action_choose),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                if (picking) {
                    NotificationAppPicker(
                        apps = apps,
                        onBlocked = onAppBlocked,
                        onAllBlocked = onAllBlocked,
                        onDismiss = { picking = false },
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
 * Every app on the phone, with a switch each.
 *
 * Its own screen rather than a section of the card, because there are hundreds of them: a
 * list that long inside a page that already scrolls is both slow to lay out and awkward to
 * use, and a search box is the only thing that makes such a list usable at all.
 */
@Composable
private fun NotificationAppPicker(
    apps: List<NotificationApp>,
    onBlocked: (String, Boolean) -> Unit,
    onAllBlocked: (List<String>, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                var query by rememberSaveable { mutableStateOf("") }

                val shown = remember(apps, query) {
                    if (query.isBlank()) apps else apps.filter { it.label.contains(query, ignoreCase = true) }
                }

                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.notification_apps),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) }
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.notification_apps_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                )

                // Both directions, because the sensible starting point differs by person:
                // some want everything except a handful, some want nothing except a
                // handful, and neither should mean a hundred taps.
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = { onAllBlocked(apps.map { it.packageName }, false) }) {
                        Text(stringResource(R.string.action_allow_all))
                    }
                    TextButton(onClick = { onAllBlocked(apps.map { it.packageName }, true) }) {
                        Text(stringResource(R.string.action_block_all))
                    }
                }

                HorizontalDivider()

                LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { it.packageName }) { app ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onBlocked(app.packageName, !app.blocked) }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIcon(app.packageName)
                            Text(
                                app.label,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = !app.blocked,
                                onCheckedChange = { allowed -> onBlocked(app.packageName, !allowed) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One app's icon, fetched off the main thread.
 *
 * Loading a few hundred icons at once would be a stall; the list only ever asks for the
 * rows it is showing, and each row asks in the background and appears when it has one.
 */
@Composable
private fun AppIcon(packageName: String) {
    val context = LocalContext.current

    val icon by produceState<ImageBitmap?>(initialValue = null, key1 = packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(packageName).toBitmap(ICON_PX, ICON_PX)
            }.getOrNull()?.asImageBitmap()
        }
    }

    val size = Modifier.size(40.dp)

    // The space is held whether or not the icon arrives, so a row does not jump sideways
    // when it does, and an app whose icon cannot be read still lines up with the rest.
    if (icon == null) {
        Box(size)
    } else {
        Image(bitmap = icon!!, contentDescription = null, modifier = size)
    }
}

private const val ICON_PX = 96

/**
 * The step count as a fraction of its goal.
 *
 * A ring rather than a bar because it holds its own number in the middle, and because a
 * day's progress is a thing that comes round again. Over-achievement is drawn full rather
 * than wrapped: past the goal the number is the news, not the geometry.
 */
@Composable
private fun StepsRing(steps: Int, goal: Int) {
    val fraction = if (goal > 0) (steps.toFloat() / goal).coerceIn(0f, 1f) else 0f
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = MaterialTheme.colorScheme.primary

    Box(Modifier.size(RING_SIZE), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(RING_SIZE)) {
            val stroke = RING_STROKE.toPx()
            val inset = stroke / 2f
            val box = Size(size.width - stroke, size.height - stroke)

            drawArc(
                color = track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = box,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )

            if (fraction > 0f) {
                // From the top, clockwise, like every other progress ring anyone has met.
                drawArc(
                    color = fill,
                    startAngle = -90f,
                    sweepAngle = 360f * fraction,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = box,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painter = painterResource(R.drawable.ic_metric_steps),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Text(steps.toString(), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.metric_steps),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A chart under the name of the thing it draws.
 *
 * [note] is the range the chart was drawn against, when it has one. Marks without a scale
 * are a picture of a shape, not a reading — and blood oxygen spends whole days inside two
 * percent, where the shape is the only thing the dots can show.
 */
@Composable
private fun ChartSection(title: String, note: String? = null, chart: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            note?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        chart()
    }
}

private val RING_SIZE = 116.dp
private val RING_STROKE = 10.dp

/**
 * The recent protocol exchange.
 *
 * "Connected, but nothing arrives" is the failure this app is most likely to hit against
 * an incompletely understood protocol, and it looks identical to working from the outside.
 * Showing the traffic is what makes it reportable without a cable and logcat.
 */
@Composable
private fun ProtocolLogCard() {
    var expanded by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.protocol_log_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                TextButton(onClick = { expanded = !expanded }) {
                    Text(stringResource(if (expanded) R.string.action_hide else R.string.action_show))
                }
            }

            if (!expanded) return@Column

            // Subscribed here, and only while the log is open. Collected at the top of the
            // tree it recomposed the whole screen — both pager pages included — on every
            // frame the watch sent, which during a sync burst is a dozen a second. That
            // was the scrolling and swiping stutter.
            val entries by ProtocolLog.entries.collectAsStateWithLifecycle()

            HorizontalDivider()

            if (entries.isEmpty()) {
                Text(stringResource(R.string.protocol_log_empty), style = MaterialTheme.typography.bodyMedium)
                return@Column
            }

            // Newest first: the interesting event is always the most recent one.
            val newestFirst = entries.asReversed().take(LOG_LINES)

            newestFirst.forEach { entry ->
                Text(
                    text = entry.render(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = when (entry.direction) {
                        ProtocolLog.Direction.DROP -> MaterialTheme.colorScheme.error
                        ProtocolLog.Direction.ACK -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // The same lines the screen is showing, so what gets pasted is what was
                // read — a copy built from a second formatter would drift from it.
                val clipboard = LocalClipboard.current
                val scope = rememberCoroutineScope()
                val copied = pluralStringResource(
                    R.plurals.log_copied,
                    newestFirst.size,
                    newestFirst.size,
                )
                val context = LocalContext.current

                TextButton(
                    onClick = {
                        val text = newestFirst.joinToString("\n") { it.render() }
                        scope.launch {
                            clipboard.setClipEntry(
                                ClipEntry(ClipData.newPlainText("reCMF protocol log", text)),
                            )
                            // Android 13 and up shows its own paste confirmation, and two
                            // overlapping toasts is worse than none.
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.action_copy))
                }

                TextButton(onClick = ProtocolLog::clear) { Text(stringResource(R.string.action_clear)) }
            }
        }
    }
}

/**
 * The watch's alarms, as reCMF believes them to be.
 *
 * What is shown starts as what the watch reported at connection, so editing here changes
 * the real list rather than replacing it with a guess. The watch keeps exactly what it is
 * sent — there is no "add one alarm" — which is why nothing is sent until something here
 * is changed.
 */
@Composable
private fun AlarmsCard(
    alarms: List<CmfAlarm>,
    onChange: (WatchSetting, (WatchPreferences) -> WatchPreferences) -> Unit,
) {
    var editing by remember { mutableStateOf<Int?>(null) }

    fun update(transform: (List<CmfAlarm>) -> List<CmfAlarm>) {
        onChange(WatchSetting.ALARMS) { current -> current.copy(alarms = transform(current.alarms)) }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.alarms), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.alarms_explainer),
                style = MaterialTheme.typography.bodyMedium,
            )

            if (alarms.isEmpty()) {
                Text(
                    stringResource(R.string.alarms_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            alarms.forEachIndexed { index, alarm ->
                HorizontalDivider()
                AlarmRow(
                    alarm = alarm,
                    onTime = { editing = index },
                    onEnabled = { enabled ->
                        update { list -> list.mapIndexed { i, a -> if (i == index) a.copy(enabled = enabled) else a } }
                    },
                    onToggleDay = { day ->
                        update { list ->
                            list.mapIndexed { i, a ->
                                if (i != index) {
                                    a
                                } else {
                                    a.copy(days = if (day in a.days) a.days - day else a.days + day)
                                }
                            }
                        }
                    },
                    onDelete = { update { list -> list.filterIndexed { i, _ -> i != index } } },
                )
            }

            if (alarms.size < CmfAlarms.MAX_ALARMS) {
                FilledTonalButton(
                    onClick = { update { list -> list + CmfAlarm(hour = 7, minute = 0) } },
                ) {
                    Text(stringResource(R.string.action_add_alarm))
                }
            }
        }
    }

    editing?.let { index ->
        val alarm = alarms.getOrNull(index)
        if (alarm == null) {
            editing = null
        } else {
            TimePickerDialog(
                initialMinutes = alarm.hour * 60 + alarm.minute,
                onDismiss = { editing = null },
                onConfirm = { minutes ->
                    update { list ->
                        list.mapIndexed { i, a ->
                            if (i == index) a.copy(hour = minutes / 60, minute = minutes % 60) else a
                        }
                    }
                    editing = null
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlarmRow(
    alarm: CmfAlarm,
    onTime: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    onToggleDay: (CmfWeekday) -> Unit,
    onDelete: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onTime) {
                Text(
                    "%02d:%02d".format(alarm.hour, alarm.minute),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = alarm.enabled, onCheckedChange = onEnabled)
                TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete)) }
            }
        }

        // No days selected is a one-shot alarm, which is what a mask of zero means to the
        // watch — so an empty row here is a real choice rather than an unfinished one.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CmfWeekday.entries.forEach { day ->
                FilterChip(
                    selected = day in alarm.days,
                    onClick = { onToggleDay(day) },
                    label = { Text(stringResource(day.labelRes())) },
                )
            }
        }
    }
}

@StringRes
private fun CmfWeekday.labelRes(): Int = when (this) {
    CmfWeekday.MONDAY -> R.string.day_mon
    CmfWeekday.TUESDAY -> R.string.day_tue
    CmfWeekday.WEDNESDAY -> R.string.day_wed
    CmfWeekday.THURSDAY -> R.string.day_thu
    CmfWeekday.FRIDAY -> R.string.day_fri
    CmfWeekday.SATURDAY -> R.string.day_sat
    CmfWeekday.SUNDAY -> R.string.day_sun
}

/**
 * Fetches a newer build and hands it to Android's installer.
 *
 * There is no silent install: Android confirms every sideloaded package itself and this
 * card can only get as far as that dialog. What it saves is the browser, the download, the
 * file manager and — since every build now shares a signing key — the uninstall that used
 * to take the settings with it.
 */
@Composable
private fun UpdateCard(
    state: UpdateState,
    onCheck: () -> Unit,
    onInstall: (AvailableUpdate) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.updates), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.app_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodyMedium,
            )

            when (state) {
                UpdateState.Idle -> Unit

                UpdateState.Checking -> Text(
                    stringResource(R.string.update_checking),
                    style = MaterialTheme.typography.bodySmall,
                )

                UpdateState.UpToDate -> Text(
                    stringResource(R.string.update_none),
                    style = MaterialTheme.typography.bodySmall,
                )

                is UpdateState.Available -> {
                    Text(
                        stringResource(R.string.update_available, state.update.name),
                        style = MaterialTheme.typography.bodySmall,
                    )

                    // Only when the release said something. An empty "What changed"
                    // heading is worse than no heading.
                    state.update.notes?.let { notes ->
                        Text(
                            stringResource(R.string.update_notes_title),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            notes,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                is UpdateState.Downloading -> {
                    Text(
                        stringResource(R.string.update_downloading),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (state.percent == null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { state.percent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                UpdateState.AwaitingConfirmation -> Text(
                    stringResource(R.string.update_confirm),
                    style = MaterialTheme.typography.bodySmall,
                )

                is UpdateState.Failed -> Text(
                    stringResource(R.string.update_failed, state.reason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            val available = state as? UpdateState.Available
            val busy = state is UpdateState.Checking || state is UpdateState.Downloading

            if (available != null) {
                FilledTonalButton(onClick = { onInstall(available.update) }, enabled = !busy) {
                    Text(stringResource(R.string.action_install_update))
                }
            } else {
                OutlinedButton(onClick = onCheck, enabled = !busy) {
                    Text(stringResource(R.string.action_check_update))
                }
            }
        }
    }
}

/** Rings the watch. Disabled while it is out of range, where the button would do nothing. */
@Composable
private fun FindWatchCard(connected: Boolean, onFindWatch: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.find_watch), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.find_watch_explainer),
                style = MaterialTheme.typography.bodyMedium,
            )
            FilledTonalButton(onClick = onFindWatch, enabled = connected) {
                Text(stringResource(R.string.action_find_watch))
            }
        }
    }
}

/**
 * The faces the watch has, and which one it is wearing.
 *
 * The list is the watch's own — asked for on every connection, volunteered again whenever
 * the face is changed on the wrist, and never stored, because a remembered copy would be
 * wrong from the moment someone reached for the watch. No names and no pictures: it
 * reports six numbers and nothing else, and inventing labels would be pretending to know
 * which is which.
 *
 * Tapping one hands the watch its own list back with that one marked active, which is what
 * the official app does. An earlier version of this card sent single ids and single indices
 * to a different opcode and the watch acknowledged every one of them without moving.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WatchfaceCard(
    watchfaces: WatchfaceList?,
    install: WatchfaceInstall?,
    connected: Boolean,
    onSelect: (Int) -> Unit,
    onInstall: (Int) -> Unit,
) {
    if (watchfaces == null) {
        // On its own tab this is the whole screen, so an empty one would read as broken.
        // The list arrives moments after a connection, so the honest thing to say is that
        // it has not arrived yet.
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.watchfaces), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.watchfaces_waiting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    // Which face the next install displaces. The watch holds a fixed six, so there is no
    // "add" — something goes, and the person doing it should be the one to say what.
    var replacing by rememberSaveable { mutableIntStateOf(-1) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.watchfaces), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.watchfaces_explainer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                watchfaces.ids.indices.forEach { index ->
                    FilterChip(
                        selected = index == watchfaces.active,
                        onClick = { onSelect(index) },
                        enabled = connected,
                        label = { Text(stringResource(R.string.watchface_number, index + 1)) },
                    )
                }
            }

            HorizontalDivider()

            Text(stringResource(R.string.watchface_install), style = MaterialTheme.typography.labelLarge)
            Text(
                stringResource(R.string.watchface_install_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                watchfaces.ids.indices.forEach { index ->
                    FilterChip(
                        selected = index == replacing,
                        onClick = { replacing = if (replacing == index) -1 else index },
                        enabled = connected,
                        label = { Text(stringResource(R.string.watchface_number, index + 1)) },
                    )
                }
            }

            // Deliberately inert until a slot is chosen: a file picker that opens before
            // the question is answered invites answering it afterwards, in a hurry.
            FilledTonalButton(
                onClick = { onInstall(replacing) },
                enabled = connected && replacing >= 0 && install !is WatchfaceInstall.Sending,
            ) {
                Text(stringResource(R.string.watchface_choose_file))
            }

            when (install) {
                is WatchfaceInstall.Sending -> {
                    Text(
                        stringResource(R.string.watchface_sending, install.name, install.percent),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LinearProgressIndicator(
                        progress = { install.percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                is WatchfaceInstall.Done -> Text(
                    stringResource(R.string.watchface_sent, install.name),
                    style = MaterialTheme.typography.bodyMedium,
                )

                is WatchfaceInstall.Failed -> Text(
                    install.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )

                null -> Unit
            }
        }
    }
}

/**
 * The two halves of the app.
 *
 * Health is what the watch measured; Device is everything about the watch itself. The
 * split waited until there was more than one metric to put on the first — a tab holding a
 * single card is a worse arrangement than no tabs at all.
 */
private enum class HomeTab(@param:StringRes val labelRes: Int) {
    HEALTH(R.string.tab_health),

    // Between the two rather than after them: sleep is a measurement, and the watch tab
    // is where the settings live. The dock reads health, sleep, faces, watch.
    SLEEP(R.string.tab_sleep),

    // Its own tab rather than a card buried in the watch settings: switching and
    // installing a face is a thing people come to do, not a setting they adjust once.
    FACES(R.string.tab_faces),
    DEVICE(R.string.tab_device),
}

/** One log line, as both the screen and the clipboard render it. */
private fun ProtocolLog.Entry.render(): String = buildString {
    append(LOG_TIME.format(Instant.ofEpochMilli(atMillis)))
    append("  ")
    append(
        when (direction) {
            ProtocolLog.Direction.OUT -> "→"
            ProtocolLog.Direction.IN -> "←"
            ProtocolLog.Direction.ACK -> "✓"
            ProtocolLog.Direction.DROP -> "✕"
            ProtocolLog.Direction.NOTE -> "·"
        },
    )
    append(" ")
    append(label)
    detail?.let { append("\n        ").append(it) }
}

/** Enough to see a whole handshake, few enough to scroll past. */
private const val LOG_LINES = 60

/**
 * Shared and immutable. SimpleDateFormat is neither, and ofPattern parses its pattern on
 * every call — which the log did sixty times per redraw.
 */
private val LOG_TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
private val CLOCK_TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
private val RECORD_STAMP: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneId.systemDefault())
