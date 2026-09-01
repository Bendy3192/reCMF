/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.protocol

import dev.recmf.protocol.CmfWorkouts.looksLikeAPlace
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CmfWorkoutsTest {

    /**
     * One 32-byte summary, built to the layout rather than captured.
     *
     * Said plainly because it matters: this pins the reading the code was written to, not
     * the watch's behaviour. Only a real workout can do that, and when one arrives its
     * bytes belong here beside this.
     */
    private fun summary(
        start: Long = 1_756_720_000,
        duration: Int = 300,
        type: Byte = 0x02,
        end: Long = 1_756_720_300,
        gps: Int = 1,
    ): ByteArray {
        val out = ByteArray(CmfWorkouts.SUMMARY_SHORT)
        fun le32(at: Int, value: Long) {
            for (i in 0 until 4) out[at + i] = ((value shr (8 * i)) and 0xff).toByte()
        }
        le32(0, start)
        out[4] = (duration and 0xff).toByte()
        out[5] = ((duration shr 8) and 0xff).toByte()
        out[6] = type
        le32(26, end)
        out[30] = gps.toByte()
        return out
    }

    @Test
    fun `a summary reads back as the session it describes`() {
        val parsed = CmfWorkouts.parseWorkoutSummaries(summary()).single()

        assertEquals(1_756_720_000L, parsed.startTimestamp)
        assertEquals(1_756_720_300L, parsed.endTimestamp)
        assertEquals(300, parsed.durationSeconds)
        assertEquals(CmfActivityType.OUTDOOR_RUNNING, parsed.type)
        assertTrue(parsed.hasGpsTrack)
    }

    @Test
    fun `several summaries arrive in one payload`() {
        val payload = summary(start = 1_000, end = 1_300) + summary(start = 9_000, end = 9_300)

        assertEquals(
            listOf(1_000L, 9_000L),
            CmfWorkouts.parseWorkoutSummaries(payload).map { it.startTimestamp },
        )
    }

    @Test
    fun `the long record is read with a longer stride and the same fields`() {
        // 54 bytes rather than 32, with the extra 22 unidentified and skipped.
        val long = summary(start = 500, end = 800) + ByteArray(CmfWorkouts.SUMMARY_LONG - 32)

        val parsed = CmfWorkouts.parseWorkoutSummaries(long).single()

        assertEquals(500L, parsed.startTimestamp)
        assertEquals(800L, parsed.endTimestamp)
    }

    @Test
    fun `a workout done indoors says it has no track`() {
        assertFalse(CmfWorkouts.parseWorkoutSummaries(summary(gps = 0)).single().hasGpsTrack)
    }

    @Test
    fun `a sport code this app does not know is a gap rather than a failure`() {
        // The table is Gadgetbridge's and the watch's firmware is not obliged to match it.
        // An unknown code must still yield the session, with its time and its duration.
        // 0x12 is one of the codes the table leaves out, unlike 0x7f, which is gateball.
        val parsed = CmfWorkouts.parseWorkoutSummaries(summary(type = 0x12)).single()

        assertEquals(null, parsed.type)
        assertEquals(0x12, parsed.typeCode)
        assertEquals(300, parsed.durationSeconds)
    }

    @Test
    fun `a payload that is not a whole number of records is no workouts`() {
        assertEquals(emptyList<WorkoutSummary>(), CmfWorkouts.parseWorkoutSummaries(ByteArray(33)))
        assertEquals(emptyList<WorkoutSummary>(), CmfWorkouts.parseWorkoutSummaries(ByteArray(0)))
    }

    @Test
    fun `a duration past nine hours still fits the two bytes it is given`() {
        // 65535 seconds is the most the field can say, and an 18-hour hike would exceed
        // it. Worth pinning so that a wrapped duration is recognised as the field's limit
        // rather than mistaken for a parsing error.
        assertEquals(
            65_535,
            CmfWorkouts.parseWorkoutSummaries(summary(duration = 65_535)).single().durationSeconds,
        )
    }

    @Test
    fun `a track reads back as points on Earth`() {
        // Timestamp, then longitude, then latitude — that order, which is the unusual one.
        val payload = byteArrayOf(
            0x00, 0x01, 0x02, 0x03,
            0x80.toByte(), 0x1a, 0x06, 0x00, // 400,000 = 0.04°E
            0x00, 0x35, 0x0c, 0x00, // 800,000 = 0.08°N
        )

        val point = CmfWorkouts.parseWorkoutGps(payload).single()

        assertEquals(0.08, point.latitude, 1e-9)
        assertEquals(0.04, point.longitude, 1e-9)
        assertTrue(point.looksLikeAPlace())
    }

    @Test
    fun `west and south come back negative`() {
        // Unsigned would put the western hemisphere at 429 degrees east, which is nowhere.
        val payload = ByteArray(12)
        fun le32(at: Int, value: Int) {
            for (i in 0 until 4) payload[at + i] = ((value shr (8 * i)) and 0xff).toByte()
        }
        le32(4, -740_060_000) // 74.006°W
        le32(8, 407_128_000) // 40.7128°N

        val point = CmfWorkouts.parseWorkoutGps(payload).single()

        assertEquals(40.7128, point.latitude, 1e-6)
        assertEquals(-74.006, point.longitude, 1e-6)
        assertTrue(point.looksLikeAPlace())
    }

    @Test
    fun `a latitude beyond the poles is not a place`() {
        // The symptom that would say the two fields are the other way round.
        assertFalse(WorkoutGpsPoint(0, latitude = 174.5, longitude = 41.2).looksLikeAPlace())
        assertFalse(WorkoutGpsPoint(0, latitude = 0.0, longitude = 0.0).looksLikeAPlace())
    }

    @Test
    fun `a track that is not a whole number of points is no track`() {
        assertEquals(emptyList<WorkoutGpsPoint>(), CmfWorkouts.parseWorkoutGps(ByteArray(13)))
        assertEquals(emptyList<WorkoutGpsPoint>(), CmfWorkouts.parseWorkoutGps(ByteArray(0)))
    }
}
