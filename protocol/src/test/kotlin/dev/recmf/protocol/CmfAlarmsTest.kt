package dev.recmf.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CmfAlarmsTest {

    @Test
    fun `an alarm is forty bytes with the time in seconds from midnight`() {
        val payload = CmfAlarms.payload(listOf(CmfAlarm(hour = 7, minute = 30)))

        assertEquals(CmfAlarms.RECORD_SIZE, payload.size)
        // 7 * 3600 + 30 * 60 = 27000 = 0x6978, big-endian.
        assertEquals("00006978", payload.copyOf(4).toHex())
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
    fun `a Cyrillic label is cut on a character boundary`() {
        // Eight bytes is four Cyrillic characters, and cutting at the byte would send the
        // watch half of the fifth.
        val payload = CmfAlarms.payload(listOf(CmfAlarm(7, 0, label = "Подъём")))
        val label = payload.copyOfRange(CmfAlarms.RECORD_SIZE - 8, CmfAlarms.RECORD_SIZE)

        val text = String(label.dropWhile { it == 0.toByte() }.toByteArray(), Charsets.UTF_8)
        assertEquals("Подъ", text)
    }

    @Test
    fun `more alarms than the watch holds are cut rather than sent`() {
        val payload = CmfAlarms.payload(List(20) { CmfAlarm(hour = it % 24, minute = 0) })

        assertEquals(CmfAlarms.MAX_ALARMS * CmfAlarms.RECORD_SIZE, payload.size)
    }

    @Test
    fun `an empty list produces an empty payload rather than a wiping one`() {
        // Worth pinning: the watch keeps exactly what it receives, so this is the
        // difference between "no change requested" and "delete every alarm".
        assertTrue(CmfAlarms.payload(emptyList()).isEmpty())
    }
}
