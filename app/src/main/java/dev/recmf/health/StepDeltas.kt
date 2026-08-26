/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.health

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
 * value is itself the movement since the reset rather than a negative delta.
 *
 * @param previous the last reading before this run, if one is known. Without it the first
 *   reading cannot be turned into an interval and is used only as the baseline for the
 *   second, since its own total covers a period we may already have recorded.
 */
fun stepDeltas(
    readings: List<CumulativeReading>,
    previous: CumulativeReading? = null,
): List<IntervalDelta> {
    val ordered = readings.sortedBy { it.timestamp }
    if (ordered.isEmpty()) return emptyList()

    val out = ArrayList<IntervalDelta>(ordered.size)
    var baseline = previous

    for (reading in ordered) {
        val from = baseline
        baseline = reading

        if (from == null) continue
        if (reading.timestamp <= from.timestamp) continue

        val moved = if (reading.steps >= from.steps) {
            reading.steps - from.steps
        } else {
            // The counter reset between the two readings.
            reading.steps
        }

        if (moved <= 0) continue

        out.add(IntervalDelta(startSeconds = from.timestamp, endSeconds = reading.timestamp, steps = moved))
    }

    return out
}
