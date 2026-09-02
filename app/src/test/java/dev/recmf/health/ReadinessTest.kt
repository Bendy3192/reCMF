package dev.recmf.health

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReadinessTest {

    private val steadyWeek = mapOf(
        ReadinessSignal.SLEEP_DURATION to List(7) { 420f },
        ReadinessSignal.SLEEP_QUALITY to List(7) { 0.4f },
        ReadinessSignal.RESTING_HEART_RATE to List(7) { 60f },
        ReadinessSignal.STRESS to List(7) { 40f },
    )

    private fun day(
        sleep: Float = 420f,
        quality: Float = 0.4f,
        resting: Float = 60f,
        stress: Float = 40f,
    ) = mapOf(
        ReadinessSignal.SLEEP_DURATION to sleep,
        ReadinessSignal.SLEEP_QUALITY to quality,
        ReadinessSignal.RESTING_HEART_RATE to resting,
        ReadinessSignal.STRESS to stress,
    )

    @Test
    fun `an ordinary day against an ordinary week scores exactly usual`() {
        assertEquals(50, readiness(day(), steadyWeek)!!.score)
    }

    @Test
    fun `a bad night and a raised pulse score below usual`() {
        val score = readiness(day(sleep = 300f, resting = 66f, stress = 55f), steadyWeek)!!.score

        assertTrue(score < 50, "expected below usual, got $score")
    }

    @Test
    fun `a long night and a low pulse score above usual`() {
        val score = readiness(day(sleep = 520f, resting = 55f, stress = 30f), steadyWeek)!!.score

        assertTrue(score > 50, "expected above usual, got $score")
    }

    @Test
    fun `resting heart rate counts the other way round from sleep`() {
        // The whole point of the direction table: more sleep is better, more pulse is worse.
        val slept = readiness(day(sleep = 520f), steadyWeek)!!
        val raced = readiness(day(resting = 70f), steadyWeek)!!

        assertTrue(slept.score > 50)
        assertTrue(raced.score < 50)
    }

    @Test
    fun `a flat baseline does not blow the score up`() {
        // Seven identical days give a spread of zero. Without a floor under it, the very
        // next day divides by nothing and scores 0 or 100 on a change of one beat.
        val score = readiness(day(resting = 61f), steadyWeek)!!.score

        assertTrue(score in 30..50, "one beat should nudge, not slam: got $score")
    }

    @Test
    fun `a missing signal is left out rather than counted as average`() {
        val withoutSleep = day().filterKeys { it != ReadinessSignal.SLEEP_DURATION }
        val scored = readiness(withoutSleep, steadyWeek)!!

        assertEquals(3, scored.parts.size)
        assertTrue(scored.parts.none { it.signal == ReadinessSignal.SLEEP_DURATION })
    }

    @Test
    fun `a signal missing today does not drag the rest down`() {
        // A night the watch failed to record is not a bad night, and the day's other three
        // signals should decide the score entirely between them.
        val onlyPulse = mapOf(ReadinessSignal.RESTING_HEART_RATE to 60f)

        assertEquals(50, readiness(onlyPulse, steadyWeek)!!.score)
    }

    @Test
    fun `a first week has no baseline and says so`() {
        val threeDays = steadyWeek.mapValues { (_, days) -> days.take(3) }

        assertNull(readiness(day(), threeDays))
    }

    @Test
    fun `four days is enough to start`() {
        val fourDays = steadyWeek.mapValues { (_, days) -> days.take(4) }

        assertNotNull(readiness(day(), fourDays))
    }

    @Test
    fun `a wild day is clamped rather than pinned by one reading`() {
        // A resting pulse of 200 is a broken measurement, not a body. It should max out
        // that one signal's contribution and leave the other three saying what they say.
        val broken = readiness(day(resting = 200f), steadyWeek)!!
        val merelyBad = readiness(day(resting = 80f), steadyWeek)!!

        assertEquals(merelyBad.score, broken.score)
        assertTrue(broken.score > 0, "three good signals should still hold it up")
    }

    @Test
    fun `the parts report what they were built from`() {
        val scored = readiness(day(resting = 66f), steadyWeek)!!
        val pulse = scored.parts.single { it.signal == ReadinessSignal.RESTING_HEART_RATE }

        assertEquals(66f, pulse.today)
        assertEquals(60f, pulse.usual)
        assertTrue(pulse.standing < 0f, "a raised pulse stands below usual")
    }

    @Test
    fun `nothing in, nothing out`() {
        assertNull(readiness(emptyMap(), emptyMap()))
    }
}
