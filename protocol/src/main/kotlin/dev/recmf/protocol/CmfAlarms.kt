/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 *
 * Payload layout ported from Gadgetbridge (AGPL-3.0-or-later); see NOTICE.
 */
package dev.recmf.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The repeat days, as the watch's bitmask numbers them. */
enum class CmfWeekday(val bit: Int) {
    MONDAY(1),
    TUESDAY(2),
    WEDNESDAY(4),
    THURSDAY(8),
    FRIDAY(16),
    SATURDAY(32),
    SUNDAY(64),
}

/**
 * One alarm.
 *
 * An empty [days] means it rings once and does not repeat, which is how the watch reads a
 * repeat mask of zero.
 */
data class CmfAlarm(
    val hour: Int,
    val minute: Int,
    val enabled: Boolean = true,
    val days: Set<CmfWeekday> = emptySet(),
    val label: String = "",
)

object CmfAlarms {

    /**
     * `ALARMS_SET`: forty bytes per alarm, big-endian.
     *
     * The whole list is sent at once and **the watch keeps exactly what it receives** —
     * there is no way to add one alarm. That makes this the same hazard as the sport
     * list: sending a default would delete whatever the wearer had set up elsewhere, so
     * nothing may call this until it holds a list the user actually chose or the watch
     * actually reported.
     *
     * The time is seconds since midnight rather than an hour and a minute, and the label
     * is right-aligned in its eight bytes — which looks wrong but is what Gadgetbridge
     * sends, and Gadgetbridge records that the watch does not display labels at all, so
     * there is nothing to check it against. Following the known-working layout beats
     * improving on it blind.
     */
    fun payload(alarms: List<CmfAlarm>): ByteArray {
        val capped = alarms.take(MAX_ALARMS)
        val buf = ByteBuffer.allocate(capped.size * RECORD_SIZE).order(ByteOrder.BIG_ENDIAN)

        capped.forEachIndexed { index, alarm ->
            buf.putInt(alarm.hour * 3600 + alarm.minute * 60)
            buf.put(index.toByte())
            buf.put(if (alarm.enabled) 1 else 0)
            buf.put(alarm.days.fold(0) { mask, day -> mask or day.bit }.toByte())
            buf.put(0xff.toByte()) // unidentified, and constant in every capture
            buf.put(ByteArray(UNKNOWN_TAIL))

            val label = alarm.label.truncateToUtf8Bytes(LABEL_BYTES)
            buf.put(ByteArray(LABEL_BYTES - label.size))
            buf.put(label)
        }

        return buf.array()
    }

    /** Beyond this the watch ignores the rest, so the list is cut before it is sent. */
    const val MAX_ALARMS: Int = 8

    const val RECORD_SIZE: Int = 40
    private const val UNKNOWN_TAIL = 24
    private const val LABEL_BYTES = 8
}
