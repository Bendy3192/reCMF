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
)

object CmfAlarms {

    /**
     * `ALARMS_SET`: forty bytes per alarm.
     *
     * ```
     * seconds from midnight : u32 little-endian
     * index                 : u8   position in this list
     * enabled               : u8
     * repeat days           : u8   the [CmfWeekday] bitmask
     * 0x01                  : u8   unidentified, and 1 in everything the watch reports
     * 24 bytes of zero
     * 8 bytes of label, sent empty
     * ```
     *
     * The whole list is sent at once and **the watch keeps exactly what it receives** —
     * there is no way to add one alarm. That makes this the same hazard as the sport
     * list: sending a default would delete whatever the wearer had set up elsewhere, so
     * nothing may call this until it holds a list the user actually chose or the watch
     * actually reported.
     *
     * The time is seconds since midnight rather than an hour and a minute, and it is
     * **little-endian**. This was big-endian here for a long time and nothing caught it:
     * [parse] read it back the same way round, so every round-trip test passed while the
     * watch was being sent 03:10 as 2,284,126,208 seconds and collapsing the whole list
     * into a single 00:00. Only the watch's own reply to `ALARMS_GET` settles it, which
     * is why [CmfAlarmsTest] now pins these bytes rather than only the round trip.
     *
     * The last eight bytes are a label, sent empty: Gadgetbridge records that the watch
     * does not display labels at all, and a field that cannot be seen, cannot be read
     * back, and would make the round trip asymmetric is worse than no field.
     */
    fun payload(alarms: List<CmfAlarm>): ByteArray {
        val capped = alarms.take(MAX_ALARMS)
        val buf = ByteBuffer.allocate(capped.size * RECORD_SIZE).order(ByteOrder.LITTLE_ENDIAN)

        capped.forEachIndexed { index, alarm ->
            buf.putInt(alarm.hour * 3600 + alarm.minute * 60)
            buf.put(index.toByte())
            buf.put(if (alarm.enabled) 1 else 0)
            buf.put(alarm.days.fold(0) { mask, day -> mask or day.bit }.toByte())
            buf.put(1) // unidentified, and 1 in every record the watch has reported
            buf.put(ByteArray(UNKNOWN_TAIL))

            buf.put(ByteArray(LABEL_BYTES))
        }

        return buf.array()
    }

    /**
     * Reads back the list the watch holds, in the layout [payload] writes.
     *
     * An empty payload is an empty list, not a malformed one — confirmed against a watch
     * with no alarms set, which answered `ALARMS_GET` with no bytes at all. That is the
     * case that matters most: it is the difference between "the watch has none" and "the
     * watch did not answer", and reading it wrong in the cautious direction would leave
     * the list permanently unreadable.
     */
    fun parse(payload: ByteArray): List<CmfAlarm>? {
        if (payload.size % RECORD_SIZE != 0) return null

        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val out = ArrayList<CmfAlarm>(payload.size / RECORD_SIZE)

        while (buf.remaining() >= RECORD_SIZE) {
            val secondsFromMidnight = buf.int
            buf.get() // index, which is the position in this list
            val enabled = buf.get().toInt() != 0
            val mask = buf.get().toInt() and 0xff
            buf.position(buf.position() + 1 + UNKNOWN_TAIL + LABEL_BYTES)

            out.add(
                CmfAlarm(
                    hour = secondsFromMidnight / 3600,
                    minute = (secondsFromMidnight % 3600) / 60,
                    enabled = enabled,
                    days = CmfWeekday.entries.filter { it.bit and mask != 0 }.toSet(),
                ),
            )
        }

        return out
    }

    /** Beyond this the watch ignores the rest, so the list is cut before it is sent. */
    const val MAX_ALARMS: Int = 8

    const val RECORD_SIZE: Int = 40
    private const val UNKNOWN_TAIL = 24
    private const val LABEL_BYTES = 8

}
