/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 * See LICENSE and NOTICE at the repository root.
 */
package dev.recmf.ble

/** Where the link to the watch currently is. Ordered roughly as the handshake runs. */
enum class ConnectionState {
    /** No watch is configured, or the user asked us to stop. */
    IDLE,

    /** Waiting for the watch to come back into range, or backing off after a failure. */
    WAITING,

    CONNECTING,

    /** GATT is up; services, MTU and notifications are being set up. */
    INITIALIZING,

    /** Running the pairing or session-key handshake. */
    AUTHENTICATING,

    /** Authenticated — commands and sync can run. */
    READY,
    ;

    val isUsable: Boolean get() = this == READY
}

/** Why the link went down, when we know. Surfaced to the UI so failures are legible. */
sealed interface ConnectionFailure {
    /** The watch rejected our key; the user has to pair again. */
    data object AuthRejected : ConnectionFailure

    /** Bluetooth is off, or the runtime permission was revoked. */
    data class Unavailable(val reason: String) : ConnectionFailure

    /** GATT reported an error status; [status] is the raw Android code. */
    data class GattError(val status: Int) : ConnectionFailure

    /** Dropped out of range, or the watch closed the link. */
    data object LinkLost : ConnectionFailure
}
