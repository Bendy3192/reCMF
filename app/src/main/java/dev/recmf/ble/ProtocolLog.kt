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
    enum class Direction { OUT, IN, DROP, NOTE }

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

    fun dropped(cmd: CmfCommand?, reason: String) {
        record(Direction.DROP, cmd?.name ?: "unknown frame", reason)
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

    private const val CAPACITY = 200
    private const val MAX_PAYLOAD_BYTES = 24
}
