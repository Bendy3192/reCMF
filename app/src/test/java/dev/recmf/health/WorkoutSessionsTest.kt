/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.health

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WorkoutSessionsTest {

    /** Workout pulse arrives about every five seconds, which is what these imitate. */
    private fun run(from: Long, seconds: Long, every: Long = 5): List<Long> =
        (0..seconds step every).map { from + it }

    @Test
    fun `a run of samples is one session`() {
        assertEquals(
            listOf(1_000L..1_600L),
            workoutSessions(run(1_000, 600)),
        )
    }

    @Test
    fun `a long gap separates two sessions`() {
        // Morning and evening, hours apart. Run together they would be one nine-hour
        // exercise session, which is not a thing anybody did.
        val morning = run(0, 900)
        val evening = run(30_000, 1_200)

        assertEquals(
            listOf(0L..900L, 30_000L..31_200L),
            workoutSessions(morning + evening),
        )
    }

    @Test
    fun `a pause inside a workout does not split it`() {
        // Waiting at a crossing, or the watch simply missing a few readings. Four minutes
        // is inside the gap allowed, so this stays one session.
        val samples = run(0, 300) + run(540, 300)

        assertEquals(listOf(0L..840L), workoutSessions(samples))
    }

    @Test
    fun `a stray sample is not a session`() {
        // The watch takes a flagged reading now and then with no workout behind it. A
        // one-second exercise session in a health record is worse than nothing there.
        assertEquals(emptyList<LongRange>(), workoutSessions(listOf(1_000L)))
        assertEquals(emptyList<LongRange>(), workoutSessions(listOf(1_000L, 1_005L, 1_010L)))
    }

    @Test
    fun `the two-minute walk that started this is kept`() {
        // Short enough that the official app refused to record it, which is exactly the
        // complaint. Nothing here is too short to be real.
        assertEquals(listOf(0L..120L), workoutSessions(run(0, 120)))
    }

    @Test
    fun `order and repeats do not matter`() {
        // Samples arrive from the watch in whatever order a backlog comes out in, and the
        // same minute is resent freely.
        val shuffled = listOf(1_100L, 1_000L, 1_200L, 1_000L, 1_050L)

        assertEquals(listOf(1_000L..1_200L), workoutSessions(shuffled))
    }

    @Test
    fun `nothing in, nothing out`() {
        assertEquals(emptyList<LongRange>(), workoutSessions(emptyList()))
    }

    @Test
    fun `a session that is still going is reported as far as it has got`() {
        // Written now and written again on the next sync with a later end. The record is
        // keyed on where the session started, so the second write replaces the first
        // rather than adding a second overlapping session.
        val sofar = workoutSessions(run(0, 300))
        val later = workoutSessions(run(0, 900))

        assertEquals(listOf(0L..300L), sofar)
        assertEquals(listOf(0L..900L), later)
        assertEquals(sofar.first().first, later.first().first)
    }
}
