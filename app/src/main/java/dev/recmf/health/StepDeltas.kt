/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.health

import java.time.Instant
import java.time.ZoneId

/**
 * One `ACTIVITY_DATA` record: the watch's running totals at a moment in time.
 *
 * These are **cumulative**, not per-interval. Observed on firmware 1.0.0.73: records
 * seconds apart carry an identical step count, and the numbers agree with each other as
 * daily totals (7125 steps against 5550 m). Treating them as increments and adding them
 * up multiplies the day's step count by however many times the watch was polled.
 */
data class CumulativeReading(
    val timestamp: Long,
    val steps: Int,
    val distanceMeters: Int,
    val calories: Int,
)

/** Movement between two readings, which is what Health Connect wants to store. */
data class IntervalDelta(
    val startSeconds: Long,
    val endSeconds: Long,
    val steps: Int,
)

/**
 * Turns a run of cumulative readings into the intervals between them.
 *
 * A drop in the counter means it was reset — the watch zeroes at midnight — so the new
 * value is itself the movement since the reset rather than a negative delta. **When that
 * happens the interval has to start at the reset, not at the previous reading.** The last
 * reading of a day is typically late in the evening and the first of the next is hours
 * later, so spanning them puts a whole morning's steps in a window that begins yesterday.
 * Health Connect splits a record across the hours it covers, so most of those steps land
 * on the wrong day and the rest on the wrong hours — which is exactly what a day showing
 * 1421 of the watch's 3517 looks like.
 *
 * @param zone the wearer's own zone, because the reset is local midnight and nothing else.
 * @param previous the last reading before this run, if one is known. Without it the first
 *   reading cannot be turned into an interval and is used only as the baseline for the
 *   second, since its own total covers a period we may already have recorded.
 * @param previousIsRecordedTotal whether [previous] is what has already been *stored* for
 *   the day rather than a reading of the watch's counter. The two are the same number
 *   when all is well, but they behave differently when the counter drops below it: a
 *   counter below its own last value has been reset, while a counter below what is
 *   already recorded means the recording is ahead of the watch — and writing the
 *   difference then would count those steps twice.
 */
fun stepDeltas(
    readings: List<CumulativeReading>,
    previous: CumulativeReading? = null,
    zone: ZoneId = ZoneId.systemDefault(),
    previousIsRecordedTotal: Boolean = false,
): List<IntervalDelta> {
    val ordered = readings.sortedBy { it.timestamp }
    if (ordered.isEmpty()) return emptyList()

    val out = ArrayList<IntervalDelta>(ordered.size)
    var baseline = previous

    // Only the first reading is measured against a total that was already recorded;
    // everything after it is measured against the reading before, which is a counter.
    var againstRecorded = previousIsRecordedTotal

    for (reading in ordered) {
        val from = baseline
        val fromRecorded = againstRecorded
        baseline = reading
        againstRecorded = false

        if (from == null) continue
        if (reading.timestamp <= from.timestamp) continue

        val reset = reading.steps < from.steps

        // The recording is ahead of the watch, so there is nothing owed and the next
        // reading will settle it. Writing the difference here would be writing steps that
        // are already in Health Connect.
        if (reset && fromRecorded) continue

        // A reset makes the reading its own total: it counts from the zero, not from a
        // number that no longer exists.
        val moved = if (reset) reading.steps else reading.steps - from.steps
        if (moved <= 0) continue

        val start = if (reset) {
            // Local midnight, but never earlier than the reading it follows. A counter
            // can drop for reasons other than the date — a reboot, a factory reset — and
            // in those the previous reading is the honest start.
            maxOf(from.timestamp, startOfDay(reading.timestamp, zone))
        } else {
            from.timestamp
        }

        // Belt and braces: a zero-length interval is not a period anything moved in, and
        // Health Connect will not take one.
        if (start >= reading.timestamp) continue

        out.add(IntervalDelta(startSeconds = start, endSeconds = reading.timestamp, steps = moved))
    }

    return out
}

/** Midnight of the day [epochSeconds] falls in, as the wearer's own clock reads it. */
private fun startOfDay(epochSeconds: Long, zone: ZoneId): Long =
    Instant.ofEpochSecond(epochSeconds).atZone(zone).toLocalDate().atStartOfDay(zone).toEpochSecond()
