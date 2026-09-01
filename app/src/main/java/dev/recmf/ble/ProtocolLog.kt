/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.ble

import dev.recmf.protocol.CmfCommand
import dev.recmf.protocol.toHex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A bounded, in-memory record of what was said to the watch and what came back.
 *
 * A protocol this app cannot observe is a protocol it cannot be fixed against: the
 * failure that matters — "connected, but no data" — leaves no trace in the UI and the
 * user is not going to read logcat. So the exchange is kept here and shown in the app.
 *
 * Bounded on both axes: at most [CAPACITY] entries, each with at most [MAX_PAYLOAD_BYTES]
 * of payload rendered. This has to be able to run for days without growing.
 */
object ProtocolLog {
    enum class Direction { OUT, IN, ACK, DROP, NOTE }

    data class Entry(
        val atMillis: Long,
        val direction: Direction,
        val label: String,
        val detail: String? = null,
    )

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun sent(cmd: CmfCommand, payload: ByteArray, ok: Boolean) {
        record(
            if (ok) Direction.OUT else Direction.DROP,
            cmd.name,
            buildString {
                if (!ok) append("write failed")
                if (payload.isNotEmpty()) {
                    if (isNotEmpty()) append(" · ")
                    append(payload.preview())
                }
            }.ifEmpty { null },
        )
    }

    fun received(cmd: CmfCommand, payload: ByteArray) {
        record(Direction.IN, cmd.name, payload.takeIf { it.isNotEmpty() }?.preview())
    }

    /** The watch confirming a command it applied. */
    /**
     * [payload] is empty for an ordinary confirmation and shown when it is not.
     *
     * An acknowledging opcode is not always an acknowledgement: frames arrived under one
     * during a workout with nothing outstanding to confirm, and reading them as "applied"
     * hid whatever they were actually saying.
     */
    fun acknowledged(cmd: CmfCommand, payload: ByteArray = ByteArray(0)) {
        record(
            Direction.ACK,
            cmd.name,
            if (payload.isEmpty()) "applied" else "${payload.size} B: ${payload.toHex()}",
        )
    }

    fun dropped(
        cmd: CmfCommand?,
        reason: String,
        cmd1: Int? = null,
        cmd2: Int? = null,
        payload: ByteArray? = null,
    ) {
        val label = cmd?.name
            ?: if (cmd1 != null && cmd2 != null) {
                // The numbers are the only handle on a command reCMF does not know yet.
                "unknown %04x/%04x".format(cmd1, cmd2)
            } else {
                "unknown frame"
            }

        val detail = if (payload != null && payload.isNotEmpty()) {
            "$reason · ${payload.preview()}"
        } else {
            reason
        }

        record(Direction.DROP, label, detail)
    }

    fun note(text: String) {
        record(Direction.NOTE, text)
    }

    fun clear() {
        _entries.value = emptyList()
    }

    private fun record(direction: Direction, label: String, detail: String? = null) {
        val entry = Entry(System.currentTimeMillis(), direction, label, detail)
        _entries.value = (_entries.value + entry).takeLast(CAPACITY)
    }

    /** Long payloads are truncated: an activity backlog is kilobytes and unreadable anyway. */
    private fun ByteArray.preview(): String = if (size <= MAX_PAYLOAD_BYTES) {
        "$size B: ${toHex()}"
    } else {
        "$size B: ${copyOf(MAX_PAYLOAD_BYTES).toHex()}…"
    }

    /**
     * Two hundred entries was a couple of hours on a phone syncing every five minutes,
     * which is short enough that a frame arriving at six in the morning was gone before
     * anyone looked at it. That is how a night of sleep went unexamined.
     */
    private const val CAPACITY = 600
    /**
     * Enough to hold an unidentified frame whole. The first unknown command to arrive was
     * 28 bytes and the preview cut it at 24 — losing exactly the tail that would say what
     * it is. 200 entries of this is still tens of kilobytes.
     */
    private const val MAX_PAYLOAD_BYTES = 64
}
