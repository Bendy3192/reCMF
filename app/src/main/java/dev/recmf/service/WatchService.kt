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
import dev.recmf.media.MediaWatcher
import dev.recmf.media.NowPlaying
import dev.recmf.protocol.CmfAlarms
import dev.recmf.protocol.CmfMusic
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /**
     * What the watch was last given, so a refresh does not re-send an unchanged forecast.
     *
     * Separating the fetch from the send fixed a watch left blank after a reconnect, and
     * went too far the other way: 199 bytes of identical forecast went out on every tick,
     * which at a thirty-second interval is radio time on both sides for nothing.
     */
    private var weatherSentSnapshot: WeatherSnapshot? = null

    /** What was last pushed, so only the groups that moved are sent again. */
    private var lastAppliedPreferences: WatchPreferences? = null

    private val media by lazy { MediaWatcher(this) }
    private val ringer by lazy { PhoneRinger(this) }

    /** What the watch was last told is playing, so an unchanged track is not resent. */
    private var lastSentNowPlaying: NowPlaying? = null

    /**
     * One now-playing send at a time.
     *
     * Turning the volume down two steps produced six identical 131-byte writes in three
     * seconds. Several things notice the same change at once — the volume observer, the
     * session's audio callback, a refresh — and each launched a coroutine that read
     * [lastSentNowPlaying] before any of them had written it, so the guard against
     * resending an unchanged track never saw a change to guard against.
     */
    private val nowPlayingLock = Mutex()

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

        // Pushed on the change rather than polled: a track lasts minutes and the refresh
        // timer is five, so a polled watch would spend half its time showing the song
        // before.
        media.start { lifecycleScope.launch { sendNowPlaying() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        promoteToForeground(_status.value)

        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }

            ACTION_SYNC_NOW -> lifecycleScope.launch {
                refreshNow()

                // Only here, not on the timer: a person pressing Sync is asking what the
                // watch says, and the answer is worth a dozen frames.
                if (_status.value == ConnectionState.READY) readBackSettings()
            }

            ACTION_FIND_WATCH -> lifecycleScope.launch {
                if (_status.value == ConnectionState.READY) connection.send(CmfCommand.FIND_WATCH)
            }

            // From the notification the ringing itself posts. It has to come through the
            // service rather than a receiver of its own because the ringer is the
            // service's, and a second instance would silence nothing.
            ACTION_STOP_RINGING -> ringer.stop()

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
        media.stop()
        ringer.stop()
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
        sendNowPlaying()

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

        // But only once. The watch keeps what it is given, so re-sending an unchanged
        // forecast every tick is 199 bytes of radio on both sides for no change on the
        // watch face. Cleared on connect, where the watch may have lost it.
        if (snapshot == weatherSentSnapshot) return

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

        weatherSentSnapshot = snapshot
        WatchStatus.weatherSentAtMillis.value = now
        WatchStatus.weatherTemperatureC.value = snapshot.today.temperatureC
    }

    /**
     * Whether a settings group differs between two snapshots.
     *
     * Written out per group rather than derived, because the alternative — comparing whole
     * objects — would send everything whenever anything moved, which is the behaviour this
     * replaces.
     */
    private fun changed(
        setting: WatchSetting,
        before: WatchPreferences,
        after: WatchPreferences,
    ): Boolean = when (setting) {
        WatchSetting.MONITORING ->
            before.heartRateMonitoring != after.heartRateMonitoring ||
                before.spo2Monitoring != after.spo2Monitoring ||
                before.stressMonitoring != after.stressMonitoring

        WatchSetting.RAISE_TO_WAKE -> before.raiseToWake != after.raiseToWake
        WatchSetting.TIME_FORMAT -> before.use24Hour != after.use24Hour
        WatchSetting.UNITS -> before.metric != after.metric

        WatchSetting.GOALS ->
            before.stepsGoal != after.stepsGoal ||
                before.distanceGoalMeters != after.distanceGoalMeters ||
                before.caloriesGoal != after.caloriesGoal

        WatchSetting.ALERTS ->
            before.heartRateAlertLow != after.heartRateAlertLow ||
                before.heartRateAlertRestingHigh != after.heartRateAlertRestingHigh ||
                before.heartRateAlertActiveHigh != after.heartRateAlertActiveHigh ||
                before.spo2AlertLow != after.spo2AlertLow

        WatchSetting.STAND_REMINDER ->
            before.standReminder != after.standReminder ||
                before.standIntervalMinutes != after.standIntervalMinutes ||
                before.standQuietStartMinutes != after.standQuietStartMinutes ||
                before.standQuietEndMinutes != after.standQuietEndMinutes

        WatchSetting.DRINK_REMINDER ->
            before.drinkReminder != after.drinkReminder ||
                before.drinkIntervalMinutes != after.drinkIntervalMinutes ||
                before.drinkQuietStartMinutes != after.drinkQuietStartMinutes ||
                before.drinkQuietEndMinutes != after.drinkQuietEndMinutes

        WatchSetting.SPORTS -> before.sportTypes != after.sportTypes
        WatchSetting.ALARMS -> before.alarms != after.alarms
    }

    /**
     * Tells the watch what is playing.
     *
     * Sent only when it differs from what the watch was last given, for the same reason
     * the forecast is: the watch keeps what it is handed, and a track that has not changed
     * is radio time for a display that would not change either.
     */
    private suspend fun sendNowPlaying() {
        // A block body, not an expression one: Kotlin refuses a bare `return` in an
        // expression-bodied function, and the early exits below are the point.
        nowPlayingLock.withLock {
            if (_status.value != ConnectionState.READY) return

            val playing = media.nowPlaying()
            if (playing == lastSentNowPlaying) return

            connection.send(
                CmfCommand.MUSIC_INFO_SET,
                CmfMusic.payload(
                    state = playing.state,
                    volume = playing.volume,
                    maxVolume = playing.maxVolume,
                    track = playing.track,
                    artist = playing.artist,
                ),
            )

            lastSentNowPlaying = playing
        }
    }

    /**
     * Reports one setting the watch answered with, and takes it if it is ours to take.
     *
     * The log says which of the two happened. "Taken" and "the user has already chosen
     * this, so it stands" look identical in the settings screen afterwards, and telling
     * them apart is the difference between a read-back that works and one that silently
     * does nothing.
     *
     * @param adopt returns whether the value was stored — false when the user has
     *   configured that group themselves, or when there is no preference for it at all.
     */
    private suspend fun <T> readBack(
        value: T?,
        describe: (T) -> String,
        adopt: suspend (T) -> Boolean,
    ) {
        if (value == null) {
            ProtocolLog.note("Unreadable reply from the watch")
            return
        }

        val taken = adopt(value)
        ProtocolLog.note(describe(value) + if (taken) " (taken)" else "")
    }

    private fun onOff(enabled: Boolean): String = if (enabled) "on" else "off"

    /** Wall-clock time of a watch timestamp, for log lines a human has to check. */
    private fun clock(epochSeconds: Long): String =
        java.time.format.DateTimeFormatter.ofPattern("HH:mm")
            .format(java.time.Instant.ofEpochSecond(epochSeconds).atZone(java.time.ZoneId.systemDefault()))

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
                    val previous = lastAppliedPreferences
                    if (_status.value == ConnectionState.READY) {
                        applyWatchPreferences(preferences, previous)
                        lastAppliedPreferences = preferences
                    }
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
                        weatherSentSnapshot = null
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
                //
                // A ringing phone is the exception, and it is not a small one: an incoming
                // call turns the screen on by itself, so this rule would have silenced
                // exactly the notification the wrist is for.
                if (prefs.notifyOnlyWhenScreenOff && isScreenOn() && !notification.isCall) {
                    return@collect
                }

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

        readBackSettings()

        // No previous on a fresh connection: the watch may have been reset, used with
        // another app, or simply be a different watch, so everything configured goes out.
        val preferences = settings.watchPreferences.first()
        applyWatchPreferences(preferences)
        lastAppliedPreferences = preferences

        requestSync()

        // The watch may have been reset or used with another app since we last spoke, so
        // what it holds is not knowable from here.
        weatherSentSnapshot = null
        lastSentNowPlaying = null
        sendWeather()
        sendNowPlaying()
    }

    /**
     * Asks the watch what it is actually set to.
     *
     * The reply arrives under the matching SET opcode, not a distinct one: the `0x0002`
     * half of a pair is answered by its `0x0001` half, every time it has been tried on
     * this watch — the serial number, the alarms, both reminders, raise-to-wake, the clock
     * format, the sport list, Do Not Disturb, the goals and the battery.
     *
     * Also runs on a *manual* sync, and only a manual one. It is a dozen small frames, too
     * many to spend every five minutes on a timer — but pressing Sync is how a person
     * asks "what does the watch actually say", and until this ran there, checking whether
     * a setting had landed meant reconnecting the Bluetooth link by hand.
     */
    private suspend fun readBackSettings() {
        connection.send(CmfCommand.HEART_MONITORING_ENABLED_GET)
        connection.send(CmfCommand.STANDING_REMINDER_GET)
        connection.send(CmfCommand.WATER_REMINDER_GET)
        connection.send(CmfCommand.GOALS_GET)
        connection.send(CmfCommand.TIME_FORMAT_GET)
        connection.send(CmfCommand.WAKE_ON_WRIST_RAISE_GET)
        connection.send(CmfCommand.SPORTS_GET)
        connection.send(CmfCommand.DO_NOT_DISTURB_GET)

        // The one that unblocks alarms. The watch keeps exactly the list it is sent, so
        // reCMF must not offer an alarm UI until it can read what is already there —
        // otherwise the first save deletes whatever the wearer set up in the stock app.
        connection.send(CmfCommand.ALARMS_GET)

        // Last night, if there was one. Sleep is the one measurement the watch does not
        // volunteer: everything else arrives with the activity fetch, and a whole night of
        // it was missing simply because nobody had ever asked.
        //
        // Asked twice, on purpose, for one build. The bare question was answered — the
        // opcode pair is right — but answered with nothing at all, and there is no telling
        // from an empty reply whether the watch has no sleep to give or wants an argument
        // it did not get. The activity fetch takes 0xa5 for "everything you have", so that
        // is the argument worth trying, and the log shows which of the two produced bytes.
        connection.send(CmfCommand.SLEEP_DATA_GET)
        connection.send(CmfCommand.SLEEP_DATA_GET, CmfFrame.A5)
    }

    /**
     * Pushes the settings the user has actually changed in reCMF, and only those.
     *
     * Most of these have no read-back command, so reCMF does not know what the watch
     * already holds. Sending its own defaults would silently replace a configuration the
     * user made in the official app — which is what it used to do.
     *
     * @param previous what was last sent, or null on a fresh connection where the watch
     *   needs the whole configured set. Given one, only the groups that actually differ
     *   go out: editing an alarm used to re-send the monitoring switches, the goals, the
     *   reminders and the sport list along with it, every time.
     */
    private suspend fun applyWatchPreferences(
        preferences: WatchPreferences,
        previous: WatchPreferences? = null,
    ) {
        suspend fun ifSet(setting: WatchSetting, send: suspend () -> Unit) {
            if (setting !in preferences.configured) return
            if (previous != null && !changed(setting, previous, preferences)) return
            send()
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
        ifSet(WatchSetting.ALARMS) {
            connection.send(CmfCommand.ALARMS_SET, CmfAlarms.payload(preferences.alarms))
        }

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

            CmfCommand.HEART_RATE_RESTING -> {
                val samples = CmfParsers.parseRestingHeartRate(message.payload)
                samples.lastOrNull { it.isValid }?.let { WatchStatus.restingHeartRate.value = it }
                ingest.storeRestingHeartRate(samples)
            }

            // Informational: the timestamp the watch is sending activity from, plus four
            // bytes nobody has identified. Gadgetbridge logs it and does nothing with it.
            // Named here so it stops being reported as a command with no handler.
            CmfCommand.ACTIVITY_FETCH_ACK_2 -> Unit

            // Consumed by the connection layer's authenticator before this ever sees them.
            // They are relayed here too, so without naming them the log accused reCMF of
            // dropping the very handshake it had just completed.
            CmfCommand.AUTH_PAIR_REPLY,
            CmfCommand.AUTH_WATCH_MAC,
            CmfCommand.AUTH_NONCE_REPLY,
            CmfCommand.AUTHENTICATED_CONFIRM_REPLY,
            -> Unit

            // The watch volunteers its language at connection time. reCMF does not set the
            // language — the watch ignores that command — but this is the first read-back
            // seen arriving at all, which is worth knowing when the time comes to use it
            // for the settings that do have one.
            CmfCommand.LANGUAGE_RET -> Unit

            // The alarms the watch already holds, arriving under the SET opcode. Adopted
            // rather than only reported, because this list is the one reCMF must not
            // guess at: the watch keeps exactly what it is sent, so editing without
            // knowing what is there deletes the rest.
            CmfCommand.ALARMS_SET -> {
                val alarms = CmfAlarms.parse(message.payload)
                if (alarms == null) {
                    ProtocolLog.note("Alarm list did not fit the expected layout")
                } else {
                    ProtocolLog.note(
                        if (alarms.isEmpty()) {
                            "No alarms on the watch"
                        } else {
                            "Alarms on the watch: " + alarms.joinToString(", ") {
                                "%02d:%02d".format(it.hour, it.minute) +
                                    if (it.enabled) "" else " (off)"
                            }
                        },
                    )
                    settings.adoptAlarmsFromWatch(alarms)
                }
            }

            // The replies to the GETs, each arriving under its own SET opcode. Taken
            // only where the user has not configured that group themselves — their
            // choice outranks the watch's current state, and a read must never become
            // the write-back it exists to prevent.
            CmfCommand.WAKE_ON_WRIST_RAISE -> readBack(
                value = CmfSettings.parseWakeOnWristRaise(message.payload),
                describe = { "Raise to wake on the watch: " + onOff(it) },
                adopt = { settings.adoptRaiseToWakeFromWatch(it) },
            )

            CmfCommand.TIME_FORMAT -> readBack(
                value = CmfSettings.parseTimeFormat(message.payload),
                describe = { "Clock on the watch: " + if (it) "24-hour" else "12-hour" },
                adopt = { settings.adoptTimeFormatFromWatch(it) },
            )

            CmfCommand.SPORTS_SET -> readBack(
                value = CmfSettings.parseSportTypes(message.payload),
                describe = { "Sports on the watch: " + it.joinToString(", ") { type -> type.name } },
                adopt = { settings.adoptSportTypesFromWatch(it) },
            )

            // Nothing here writes Do Not Disturb, so this is read and reported and that
            // is all — there is no preference for it to disagree with.
            CmfCommand.DO_NOT_DISTURB -> readBack(
                value = CmfSettings.parseDoNotDisturb(message.payload),
                describe = { "Do not disturb on the watch: " + onOff(it) },
                adopt = { false },
            )

            // Read only, and always taken. reCMF does not write goals: the watch
            // acknowledges a goal write and keeps what it had, proven in two payload
            // shapes, so there is no setting of ours here for a reading to overrule.
            // Steps, calories, active minutes and climbs were each checked against the
            // watch's own screen and match.
            CmfCommand.GOALS_SET -> readBack(
                value = CmfSettings.parseGoals(message.payload),
                describe = {
                    "Goals on the watch: ${it.steps} steps, ${it.distanceMeters} m, " +
                        "${it.calories} kcal, ${it.activeMinutes} active min, " +
                        "${it.climbs} climbs (and ${it.unidentified}, unidentified)"
                },
                adopt = { settings.adoptGoalsFromWatch(it) },
            )

            CmfCommand.STANDING_REMINDER_SET, CmfCommand.WATER_REMINDER_SET -> {
                val stand = message.cmd == CmfCommand.STANDING_REMINDER_SET
                val which = if (stand) "Stand" else "Drink"

                readBack(
                    value = CmfSettings.parseReminder(message.payload),
                    describe = {
                        "$which reminder on the watch: ${onOff(it.enabled)}" +
                            ", every ${it.intervalMinutes} min"
                    },
                    adopt = {
                        if (stand) {
                            settings.adoptStandReminderFromWatch(it.enabled, it.intervalMinutes)
                        } else {
                            settings.adoptDrinkReminderFromWatch(it.enabled, it.intervalMinutes)
                        }
                    },
                )
            }

            // The watch answering the ring we asked for, with one byte. Nothing to do
            // with it — the wearer's own find-phone button arrives as FIND_PHONE below.
            CmfCommand.FIND_WATCH -> Unit

            CmfCommand.FIND_PHONE -> {
                // The layout is not documented anywhere and Gadgetbridge does not send
                // this one, so it is read the way every other toggle in this protocol is
                // shaped: one leading byte, zero for off. Anything else — including no
                // payload at all — starts the ringing. The log line above this one
                // carries the actual bytes, so a wrong guess is visible rather than
                // mysterious, and being wrong costs a ring that the notification and the
                // thirty-second timer both end.
                if (message.payload.firstOrNull()?.toInt() == 0) {
                    ProtocolLog.note("Watch ended the search")
                    ringer.stop()
                } else {
                    ProtocolLog.note("Ringing this phone")
                    ringer.start()
                }
            }

            CmfCommand.MUSIC_BUTTON -> {
                val button = CmfMusic.parseButton(message.payload)
                if (button == null) {
                    ProtocolLog.note("Unrecognised music button")
                } else if (media.press(button)) {
                    // The phone's state has just changed under the watch, and it will not
                    // find out any other way — there is no notification for "the volume
                    // moved". Sending it back is what makes the watch's own display agree
                    // with what its buttons just did.
                    sendNowPlaying()
                } else {
                    ProtocolLog.note("Nothing to press for $button")
                }
            }

            // The watch confirming it took the track. Nothing to do, but naming it keeps
            // it out of the unhandled list.
            CmfCommand.MUSIC_INFO_ACK -> Unit

            // Kept in memory rather than stored: enough to show the newest reading and to
            // prove the data is arriving. Persistence and Health Connect follow once the
            // shape has been seen against a real watch rather than only a unit test.
            CmfCommand.SPO2 -> {
                val samples = CmfParsers.parseSpo2(message.payload)
                samples.lastOrNull { it.isValid }?.let { WatchStatus.spo2.value = it }
                ingest.storeSpo2(samples)
            }

            // Parsed and reported, not stored. The layout is ported from Gadgetbridge and
            // has never met a real night, so the log carries reCMF's reading of it in
            // plain words next to the raw bytes: if the watch says the night ran from
            // 23:41 to 07:12 in four stages, that is checkable at a glance against having
            // been there. Storage follows confirmation, not the other way round.
            CmfCommand.SLEEP_DATA -> {
                val session = CmfParsers.parseSleep(message.payload)
                if (message.payload.isEmpty()) {
                    // Not the same as unreadable, and saying so saves looking for a bug in
                    // the parser when the watch simply had nothing to send.
                    ProtocolLog.note("Sleep: the watch answered with nothing")
                } else if (session == null) {
                    ProtocolLog.note("Sleep frame did not fit the expected layout")
                } else {
                    ProtocolLog.note(
                        "Sleep ${clock(session.startTimestamp)}→${clock(session.wakeTimestamp)}, " +
                            "${session.stages.size} stages: " +
                            session.stages.joinToString(" ") {
                                "${it.stage.name.first()}${it.duration}"
                            },
                    )
                }
            }

            // Not stored: Health Connect has no record type for stress, so there is
            // nowhere for it to go beyond the screen until reCMF grows its own history.
            CmfCommand.STRESS ->
                CmfParsers.parseStress(message.payload)
                    .lastOrNull { it.isValid }
                    ?.let { WatchStatus.stress.value = it }

            CmfCommand.BATTERY ->
                CmfParsers.parseBattery(message.payload)?.let { WatchStatus.battery.value = it }

            CmfCommand.FIRMWARE_VERSION_RET ->
                WatchStatus.firmware.value = CmfParsers.parseFirmwareVersion(message.payload)

            CmfCommand.SERIAL_NUMBER_RET ->
                WatchStatus.serialNumber.value = CmfParsers.parseSerialNumber(message.payload)

            // A command reCMF knows the name of but has no use for yet — sleep, SpO2,
            // stress, workouts. These arrive from the same fetch as the step counts and
            // used to be dropped here in silence: an unknown opcode at least shows up in
            // the log as unknown, whereas a known one with no branch showed up as nothing
            // at all. Recording them is how we find out what the watch is already sending.
            else -> ProtocolLog.dropped(
                reason = "no handler yet",
                cmd = message.cmd,
                cmd1 = message.cmd.cmd1,
                cmd2 = message.cmd.cmd2,
                payload = message.payload,
            )
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

        // 0x005c/0x0002 is the question and BATTERY is the answer — confirmed: the watch
        // replied `6000` to this, 96% and not charging, matching its own screen. The
        // 0x0001 half was sent as a request for one build to settle which was which; it
        // drew nothing, and is gone.
        connection.send(CmfCommand.BATTERY_GET)
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
        const val ACTION_FIND_WATCH = "dev.recmf.action.FIND_WATCH"
        const val ACTION_STOP_RINGING = "dev.recmf.action.STOP_RINGING"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, WatchService::class.java))
        }

        fun syncNow(context: Context) {
            context.startForegroundService(
                Intent(context, WatchService::class.java).setAction(ACTION_SYNC_NOW),
            )
        }

        /** What the find-phone notification fires to silence itself. */
        fun stopRingingIntent(context: Context): Intent =
            Intent(context, WatchService::class.java).setAction(ACTION_STOP_RINGING)

        /** Makes the watch ring, for the usual reason. */
        fun findWatch(context: Context) {
            context.startForegroundService(
                Intent(context, WatchService::class.java).setAction(ACTION_FIND_WATCH),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, WatchService::class.java).setAction(ACTION_STOP))
        }
    }
}
