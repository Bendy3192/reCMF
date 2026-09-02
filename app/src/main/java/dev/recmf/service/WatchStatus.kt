/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.service

import dev.recmf.ble.ConnectionState
import dev.recmf.protocol.BatteryStatus
import dev.recmf.protocol.WatchfaceList
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Process-wide view of the live connection.
 *
 * The UI reads this instead of binding to [WatchService]. A bound service would keep the
 * service alive for as long as the activity is up and complicate its lifecycle for no
 * gain — the service's real owner is the foreground notification, not the UI.
 *
 * These flows describe the current process only; after a restart they start empty and
 * are repopulated as the service reconnects. Anything that must survive a restart lives
 * in the database or in settings instead.
 */
object WatchStatus {
    val state = MutableStateFlow(ConnectionState.IDLE)
    val battery = MutableStateFlow<BatteryStatus?>(null)
    val firmware = MutableStateFlow<String?>(null)
    val serialNumber = MutableStateFlow<String?>(null)
    val lastSyncEpochSeconds = MutableStateFlow<Long?>(null)

    /**
     * The newest activity record the watch has handed over, by the watch's own clock,
     * and how many records came with it.
     *
     * This is the only view reCMF has of what the watch believes the date to be. Every
     * figure in the app is filtered against the phone's midnight, so a watch whose clock
     * has drifted reports its steps under a date the phone will not count — and the app
     * shows a zero that looks exactly like a day without walking. Keeping the raw
     * timestamp lets the two be told apart.
     */
    val lastRecordEpochSeconds = MutableStateFlow<Long?>(null)
    val lastRecordCount = MutableStateFlow<Int?>(null)

    /** When a forecast last reached the watch, and what temperature went with it. */
    val weatherSentAtMillis = MutableStateFlow<Long?>(null)
    val weatherTemperatureC = MutableStateFlow<Int?>(null)

    /** Why there is no forecast on the watch, when there is not one. */
    val weatherProblem = MutableStateFlow<WeatherProblem?>(null)

    /**
     * Why the phone's alarms are not reaching the watch, when the mirror is on.
     *
     * Null while the mirror is off, and null again the moment a read succeeds. This is the
     * one setting in reCMF that can be switched on and still do nothing on a perfectly
     * healthy phone, so the reason has to be able to reach the screen.
     */
    val alarmMirrorProblem = MutableStateFlow<AlarmMirrorProblem?>(null)

    /**
     * When the watch last finished answering a fetch, whether or not it had anything new.
     *
     * Distinct from [lastRecordEpochSeconds], which is the date on the data, and from
     * [lastSyncEpochSeconds], which is the last Health Connect write. A watch that has
     * nothing new to report is a successful sync, and this is the only one of the three
     * that moves when that happens — without it, a working Sync button and a dead one
     * look exactly the same.
     */
    val lastExchangeAtMillis = MutableStateFlow<Long?>(null)

    /**
     * How an install is going, or null when none is.
     *
     * Not persisted and not resumable: the watch asks for the file in pieces and holds no
     * position across a disconnection, so an interrupted install is one that has to start
     * again. Saying so honestly is better than a progress bar that resumes into nothing.
     */
    val watchfaceInstall = MutableStateFlow<WatchfaceInstall?>(null)

    /**
     * The watchfaces the watch reported, and which of them it is showing.
     *
     * Here rather than in the database because it is not history: it is what the watch
     * said this connection, and a face changed on the wrist would make a stored copy a
     * lie until the next sync.
     */
    val watchfaces = MutableStateFlow<WatchfaceList?>(null)
}

/**
 * The reasons a forecast fails to reach the watch, as far apart as they need to be for
 * the user to know what to do about each.
 */
enum class WeatherProblem {
    /** No place has been resolved yet, so there is nothing to ask the provider about. */
    NO_CITY,

    /** The provider could not be reached, or answered something unreadable. */
    UNREACHABLE,
}

/**
 * Why mirroring the phone's alarms is not working.
 *
 * Two failures that want opposite things from the wearer, which is the whole reason they
 * are separate values rather than one "did not work".
 */
enum class AlarmMirrorProblem {
    /** The clock is there and closed. Root opens it; nothing else will. */
    NEEDS_ROOT,

    /** Nothing readable at that address — a clock that is not AOSP's. Root will not help. */
    NO_CLOCK,
}

/** Where an install has got to, in the words the screen uses. */
sealed interface WatchfaceInstall {
    data class Sending(val name: String, val percent: Int) : WatchfaceInstall
    data class Done(val name: String) : WatchfaceInstall
    data class Failed(val reason: String) : WatchfaceInstall
}
