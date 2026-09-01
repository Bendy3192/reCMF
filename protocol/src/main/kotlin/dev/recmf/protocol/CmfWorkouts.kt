/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 *
 * Layouts ported from Gadgetbridge (AGPL-3.0-or-later); see NOTICE.
 */
package dev.recmf.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One exercise session, as the watch summarises it.
 *
 * The watch sends these unasked during an activity fetch, alongside the step and heart
 * rate backlog, so nothing has to request them — a sync that happens after a workout
 * carries it.
 */
data class WorkoutSummary(
    val startTimestamp: Long,
    val endTimestamp: Long,
    val durationSeconds: Int,
    val typeCode: Int,

    /**
     * Whether the watch recorded a track for this one.
     *
     * A workout done indoors sets this to zero and has no track to ask for, which is
     * worth knowing before asking: an indoor walk and a walk whose track failed to
     * download look the same afterwards.
     */
    val hasGpsTrack: Boolean,
) {
    /** Null for a code this app does not know, which is a gap in the table, not an error. */
    val type: CmfActivityType? get() = CmfActivityType.fromCode(typeCode.toByte())
}

/** One point of a recorded track. */
data class WorkoutGpsPoint(
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
)

object CmfWorkouts {

    /**
     * Two record sizes have been seen in the wild, and which one arrives is decided by
     * the payload's length rather than by anything inside it.
     *
     * Everything this app reads sits in the first 32 bytes, so the longer record is read
     * the same way with a longer stride. What the extra 22 bytes hold is unknown; a watch
     * that sends them will show them in the protocol log, which is where the answer will
     * come from.
     */
    const val SUMMARY_SHORT: Int = 32
    const val SUMMARY_LONG: Int = 54

    /** Timestamp, longitude, latitude. */
    const val GPS_POINT_SIZE: Int = 12

    /**
     * Degrees are sent as whole numbers, scaled by ten million — about a centimetre of
     * resolution, which is far finer than the fix behind it.
     */
    private const val DEGREE_SCALE = 10_000_000.0

    /**
     * `WORKOUT_SUMMARY`: a run of fixed-size records.
     *
     * ```
     * 0   u32 LE  start, epoch seconds
     * 4   u16 LE  duration, seconds
     * 6   u8      sport type, a CmfActivityType code
     * 7   19 bytes unidentified
     * 26  u32 LE  end, epoch seconds
     * 30  u8      1 if a track was recorded
     * 31  u8      unidentified
     * ```
     *
     * Both the start and the end are carried even though the duration is there too. They
     * are not redundant: a workout that was paused runs longer on the clock than it did in
     * the legs, and which of the two a step count belongs to depends on that difference.
     */
    fun parseWorkoutSummaries(payload: ByteArray): List<WorkoutSummary> {
        val stride = summaryStride(payload) ?: return emptyList()

        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val out = ArrayList<WorkoutSummary>(payload.size / stride)

        var offset = 0
        while (offset + stride <= payload.size) {
            out.add(
                WorkoutSummary(
                    startTimestamp = buf.getInt(offset).toUnsignedLong(),
                    durationSeconds = buf.getShort(offset + 4).toInt() and 0xffff,
                    typeCode = buf.get(offset + 6).toInt() and 0xff,
                    endTimestamp = buf.getInt(offset + 26).toUnsignedLong(),
                    hasGpsTrack = buf.get(offset + 30).toInt() != 0,
                ),
            )
            offset += stride
        }

        return out
    }

    /**
     * Which of the two record sizes this payload is made of.
     *
     * The short one is tried first, as Gadgetbridge does. A length divisible by both is
     * possible in principle — 864 bytes is 27 short records or 16 long ones — and there is
     * nothing in the payload to break the tie, so the commoner reading wins and the
     * ambiguity is written down rather than pretended away.
     */
    private fun summaryStride(payload: ByteArray): Int? = when {
        payload.isEmpty() -> null
        payload.size % SUMMARY_SHORT == 0 -> SUMMARY_SHORT
        payload.size % SUMMARY_LONG == 0 -> SUMMARY_LONG
        else -> null
    }

    /**
     * `WORKOUT_GPS`: twelve bytes a point — timestamp, longitude, latitude.
     *
     * Longitude comes first, which is the opposite of how coordinates are usually written
     * and worth stating for that reason alone. Both are signed: west and south are
     * negative, so reading them unsigned would put half the planet somewhere near Siberia.
     */
    fun parseWorkoutGps(payload: ByteArray): List<WorkoutGpsPoint> {
        if (payload.isEmpty() || payload.size % GPS_POINT_SIZE != 0) return emptyList()

        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val out = ArrayList<WorkoutGpsPoint>(payload.size / GPS_POINT_SIZE)

        while (buf.remaining() >= GPS_POINT_SIZE) {
            val timestamp = buf.int.toUnsignedLong()
            val longitude = buf.int / DEGREE_SCALE
            val latitude = buf.int / DEGREE_SCALE
            out.add(WorkoutGpsPoint(timestamp, latitude, longitude))
        }

        return out
    }

    /**
     * Whether a point could be a place on Earth.
     *
     * Kept although the order is now corroborated: `GPS_COORDS`, decrypted out of a
     * capture of the official app, puts longitude first as well, so the watch uses one
     * convention in both directions. A latitude beyond the poles would still be the
     * symptom of having it backwards, and this reading has never been checked against a
     * track the watch actually recorded.
     */
    fun WorkoutGpsPoint.looksLikeAPlace(): Boolean =
        latitude in -90.0..90.0 && longitude in -180.0..180.0 && (latitude != 0.0 || longitude != 0.0)

    /** Epoch seconds arrive as a 32-bit field, which is negative in Kotlin past 2038. */
    private fun Int.toUnsignedLong(): Long = toLong() and 0xffffffffL
}
