package dev.recmf.protocol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CmfSettingsTest {
    @Test
    fun `the three continuous measurements share a command and differ by selector`() {
        assertArrayEquals(byteArrayOf(0x01, 1), CmfSettings.monitoring(MonitoringChannel.HEART_RATE, true))
        assertArrayEquals(byteArrayOf(0x02, 1), CmfSettings.monitoring(MonitoringChannel.SPO2, true))
        assertArrayEquals(byteArrayOf(0x04, 1), CmfSettings.monitoring(MonitoringChannel.STRESS, true))
        assertArrayEquals(byteArrayOf(0x01, 0), CmfSettings.monitoring(MonitoringChannel.HEART_RATE, false))
    }

    @Test
    fun `goals are big-endian and in the documented slots`() {
        val payload = CmfSettings.goals(steps = 8000, distanceMeters = 5000, calories = 300)

        assertEquals(10, payload.size)
        assertArrayEquals(
            byteArrayOf(
                0, 0,
                0x1f, 0x40.toByte(), // 8000
                0, 0,
                0x13, 0x88.toByte(), // 5000
                0x01, 0x2c, //          300
            ),
            payload,
        )
    }

    @Test
    fun `a goal too large to fit is clamped, not wrapped`() {
        // 70000 as a raw unsigned short is 4464 — a goal the user never asked for.
        val payload = CmfSettings.goals(steps = 70_000, distanceMeters = 0, calories = 0)

        assertArrayEquals(byteArrayOf(0xff.toByte(), 0xff.toByte()), payload.copyOfRange(2, 4))
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
