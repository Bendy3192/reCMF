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
     * seconds from midnight : u32 BIG-endian
     * index                 : u8   position in this list
     * enabled               : u8
     * repeat days           : u8   the [CmfWeekday] bitmask
     * 0x01                  : u8   unidentified
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
     * **The watch reads this big-endian and answers little-endian.** That is not a typo
     * and there is no round trip between [payload] and [parse]; the two are deliberately
     * mirror images, and a test that fed one into the other would be testing nothing. Both
     * halves are settled by evidence rather than by symmetry:
     *
     * - Write. Gadgetbridge, which works on this watch, builds the record big-endian.
     *   Sending it little-endian instead produced a watch that stored the index, the
     *   enabled flag and the repeat mask of every record correctly and threw the time
     *   away, keeping whatever was in the slot — one alarm came back reading `153:36`.
     *   A little-endian 14:10 read big-endian is 952,860,672 seconds, which is not a time
     *   of day, so the watch appears to reject the field and keep the rest.
     * - Read. The watch's own reply is little-endian: `88 2c 00 00` against a phone whose
     *   Wednesday alarm was 03:10, and 0x2c88 is 11400 seconds, which is 03:10. It then
     *   displayed a stored `00 70 08 00` as `153:36`, and 0x87000 seconds is 153.6 hours,
     *   so the little-endian reading is the watch's own.
     *
     * Only enabled alarms belong in the list — a slot spent on one that will not ring is
     * a slot taken from one that will — and Gadgetbridge likewise skips its unused ones.
     *
     * The records go out in ascending time of day, which is the order the watch sorts its
     * own list into anyway.
     *
     * The last eight bytes are a label, sent empty: Gadgetbridge records that the watch
     * does not display labels at all, and a field that cannot be seen, cannot be read
     * back, and would make the round trip asymmetric is worse than no field.
     */
    fun payload(alarms: List<CmfAlarm>): ByteArray {
        // Which eight is the caller's decision; what order they go out in is not. The
        // watch stores and displays them by time of day, and the only list it has ever
        // been seen to accept — the one the official app left on it — was in that order.
        // Sorting after the cut rather than before it keeps both: the caller still chooses
        // which alarms survive, and the watch still gets them the way round it wants.
        val capped = alarms.take(MAX_ALARMS).sortedBy { it.hour * 60 + it.minute }
        val buf = ByteBuffer.allocate(capped.size * RECORD_SIZE).order(ByteOrder.BIG_ENDIAN)

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
     * Reads back the list the watch holds.
     *
     * Little-endian, where [payload] writes big-endian; see there for why, and for why
     * feeding one into the other proves nothing.
     *
     * An empty payload is an empty list, not a malformed one — confirmed against a watch
     * with no alarms set, which answered `ALARMS_GET` with no bytes at all. That is the
     * case that matters most: it is the difference between "the watch has none" and "the
     * watch did not answer", and reading it wrong in the cautious direction would leave
     * the list permanently unreadable.
     *
     * Going the other way, an empty payload **written** to the watch deletes every alarm
     * it holds. That is settled on hardware, and it is worth knowing before writing one:
     * with all the phone's alarms switched off, reCMF sent nothing, the watch said applied,
     * and a read taken immediately afterwards found nothing left.
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
