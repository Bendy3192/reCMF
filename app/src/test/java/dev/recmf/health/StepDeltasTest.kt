package dev.recmf.health

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId

class StepDeltasTest {
    private val utc = ZoneId.of("UTC")

    private fun reading(ts: Long, steps: Int) = CumulativeReading(ts, steps, steps / 2, steps / 10)

    /**
     * A delta with only its step arithmetic left.
     *
     * Most of the tests here are about the differencing itself — resets, baselines, the
     * day boundary — and the other two counters ride along under exactly the same rules.
     * Blanking them keeps those tests reading as the step tests they are, and leaves the
     * counters to the tests written for them.
     */
    private fun List<IntervalDelta>.stepsOnly(): List<IntervalDelta> =
        map { it.copy(distanceMeters = 0, activeCalories = 0) }

    /** An hour on a numbered day, as an epoch second. Day 1 is the epoch itself. */
    private fun at(day: Int, hour: Int): Long = (day - 1) * 86_400L + hour * 3_600L

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
            deltas.stepsOnly(),
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
            zone = utc,
        )

        // The second interval starts where the counter did, not where the previous
        // reading was: both fall on the same UTC day here, so midnight is behind them and
        // the previous reading is the honest start.
        assertEquals(
            listOf(IntervalDelta(500, 1_000, 1000), IntervalDelta(1_000, 2_000, 40)),
            deltas.stepsOnly(),
        )
    }

    @Test
    fun `a morning's steps belong to the morning, not to last night`() {
        // The real shape of a day: the last reading before bed, then the first one after
        // getting up. Spanning them puts the whole morning in a window that begins
        // yesterday evening, and Health Connect splits a record across the hours it
        // covers — so most of those steps land on the wrong day.
        val lastNight = reading(at(day = 1, hour = 22), 8_000)
        val thisMorning = reading(at(day = 2, hour = 9), 2_100)

        val deltas = stepDeltas(listOf(thisMorning), previous = lastNight, zone = utc)

        assertEquals(
            listOf(IntervalDelta(at(day = 2, hour = 0), at(day = 2, hour = 9), 2_100)),
            deltas.stepsOnly(),
        )
    }

    @Test
    fun `a counter that drops for some other reason starts where the last reading was`() {
        // A reboot or a factory reset mid-afternoon. Midnight is hours behind the reading
        // it follows, and claiming that window would be claiming steps already recorded.
        val before = reading(at(day = 2, hour = 14), 5_000)
        val after = reading(at(day = 2, hour = 15), 120)

        val deltas = stepDeltas(listOf(after), previous = before, zone = utc)

        assertEquals(
            listOf(IntervalDelta(at(day = 2, hour = 14), at(day = 2, hour = 15), 120)),
            deltas.stepsOnly(),
        )
    }

    @Test
    fun `a reset landing exactly on midnight is not a zero-length interval`() {
        // Health Connect will not take one, and nothing moved in no time anyway.
        val midnight = at(day = 2, hour = 0)

        val deltas = stepDeltas(
            listOf(reading(midnight, 500)),
            previous = reading(at(day = 1, hour = 23), 9_000),
            zone = utc,
        )

        assertTrue(deltas.isEmpty())
    }

    @Test
    fun `a day already recorded elsewhere is measured from what it holds`() {
        // A fresh install: the staging table is empty, but Health Connect kept what an
        // earlier install wrote. 1400 steps are already there, ending at 09:00, and the
        // watch now says 2100 — so 700 are owed, for the stretch since that record.
        val alreadyWritten = CumulativeReading(at(day = 2, hour = 9), 1_400, 0, 0)
        val now = reading(at(day = 2, hour = 11), 2_100)

        val deltas = stepDeltas(
            listOf(now),
            previous = alreadyWritten,
            zone = utc,
            previousIsRecordedTotal = true,
        )

        assertEquals(
            listOf(IntervalDelta(at(day = 2, hour = 9), at(day = 2, hour = 11), 700)),
            deltas.stepsOnly(),
        )
    }

    @Test
    fun `a recording ahead of the watch writes nothing rather than twice`() {
        // The watch was reset mid-day, so its counter is now below what is already in
        // Health Connect. Treating that as a midnight reset would write the day again.
        val alreadyWritten = CumulativeReading(at(day = 2, hour = 9), 1_400, 0, 0)
        val afterReset = reading(at(day = 2, hour = 11), 60)

        val deltas = stepDeltas(
            listOf(afterReset),
            previous = alreadyWritten,
            zone = utc,
            previousIsRecordedTotal = true,
        )

        assertTrue(deltas.isEmpty())
    }

    @Test
    fun `only the first reading is measured against the recorded total`() {
        // Everything after it is a counter against a counter, so a drop in the middle of
        // the batch is a reset and is written — starting at the reading before it, which
        // is later than midnight and therefore the honest start.
        val alreadyWritten = CumulativeReading(at(day = 2, hour = 9), 1_000, 0, 0)

        val deltas = stepDeltas(
            listOf(reading(at(day = 2, hour = 10), 1_500), reading(at(day = 2, hour = 11), 30)),
            previous = alreadyWritten,
            zone = utc,
            previousIsRecordedTotal = true,
        )

        assertEquals(
            listOf(
                IntervalDelta(at(day = 2, hour = 9), at(day = 2, hour = 10), 500),
                IntervalDelta(at(day = 2, hour = 10), at(day = 2, hour = 11), 30),
            ),
            deltas.stepsOnly(),
        )
    }

    @Test
    fun `without a baseline the first reading is not counted`() {
        // Its total covers a period that may already have been recorded, so counting it
        // would double whatever came before.
        val deltas = stepDeltas(listOf(reading(100, 5000), reading(200, 5300)))

        assertEquals(listOf(IntervalDelta(100, 200, 300)), deltas.stepsOnly())
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

    @Test
    fun `distance and calories are differenced alongside the steps`() {
        // Without this they never reached Health Connect at all, and every other app on
        // the phone showed nought kilometres however far the wearer had walked.
        val deltas = stepDeltas(
            listOf(CumulativeReading(200, 1_200, 900, 60)),
            previous = CumulativeReading(100, 1_000, 750, 50),
        )

        assertEquals(listOf(IntervalDelta(100, 200, 200, 150, 10)), deltas)
    }

    @Test
    fun `a midnight reset makes the reading its own distance too`() {
        // They reset together because they arrive together, in one record. Treating the
        // drop as a negative would have written nothing for the morning.
        val deltas = stepDeltas(
            listOf(CumulativeReading(2_000, 40, 30, 2)),
            previous = CumulativeReading(1_000, 9_000, 7_000, 400),
            zone = utc,
        )

        assertEquals(listOf(IntervalDelta(1_000, 2_000, 40, 30, 2)), deltas)
    }

    @Test
    fun `a counter that slips backwards on its own is floored rather than negative`() {
        // A firmware correction, not a wearer walking in reverse — and Health Connect
        // refuses a negative distance outright, which would cost the steps in the same
        // batch if it were allowed through.
        val deltas = stepDeltas(
            listOf(CumulativeReading(200, 1_200, 700, 40)),
            previous = CumulativeReading(100, 1_000, 750, 50),
        )

        assertEquals(listOf(IntervalDelta(100, 200, 200, 0, 0)), deltas)
    }
}
