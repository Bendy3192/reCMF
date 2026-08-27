/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.recmf.R
import dev.recmf.ble.CmfConnection
import dev.recmf.ble.CmfMessage
import dev.recmf.ble.ConnectionFailure
import dev.recmf.ble.ConnectionState
import dev.recmf.ble.ProtocolLog
import dev.recmf.ble.ReconnectBackoff
import dev.recmf.data.RecmfDatabase
import dev.recmf.data.SettingsStore
import dev.recmf.data.WatchPreferences
import dev.recmf.data.WatchSetting
import dev.recmf.notifications.OutgoingNotifications
import dev.recmf.weather.WeatherClient
import dev.recmf.weather.WeatherLocation
import dev.recmf.weather.WeatherSnapshot
import dev.recmf.protocol.CmfCommand
import dev.recmf.protocol.CmfFrame
import dev.recmf.protocol.CmfParsers
import dev.recmf.protocol.CmfSettings
import dev.recmf.protocol.CmfWeather
import dev.recmf.protocol.MonitoringChannel
import dev.recmf.protocol.ActivityFetchState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * Keeps the watch connected and its data flowing, for as long as the user wants it to.
 *
 * This runs as a foreground service of type `connectedDevice` because that is the only
 * category Android will let hold a Bluetooth link indefinitely. Everything else here is
 * about surviving the things that kill companion apps in practice:
 *
 * - **Process death.** `START_STICKY` plus [WatchdogWorker] means a kill is recovered
 *   from within minutes, and the notification comes back with it.
 * - **Task removal.** Swiping the app away does not stop a sync the user asked for, so
 *   [onTaskRemoved] restarts the service.
 * - **Range flapping.** Reconnects back off ([ReconnectBackoff]) instead of hammering
 *   the radio while the watch is in another room.
 * - **Unbounded growth.** Samples go straight into Room in batches and are pruned after
 *   they reach Health Connect; nothing accumulates in memory across a long sync.
 */
class WatchService : LifecycleService() {

    /**
     * All GATT and codec state is confined to this one thread. The codec keeps mutable
     * reassembly buffers, and the Android GATT stack is unforgiving about concurrent
     * operations, so both want a single owner.
     */
    private val bleDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "recmf-ble")
    }.asCoroutineDispatcher()

    private val bleScope = CoroutineScope(SupervisorJob() + bleDispatcher)

    private lateinit var settings: SettingsStore
    private lateinit var connection: CmfConnection
    private lateinit var ingest: SampleIngest

    private val backoff = ReconnectBackoff()

    private var autoSyncJob: Job? = null

    private val weather = WeatherClient()

    /** When the forecast was last fetched, so a short refresh interval does not hammer it. */
    private var weatherFetchedAtMillis = 0L

    /** The last forecast fetched, kept so a reconnect does not leave the watch blank. */
    private var weatherSnapshot: WeatherSnapshot? = null

    /** Mirrors into [WatchStatus] so the UI can observe without binding to the service. */
    private val _status = WatchStatus.state

    override fun onCreate() {
        super.onCreate()

        settings = SettingsStore(applicationContext)
        ingest = SampleIngest(
            dao = RecmfDatabase.get(applicationContext).sampleDao(),
            settings = settings,
            context = applicationContext,
        )

        connection = CmfConnection(
            context = applicationContext,
            scope = bleScope,
            onAuthKeyNegotiated = { key ->
                // Pairing succeeded — persist K1 so the next connection skips the
                // shell handshake entirely.
                lifecycleScope.launch { settings.setAuthKey(key) }
            },
        )

        createNotificationChannel()
        observeConnection()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        promoteToForeground(_status.value)

        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }

            ACTION_SYNC_NOW -> lifecycleScope.launch { refreshNow() }

            else -> lifecycleScope.launch { connectToPairedWatch() }
        }

        // Restarted without the original intent after a kill; onStartCommand then falls
        // into the branch above and reconnects to whatever is paired.
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)

        // Swiping the app from Recents is not a request to stop syncing.
        if (_status.value != ConnectionState.IDLE) {
            startService(Intent(this, WatchService::class.java))
        }
    }

    override fun onDestroy() {
        autoSyncJob?.cancel()
        connection.disconnect()
        bleScope.cancel()
        bleDispatcher.close()

        // Whatever the reason — stopSelf, or the system reclaiming the service — the
        // link is gone. Leaving a stale READY here would convince [WatchdogWorker] that
        // everything is fine and stop it from restarting us.
        WatchStatus.state.value = ConnectionState.IDLE
        WatchStatus.battery.value = null

        super.onDestroy()
    }

    /**
     * Polls the watch on a timer so the figures stay current without being asked.
     *
     * Each poll is a handful of small writes, but it is still radio time on both sides —
     * so the interval is the user's to choose and can be turned off entirely. The timer
     * runs regardless of connection state and skips when the watch is away, rather than
     * being started and stopped with the link: that keeps one job to reason about instead
     * of a lifecycle that has to be unwound on every reconnect.
     */
    private fun restartAutoSync(intervalSeconds: Int) {
        autoSyncJob?.cancel()
        if (intervalSeconds <= 0) {
            Log.i(TAG, "Automatic sync is off")
            return
        }

        autoSyncJob = lifecycleScope.launch {
            while (true) {
                delay(intervalSeconds * 1000L)
                if (_status.value != ConnectionState.READY) continue

                refreshNow()
            }
        }
    }

    /**
     * Everything a refresh means: the watch's counters, and the forecast.
     *
     * The two used to be separate, and only the counters were wired to the watchdog — so
     * the one refresh path that survives Doze never touched the weather. The interval
     * loop that did is a coroutine delay, and coroutine delays are measured against
     * uptime, which stops advancing while the phone is in deep sleep: a five-minute tick
     * scheduled at bedtime fires five *awake* minutes later, which can be most of a day.
     * That is why the forecast refreshed on no schedule anyone could name.
     */
    private suspend fun refreshNow() {
        requestSync()

        // requestSync may have started a reconnect rather than a fetch; there is nothing
        // to send a forecast down until that finishes, and the next refresh will carry it.
        if (_status.value == ConnectionState.READY) sendWeather()
    }

    /**
     * Puts a forecast on the watch, fetching a fresh one only when the one in hand has
     * gone stale.
     *
     * Fetching and sending are deliberately separate. They used to be one step behind a
     * single half-hour timer, which meant that reconnecting inside that half hour sent
     * the watch nothing at all — the rate limit was written to spare the provider, and it
     * was silencing the watch instead. The provider is still asked at most every half
     * hour; the watch is given what we have every time it asks.
     */
    private suspend fun sendWeather() {
        val current = settings.current()
        if (!current.weatherEnabled) return

        val city = current.weatherCity
        if (city == null) {
            WatchStatus.weatherProblem.value = WeatherProblem.NO_CITY
            return
        }

        val now = System.currentTimeMillis()
        val stale = now - weatherFetchedAtMillis >= WEATHER_REFRESH_MILLIS

        if (stale || weatherSnapshot == null) {
            val fetched = weather.forecast(
                WeatherLocation(city, current.weatherLatitude, current.weatherLongitude),
                nowEpochSeconds = now / 1000,
            )

            if (fetched != null) {
                weatherSnapshot = fetched
                // Only after a success: a failure should be retried on the next tick,
                // not held off for another half hour.
                weatherFetchedAtMillis = now
                WatchStatus.weatherProblem.value = null
            } else {
                ProtocolLog.note("Weather provider unreachable")
                WatchStatus.weatherProblem.value = WeatherProblem.UNREACHABLE
            }
        }

        // Whatever we last managed to fetch still beats leaving the watch with nothing:
        // an hour-old temperature is a better watch face than a blank one.
        val snapshot = weatherSnapshot ?: return

        connection.send(
            CmfCommand.WEATHER_SET_1,
            CmfWeather.payload(
                today = snapshot.today,
                forecast = snapshot.forecast,
                hourly = snapshot.hourly,
                location = city,
                sun = snapshot.sun,
            ),
        )

        WatchStatus.weatherSentAtMillis.value = now
        WatchStatus.weatherTemperatureC.value = snapshot.today.temperatureC
    }

    private fun isScreenOn(): Boolean =
        getSystemService<PowerManager>()?.isInteractive ?: false

    /**
     * Recovers from a key the watch no longer accepts.
     *
     * The watch keeps one pairing key, so pairing it with the stock app or with
     * Gadgetbridge replaces reCMF's. That is a normal thing for a user to do, not a fault,
     * so the stale key is dropped and the next attempt pairs from scratch — once. If the
     * fresh key is refused too, something else is wrong and retrying would only burn
     * battery against a watch that will not have us.
     */
    private suspend fun onKeyRejected() {
        if (settings.authKey() == null) {
            ProtocolLog.note("Pairing refused even with a new key")
            stopEverything()
            return
        }

        ProtocolLog.note("Key rejected — pairing again")
        settings.clearAuthKey()

        delay(RE_PAIR_DELAY_MILLIS)
        connectToPairedWatch()
    }

    private suspend fun connectToPairedWatch() {
        val current = settings.current()
        val address = current.address
        if (address == null) {
            Log.i(TAG, "No watch paired; stopping")
            stopEverything()
            return
        }

        connection.connect(address, settings.authKey())
    }

    @OptIn(FlowPreview::class)
    private fun observeConnection() {
        lifecycleScope.launch {
            connection.state.collect { state ->
                _status.value = state
                ProtocolLog.note("State: $state")
                updateNotification(state)

                if (state == ConnectionState.READY) {
                    backoff.reset()
                    // Launched rather than awaited: initialising fetches a forecast, and a
                    // provider that takes its fifteen seconds to time out would hold up
                    // every later state change behind it — including the disconnect that
                    // would explain why it was slow.
                    lifecycleScope.launch { initializeWatch() }
                }
            }
        }

        lifecycleScope.launch {
            connection.messages.collect { message -> onMessage(message) }
        }

        lifecycleScope.launch {
            // Re-applied whenever they change, and again on every connection: the watch
            // has no read-back for most of these, so the phone is the source of truth and
            // a watch that was reset elsewhere converges back.
            // Debounced because each write pushes the whole configuration: without it,
            // flicking three switches sends two dozen commands the watch has to chew
            // through before it will answer anything else.
            settings.watchPreferences
                .distinctUntilChanged()
                .debounce(SETTINGS_DEBOUNCE_MILLIS)
                .collect { preferences ->
                    if (_status.value == ConnectionState.READY) applyWatchPreferences(preferences)
                }
        }

        lifecycleScope.launch {
            settings.settings
                .map { it.autoSyncSeconds }
                .distinctUntilChanged()
                .collect(::restartAutoSync)
        }

        lifecycleScope.launch {
            // Turning weather on, or naming a different place, used to change nothing
            // until the next reconnect or refresh tick — with automatic refresh off, that
            // meant never. A setting the user just changed should take effect while they
            // are still looking at the screen.
            settings.settings
                .map { Triple(it.weatherEnabled, it.weatherCity, it.weatherLatitude) }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    if (_status.value == ConnectionState.READY) {
                        // The place changed, so what we are holding is for somewhere else.
                        weatherFetchedAtMillis = 0L
                        weatherSnapshot = null
                        sendWeather()
                    }
                }
        }

        lifecycleScope.launch {
            OutgoingNotifications.pending.collect { notification ->
                // Dropped rather than queued when the watch is away: a notification the
                // user has already dealt with is not worth buzzing their wrist for later.
                if (_status.value != ConnectionState.READY) return@collect

                val prefs = settings.current()
                if (!prefs.notificationsEnabled) return@collect

                // With the screen on the user is already looking at the phone, and the
                // watch buzzing for what they just read is noise.
                if (prefs.notifyOnlyWhenScreenOff && isScreenOn()) return@collect

                connection.send(CmfCommand.APP_NOTIFICATION, notification.toPayload())
            }
        }

        lifecycleScope.launch {
            connection.failures.collect { failure ->
                Log.w(TAG, "Connection failure: $failure")
                ProtocolLog.note("Failure: $failure")

                if (failure is ConnectionFailure.AuthRejected) {
                    onKeyRejected()
                    return@collect
                }

                val wait = backoff.nextDelayMillis()
                Log.i(TAG, "Reconnecting in ${wait}ms (attempt ${backoff.attempt})")
                delay(wait)

                if (_status.value != ConnectionState.READY) connectToPairedWatch()
            }
        }
    }

    /**
     * What the watch expects once the handshake is done, before it will serve anything:
     * the current time, then the identity queries. Gadgetbridge does the same, and
     * skipping it leaves a connection that is authenticated but answers nothing.
     */
    private suspend fun initializeWatch() {
        val nowMillis = System.currentTimeMillis()

        connection.send(
            CmfCommand.TIME,
            CmfParsers.buildTimePayload(
                epochSeconds = nowMillis / 1000,
                utcOffsetMillis = TimeZone.getDefault().getOffset(nowMillis),
            ),
        )
        connection.send(CmfCommand.FIRMWARE_VERSION_GET)
        connection.send(CmfCommand.SERIAL_NUMBER_GET)

        applyWatchPreferences(settings.watchPreferences.first())

        requestSync()
        sendWeather()
    }

    /**
     * Pushes the settings the user has actually changed in reCMF, and only those.
     *
     * Most of these have no read-back command, so reCMF does not know what the watch
     * already holds. Sending its own defaults would silently replace a configuration the
     * user made in the official app — which is what it used to do.
     */
    private suspend fun applyWatchPreferences(preferences: WatchPreferences) {
        suspend fun ifSet(setting: WatchSetting, send: suspend () -> Unit) {
            if (setting in preferences.configured) send()
        }

        ifSet(WatchSetting.MONITORING) {
            connection.send(
                CmfCommand.HEART_MONITORING_ENABLED_SET,
                CmfSettings.monitoring(MonitoringChannel.HEART_RATE, preferences.heartRateMonitoring),
            )
            connection.send(
                CmfCommand.HEART_MONITORING_ENABLED_SET,
                CmfSettings.monitoring(MonitoringChannel.SPO2, preferences.spo2Monitoring),
            )
            connection.send(
                CmfCommand.HEART_MONITORING_ENABLED_SET,
                CmfSettings.monitoring(MonitoringChannel.STRESS, preferences.stressMonitoring),
            )
        }

        ifSet(WatchSetting.RAISE_TO_WAKE) {
            connection.send(
                CmfCommand.WAKE_ON_WRIST_RAISE,
                CmfSettings.wakeOnWristRaise(preferences.raiseToWake),
            )
        }

        ifSet(WatchSetting.TIME_FORMAT) {
            connection.send(CmfCommand.TIME_FORMAT, CmfSettings.timeFormat(preferences.use24Hour))
        }

        ifSet(WatchSetting.UNITS) {
            // The watch expects length and temperature to be set together.
            val units = CmfSettings.measurementSystem(preferences.metric)
            connection.send(CmfCommand.UNIT_LENGTH, units)
            connection.send(CmfCommand.UNIT_TEMPERATURE, units)
        }

        ifSet(WatchSetting.GOALS) {
            connection.send(
                CmfCommand.GOALS_SET,
                CmfSettings.goals(
                    steps = preferences.stepsGoal,
                    distanceMeters = preferences.distanceGoalMeters,
                    calories = preferences.caloriesGoal,
                ),
            )
        }

        ifSet(WatchSetting.ALERTS) {
            connection.send(
                CmfCommand.HEART_MONITORING_ALERTS,
                CmfSettings.heartAlerts(
                    restingHigh = preferences.heartRateAlertRestingHigh,
                    activeHigh = preferences.heartRateAlertActiveHigh,
                    low = preferences.heartRateAlertLow,
                    spo2Low = preferences.spo2AlertLow,
                ),
            )
        }

        ifSet(WatchSetting.STAND_REMINDER) {
            connection.send(
                CmfCommand.STANDING_REMINDER_SET,
                CmfSettings.reminder(
                    enabled = preferences.standReminder,
                    intervalMinutes = preferences.standIntervalMinutes,
                    quietStartSeconds = preferences.standQuietStartMinutes * 60,
                    quietEndSeconds = preferences.standQuietEndMinutes * 60,
                ),
            )
        }

        ifSet(WatchSetting.DRINK_REMINDER) {
            connection.send(
                CmfCommand.WATER_REMINDER_SET,
                CmfSettings.reminder(
                    enabled = preferences.drinkReminder,
                    intervalMinutes = preferences.drinkIntervalMinutes,
                    quietStartSeconds = preferences.drinkQuietStartMinutes * 60,
                    quietEndSeconds = preferences.drinkQuietEndMinutes * 60,
                ),
            )
        }

        // The most destructive of the lot: the watch replaces its whole sport menu with
        // whatever list arrives, so sending reCMF's default would delete most of it.
        ifSet(WatchSetting.SPORTS) {
            connection.send(CmfCommand.SPORTS_SET, CmfSettings.sportTypes(preferences.sportTypes))
        }
    }

    /**
     * Drives the backlog download. The watch will not start sending until it has
     * acknowledged step 1, and it signals the end of the backlog with the same
     * acknowledgement command — so this is where a sync begins and ends.
     */
    private suspend fun onMessage(message: CmfMessage) {
        when (message.cmd) {
            CmfCommand.ACTIVITY_FETCH_ACK_1 -> when (CmfParsers.parseFetchState(message.payload)) {
                ActivityFetchState.READY ->
                    connection.send(CmfCommand.ACTIVITY_FETCH_2, CmfFrame.A5)

                ActivityFetchState.FINISHED -> {
                    // The watch has said its piece. It may well have had nothing new,
                    // which is still a completed exchange and the thing the user is
                    // actually asking about when they press Sync.
                    WatchStatus.lastExchangeAtMillis.value = System.currentTimeMillis()
                    ingest.flushToHealthConnect()
                    ingest.prune()
                }

                null -> Log.w(TAG, "Unrecognised fetch acknowledgement")
            }

            CmfCommand.ACTIVITY_DATA -> {
                val samples = CmfParsers.parseActivity(message.payload)
                if (samples.isNotEmpty()) {
                    WatchStatus.lastRecordCount.value = samples.size
                    WatchStatus.lastRecordEpochSeconds.value = samples.maxOf { it.timestamp }
                }
                ingest.storeActivity(samples)
            }

            CmfCommand.HEART_RATE_MANUAL_AUTO, CmfCommand.HEART_RATE_WORKOUT ->
                ingest.storeHeartRate(CmfParsers.parseHeartRate(message.payload))

            CmfCommand.BATTERY ->
                CmfParsers.parseBattery(message.payload)?.let { WatchStatus.battery.value = it }

            CmfCommand.FIRMWARE_VERSION_RET ->
                WatchStatus.firmware.value = CmfParsers.parseFirmwareVersion(message.payload)

            CmfCommand.SERIAL_NUMBER_RET ->
                WatchStatus.serialNumber.value = CmfParsers.parseSerialNumber(message.payload)

            else -> Unit
        }
    }

    /**
     * Asks the watch for the battery level and anything it has recorded.
     *
     * When the link is not up this reconnects instead of returning quietly. Pressing Sync
     * and having nothing at all happen — no data, no error, no attempt — was indisputably
     * worse than either outcome.
     */
    private suspend fun requestSync() {
        val state = _status.value
        if (state != ConnectionState.READY) {
            // Mid-handshake is already on its way to ready, and reconnecting would throw
            // that progress away. Only a link that is idle or waiting needs a push.
            if (state == ConnectionState.IDLE || state == ConnectionState.WAITING) {
                ProtocolLog.note("Sync asked for while $state; reconnecting")
                connectToPairedWatch()
            } else {
                ProtocolLog.note("Sync asked for while $state; already connecting")
            }
            return
        }

        connection.send(CmfCommand.BATTERY)
        connection.send(CmfCommand.ACTIVITY_FETCH_1, dev.recmf.protocol.CmfFrame.A5)
    }

    private fun stopEverything() {
        connection.disconnect()
        _status.value = ConnectionState.IDLE
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // region Notification

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_connection),
            // Low: this notification exists because the platform requires one, not
            // because the user needs to be told anything.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_connection_description)
            setShowBadge(false)
        }

        getSystemService<NotificationManager>()?.createNotificationChannel(channel)
    }

    private fun promoteToForeground(state: ConnectionState) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(state),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
    }

    private fun updateNotification(state: ConnectionState) {
        getSystemService<NotificationManager>()?.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: ConnectionState): Notification {
        val text = when (state) {
            ConnectionState.IDLE -> getString(R.string.status_idle)
            ConnectionState.WAITING -> getString(R.string.status_waiting)
            ConnectionState.CONNECTING -> getString(R.string.status_connecting)
            ConnectionState.INITIALIZING -> getString(R.string.status_initializing)
            ConnectionState.AUTHENTICATING -> getString(R.string.status_authenticating)
            ConnectionState.READY -> getString(R.string.status_ready)
        }

        val open = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_watch)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    // endregion

    companion object {
        private const val TAG = "WatchService"
        private const val CHANNEL_ID = "recmf.connection"

        /** Long enough to cover a run of switch taps, short enough to feel immediate. */
        private const val SETTINGS_DEBOUNCE_MILLIS = 700L

        /** A forecast does not change faster than this, whatever the refresh interval is. */
        private const val WEATHER_REFRESH_MILLIS = 30 * 60_000L

        /** Long enough for the watch to drop the refused link before we open a new one. */
        private const val RE_PAIR_DELAY_MILLIS = 2_000L
        private const val NOTIFICATION_ID = 1

        const val ACTION_STOP = "dev.recmf.action.STOP"
        const val ACTION_SYNC_NOW = "dev.recmf.action.SYNC_NOW"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, WatchService::class.java))
        }

        fun syncNow(context: Context) {
            context.startForegroundService(
                Intent(context, WatchService::class.java).setAction(ACTION_SYNC_NOW),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, WatchService::class.java).setAction(ACTION_STOP))
        }
    }
}
