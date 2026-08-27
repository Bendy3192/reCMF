/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.service

import dev.recmf.ble.ConnectionState
import dev.recmf.protocol.BatteryStatus
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
