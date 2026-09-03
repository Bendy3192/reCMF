/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.ui

import androidx.compose.foundation.layout.Box
import android.content.Context
import android.text.format.DateUtils
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
import androidx.compose.material3.CardColors
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.recmf.ai.AiClient
import dev.recmf.ai.AiContext
import dev.recmf.ai.AiEndpoint
import dev.recmf.data.AiSettings
import dev.recmf.health.HealthConnectSync
import dev.recmf.health.Readiness
import dev.recmf.health.Sex
import dev.recmf.health.SleepScore
import dev.recmf.health.ReadinessSignal
import dev.recmf.ui.theme.Motion
import dev.recmf.data.WatchPreferences
import java.time.Year
import dev.recmf.data.WatchSetting
import dev.recmf.protocol.BatteryStatus
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
import dev.recmf.service.AlarmMirrorProblem
import dev.recmf.service.WeatherProblem
import dev.recmf.data.CoachMessageEntity
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
    workouts: List<WorkoutRow>,
    watchfaces: WatchfaceList?,
    watchfaceInstall: WatchfaceInstall?,
    alarmMirrorProblem: AlarmMirrorProblem?,
    readiness: Readiness?,
    sleepScore: SleepScore?,
    coachMessages: List<CoachMessageEntity>,
    coachThinking: Boolean,
    coachProblem: String?,
    onCoachSend: (String) -> Unit,
    onCoachClear: () -> Unit,
    backupState: BackupState?,
    ai: AiSettings,
    aiProbe: AiClient.Answer?,
    aiModels: AiClient.Models?,
    aiInsights: Map<String, AiInsight>,
    aiAsking: Set<String>,
    onAskAboutMetric: (String, String, String, Boolean) -> Unit,
    aiDays: List<AiContext.Day>,
    onAiInsights: (Boolean) -> Unit,
    onAiCoach: (Boolean) -> Unit,
    onAiEndpoint: (String, String, AiEndpoint.Wire) -> Unit,
    onAiKey: (String?) -> Unit,
    onAiSystemPrompt: (String) -> Unit,
    onAiProbe: () -> Unit,
    onAiModels: (String) -> Unit,
    onAiWebSearch: (Boolean) -> Unit,
    onAiProfile: (AiContext.Profile) -> Unit,
    held: List<HealthConnectSync.Held>?,
    onSurveyHealthConnect: () -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onNotificationAppBlocked: (String, Boolean) -> Unit,
    onNotificationAppsBlocked: (List<String>, Boolean) -> Unit,
    isBatteryExempt: Boolean,
    onNotificationsEnabled: (Boolean) -> Unit,
    onScreenOffOnlyEnabled: (Boolean) -> Unit,
    cityLookup: CityLookup,
    onWeatherEnabled: (Boolean) -> Unit,
    onFindCity: (String) -> Unit,
    onUseMyLocation: () -> Unit,
    onAutoPlace: (Boolean) -> Unit,
    followsInBackground: Boolean,
    onGrantNotificationAccess: () -> Unit,
    onAllowBackgroundWork: () -> Unit,
    onScan: () -> Unit,
    onPair: (DiscoveredWatch) -> Unit,
    onForget: () -> Unit,
    onSyncNow: () -> Unit,
    onFindWatch: () -> Unit,
    onInstallAgps: () -> Unit,
    onGpsAlmanacAuto: (Boolean) -> Unit,
    onSelectWatchface: (Int) -> Unit,
    onInstallWatchface: (Int) -> Unit,
    updateState: UpdateState,
    onCheckForUpdate: () -> Unit,
    onInstallUpdate: (AvailableUpdate) -> Unit,
    onAutoSyncSeconds: (Int) -> Unit,
    onHealthConnectEnabled: (Boolean) -> Unit,
    onPhoneAlarmsEnabled: (Boolean) -> Unit,
) {
    // A tab for a switch nobody turned on would open onto an explanation of why it is
    // empty, and five other tabs would rather have the room. Turning the coach on is what
    // puts it there, which is also the clearest thing that switch could do.
    val tabs = remember(ai.coachEnabled) {
        HomeTab.entries.filter { it != HomeTab.COACH || ai.coachEnabled }
    }

    val pager = rememberPagerState { tabs.size }
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            WatchBar(
                state = state,
                onSyncNow = onSyncNow,
            )
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)) {
            if (state.settings.isPaired) {
                // A pager rather than a swapped body, so the tabs can be swiped as well as
                // tapped. Each page carries its own scroll, which is why the connection
                // card is repeated rather than hoisted: hoisting it would pin a card above
                // a horizontally sliding surface and the two would come apart mid-swipe.
                HorizontalPager(state = pager) { page ->
                    TabContent(
                        tab = tabs[page],
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
                        workouts = workouts,
                        watchfaces = watchfaces,
                        watchfaceInstall = watchfaceInstall,
                        alarmMirrorProblem = alarmMirrorProblem,
                        readiness = readiness,
                        sleepScore = sleepScore,
                        coachMessages = coachMessages,
                        coachThinking = coachThinking,
                        coachProblem = coachProblem,
                        onCoachSend = onCoachSend,
                        onCoachClear = onCoachClear,
                        backupState = backupState,
                        ai = ai,
                        aiProbe = aiProbe,
                        aiModels = aiModels,
                        aiInsights = aiInsights,
                        aiAsking = aiAsking,
                        onAskAboutMetric = onAskAboutMetric,
                        aiDays = aiDays,
                        onAiInsights = onAiInsights,
                        onAiCoach = onAiCoach,
                        onAiEndpoint = onAiEndpoint,
                        onAiKey = onAiKey,
                        onAiSystemPrompt = onAiSystemPrompt,
                        onAiProbe = onAiProbe,
                        onAiModels = onAiModels,
                        onAiWebSearch = onAiWebSearch,
                        onAiProfile = onAiProfile,
                        held = held,
                        onSurveyHealthConnect = onSurveyHealthConnect,
                        onExportBackup = onExportBackup,
                        onImportBackup = onImportBackup,
                        onNotificationAppBlocked = onNotificationAppBlocked,
                        onNotificationAppsBlocked = onNotificationAppsBlocked,
                        isBatteryExempt = isBatteryExempt,
                        onNotificationsEnabled = onNotificationsEnabled,
                        onScreenOffOnlyEnabled = onScreenOffOnlyEnabled,
                        cityLookup = cityLookup,
                        onWeatherEnabled = onWeatherEnabled,
                        onFindCity = onFindCity,
                        onUseMyLocation = onUseMyLocation,
                        onAutoPlace = onAutoPlace,
                        followsInBackground = followsInBackground,
                        onGrantNotificationAccess = onGrantNotificationAccess,
                        onAllowBackgroundWork = onAllowBackgroundWork,
                        onForget = onForget,
                        onFindWatch = onFindWatch,
                        onInstallAgps = onInstallAgps,
                        onGpsAlmanacAuto = onGpsAlmanacAuto,
                        onSelectWatchface = onSelectWatchface,
                        onInstallWatchface = onInstallWatchface,
                        updateState = updateState,
                        onCheckForUpdate = onCheckForUpdate,
                        onInstallUpdate = onInstallUpdate,
                        onAutoSyncSeconds = onAutoSyncSeconds,
                        onHealthConnectEnabled = onHealthConnectEnabled,
                        onPhoneAlarmsEnabled = onPhoneAlarmsEnabled,
                    )
                }

                FloatingTabDock(
                    tabs = tabs,
                    selected = pager.currentPage,
                    onSelect = { scope.launch { pager.animateScrollToPage(it) } },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        // A guard, not a measurement: the dock is as wide as its pills
                        // and one of them carries a word, so this only stops a very long
                        // word in some future language from reaching the glass.
                        .padding(horizontal = 16.dp)
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
    workouts: List<WorkoutRow>,
    watchfaces: WatchfaceList?,
    watchfaceInstall: WatchfaceInstall?,
    alarmMirrorProblem: AlarmMirrorProblem?,
    readiness: Readiness?,
    sleepScore: SleepScore?,
    coachMessages: List<CoachMessageEntity>,
    coachThinking: Boolean,
    coachProblem: String?,
    onCoachSend: (String) -> Unit,
    onCoachClear: () -> Unit,
    backupState: BackupState?,
    ai: AiSettings,
    aiProbe: AiClient.Answer?,
    aiModels: AiClient.Models?,
    aiInsights: Map<String, AiInsight>,
    aiAsking: Set<String>,
    onAskAboutMetric: (String, String, String, Boolean) -> Unit,
    aiDays: List<AiContext.Day>,
    onAiInsights: (Boolean) -> Unit,
    onAiCoach: (Boolean) -> Unit,
    onAiEndpoint: (String, String, AiEndpoint.Wire) -> Unit,
    onAiKey: (String?) -> Unit,
    onAiSystemPrompt: (String) -> Unit,
    onAiProbe: () -> Unit,
    onAiModels: (String) -> Unit,
    onAiWebSearch: (Boolean) -> Unit,
    onAiProfile: (AiContext.Profile) -> Unit,
    held: List<HealthConnectSync.Held>?,
    onSurveyHealthConnect: () -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onNotificationAppBlocked: (String, Boolean) -> Unit,
    onNotificationAppsBlocked: (List<String>, Boolean) -> Unit,
    isBatteryExempt: Boolean,
    onNotificationsEnabled: (Boolean) -> Unit,
    onScreenOffOnlyEnabled: (Boolean) -> Unit,
    cityLookup: CityLookup,
    onWeatherEnabled: (Boolean) -> Unit,
    onFindCity: (String) -> Unit,
    onUseMyLocation: () -> Unit,
    onAutoPlace: (Boolean) -> Unit,
    followsInBackground: Boolean,
    onGrantNotificationAccess: () -> Unit,
    onAllowBackgroundWork: () -> Unit,
    onForget: () -> Unit,
    onFindWatch: () -> Unit,
    onInstallAgps: () -> Unit,
    onGpsAlmanacAuto: (Boolean) -> Unit,
    onSelectWatchface: (Int) -> Unit,
    onInstallWatchface: (Int) -> Unit,
    updateState: UpdateState,
    onCheckForUpdate: () -> Unit,
    onInstallUpdate: (AvailableUpdate) -> Unit,
    onAutoSyncSeconds: (Int) -> Unit,
    onHealthConnectEnabled: (Boolean) -> Unit,
    onPhoneAlarmsEnabled: (Boolean) -> Unit,
) {
    // The one tab that is not a list of cards: a conversation needs the box to type in
    // pinned to the bottom, and a box that scrolls away with the messages is a box nobody
    // can find after the third answer.
    if (tab == HomeTab.COACH) {
        CoachScreen(
            messages = coachMessages,
            thinking = coachThinking,
            problem = coachProblem,
            ready = ai.coachEnabled && ai.usable,
            // Built here because this is where it is known what there is to ask about.
            // Offering "how did I sleep" to a phone with no night recorded buys an answer
            // that begins "there is no data for that", which is worse than one suggestion
            // fewer.
            suggestions = buildList {
                add(stringResource(R.string.coach_ask_overall))
                if (sleepScore != null) add(stringResource(R.string.coach_ask_sleep))
                if (readiness != null) add(stringResource(R.string.coach_ask_readiness))
                add(stringResource(R.string.coach_ask_change))
            },
            onSend = onCoachSend,
            onClear = onCoachClear,
        )
        return
    }

    // Which measurement is open, if any. Held here rather than in the tile so that the
    // sheet outlives the row scrolling off the screen.
    var opened by remember { mutableStateOf<TileSpec?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        // Room for the floating dock, which sits over the content rather than beside it.
        // One row tall again — icon and, on the selected tab only, its name beside it —
        // so this is back to clearing that rather than the two-line pills it grew for.
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {

        when (tab) {
            HomeTab.HEALTH -> {
                item { TodayCard(state, lastSleep, watchPreferences.stepsGoal) }

                // Under Today and above the tiles, because it summarises them: a single
                // reading of the morning, before the figures it was built from. Shown even
                // with nothing to say yet — "not enough days" tells a new wearer the thing
                // exists and is coming, where an absent card tells them nothing at all.
                item { ReadinessCard(readiness) }

                // Tiles come before the charts: a figure is what gets looked for, and the
                // shape of the day is what gets looked at once the figure has been read.
                metricTiles(state, weekly) { opened = it }

                if (charts.heartRate.size >= 2 || charts.stepsByHour.any { it > 0f } ||
                    charts.spo2.isNotEmpty()
                ) {
                    item { ChartsCard(charts) }
                }

                // Not a setting but a prompt, and it stays where it will be seen: a
                // wearer whose watch stops syncing overnight finds out here.
                if (!isBatteryExempt) {
                    item { BackgroundWorkCard(onAllowBackgroundWork) }
                }
            }

            HomeTab.WORKOUTS -> {
                if (workouts.isEmpty()) {
                    item { NoWorkoutsCard() }
                } else {
                    items(workouts, key = { it.startSeconds }) { WorkoutCard(it) }
                }
            }

            HomeTab.SLEEP -> {
                item {
                    // The night's own key, so a reading of it caches beside the tiles'
                    // rather than colliding with one.
                    val night = stringResource(R.string.metric_sleep)
                    val slept = sleepSession?.stages?.sumOf { it.duration } ?: 0

                    // Formatted here and not inside the lambdas below. `readableDuration`
                    // reads a string resource, which makes it composable, and neither a
                    // LaunchedEffect body nor a click handler is a composition.
                    val howLong = readableDuration(slept.toLong())

                    LaunchedEffect(night, howLong) {
                        if (slept > 0) onAskAboutMetric(night, howLong, SLEEP_COLUMN, false)
                    }

                    SleepCard(
                        session = sleepSession,
                        insight = aiInsights[night],
                        thinking = night in aiAsking,
                        onAskAgain = { onAskAboutMetric(night, howLong, SLEEP_COLUMN, true) },
                    )
                }

                // Under the night rather than above it: the card above is what the watch
                // reported, and this is a judgement about it. Reading the evidence first
                // is the order the two belong in.
                item { SleepScoreCard(sleepScore) }
            }

            // Drawn above, where it can have a layout of its own. Named here because a
            // when over an enum that misses a case is a warning, and warnings fail this
            // build.
            HomeTab.COACH -> Unit

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
                // Grouped, because eight unrelated cards in a row is a list you read from
                // the top rather than a screen you navigate. The headings are what let the
                // eye skip to the third of it that anyone came for.
                item { SectionHeading(R.string.section_watch) }
                item {
                    WatchSettingsCard(watchPreferences, state.connection.isUsable, onWatchPreferences)
                }
                item { FindWatchCard(state.connection.isUsable, onFindWatch) }

                item { SectionHeading(R.string.section_on_the_wrist) }
                item {
                    WeatherCard(
                        state = state,
                        lookup = cityLookup,
                        onEnabled = onWeatherEnabled,
                        onFindCity = onFindCity,
                        onUseMyLocation = onUseMyLocation,
                        onAutoPlace = onAutoPlace,
                        followsInBackground = followsInBackground,
                    )
                }
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
                item {
                    AlarmsCard(
                        alarms = watchPreferences.alarms,
                        mirroring = state.settings.phoneAlarmsEnabled,
                        problem = alarmMirrorProblem,
                        onMirroring = onPhoneAlarmsEnabled,
                        onChange = onWatchPreferences,
                    )
                }

                item { SectionHeading(R.string.section_data) }
                item { HealthConnectCard(state, healthConnectAvailability, onHealthConnectEnabled) }
                item { HealthConnectSurveyCard(held, onSurveyHealthConnect) }
                item { AutoSyncCard(state.settings.autoSyncSeconds, onAutoSyncSeconds) }
                item {
                    GpsDataCard(
                        connected = state.connection.isUsable,
                        auto = state.settings.gpsAlmanacAuto,
                        sentAtMillis = state.settings.almanacSentAtMillis,
                        onAuto = onGpsAlmanacAuto,
                        onInstallAgps = onInstallAgps,
                    )
                }
                item { SectionHeading(R.string.section_app) }
                item { UpdateCard(updateState, onCheckForUpdate, onInstallUpdate) }
                item {
                    AiCard(
                        settings = ai,
                        probe = aiProbe,
                        models = aiModels,
                        days = aiDays,
                        onInsights = onAiInsights,
                        onCoach = onAiCoach,
                        onEndpoint = onAiEndpoint,
                        onKey = onAiKey,
                        onPrompt = onAiSystemPrompt,
                        onProbe = onAiProbe,
                        onModels = onAiModels,
                        onWebSearch = onAiWebSearch,
                        onProfile = onAiProfile,
                    )
                }
                item { BackupCard(backupState, onExportBackup, onImportBackup) }
                item { ProtocolLogCard() }
                item { PairedWatchCard(state, onForget) }
            }
        }
    }

    opened?.let { tile ->
        val context = LocalContext.current
        val name = stringResource(tile.label)
        val reading = tile.value(context)

        // Asked on opening, and again whenever the sheet is reopened onto a metric whose
        // answer has gone stale. The view model decides whether that costs a request; from
        // here it is simply "show me what you have on this".
        LaunchedEffect(name, reading) { onAskAboutMetric(name, reading, tile.column, false) }

        MetricDetailSheet(
            icon = tile.icon,
            label = name,
            value = reading,
            explains = stringResource(tile.explains),
            insight = aiInsights[name],
            thinking = name in aiAsking,
            onAskAgain = { onAskAboutMetric(name, reading, tile.column, true) },
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
    tabs: List<HomeTab>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        // A rounded rectangle rather than a stadium. A stadium curves inward at its ends,
        // and the first and last pills — now rectangles themselves, being two lines tall —
        // pushed their corners out through that curve. Matching shapes nest; a capsule
        // holding rectangles does not.
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            tabs.forEachIndexed { index, entry ->
                val isSelected = index == selected
                val background by animateColorAsState(
                    targetValue = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.Transparent
                    },
                    animationSpec = Motion.effects(),
                    label = "dock background",
                )
                val content by animateColorAsState(
                    targetValue = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    animationSpec = Motion.effects(),
                    label = "dock label",
                )

                // A spring rather than a curve, and one loose enough to overshoot.
                //
                // This is most of what the newer Material feels like, and it needs none of
                // the newer Material: a tab that springs past its size and settles reads as
                // something that answered, where the same movement on a fixed curve reads
                // as a screen redrawing. It is small on purpose — a bounce you can name is
                // a bounce that will annoy by the hundredth tap.
                // A tap on a dock icon is the shortest travel on the screen, so the
                // fast spatial spring: it is over before the finger lifts, and still
                // overshoots enough to be felt.
                val scale by animateFloatAsState(
                    targetValue = if (isSelected) 1f else 0.92f,
                    animationSpec = Motion.spatialFast(),
                    label = "dock scale",
                )

                Box(
                    modifier = Modifier
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                        // Slightly tighter than the dock's own corners, so the two read
                        // as one shape inside another rather than as two competing ones.
                        .clip(RoundedCornerShape(20.dp))
                        .background(background)
                        .clickable { onSelect(index) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    // The word appears on the tab you are on, and nowhere else.
                    //
                    // Five tabs cannot all carry their names on a narrow phone. Sized to
                    // the longest word they ran past the edge of the screen; given an
                    // equal share each they fitted and then cut the words in half, and
                    // "Цифербл…" is not a word. Naming one at a time costs nothing —
                    // the tab you are on is the one whose name you least need — and it
                    // holds for any language and any number of tabs, which neither of
                    // the other two did.
                    //
                    // Beside the icon rather than under it, so the pill grows sideways as
                    // the selection moves. Stacked, the dock would change height whenever
                    // a label appeared.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(entry.iconRes),
                            contentDescription = stringResource(entry.labelRes),
                            tint = content,
                            modifier = Modifier.size(20.dp),
                        )

                        if (isSelected) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(entry.labelRes),
                                color = content,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A heading over a run of cards.
 *
 * Deliberately not a card itself: it is the label on a drawer, not another thing in it.
 */
@Composable
private fun SectionHeading(@StringRes title: Int) {
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}

/**
 * What is paired, and how to stop being paired with it.
 *
 * Unpairing used to be a button beside "sync now" at the top of all five tabs, and it
 * acted the moment it was touched: the service stopped, the watchdog was cancelled and
 * the pairing was gone. Recovering meant pairing again, granting again, and sending the
 * watch a fresh almanac. That is not a thing to have within a thumb's slip of the button
 * people press most.
 *
 * So it lives at the bottom of the last section of the last tab, and it asks first.
 */
@Composable
private fun PairedWatchCard(state: HomeUiState, onForget: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = UtilityCardShape,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CardTitle(R.drawable.ic_watch, R.string.paired_watch)

            state.settings.name?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            state.watch.firmware?.let {
                Text(
                    stringResource(R.string.firmware_version, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()

            TextButton(onClick = { confirming = true }) {
                Text(
                    stringResource(R.string.action_forget),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.forget_title)) },
            text = { Text(stringResource(R.string.forget_explainer)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        onForget()
                    },
                ) {
                    Text(
                        stringResource(R.string.action_forget),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/**
 * The watch, along the top of every screen.
 *
 * This used to be a card at the top of each of the five tabs — the same name, the same
 * state, the same battery, the same two buttons, five times over, taking a third of the
 * first screenful everywhere. It was repeated rather than hoisted because a card above a
 * horizontally sliding pager comes apart mid-swipe; the bar does not, because it is not
 * in the pager at all.
 *
 * It is also the app's one piece of live presence. A wearer glancing at any screen should
 * be able to tell whether the thing on their wrist is talking to the phone, and how much
 * of its day is left, without going and looking for a card that says so.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WatchBar(state: HomeUiState, onSyncNow: () -> Unit) {
    Column {
        TopAppBar(
            title = {
                Column {
                    Text(
                        text = state.settings.name ?: stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                    )
                    Text(
                        text = stringResource(state.connection.labelRes()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            },
            actions = {
                state.watch.battery?.let { BatteryRing(it) }

                if (state.settings.isPaired) {
                    IconButton(onClick = onSyncNow, enabled = state.connection.isUsable) {
                        Icon(
                            painter = painterResource(R.drawable.ic_ui_sync),
                            contentDescription = stringResource(R.string.action_sync_now),
                        )
                    }
                }
            },
        )

        // Only while something is genuinely in flight; a bar that is always animating is
        // a bar people stop seeing. Under the title rather than inside a card, so it
        // reads as the connection working rather than as one screen loading.
        if (state.connection.isSettling()) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

/**
 * The watch's battery as a ring rather than a number.
 *
 * A percentage is read; a ring is seen. This sits in the bar on every screen, where being
 * *glanceable* is the whole of its job — the figure is still there in the middle for
 * anyone who wants it.
 */
@Composable
private fun BatteryRing(battery: BatteryStatus) {
    // Animated, so a battery that has moved since the last sync arrives as a movement
    // rather than as a different number that was always there.
    // The ring sweeps most of a circle on the first reading after a connect, which is the
    // longest travel anything here makes — the slow spatial spring, and a spring rather
    // than a fixed seven hundred milliseconds so that a small correction takes a moment
    // and a full sweep takes its time.
    val level by animateFloatAsState(
        targetValue = battery.levelPercent / 100f,
        animationSpec = Motion.spatialSlow(),
        label = "battery",
    )

    val low = battery.levelPercent <= LOW_BATTERY_PERCENT && !battery.isCharging
    val ring = if (low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest

    // Said in words for anyone who cannot see the ring. Without this a screen reader
    // reads the figure in the middle as a bare number with nothing to say what it counts,
    // which is worse than the card it replaced — that at least said "battery".
    val spoken = if (battery.isCharging) {
        stringResource(R.string.battery_charging, battery.levelPercent)
    } else {
        stringResource(R.string.battery_level, battery.levelPercent)
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(end = 4.dp)
            .clearAndSetSemantics { contentDescription = spoken },
    ) {
        Canvas(Modifier.size(34.dp)) {
            val stroke = 3.dp.toPx()
            val inset = stroke / 2
            drawArc(
                color = track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = ring,
                // From the top, like every other dial anyone has ever read.
                startAngle = -90f,
                sweepAngle = 360f * level.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }

        Text(
            text = "${battery.levelPercent}",
            style = MaterialTheme.typography.labelSmall,
            color = if (low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Below this the ring turns red, which is the only thing it says on its own. */
private const val LOW_BATTERY_PERCENT = 20

/**
 * Three corner radii, by what a card is for.
 *
 * A screen of identically-rounded cards is a screen you read from the top down, because
 * nothing in it claims to matter more than anything else. Giving the shapes a hierarchy
 * costs nothing and lets the eye skip: the wide soft ones are the readings, the middling
 * ones are the things with a switch, the tight ones are plumbing.
 */
private val HeroCardShape = RoundedCornerShape(28.dp)
private val FeatureCardShape = RoundedCornerShape(20.dp)
private val UtilityCardShape = RoundedCornerShape(12.dp)

/**
 * A card's colour by whether the thing it controls is switched on.
 *
 * The watch tab is a column of settings, most of them off most of the time, and in one
 * flat grey the ones that are actually doing something are indistinguishable from the ones
 * that are not. A tonal container says "this is running" without a word, and matches the
 * tiles on the health screen, which have been coloured this way all along.
 */
@Composable
private fun featureCardColors(active: Boolean): CardColors = if (active) {
    CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    )
} else {
    CardDefaults.cardColors()
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = HeroCardShape,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardTitle(R.drawable.ic_metric_steps, R.string.today)

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
                    R.drawable.ic_metric_heart, R.string.metric_heart_rate, "hr_avg",
                    R.string.explain_heart_rate, weekly.heartRate,
                    format = { context, bpm ->
                        context.getString(R.string.value_bpm, bpm.roundToInt())
                    },
                ) { it.getString(R.string.value_bpm, latest.bpm) },
            )
        }
        state.restingHeartRate?.let { bpm ->
            add(
                TileSpec(
                    R.drawable.ic_metric_heart, R.string.metric_resting_heart_rate, "resting",
                    R.string.explain_resting_heart_rate, weekly.restingHeartRate,
                    format = { context, beats ->
                        context.getString(R.string.value_bpm, beats.roundToInt())
                    },
                ) { it.getString(R.string.value_bpm, bpm) },
            )
        }
        state.spo2?.let { percent ->
            add(
                TileSpec(
                    R.drawable.ic_metric_oxygen, R.string.metric_spo2, "spo2_pct",
                    R.string.explain_spo2, weekly.spo2,
                    format = { context, share ->
                        context.getString(R.string.value_percent, share.roundToInt())
                    },
                ) { it.getString(R.string.value_percent, percent) },
            )
        }
        state.stress?.let { level ->
            add(
                TileSpec(
                    R.drawable.ic_metric_stress, R.string.metric_stress, "stress",
                    R.string.explain_stress, weekly.stress,
                ) {
                    level.toString()
                },
            )
        }
        if (state.today.distanceMeters > 0 || weekly.distanceMeters.hasReadings()) {
            add(
                TileSpec(
                    R.drawable.ic_metric_distance, R.string.metric_distance, "distance_m",
                    R.string.explain_distance, weekly.distanceMeters,
                    format = { context, metres -> context.readableDistance(metres.roundToInt()) },
                ) { it.readableDistance(state.today.distanceMeters) },
            )
        }
        if (state.today.calories > 0 || weekly.calories.hasReadings()) {
            add(
                TileSpec(
                    R.drawable.ic_metric_calories, R.string.metric_calories, "kcal",
                    R.string.explain_calories, weekly.calories,
                    format = { context, kcal ->
                        context.getString(R.string.value_kcal, kcal.roundToInt())
                    },
                ) { it.getString(R.string.value_kcal, state.today.calories) },
            )
        }
        if (state.today.climbs > 0 || weekly.climbs.hasReadings()) {
            add(
                TileSpec(
                    R.drawable.ic_metric_climbs, R.string.metric_climbs, "climbs",
                    R.string.explain_climbs, weekly.climbs,
                ) {
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
 * How the morning compares with the mornings behind it.
 *
 * A summary rather than a measurement, which is why it sits under Today and above the
 * tiles: it is built out of the figures below it and is the one thing on the screen that
 * answers "so how am I" rather than "what does this read".
 *
 * The number is given a sentence beside it because 68 on its own is no better than
 * "Stress 50" was. And the parts are listed, each with today against its own usual, so the
 * score can be disagreed with — a wearer who slept badly on purpose knows why it dropped
 * and does not need the app to be mysterious about it.
 */
@Composable
private fun ReadinessCard(readiness: Readiness?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = HeroCardShape,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardTitle(R.drawable.ic_ui_readiness, R.string.readiness)

            if (readiness == null) {
                Text(
                    stringResource(R.string.readiness_thin),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    readiness.score.toString(),
                    style = MaterialTheme.typography.displaySmall,
                )
                Text(
                    stringResource(readiness.score.standing()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }

            HorizontalDivider()

            readiness.parts.forEach { part ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        stringResource(part.signal.labelRes()),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        stringResource(
                            R.string.readiness_against,
                            part.signal.write(part.today),
                            part.signal.write(part.usual),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (part.standing < -PART_WORTH_FLAGGING) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            HorizontalDivider()

            Text(
                stringResource(R.string.readiness_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Said on the card rather than buried in a help screen, because the comparison
            // with Whoop is the first thing anyone who knows those apps will make — and on
            // a phone that has one of those devices, the comparison is with a number
            // sitting in another app right now. Which sentence is true is read off the
            // parts the score actually used rather than off the settings: a permission
            // granted is not a reading taken.
            val borrowed = readiness.parts.filterNot { it.fromWatch }

            Text(
                if (borrowed.isEmpty()) {
                    stringResource(R.string.readiness_no_hrv)
                } else {
                    // Mapped and then joined, rather than joined with a transform.
                    // joinToString takes its transform as a nullable function type, which
                    // cannot be inlined, so the lambda is not a composition and cannot
                    // look up a string. `map` can.
                    stringResource(
                        R.string.readiness_elsewhere,
                        borrowed.map { stringResource(it.signal.labelRes()) }.joinToString(", "),
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The two counts a backup reports, each as its own plural.
 *
 * Separate resources because a plural carries one quantity, and the sentence needs two.
 * English hardly notices; Russian has three forms for a counted noun and gets them wrong
 * without this.
 */
@Composable
private fun countOfSettings(count: Int): String =
    pluralStringResource(R.plurals.backup_settings_count, count, count)

@Composable
private fun countOfRows(count: Int): String =
    pluralStringResource(R.plurals.backup_rows_count, count, count)

/** Far enough below usual to be worth colouring. Below this, a signal is just today. */
private const val PART_WORTH_FLAGGING = 0.25f

/** The score in words, because a bare number is the thing this screen exists to fix. */
@StringRes
private fun Int.standing(): Int = when {
    this >= 75 -> R.string.readiness_well_above
    this >= 58 -> R.string.readiness_above
    this <= 25 -> R.string.readiness_well_below
    this <= 42 -> R.string.readiness_below
    else -> R.string.readiness_usual
}

@StringRes
private fun ReadinessSignal.labelRes(): Int = when (this) {
    ReadinessSignal.HEART_RATE_VARIABILITY -> R.string.readiness_part_heart_rate_variability
    ReadinessSignal.SLEEP_DURATION -> R.string.readiness_part_sleep_duration
    ReadinessSignal.SLEEP_QUALITY -> R.string.readiness_part_sleep_quality
    ReadinessSignal.RESTING_HEART_RATE -> R.string.readiness_part_resting_heart_rate
    ReadinessSignal.STRESS -> R.string.readiness_part_stress
}

/** Each signal in the unit it was measured in, since they share a row but not a scale. */
@Composable
private fun ReadinessSignal.write(value: Float): String = when (this) {
    // Milliseconds, which is the unit RMSSD is always quoted in.
    ReadinessSignal.HEART_RATE_VARIABILITY ->
        stringResource(R.string.readiness_millis, value.roundToInt())
    ReadinessSignal.SLEEP_DURATION ->
        stringResource(R.string.readiness_minutes, value.roundToInt())
    ReadinessSignal.SLEEP_QUALITY ->
        stringResource(R.string.readiness_share, (value * 100).roundToInt())
    ReadinessSignal.RESTING_HEART_RATE ->
        stringResource(R.string.value_bpm, value.roundToInt())
    ReadinessSignal.STRESS -> value.roundToInt().toString()
}

/**
 * Everything reCMF holds, in a file the wearer keeps.
 *
 * The card says twice what does not travel, because it is the question somebody restoring
 * onto a new phone will actually have: the pairing key stays in the Android keystore,
 * which does not leave the device, so a new phone pairs again and finds everything else
 * already set up. Hiding that would make the re-pair look like the restore having failed.
 */
@Composable
private fun BackupCard(
    state: BackupState?,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = UtilityCardShape,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardTitle(R.drawable.ic_ui_download, R.string.backup)

            Text(
                stringResource(R.string.backup_explainer),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.backup_no_secrets),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = onExport,
                    enabled = state != BackupState.Working,
                ) { Text(stringResource(R.string.action_backup_export)) }

                OutlinedButton(
                    onClick = onImport,
                    enabled = state != BackupState.Working,
                ) { Text(stringResource(R.string.action_backup_import)) }
            }

            state?.let {
                Text(
                    when (it) {
                        BackupState.Working -> stringResource(R.string.backup_working)
                        is BackupState.Exported -> stringResource(
                            R.string.backup_exported,
                            countOfSettings(it.settings),
                            countOfRows(it.rows),
                        )
                        is BackupState.Imported -> stringResource(
                            R.string.backup_imported,
                            countOfSettings(it.settings),
                            countOfRows(it.rows),
                        )
                        BackupState.NotOurs -> stringResource(R.string.backup_not_ours)
                        is BackupState.Failed ->
                            stringResource(R.string.backup_failed, it.reason)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it is BackupState.Failed || it is BackupState.NotOurs) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/**
 * The assistant: two opt-ins, where to ask, and exactly what would be sent.
 *
 * Everything here is arranged around one claim the app has made since before an assistant
 * existed — that nothing about the wearer leaves the phone. That claim is now conditional,
 * and the card's job is to make the condition legible rather than to bury it in a switch.
 *
 * Hence two switches for what could have been one. Figures without a name on them and a
 * profile that describes a person are different things to send, and somebody may want the
 * first and not the second.
 *
 * Hence, too, the preview being the message itself. It is not a summary of what is sent or
 * a sample of the shape — it is the string, built by the same code that builds the real
 * one. Anything less and the reassurance would be worth less than nothing.
 */
@Composable
private fun AiCard(
    settings: AiSettings,
    probe: AiClient.Answer?,
    models: AiClient.Models?,
    days: List<AiContext.Day>,
    onInsights: (Boolean) -> Unit,
    onCoach: (Boolean) -> Unit,
    onEndpoint: (String, String, AiEndpoint.Wire) -> Unit,
    onKey: (String?) -> Unit,
    onPrompt: (String) -> Unit,
    onProbe: () -> Unit,
    onModels: (String) -> Unit,
    onWebSearch: (Boolean) -> Unit,
    onProfile: (AiContext.Profile) -> Unit,
) {
    var showing by rememberSaveable { mutableStateOf(false) }
    var editingPrompt by rememberSaveable { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = FeatureCardShape,
        colors = featureCardColors(settings.insightsEnabled || settings.coachEnabled),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardTitle(R.drawable.ic_ui_charts, R.string.ai)

            Text(stringResource(R.string.ai_explainer), style = MaterialTheme.typography.bodyMedium)

            HorizontalDivider()

            SettingSwitch(
                label = stringResource(R.string.ai_insights),
                checked = settings.insightsEnabled,
                onCheckedChange = onInsights,
            )
            Text(
                stringResource(R.string.ai_insights_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SettingSwitch(
                label = stringResource(R.string.ai_coach),
                checked = settings.coachEnabled,
                onCheckedChange = onCoach,
            )
            Text(
                stringResource(R.string.ai_coach_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (settings.coachEnabled) {
                AiProfileFields(settings.profile, onProfile)
            }

            HorizontalDivider()

            AiEndpointFields(settings, models, onEndpoint, onModels, onWebSearch)
            AiKeyField(settings, onKey)
            AiPromptField(settings, editingPrompt, { editingPrompt = it }, onPrompt)

            HorizontalDivider()

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { showing = !showing }) {
                    Text(
                        stringResource(
                            if (showing) R.string.action_ai_hide_preview else R.string.action_ai_preview,
                        ),
                    )
                }
                OutlinedButton(onClick = onProbe) {
                    Text(stringResource(R.string.action_ai_probe))
                }
            }

            probe?.let { AiProbeResult(it) }

            if (showing) {
                Text(
                    stringResource(R.string.ai_preview_explainer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Monospaced and scrolling sideways: the figures are a table, and a table
                // reflowed to the card's width stops being one.
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        // The same call the request itself makes, on the same days, and
                        // with the profile exactly when a real request would carry it. A
                        // preview missing a section the request sends would be worse than
                        // no preview at all.
                        AiContext.user(
                            question = PREVIEW_QUESTION,
                            days = days,
                            about = if (settings.coachEnabled) {
                                AiContext.about(settings.profile, Year.now().value)
                            } else {
                                ""
                            },
                        ),
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

/** Stands in for a real question, so the preview has something to be a preview of. */
private const val PREVIEW_QUESTION = "How am I doing?"

@Composable
private fun AiEndpointFields(
    settings: AiSettings,
    models: AiClient.Models?,
    onEndpoint: (String, String, AiEndpoint.Wire) -> Unit,
    onModels: (String) -> Unit,
    onWebSearch: (Boolean) -> Unit,
) {
    // Held locally while being typed and pushed on the way out, so a store write does not
    // happen on every keystroke and the cursor does not jump.
    var url by rememberSaveable(settings.baseUrl) { mutableStateOf(settings.baseUrl) }
    var model by rememberSaveable(settings.model) { mutableStateOf(settings.model) }
    var wire by rememberSaveable(settings.wire) { mutableStateOf(settings.wire) }

    Text(
        stringResource(R.string.ai_endpoint),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        label = { Text(stringResource(R.string.ai_base_url)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = model,
        onValueChange = { model = it },
        label = { Text(stringResource(R.string.ai_model)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { onModels(url) }) {
            Text(stringResource(R.string.action_ai_models))
        }
        if (url != settings.baseUrl || model != settings.model || wire != settings.wire) {
            FilledTonalButton(onClick = { onEndpoint(url, model, wire) }) {
                Text(stringResource(R.string.action_save))
            }
        }
    }

    models?.let { AiModelList(it) { picked -> model = picked } }

    Text(
        stringResource(R.string.ai_wire),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AiEndpoint.Wire.entries.forEach { option ->
            FilterChip(
                selected = wire == option,
                onClick = { wire = option },
                label = {
                    Text(
                        stringResource(
                            when (option) {
                                AiEndpoint.Wire.CHAT -> R.string.ai_wire_chat
                                AiEndpoint.Wire.RESPONSES -> R.string.ai_wire_responses
                            },
                        ),
                    )
                },
            )
        }
    }
    Text(
        stringResource(R.string.ai_wire_explainer),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    SettingSwitch(
        label = stringResource(R.string.ai_web_search),
        checked = settings.webSearch,
        onCheckedChange = onWebSearch,
    )
    Text(
        stringResource(R.string.ai_web_search_explainer),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Text(
        stringResource(R.string.ai_shape),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * What the provider said it has, or why it said nothing.
 *
 * A list is a convenience and never a gate: providers that serve one save somebody a trip
 * to the documentation, and the ones that do not get a sentence saying so while the text
 * field carries on working. Perplexity was written up here as one of the latter, on the
 * strength of a forum thread, and turned out to serve a perfectly good list.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiModelList(models: AiClient.Models, onPick: (String) -> Unit) {
    when (models) {
        AiClient.Models.Asking -> Text(
            stringResource(R.string.ai_models_asking),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        AiClient.Models.NoList -> Text(
            stringResource(R.string.ai_models_none),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        is AiClient.Models.Failed -> Text(
            stringResource(R.string.ai_models_failed, models.why),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )

        is AiClient.Models.Listed -> FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            models.ids.forEach { id ->
                SuggestionChip(onClick = { onPick(id) }, label = { Text(id) })
            }
        }
    }
}

@Composable
private fun AiKeyField(settings: AiSettings, onKey: (String?) -> Unit) {
    // Never shown back, not even masked. A key that can be read off the screen is a key
    // that can be read off a screenshot, and nothing here needs to display it — the only
    // question worth answering is whether there is one.
    var typed by rememberSaveable { mutableStateOf("") }
    val stored = !settings.key.isNullOrBlank()

    Text(
        stringResource(if (stored) R.string.ai_key_set else R.string.ai_key_missing),
        style = MaterialTheme.typography.bodySmall,
        color = if (stored) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.error
        },
    )

    OutlinedTextField(
        value = typed,
        onValueChange = { typed = it },
        label = { Text(stringResource(R.string.ai_key)) },
        placeholder = { Text(stringResource(R.string.ai_key_hint)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (typed.isNotBlank()) {
            FilledTonalButton(
                onClick = {
                    onKey(typed)
                    typed = ""
                },
            ) { Text(stringResource(R.string.action_save)) }
        }
        if (stored) {
            OutlinedButton(onClick = { onKey(null) }) {
                Text(stringResource(R.string.action_ai_key_forget))
            }
        }
    }
}

@Composable
private fun AiPromptField(
    settings: AiSettings,
    editing: Boolean,
    onEditing: (Boolean) -> Unit,
    onPrompt: (String) -> Unit,
) {
    val inUse = settings.systemPrompt.ifBlank { AiContext.DEFAULT_SYSTEM_PROMPT }
    var draft by rememberSaveable(inUse) { mutableStateOf(inUse) }

    Text(
        stringResource(R.string.ai_prompt),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        stringResource(R.string.ai_prompt_explainer),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (!editing) {
        OutlinedButton(onClick = { onEditing(true) }) {
            Text(stringResource(R.string.action_edit))
        }
        return
    }

    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        modifier = Modifier.fillMaxWidth(),
        minLines = 6,
        textStyle = MaterialTheme.typography.bodySmall,
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(
            onClick = {
                // A draft nobody changed is stored as nothing at all. The box has to be
                // filled with the default to be editable, and saving that text verbatim
                // would freeze this version of it: every later correction to the standing
                // instructions would then reach new installs and nobody who had ever
                // opened this screen. One of those corrections was the sentence about
                // variability, and the model went on insisting there was none.
                onPrompt(draft.takeIf { it.trim() != AiContext.DEFAULT_SYSTEM_PROMPT.trim() } ?: "")
                onEditing(false)
            },
        ) { Text(stringResource(R.string.action_save)) }

        OutlinedButton(onClick = { draft = AiContext.DEFAULT_SYSTEM_PROMPT }) {
            Text(stringResource(R.string.action_ai_prompt_reset))
        }
    }
}

/** What came back from a connection test, in the words the card has for it. */
@Composable
private fun AiProbeResult(answer: AiClient.Answer) {
    val bad = answer !is AiClient.Answer.Said

    Text(
        when (answer) {
            is AiClient.Answer.Said -> buildString {
                append(stringResource(R.string.ai_probe_said, answer.text))
                // Shown when they come, and silent when they do not — which is most of the
                // time for a third-party model, and is not a fault worth a message.
                if (answer.sources.isNotEmpty()) {
                    append("\n")
                    append(stringResource(R.string.ai_sources, answer.sources.joinToString(", ")))
                }
            }
            is AiClient.Answer.Refused -> answer.reason
                ?.let { stringResource(R.string.ai_probe_refused, answer.code, it) }
                ?: stringResource(R.string.ai_probe_refused_bare, answer.code)
            is AiClient.Answer.Unreachable ->
                stringResource(R.string.ai_probe_unreachable, answer.why)
            AiClient.Answer.Unreadable -> stringResource(R.string.ai_probe_unreadable)
            AiClient.Answer.NotConfigured -> stringResource(R.string.ai_probe_unconfigured)
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The profile, shown only while the coach is on.
 *
 * Hidden rather than greyed out when it is off, because a form for a switched-off feature
 * is a screen full of questions nobody has a reason to answer. Turning the coach on is
 * what makes them worth asking.
 *
 * Numbers are held as text while they are being typed. A field that turns "17" into 17 the
 * moment the first digit lands cannot be typed into at all, and one that reads a half-typed
 * year as an age is worse than one that waits.
 */
@Composable
internal fun AiProfileFields(saved: AiContext.Profile, onProfile: (AiContext.Profile) -> Unit) {

    var name by rememberSaveable(saved.name) { mutableStateOf(saved.name) }
    var born by rememberSaveable(saved.birthYear) {
        mutableStateOf(saved.birthYear.takeIf { it > 0 }?.toString().orEmpty())
    }
    var height by rememberSaveable(saved.heightCm) {
        mutableStateOf(saved.heightCm.takeIf { it > 0 }?.toString().orEmpty())
    }
    var weight by rememberSaveable(saved.weightKg) {
        mutableStateOf(saved.weightKg.takeIf { it > 0 }?.toString().orEmpty())
    }
    var notes by rememberSaveable(saved.notes) { mutableStateOf(saved.notes) }

    // Held by name rather than by the enum, which is not a type rememberSaveable knows.
    var sex by rememberSaveable(saved.sex) { mutableStateOf(saved.sex?.name.orEmpty()) }

    Text(
        stringResource(R.string.ai_profile),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        stringResource(R.string.ai_profile_explainer),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text(stringResource(R.string.ai_profile_name)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(born, { born = it }, R.string.ai_profile_born, Modifier.weight(1.2f))
        NumberField(height, { height = it }, R.string.ai_profile_height, Modifier.weight(1f))
        NumberField(weight, { weight = it }, R.string.ai_profile_weight, Modifier.weight(1f))
    }

    // Three choices, and the third is a real answer rather than a way out of the question.
    // Every published resting-energy equation needs this one and none of them can be told
    // it from a name, so unsaid means the figure is shown as the span between both
    // coefficients — which is what it honestly is.
    Text(
        stringResource(R.string.ai_profile_sex),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SEX_CHOICES.forEach { (value, label) ->
            FilterChip(
                selected = sex == value,
                onClick = { sex = value },
                label = { Text(stringResource(label)) },
            )
        }
    }

    OutlinedTextField(
        value = notes,
        onValueChange = { notes = it },
        label = { Text(stringResource(R.string.ai_profile_notes)) },
        placeholder = { Text(stringResource(R.string.ai_profile_notes_hint)) },
        minLines = 3,
        modifier = Modifier.fillMaxWidth(),
    )

    val edited = AiContext.Profile(
        name = name,
        birthYear = born.toIntOrNull() ?: 0,
        heightCm = height.toIntOrNull() ?: 0,
        weightKg = weight.toIntOrNull() ?: 0,
        sex = Sex.entries.firstOrNull { it.name == sex },
        notes = notes,
    )

    if (edited != saved) {
        FilledTonalButton(onClick = { onProfile(edited) }) {
            Text(stringResource(R.string.action_save))
        }
    }
}

/** A field that takes digits and nothing else, and stays a string until it is read. */
@Composable
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    @StringRes label: Int,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        // Filtered rather than validated: a letter typed into a year should simply not
        // appear, which is quieter than a field that turns red to explain itself.
        onValueChange = { typed -> onValueChange(typed.filter { it.isDigit() }.take(4)) },
        label = { Text(stringResource(label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

/**
 * What Health Connect is actually holding, and which app put it there.
 *
 * A diagnostic rather than a feature, and it exists because the alternative was guessing.
 * Whether a second wearable on this phone publishes heart-rate variability — the one input
 * every readiness score leans on and the one this watch cannot give — is not something
 * documentation settles. Looking does.
 *
 * The writing app is named, not just a count, because "there is heart rate here" and "there
 * is heart rate here that reCMF did not write" are different facts, and only the second one
 * says anything about a second source.
 */
@Composable
private fun HealthConnectSurveyCard(held: List<HealthConnectSync.Held>?, onLook: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = UtilityCardShape,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardTitle(R.drawable.ic_ui_list, R.string.hc_survey)

            Text(
                stringResource(R.string.hc_survey_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedButton(onClick = onLook) {
                Text(stringResource(R.string.action_hc_survey))
            }

            held?.let { rows ->
                if (rows.isEmpty()) {
                    Text(
                        stringResource(R.string.hc_survey_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    return@let
                }

                HorizontalDivider()

                rows.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            row.label,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            when (row.state) {
                                HealthConnectSync.Held.State.PRESENT -> buildString {
                                    append(
                                        stringResource(
                                            R.string.hc_survey_present,
                                            // A page, not a total: "1000" out of Health
                                            // Connect means "at least", and printing it
                                            // bare would be a precise-looking number that
                                            // is not one.
                                            if (row.capped) {
                                                "${row.count}+"
                                            } else {
                                                row.count.toString()
                                            },
                                        ),
                                    )

                                    // A line each, with its own last write. One shared
                                    // time would answer for whichever app wrote last and
                                    // say nothing about the other, which is the one the
                                    // question is usually about.
                                    row.writtenBy.forEach { writer ->
                                        append("\n")
                                        append(
                                            stringResource(
                                                R.string.hc_survey_writer,
                                                writer.app,
                                                SURVEY_CLOCK.format(
                                                    Instant.ofEpochMilli(writer.atMillis),
                                                ),
                                            ),
                                        )
                                    }
                                }
                                HealthConnectSync.Held.State.EMPTY ->
                                    stringResource(R.string.hc_survey_empty)
                                HealthConnectSync.Held.State.NOT_PERMITTED ->
                                    stringResource(R.string.hc_survey_not_permitted)
                                HealthConnectSync.Held.State.REFUSED ->
                                    stringResource(R.string.hc_survey_refused)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            // Only a refusal is coloured. An empty row is an answer, and a
                            // screen of red for "nothing there yet" would say otherwise.
                            color = if (row.state == HealthConnectSync.Held.State.REFUSED) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.weight(1.4f),
                        )
                    }
                }
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
    /**
     * The heading this metric has in the table the assistant is sent.
     *
     * Carried on the tile because only the tile knows: the label is translated and the
     * headings are not, so nothing downstream can match one to the other. Asked about
     * "Пульс" with no column named, the assistant matched it to the resting pulse, which
     * is a different measurement, and spent a paragraph reconciling two numbers that were
     * never the same one.
     */
    val column: String,
    // What the number is, for the sheet that opens on a tap. Required rather than
    // defaulted: a metric nobody could write a sentence about should not be on the screen.
    @param:StringRes val explains: Int,
    val week: List<DayValue> = emptyList(),
    // Ahead of [value] so the trailing lambda at every call site still binds to that one.
    // A metric whose unit is worth saying overrides it; a plain count does not need to.
    val format: (Context, Float) -> String = { _, number -> number.roundToInt().toString() },
    val value: (Context) -> String,
)

/** What the night is headed in the assistant's table. The sleep card is not a tile. */
private const val SLEEP_COLUMN = "sleep_min"

/**
 * The three answers to the coefficient question, in the order they are offered.
 *
 * "Not saying" last and stated plainly, because it is the default and has to read as a
 * choice rather than as an unfilled field.
 */
private val SEX_CHOICES: List<Pair<String, Int>> = listOf(
    Sex.FEMALE.name to R.string.ai_profile_sex_female,
    Sex.MALE.name to R.string.ai_profile_sex_male,
    "" to R.string.ai_profile_sex_unsaid,
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = HeroCardShape,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            CardTitle(R.drawable.ic_ui_charts, R.string.charts_today)

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

                // Resolved here rather than inside the chart: the label is written while
                // the canvas is being drawn, which is past the point resources can be read.
                val percent = stringResource(R.string.chart_percent)

                // The range used to be printed beside the heading. It says the same thing
                // the axis now says, in the same two numbers, and only the axis says which
                // row is which — so it moved to where it is still needed, which is the
                // screen reader: a canvas of dots is otherwise silent.
                val spoken = stringResource(R.string.chart_range_percent, low, high)

                ChartSection(stringResource(R.string.chart_spo2)) {
                    // Four percent is the narrowest scale worth the full height: a day
                    // that never leaves 97-99 still shows which readings were the low
                    // ones. A fixed 90-100 was tried and drew that day as a flat row.
                    DotChart(
                        charts.spo2,
                        MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.clearAndSetSemantics { contentDescription = spoken },
                        minimumSpan = 4f,
                        label = { percent.format(it.toInt()) },
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = UtilityCardShape,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardTitle(R.drawable.ic_ui_sync, R.string.auto_sync)

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
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = FeatureCardShape,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CardTitle(R.drawable.ic_metric_heart, R.string.health_connect)
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = FeatureCardShape,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CardTitle(R.drawable.ic_watch, R.string.watch_settings)
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
                label = { Text(stringResource(type.labelRes())) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(type.iconRes()),
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                    )
                },
            )
        }
    }
}

/**
 * A card's title with its own picture.
 *
 * The watch tab is a long scroll — settings, weather, notifications, alarms, find, GPS,
 * updates, background work, the log — and a column of identically-weighted headings is a
 * column you have to read. A glyph turns it into something you can aim at: the weather is
 * the one with the sun.
 *
 * Deliberately not applied to every heading in the app. The rows inside a card already
 * carry their own meaning — a switch says what it is, a time says what it is — and giving
 * each of those a picture too would be the same noise this is meant to cut through.
 */
@Composable
private fun CardTitle(@DrawableRes icon: Int, @StringRes title: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
    }
}

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
    onUseMyLocation: () -> Unit,
    onAutoPlace: (Boolean) -> Unit,
    followsInBackground: Boolean,
) {
    var typed by remember(state.settings.weatherCity) {
        mutableStateOf(state.settings.weatherCity.orEmpty())
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = FeatureCardShape,
        colors = featureCardColors(state.settings.weatherEnabled),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CardTitle(R.drawable.ic_ui_weather, R.string.weather)
                Switch(
                    checked = state.settings.weatherEnabled,
                    onCheckedChange = onEnabled,
                    enabled = state.settings.weatherCity != null,
                )
            }

            HorizontalDivider()

            Text(stringResource(R.string.weather_explainer), style = MaterialTheme.typography.bodyMedium)

            SettingSwitch(
                label = stringResource(R.string.weather_auto_place),
                checked = state.settings.weatherAutoPlace,
                onCheckedChange = onAutoPlace,
            )

            // Says which of the two it is doing, because the difference is the whole
            // question the wearer will have: with "all the time" the watch follows them,
            // and without it the place is taken when they open this screen.
            if (state.settings.weatherAutoPlace) {
                Text(
                    stringResource(
                        if (followsInBackground) {
                            R.string.weather_auto_background
                        } else {
                            R.string.weather_auto_foreground
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

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

                // Beside the search rather than instead of it. Typing a city still works
                // with no permission at all, which is how this app has always done it and
                // remains the default; this is for the wearer who has moved.
                OutlinedButton(
                    onClick = onUseMyLocation,
                    enabled = lookup != CityLookup.Searching,
                ) {
                    Text(stringResource(R.string.weather_here))
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

                    CityLookup.NoPosition -> Text(
                        stringResource(R.string.weather_no_position),
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
/**
 * One workout.
 *
 * Deliberately does not pretend to be a sport. Every other companion app puts a running
 * shoe on the card and a distance under it; this watch hands over neither, so the card
 * says what it has — when, how long, how hard — and draws the pulse that is the whole
 * evidence the session happened at all.
 */
@Composable
private fun WorkoutCard(workout: WorkoutRow) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = FeatureCardShape,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = WORKOUT_DAY.format(
                            Instant.ofEpochSecond(workout.startSeconds)
                                .atZone(ZoneId.systemDefault()),
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(
                            R.string.workout_when,
                            WORKOUT_TIME.format(
                                Instant.ofEpochSecond(workout.startSeconds)
                                    .atZone(ZoneId.systemDefault()),
                            ),
                            readableDuration(workout.seconds),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Icon(
                    painter = painterResource(R.drawable.ic_sport_generic),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                GoalReading(stringResource(R.string.workout_average), workout.averageBpm)
                GoalReading(stringResource(R.string.workout_peak), workout.maxBpm)
            }

            LineChart(
                points = workout.pulse,
                color = MaterialTheme.colorScheme.primary,
                minimumSpan = MINIMUM_BPM_SPAN,
            )
        }
    }
}

/**
 * What the workouts tab says before there are any.
 *
 * Says why rather than showing an empty list: a wearer who has done workouts and sees
 * nothing here would reasonably conclude the app is broken, and the truth — that the watch
 * only tells reCMF about a session while it is measuring the pulse — is both the reason
 * and the instruction.
 */
@Composable
private fun NoWorkoutsCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = FeatureCardShape,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CardTitle(R.drawable.ic_sport_generic, R.string.tab_workouts)
            Text(
                stringResource(R.string.workouts_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Hours and minutes, or minutes alone for anything under an hour.
 *
 * "0 h 47 min" makes the reader do arithmetic to find out it says forty-seven minutes.
 *
 * Shared with the sleep screen, which carried an identical copy taking an Int. Two
 * functions formatting the same thing the same way are one function and a drift waiting to
 * happen.
 */
@Composable
internal fun readableDuration(seconds: Long): String {
    val minutes = seconds / 60
    return if (minutes >= MINUTES_IN_HOUR) {
        stringResource(R.string.duration_hm, minutes / MINUTES_IN_HOUR, minutes % MINUTES_IN_HOUR)
    } else {
        stringResource(R.string.duration_m, minutes)
    }
}

private const val MINUTES_IN_HOUR = 60

private val WORKOUT_DAY: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)

private val WORKOUT_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** A pulse that barely moved should not be drawn as a mountain range. */
private const val MINIMUM_BPM_SPAN = 20f

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
            CardTitle(R.drawable.ic_ui_power, R.string.background_work)
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = FeatureCardShape,
        colors = featureCardColors(state.settings.notificationsEnabled && hasAccess),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CardTitle(R.drawable.ic_ui_bell, R.string.notifications)
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
private fun ChartSection(title: String, chart: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = UtilityCardShape,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CardTitle(R.drawable.ic_ui_list, R.string.protocol_log_title)
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
    mirroring: Boolean,
    problem: AlarmMirrorProblem?,
    onMirroring: (Boolean) -> Unit,
    onChange: (WatchSetting, (WatchPreferences) -> WatchPreferences) -> Unit,
) {
    var editing by remember { mutableStateOf<Int?>(null) }

    fun update(transform: (List<CmfAlarm>) -> List<CmfAlarm>) {
        onChange(WatchSetting.ALARMS) { current -> current.copy(alarms = transform(current.alarms)) }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = FeatureCardShape,
        colors = featureCardColors(mirroring),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CardTitle(R.drawable.ic_ui_alarm, R.string.alarms)
            Text(
                stringResource(R.string.alarms_explainer),
                style = MaterialTheme.typography.bodyMedium,
            )

            HorizontalDivider()

            SettingSwitch(
                label = stringResource(R.string.alarms_mirror_phone),
                checked = mirroring,
                onCheckedChange = onMirroring,
            )
            Text(
                stringResource(R.string.alarms_mirror_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Said on the card, in the error colour, and only once the switch is on and a
            // read has actually been tried. A requirement written into the paragraph above
            // is read by nobody; a switch that turns on and does nothing is noticed by
            // everybody, and this is the only place the difference can be explained.
            problem?.let {
                Text(
                    stringResource(
                        when (it) {
                            AlarmMirrorProblem.NEEDS_ROOT -> R.string.alarms_mirror_needs_root
                            AlarmMirrorProblem.NO_CLOCK -> R.string.alarms_mirror_no_clock
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (alarms.isEmpty()) {
                Text(
                    stringResource(R.string.alarms_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // While the watch mirrors the phone, the list is shown and not offered for
            // editing: controls that quietly lose what they are given are worse than none.
            if (mirroring) {
                alarms.forEach { alarm ->
                    HorizontalDivider()
                    Text(
                        buildString {
                            append("%02d:%02d".format(alarm.hour, alarm.minute))
                            if (!alarm.enabled) append("  ·  ")
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = if (alarm.enabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    if (alarm.days.isNotEmpty()) {
                        Text(
                            // Resolved through map rather than inside joinToString: that
                            // one is not inline, and a composable cannot be called from a
                            // lambda the compiler cannot see into.
                            CmfWeekday.entries
                                .filter { it in alarm.days }
                                .map { stringResource(it.labelRes()) }
                                .joinToString(" "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                return@Column
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = UtilityCardShape,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CardTitle(R.drawable.ic_ui_download, R.string.updates)
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
/**
 * Uploading predicted satellite orbits, so the watch's own GPS can find itself.
 *
 * This looks like an odd thing to put in front of a person, and it is — but the
 * alternative is worse. The watch has a GPS receiver and, starting cold, it reads the
 * satellites' orbits off the satellites themselves at fifty bits a second: minutes under
 * open sky and effectively never between buildings. The official app quietly uploads an
 * almanac to spare it that. reCMF can too, and now knows how, but it has nowhere to fetch
 * one from — the address the official app downloads from has not been found — so the file
 * has to be handed to it.
 *
 * The file is checked before a byte goes out. An almanac that is wrong is worse than none,
 * because the receiver believes it.
 */
@Composable
private fun GpsDataCard(
    connected: Boolean,
    auto: Boolean,
    sentAtMillis: Long,
    onAuto: (Boolean) -> Unit,
    onInstallAgps: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = FeatureCardShape,
        colors = featureCardColors(auto),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CardTitle(R.drawable.ic_ui_satellite, R.string.gps_data)
            Text(
                stringResource(R.string.gps_data_explainer),
                style = MaterialTheme.typography.bodyMedium,
            )

            SettingSwitch(
                label = stringResource(R.string.gps_almanac_auto),
                checked = auto,
                onCheckedChange = onAuto,
            )

            Text(
                if (sentAtMillis == 0L) {
                    stringResource(R.string.gps_almanac_never)
                } else {
                    stringResource(
                        R.string.gps_almanac_sent,
                        DateUtils.getRelativeTimeSpanString(
                            sentAtMillis,
                            System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS,
                        ).toString(),
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Kept even though the download works: a phone with no network, a server
            // having a bad day, and anyone who would rather hand it a file of their own.
            FilledTonalButton(onClick = onInstallAgps, enabled = connected) {
                Text(stringResource(R.string.action_install_agps))
            }
        }
    }
}

@Composable
private fun FindWatchCard(connected: Boolean, onFindWatch: () -> Unit) {
    val haptics = LocalHapticFeedback.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = UtilityCardShape,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CardTitle(R.drawable.ic_ui_ping, R.string.find_watch)
            Text(
                stringResource(R.string.find_watch_explainer),
                style = MaterialTheme.typography.bodyMedium,
            )
            FilledTonalButton(
                onClick = {
                    // The phone buzzes as the watch is told to. Everything else this
                    // button does happens on the other side of a radio and out of sight —
                    // a press with no answer at all reads as a press that missed.
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onFindWatch()
                },
                enabled = connected,
            ) {
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
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = FeatureCardShape,
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CardTitle(R.drawable.ic_ui_faces, R.string.watchfaces)
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

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = FeatureCardShape,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardTitle(R.drawable.ic_ui_faces, R.string.watchfaces)
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
private enum class HomeTab(
    @param:StringRes val labelRes: Int,
    @param:DrawableRes val iconRes: Int,
) {
    HEALTH(R.string.tab_health, R.drawable.ic_metric_heart),

    // Between the two rather than after them: sleep is a measurement, and the watch tab
    // is where the settings live. The dock reads health, workouts, sleep, faces, watch.
    WORKOUTS(R.string.tab_workouts, R.drawable.ic_sport_run),
    SLEEP(R.string.tab_sleep, R.drawable.ic_metric_sleep),

    // Present only when the coach is switched on; see [shownTabs]. It sits after the
    // measurements because it is about them — there is nothing to ask before there is
    // something to ask about.
    COACH(R.string.tab_coach, R.drawable.ic_ui_coach),

    // Its own tab rather than a card buried in the watch settings: switching and
    // installing a face is a thing people come to do, not a setting they adjust once.
    FACES(R.string.tab_faces, R.drawable.ic_ui_faces),
    DEVICE(R.string.tab_device, R.drawable.ic_watch),
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
/**
 * When a Health Connect record was written, for the survey.
 *
 * No year: the survey looks back a fortnight, so a year in every row would be four
 * characters of noise in a line that is already long.
 */
private val SURVEY_CLOCK: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM, HH:mm").withZone(ZoneId.systemDefault())
private val RECORD_STAMP: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneId.systemDefault())
