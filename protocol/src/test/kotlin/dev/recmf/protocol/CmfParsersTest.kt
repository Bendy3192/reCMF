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
        ByteBuffer.allocate(CmfParsers.PAIR_RECORD_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(ts).putInt(bpm)
            .array()

    private fun pairRecord(ts: Int, value: Int): ByteArray =
        ByteBuffer.allocate(CmfParsers.PAIR_RECORD_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(ts).putInt(value)
            .array()

    @Test
    fun `resting heart rate decodes the bytes a real watch sent`() {
        // Captured from a Watch Pro 2 on firmware 1.0.0.73: 2026-08-27 05:41:35 UTC, 79
        // bpm. Gadgetbridge leaves this payload unparsed, so this is the record of what
        // it actually contains rather than a restatement of someone else's decoder.
        val payload = byteArrayOf(0x0f, 0xce.toByte(), 0x8f.toByte(), 0x6a, 0x4f)

        assertEquals(
            listOf(HeartRateSample(1_787_809_295L, 79)),
            CmfParsers.parseRestingHeartRate(payload),
        )
    }

    @Test
    fun `resting heart rate reads consecutive records without sliding`() {
        // One byte for the value, not four: reading it as a paired record would swallow
        // the next record's timestamp and report a plausible, wrong series.
        val payload = byteArrayOf(0x0f, 0xce.toByte(), 0x8f.toByte(), 0x6a, 0x4f) +
            byteArrayOf(0xf1.toByte(), 0xcd.toByte(), 0x8f.toByte(), 0x6a, 0x50)

        assertEquals(
            listOf(HeartRateSample(1_787_809_295L, 79), HeartRateSample(1_787_809_265L, 80)),
            CmfParsers.parseRestingHeartRate(payload),
        )
    }

    @Test
    fun `spo2 records decode in order`() {
        val payload = pairRecord(1_700_000_000, 97) + pairRecord(1_700_003_600, 95)

        assertEquals(
            listOf(Spo2Sample(1_700_000_000, 97), Spo2Sample(1_700_003_600, 95)),
            CmfParsers.parseSpo2(payload),
        )
    }

    @Test
    fun `stress records decode in order`() {
        val payload = pairRecord(1_700_000_000, 34) + pairRecord(1_700_003_600, 71)

        assertEquals(
            listOf(StressSample(1_700_000_000, 34), StressSample(1_700_003_600, 71)),
            CmfParsers.parseStress(payload),
        )
    }

    @Test
    fun `a partial spo2 record is refused rather than half-read`() {
        val payload = pairRecord(1_700_000_000, 97) + byteArrayOf(1, 2, 3)

        assertTrue(CmfParsers.parseSpo2(payload).isEmpty())
    }

    @Test
    fun `a zero reading is carried but not counted as valid`() {
        // The watch reports zero for a measurement it could not take. Dropping it at the
        // parser would hide a wrist-off gap; treating it as a reading would invent one.
        val spo2 = CmfParsers.parseSpo2(pairRecord(1_700_000_000, 0)).single()
        val stress = CmfParsers.parseStress(pairRecord(1_700_000_000, 0)).single()

        assertEquals(0, spo2.percent)
        assertFalse(spo2.isValid)
        assertFalse(stress.isValid)
    }

    @Test
    fun `paired records keep timestamps past 2038 in the future`() {
        // The activity records have their own loop; this covers the one heart rate, SpO2
        // and stress now share, where a signed read would land the sample in 1901.
        val sample = CmfParsers.parseSpo2(pairRecord(Int.MIN_VALUE, 96)).single()

        assertEquals(2_147_483_648L, sample.timestamp)
    }

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
