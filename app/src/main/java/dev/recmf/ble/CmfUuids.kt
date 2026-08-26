/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 *
 * UUIDs reverse engineered by the Gadgetbridge project; see NOTICE.
 */
package dev.recmf.ble

import java.util.UUID

/**
 * The watch exposes three services: a command channel, a bulk-data channel (firmware,
 * watchfaces, A-GPS) and a plaintext "shell" used only during pairing.
 */
object CmfUuids {
    val SERVICE_COMMAND: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    val CHARACTERISTIC_COMMAND_READ: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
    val CHARACTERISTIC_COMMAND_WRITE: UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")

    val SERVICE_DATA: UUID = UUID.fromString("02f00000-0000-0000-0000-00000000ffe0")
    val CHARACTERISTIC_DATA_WRITE: UUID = UUID.fromString("02f00000-0000-0000-0000-00000000ffe1")
    val CHARACTERISTIC_DATA_READ: UUID = UUID.fromString("02f00000-0000-0000-0000-00000000ffe2")

    val SERVICE_SHELL: UUID = UUID.fromString("77d4e67c-2fe2-2334-0d35-9ccd078f529c")
    val CHARACTERISTIC_SHELL_WRITE: UUID = UUID.fromString("77d4ff01-2fe2-2334-0d35-9ccd078f529c")
    val CHARACTERISTIC_SHELL_READ: UUID = UUID.fromString("77d4ff02-2fe2-2334-0d35-9ccd078f529c")

    /** Client Characteristic Configuration — the standard notification-enable descriptor. */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
