/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.health

/**
 * Working out when a workout happened, from the only thing the watch says about one.
 *
 * This watch does not hand over workout summaries. Asked for them it answers with an
 * empty payload — after a session it recorded, on firmware that plainly kept the session,
 * every time. What it does send is the pulse it took during the session, under a command
 * of its own, seconds apart instead of minutes.
 *
 * So the run of samples is the workout. That is a weaker statement than a summary: there
 * is no sport, no distance, no calories, and the edges are the first and last pulse rather
 * than the taps on the button. But it is enough for the thing the wearer notices, which is
 * that a workout is missing from every other app on the phone.
 */

/** How far apart two workout samples can be and still belong to the same session. */
private const val MAX_GAP_SECONDS = 5 * 60L

/**
 * Shorter than this is not a session.
 *
 * A stray sample or two arrives without a workout behind it — the watch takes a reading
 * when it feels like it, and one that happens to be flagged should not become a
 * one-second exercise session in somebody's health record. A minute is low enough to keep
 * the two-minute walk that started this, which the official app also refused to record.
 */
private const val SHORTEST_SECONDS = 60L

/**
 * Groups workout samples into the sessions they came from.
 *
 * Takes bare timestamps rather than rows so it can be reasoned about and tested without a
 * database: what it does is entirely a question about the spacing of numbers.
 *
 * @param timestamps epoch seconds, in any order and possibly with repeats.
 * @return one closed range per session, earliest first, none shorter than
 *   [SHORTEST_SECONDS] and none containing a gap longer than [MAX_GAP_SECONDS].
 */
fun workoutSessions(
    timestamps: List<Long>,
    maxGapSeconds: Long = MAX_GAP_SECONDS,
    shortestSeconds: Long = SHORTEST_SECONDS,
): List<LongRange> {
    val ordered = timestamps.distinct().sorted()
    if (ordered.isEmpty()) return emptyList()

    val sessions = mutableListOf<LongRange>()
    var start = ordered.first()
    var last = ordered.first()

    fun close() {
        // A session of one sample has no duration at all, which is why this is a
        // comparison and not a rounding: it has to be excluded, not shortened.
        if (last - start >= shortestSeconds) sessions.add(start..last)
    }

    for (timestamp in ordered.drop(1)) {
        if (timestamp - last > maxGapSeconds) {
            close()
            start = timestamp
        }
        last = timestamp
    }
    close()

    return sessions
}
