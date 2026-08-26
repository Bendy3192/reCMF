package dev.recmf.health

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StepDeltasTest {
    private fun reading(ts: Long, steps: Int) = CumulativeReading(ts, steps, steps / 2, steps / 10)

    @Test
    fun `repeated polls of an unchanged counter produce nothing`() {
        // This is what the watch actually sends when polled several times in a row, and
        // summing it is what multiplied the day's step count.
        val readings = listOf(reading(100, 7125), reading(102, 7125), reading(108, 7125))

        assertTrue(stepDeltas(readings, previous = reading(90, 7125)).isEmpty())
    }

    @Test
    fun `movement between readings is the difference, not the total`() {
        val deltas = stepDeltas(
            listOf(reading(200, 1200), reading(300, 1500)),
            previous = reading(100, 1000),
        )

        assertEquals(
            listOf(
                IntervalDelta(100, 200, 200),
                IntervalDelta(200, 300, 300),
            ),
            deltas,
        )
    }

    @Test
    fun `a day's readings sum to the day's total`() {
        val readings = listOf(reading(100, 1000), reading(200, 4000), reading(300, 7125))

        val total = stepDeltas(readings, previous = reading(0, 0)).sumOf { it.steps }

        assertEquals(7125, total)
    }

    @Test
    fun `a counter reset at midnight is not a negative delta`() {
        val deltas = stepDeltas(
            listOf(reading(1_000, 9000), reading(2_000, 40)),
            previous = reading(500, 8000),
        )

        assertEquals(listOf(IntervalDelta(500, 1_000, 1000), IntervalDelta(1_000, 2_000, 40)), deltas)
    }

    @Test
    fun `without a baseline the first reading is not counted`() {
        // Its total covers a period that may already have been recorded, so counting it
        // would double whatever came before.
        val deltas = stepDeltas(listOf(reading(100, 5000), reading(200, 5300)))

        assertEquals(listOf(IntervalDelta(100, 200, 300)), deltas)
    }

    @Test
    fun `readings out of order are sorted before being differenced`() {
        val deltas = stepDeltas(
            listOf(reading(300, 1500), reading(100, 1000), reading(200, 1200)),
            previous = reading(50, 900),
        )

        assertEquals(listOf(50L, 100L, 200L), deltas.map { it.startSeconds })
    }

    @Test
    fun `an empty run is handled`() {
        assertTrue(stepDeltas(emptyList()).isEmpty())
        assertTrue(stepDeltas(emptyList(), previous = reading(1, 1)).isEmpty())
    }
}
