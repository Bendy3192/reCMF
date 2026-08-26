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
import dev.recmf.notifications.OutgoingNotifications
import dev.recmf.protocol.CmfCommand
import dev.recmf.protocol.CmfFrame
import dev.recmf.protocol.CmfParsers
import dev.recmf.protocol.CmfSettings
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

            ACTION_SYNC_NOW -> lifecycleScope.launch { requestSync() }

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
                if (_status.value == ConnectionState.READY) requestSync()
            }
        }
    }

    private fun isScreenOn(): Boolean =
        getSystemService<PowerManager>()?.isInteractive ?: false

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
                    initializeWatch()
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
                    // Retrying with a key the watch refuses only burns battery. Wait for
                    // the user to pair again.
                    stopEverything()
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
    }

    /**
     * Pushes the whole configuration rather than the one field that changed. It is a
     * dozen small writes, it is idempotent, and it means there is no way for the watch and
     * the phone to disagree about a setting that failed to apply once.
     */
    private suspend fun applyWatchPreferences(preferences: WatchPreferences) {
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

        connection.send(
            CmfCommand.WAKE_ON_WRIST_RAISE,
            CmfSettings.wakeOnWristRaise(preferences.raiseToWake),
        )
        connection.send(CmfCommand.TIME_FORMAT, CmfSettings.timeFormat(preferences.use24Hour))

        // The watch expects length and temperature to be set together.
        val units = CmfSettings.measurementSystem(preferences.metric)
        connection.send(CmfCommand.UNIT_LENGTH, units)
        connection.send(CmfCommand.UNIT_TEMPERATURE, units)

        connection.send(
            CmfCommand.GOALS_SET,
            CmfSettings.goals(
                steps = preferences.stepsGoal,
                distanceMeters = preferences.distanceGoalMeters,
                calories = preferences.caloriesGoal,
            ),
        )

        connection.send(
            CmfCommand.HEART_MONITORING_ALERTS,
            CmfSettings.heartAlerts(
                restingHigh = preferences.heartRateAlertRestingHigh,
                activeHigh = preferences.heartRateAlertActiveHigh,
                low = preferences.heartRateAlertLow,
                spo2Low = preferences.spo2AlertLow,
            ),
        )

        connection.send(
            CmfCommand.STANDING_REMINDER_SET,
            CmfSettings.reminder(
                enabled = preferences.standReminder,
                intervalMinutes = preferences.standIntervalMinutes,
                quietStartSeconds = preferences.standQuietStartMinutes * 60,
                quietEndSeconds = preferences.standQuietEndMinutes * 60,
            ),
        )
        connection.send(
            CmfCommand.WATER_REMINDER_SET,
            CmfSettings.reminder(
                enabled = preferences.drinkReminder,
                intervalMinutes = preferences.drinkIntervalMinutes,
                quietStartSeconds = preferences.drinkQuietStartMinutes * 60,
                quietEndSeconds = preferences.drinkQuietEndMinutes * 60,
            ),
        )

        connection.send(CmfCommand.SPORTS_SET, CmfSettings.sportTypes(preferences.sportTypes))
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
                    ingest.flushToHealthConnect()
                    ingest.prune()
                }

                null -> Log.w(TAG, "Unrecognised fetch acknowledgement")
            }

            CmfCommand.ACTIVITY_DATA ->
                ingest.storeActivity(CmfParsers.parseActivity(message.payload))

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

    private suspend fun requestSync() {
        if (_status.value != ConnectionState.READY) return

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
