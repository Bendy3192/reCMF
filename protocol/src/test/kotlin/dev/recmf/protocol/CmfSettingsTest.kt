package dev.recmf.protocol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CmfSettingsTest {
    @Test
    fun `the three continuous measurements share a command and differ by selector`() {
        assertArrayEquals(byteArrayOf(0x01, 1), CmfSettings.monitoring(MonitoringChannel.HEART_RATE, true))
        assertArrayEquals(byteArrayOf(0x02, 1), CmfSettings.monitoring(MonitoringChannel.SPO2, true))
        assertArrayEquals(byteArrayOf(0x04, 1), CmfSettings.monitoring(MonitoringChannel.STRESS, true))
        assertArrayEquals(byteArrayOf(0x01, 0), CmfSettings.monitoring(MonitoringChannel.HEART_RATE, false))
    }

    @Test
    fun `zero is the 24-hour clock, not the 12-hour one`() {
        assertArrayEquals(byteArrayOf(0), CmfSettings.timeFormat(use24Hour = true))
        assertArrayEquals(byteArrayOf(1), CmfSettings.timeFormat(use24Hour = false))
    }

    @Test
    fun `metric is zero and imperial is one, behind a leading selector`() {
        assertArrayEquals(byteArrayOf(0x01, 0), CmfSettings.measurementSystem(metric = true))
        assertArrayEquals(byteArrayOf(0x01, 1), CmfSettings.measurementSystem(metric = false))
    }

    @Test
    fun `raise to wake is a single byte`() {
        assertArrayEquals(byteArrayOf(1), CmfSettings.wakeOnWristRaise(true))
        assertArrayEquals(byteArrayOf(0), CmfSettings.wakeOnWristRaise(false))
    }

    @Test
    fun `no alerts at all is one zero byte`() {
        assertArrayEquals(byteArrayOf(0), CmfSettings.heartAlerts())
    }

    @Test
    fun `an unset high threshold is sent as 255, not zero`() {
        // Zero would tell the watch to alert above zero beats per minute.
        val payload = CmfSettings.heartAlerts(low = 45)

        assertEquals(9, payload.size)
        assertEquals(0x01, payload[0])
        assertEquals(45, payload[1])
        assertEquals(255.toByte(), payload[2])
        assertEquals(255.toByte(), payload[3])
    }

    @Test
    fun `set thresholds are carried through`() {
        val payload = CmfSettings.heartAlerts(restingHigh = 120, activeHigh = 180, low = 45, spo2Low = 90)

        assertArrayEquals(
            byteArrayOf(0x01, 45, 120, 180.toByte(), 90, 0, 0, 0, 0),
            payload,
        )
    }
}

class CmfAcknowledgementTest {
    @Test
    fun `a generic command is acknowledged as cmd1 over 0003`() {
        // Observed on firmware 1.0.0.73: every setting comes back this way.
        assertEquals(CmfCommand.GOALS_SET, CmfCommand.acknowledgedBy(0x005e, 0x0003))
        assertEquals(CmfCommand.TIME_FORMAT, CmfCommand.acknowledgedBy(0x005f, 0x0003))
        assertEquals(CmfCommand.WAKE_ON_WRIST_RAISE, CmfCommand.acknowledgedBy(0x0062, 0x0003))
        assertEquals(
            CmfCommand.HEART_MONITORING_ENABLED_SET,
            CmfCommand.acknowledgedBy(0x009b, 0x0003),
        )
    }

    @Test
    fun `a vendor command is acknowledged with 9 replaced by a`() {
        assertEquals(CmfCommand.UNIT_LENGTH, CmfCommand.acknowledgedBy(0xffff, 0xa067))
        assertEquals(CmfCommand.UNIT_TEMPERATURE, CmfCommand.acknowledgedBy(0xffff, 0xa068))
    }

    @Test
    fun `a frame that is not an acknowledgement is not mistaken for one`() {
        assertNull(CmfCommand.acknowledgedBy(0x0056, 0x0001)) // ACTIVITY_DATA itself
        assertNull(CmfCommand.acknowledgedBy(0x1234, 0x0003)) // no such command to ack
        assertNull(CmfCommand.acknowledgedBy(0xffff, 0xa999)) // no such vendor command
    }

    @Test
    fun `commands that already have a named acknowledgement keep it`() {
        // ACTIVITY_FETCH_2 is 0xffff/0x9057 and its ack 0xffff/0xa057 is a command in its
        // own right, so the rule must agree with the table rather than shadow it.
        assertEquals(CmfCommand.ACTIVITY_FETCH_2, CmfCommand.acknowledgedBy(0xffff, 0xa057))
        assertEquals(CmfCommand.ACTIVITY_FETCH_ACK_2, CmfCommand.fromCodes(0xffff, 0xa057))
    }
}

class CmfReminderTest {
    @Test
    fun `a reminder is eleven bytes of interval and quiet hours`() {
        val payload = CmfSettings.reminder(
            enabled = true,
            intervalMinutes = 60,
            quietStartSeconds = 12 * 3600,
            quietEndSeconds = 14 * 3600,
        )

        assertEquals(11, payload.size)
        assertEquals(1, payload[0].toInt())
        assertEquals(60, ByteBuffer.wrap(payload, 1, 2).order(ByteOrder.BIG_ENDIAN).short.toInt())
        assertEquals(12 * 3600, ByteBuffer.wrap(payload, 3, 4).order(ByteOrder.BIG_ENDIAN).int)
        assertEquals(14 * 3600, ByteBuffer.wrap(payload, 7, 4).order(ByteOrder.BIG_ENDIAN).int)
    }

    @Test
    fun `quiet hours are cleared when the reminder is off`() {
        val payload = CmfSettings.reminder(false, 60, 12 * 3600, 14 * 3600)

        assertEquals(0, payload[0].toInt())
        assertEquals(0, ByteBuffer.wrap(payload, 3, 4).order(ByteOrder.BIG_ENDIAN).int)
        assertEquals(0, ByteBuffer.wrap(payload, 7, 4).order(ByteOrder.BIG_ENDIAN).int)
    }

    @Test
    fun `an empty quiet window is sent as none, not as all day`() {
        val payload = CmfSettings.reminder(true, 30, 9 * 3600, 9 * 3600)

        assertEquals(0, ByteBuffer.wrap(payload, 3, 4).order(ByteOrder.BIG_ENDIAN).int)
        assertEquals(0, ByteBuffer.wrap(payload, 7, 4).order(ByteOrder.BIG_ENDIAN).int)
    }

    @Test
    fun `an interval the watch would reject is clamped`() {
        val payload = CmfSettings.reminder(true, 999)

        assertEquals(
            CmfSettings.MAX_REMINDER_INTERVAL_MINUTES,
            ByteBuffer.wrap(payload, 1, 2).order(ByteOrder.BIG_ENDIAN).short.toInt(),
        )
    }
}

class CmfSportTypesTest {
    @Test
    fun `the list is a count followed by the codes`() {
        val payload = CmfSettings.sportTypes(
            listOf(CmfActivityType.OUTDOOR_RUNNING, CmfActivityType.YOGA, CmfActivityType.BOXING),
        )

        assertArrayEquals(
            byteArrayOf(3, 0x02, 0x0F, 0x21),
            payload,
        )
    }

    @Test
    fun `an empty choice falls back rather than leaving the watch with no sports`() {
        val payload = CmfSettings.sportTypes(emptyList())

        assertArrayEquals(
            byteArrayOf(2, CmfActivityType.OUTDOOR_RUNNING.code, CmfActivityType.INDOOR_RUNNING.code),
            payload,
        )
    }

    @Test
    fun `duplicates are dropped and the list is capped`() {
        val duplicated = List(4) { CmfActivityType.YOGA }
        assertEquals(2, CmfSettings.sportTypes(duplicated).size)

        val everything = CmfActivityType.entries.toList()
        assertEquals(CmfSettings.MAX_SPORT_TYPES + 1, CmfSettings.sportTypes(everything).size)
    }

    @Test
    fun `codes above 0x7f survive as the watch wrote them`() {
        // A byte is signed in Kotlin; 0x92 must not become 0x7f or wrap to something else.
        assertEquals(0x92.toByte(), CmfActivityType.COOLDOWN.code)
        assertEquals(CmfActivityType.COOLDOWN, CmfActivityType.fromCode(0x92.toByte()))
    }

    @Test
    fun `every code is distinct`() {
        val codes = CmfActivityType.entries.map { it.code }
        assertEquals(codes.size, codes.distinct().size)
    }

    @Test
    fun `a reminder read back is the same eleven bytes read the other way`() {
        // Captured from a real watch: both reminders answered this. Off, every sixty
        // minutes, no quiet window.
        val payload = byteArrayOf(0, 0, 0x3c, 0, 0, 0, 0, 0, 0, 0, 0)

        assertEquals(
            ReminderState(enabled = false, intervalMinutes = 60, 0, 0),
            CmfSettings.parseReminder(payload),
        )
    }

    @Test
    fun `what reminder builds, parseReminder reads`() {
        val built = CmfSettings.reminder(
            enabled = true,
            intervalMinutes = 45,
            quietStartSeconds = 22 * 3600,
            quietEndSeconds = 7 * 3600,
        )

        assertEquals(
            ReminderState(true, 45, 22 * 3600, 7 * 3600),
            CmfSettings.parseReminder(built),
        )
    }

    @Test
    fun `a reminder payload of the wrong length is refused`() {
        assertNull(CmfSettings.parseReminder(ByteArray(10)))
        assertNull(CmfSettings.parseReminder(ByteArray(12)))
    }
}
