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
}
