package dev.recmf.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CmfAlarmsTest {

    /**
     * Two records a real watch answered `ALARMS_GET` with, byte for byte.
     *
     * They were put there by the official app, so they are the watch's own idea of the
     * format rather than a copy of ours: 03:10 on Wednesdays and 06:10 on weekdays.
     */
    private val fromTheWatch = (
        "882c0000" + "00" + "01" + "04" + "01" + "00".repeat(32) +
            "b8560000" + "01" + "01" + "1f" + "01" + "00".repeat(32)
        ).hexToBytes()

    @Test
    fun `the watch's own alarms read back as the times it shows`() {
        // The test that matters. Everything below it round-trips our own bytes, which
        // stayed green for months while the time field was written the wrong way round —
        // parse read it back reversed too, so the two agreed with each other and with
        // nothing else. Only these bytes, which we did not write, catch that.
        val alarms = CmfAlarms.parse(fromTheWatch)

        assertEquals(
            listOf(
                CmfAlarm(3, 10, enabled = true, days = setOf(CmfWeekday.WEDNESDAY)),
                CmfAlarm(
                    6, 10, enabled = true,
                    days = setOf(
                        CmfWeekday.MONDAY, CmfWeekday.TUESDAY, CmfWeekday.WEDNESDAY,
                        CmfWeekday.THURSDAY, CmfWeekday.FRIDAY,
                    ),
                ),
            ),
            alarms,
        )
    }

    @Test
    fun `what we send the watch is the mirror of what it sends back`() {
        // The write is big-endian and the read is little-endian: the watch does not answer
        // in the order it accepts. Both halves are pinned here against the same two
        // alarms so the asymmetry is visible rather than inferred, and so nobody
        // "corrects" one side into agreeing with the other — which is exactly what a
        // round-trip test tempted someone into doing, twice.
        val payload = CmfAlarms.payload(
            listOf(
                CmfAlarm(3, 10, enabled = true, days = setOf(CmfWeekday.WEDNESDAY)),
                CmfAlarm(
                    6, 10, enabled = true,
                    days = setOf(
                        CmfWeekday.MONDAY, CmfWeekday.TUESDAY, CmfWeekday.WEDNESDAY,
                        CmfWeekday.THURSDAY, CmfWeekday.FRIDAY,
                    ),
                ),
            ),
        )

        assertEquals(
            (
                "00002c88" + "00" + "01" + "04" + "01" + "00".repeat(32) +
                    "000056b8" + "01" + "01" + "1f" + "01" + "00".repeat(32)
                ),
            payload.toHex(),
        )
    }

    @Test
    fun `an alarm is forty bytes with the time in seconds from midnight`() {
        val payload = CmfAlarms.payload(listOf(CmfAlarm(hour = 7, minute = 30)))

        assertEquals(CmfAlarms.RECORD_SIZE, payload.size)
        // 7 * 3600 + 30 * 60 = 27000 = 0x6978, big-endian as the watch accepts it.
        assertEquals("00006978", payload.copyOf(4).toHex())
    }

    @Test
    fun `the records go out in the watch's own order, whatever order they arrive in`() {
        // The caller picks which alarms survive — by when they next ring, which is not
        // time of day — and the watch wants them by time of day. A list in another order
        // was acknowledged and then not stored.
        val payload = CmfAlarms.payload(
            listOf(CmfAlarm(16, 38), CmfAlarm(3, 10), CmfAlarm(6, 10)),
        )

        assertEquals(
            listOf(3 * 3600 + 10 * 60, 6 * 3600 + 10 * 60, 16 * 3600 + 38 * 60),
            (0 until 3).map { seconds(payload, it) },
        )
        // And renumbered to match, since the index is the position in the list sent.
        assertEquals(listOf(0, 1, 2), (0 until 3).map { payload[it * CmfAlarms.RECORD_SIZE + 4].toInt() })
    }

    @Test
    fun `sorting for the wire happens after the cut, not before it`() {
        // Otherwise the eight kept would be the eight earliest in the day rather than the
        // eight that ring soonest, which is the opposite of what the caller asked for.
        val late = CmfAlarm(23, 0)
        val early = List(CmfAlarms.MAX_ALARMS) { CmfAlarm(hour = it, minute = 0) }

        val payload = CmfAlarms.payload(listOf(late) + early)

        assertEquals(CmfAlarms.MAX_ALARMS, payload.size / CmfAlarms.RECORD_SIZE)
        // The 23:00 was first in, so it survives the cut; 07:00 was ninth and does not.
        assertEquals(23 * 3600, seconds(payload, CmfAlarms.MAX_ALARMS - 1))
    }

    /** The time of one record of a payload we built, so big-endian. */
    private fun seconds(payload: ByteArray, record: Int): Int {
        val at = record * CmfAlarms.RECORD_SIZE
        return ((payload[at].toInt() and 0xff) shl 24) or
            ((payload[at + 1].toInt() and 0xff) shl 16) or
            ((payload[at + 2].toInt() and 0xff) shl 8) or
            (payload[at + 3].toInt() and 0xff)
    }

    @Test
    fun `repeat days become the watch's bitmask`() {
        val payload = CmfAlarms.payload(
            listOf(
                CmfAlarm(
                    hour = 6,
                    minute = 0,
                    days = setOf(CmfWeekday.MONDAY, CmfWeekday.WEDNESDAY, CmfWeekday.FRIDAY),
                ),
            ),
        )

        assertEquals(1 or 4 or 16, payload[6].toInt() and 0xff)
    }

    @Test
    fun `no repeat days is a mask of zero, which the watch reads as ringing once`() {
        val payload = CmfAlarms.payload(listOf(CmfAlarm(hour = 6, minute = 0)))

        assertEquals(0, payload[6].toInt() and 0xff)
    }

    @Test
    fun `alarms are numbered by their position in the list`() {
        val payload = CmfAlarms.payload(
            listOf(CmfAlarm(7, 0), CmfAlarm(8, 0), CmfAlarm(9, 0)),
        )

        assertEquals(0, payload[4].toInt())
        assertEquals(1, payload[CmfAlarms.RECORD_SIZE + 4].toInt())
        assertEquals(2, payload[2 * CmfAlarms.RECORD_SIZE + 4].toInt())
    }

    @Test
    fun `a disabled alarm still occupies its record`() {
        val payload = CmfAlarms.payload(listOf(CmfAlarm(7, 0, enabled = false)))

        assertEquals(CmfAlarms.RECORD_SIZE, payload.size)
        assertEquals(0, payload[5].toInt())
    }

    @Test
    fun `more alarms than the watch holds are cut rather than sent`() {
        val payload = CmfAlarms.payload(List(20) { CmfAlarm(hour = it % 24, minute = 0) })

        assertEquals(CmfAlarms.MAX_ALARMS * CmfAlarms.RECORD_SIZE, payload.size)
    }

    @Test
    fun `an empty list produces an empty payload, which the watch reads as delete them all`() {
        // This used to say the opposite — that an empty payload meant "no change
        // requested" — and that was a guess dressed as a fact. Watched on hardware: with
        // every phone alarm switched off, reCMF sent this, the watch answered "applied",
        // and the read straight afterwards came back with nothing. An empty list is a
        // wipe, so nothing may send one that the user did not ask for.
        assertTrue(CmfAlarms.payload(emptyList()).isEmpty())
    }

    @Test
    fun `an empty reply is no alarms, not a broken one`() {
        // What a real watch with nothing set answers ALARMS_GET with: no bytes at all.
        assertEquals(emptyList<CmfAlarm>(), CmfAlarms.parse(ByteArray(0)))
    }

    @Test
    fun `a reply that is not a whole number of records is refused`() {
        assertNull(CmfAlarms.parse(ByteArray(CmfAlarms.RECORD_SIZE + 7)))
    }

    @Test
    fun `midnight and one minute to midnight both encode`() {
        val payload = CmfAlarms.payload(listOf(CmfAlarm(0, 0), CmfAlarm(23, 59)))

        assertEquals(listOf(0, 23 * 3600 + 59 * 60), (0 until 2).map { seconds(payload, it) })
    }

    @Test
    fun `every repeat day encodes into the one mask byte`() {
        val payload = CmfAlarms.payload(listOf(CmfAlarm(6, 0, days = CmfWeekday.entries.toSet())))

        assertEquals(0x7f, payload[6].toInt() and 0xff)
    }
}
