package dev.recmf.protocol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CmfParsersTest {
    private fun activityRecord(ts: Int, steps: Int, distance: Int, calories: Int): ByteArray =
        ByteBuffer.allocate(CmfParsers.ACTIVITY_RECORD_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(ts).putInt(steps).putInt(distance).putInt(calories)
            .array()

    private fun heartRateRecord(ts: Int, bpm: Int): ByteArray =
        ByteBuffer.allocate(CmfParsers.HEART_RATE_RECORD_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(ts).putInt(bpm)
            .array()

    @Test
    fun `activity records decode in order`() {
        val payload = activityRecord(1_700_000_000, 120, 90, 5) +
            activityRecord(1_700_000_060, 0, 0, 1)

        val samples = CmfParsers.parseActivity(payload)

        assertEquals(
            listOf(
                ActivitySample(1_700_000_000, 120, 90, 5),
                ActivitySample(1_700_000_060, 0, 0, 1),
            ),
            samples,
        )
    }

    @Test
    fun `a payload that is not a whole number of records is refused`() {
        assertTrue(CmfParsers.parseActivity(ByteArray(31)).isEmpty())
        assertTrue(CmfParsers.parseActivity(ByteArray(33)).isEmpty())
        assertTrue(CmfParsers.parseActivity(ByteArray(0)).isEmpty())
        assertTrue(CmfParsers.parseHeartRate(ByteArray(7)).isEmpty())
        assertTrue(CmfParsers.parseHeartRate(ByteArray(0)).isEmpty())
    }

    @Test
    fun `timestamps past 2038 stay in the future`() {
        // 0x80000000 seconds is 2038-01-19; a signed read would land it in 1901.
        val samples = CmfParsers.parseActivity(activityRecord(Int.MIN_VALUE, 1, 1, 1))

        assertEquals(2_147_483_648L, samples.single().timestamp)
    }

    @Test
    fun `heart rate records decode`() {
        val payload = heartRateRecord(1_700_000_000, 62) + heartRateRecord(1_700_000_060, 71)

        assertEquals(
            listOf(HeartRateSample(1_700_000_000, 62), HeartRateSample(1_700_000_060, 71)),
            CmfParsers.parseHeartRate(payload),
        )
    }

    @Test
    fun `an unmeasured minute is not a heart rate of zero`() {
        assertFalse(HeartRateSample(1_700_000_000, 0).isValid)
        assertFalse(HeartRateSample(1_700_000_000, 255).isValid)
        assertTrue(HeartRateSample(1_700_000_000, 62).isValid)
    }

    @Test
    fun `battery decodes level and charging flag`() {
        assertEquals(BatteryStatus(85, false), CmfParsers.parseBattery(byteArrayOf(85, 0)))
        assertEquals(BatteryStatus(85, true), CmfParsers.parseBattery(byteArrayOf(85, 1)))
        assertEquals(100, CmfParsers.parseBattery(byteArrayOf(200.toByte(), 0))?.levelPercent)
        assertNull(CmfParsers.parseBattery(byteArrayOf(85)))
    }

    @Test
    fun `fetch acknowledgements map to states`() {
        assertEquals(ActivityFetchState.READY, CmfParsers.parseFetchState(byteArrayOf(1)))
        assertEquals(ActivityFetchState.FINISHED, CmfParsers.parseFetchState(byteArrayOf(2)))
        assertNull(CmfParsers.parseFetchState(byteArrayOf(9)))
        assertNull(CmfParsers.parseFetchState(ByteArray(0)))
    }

    @Test
    fun `firmware version joins its bytes`() {
        assertEquals("1.0.0.51", CmfParsers.parseFirmwareVersion(byteArrayOf(1, 0, 0, 51)))
        assertNull(CmfParsers.parseFirmwareVersion(ByteArray(0)))
    }

    @Test
    fun `serial number honours its length prefix`() {
        val serial = "D398ABC"
        val payload = byteArrayOf(serial.length.toByte()) + serial.toByteArray()

        assertEquals(serial, CmfParsers.parseSerialNumber(payload))
        assertNull(CmfParsers.parseSerialNumber(byteArrayOf(9, 65, 66)))
        assertNull(CmfParsers.parseSerialNumber(ByteArray(0)))
    }

    @Test
    fun `the time payload is big-endian, unlike sample payloads`() {
        val payload = CmfParsers.buildTimePayload(0x01020304, 3 * 3600 * 1000)

        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04), payload.copyOfRange(0, 4))
        assertEquals(3 * 3600 * 1000, ByteBuffer.wrap(payload, 4, 4).order(ByteOrder.BIG_ENDIAN).int)
    }
}
