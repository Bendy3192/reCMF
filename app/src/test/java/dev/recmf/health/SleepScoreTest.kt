package dev.recmf.health

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SleepScoreTest {

    private val hour = 60 * 60

    /** Eight hours, 40% of it restorative — an unremarkable night. */
    private fun night(
        asleep: Int = 8 * hour,
        restful: Int = (8 * hour * 0.4f).toInt(),
        awake: Int? = null,
        resting: Float? = null,
    ) = Night(asleep, restful, awake, resting)

    private val ordinaryNights = listOf(0.40f, 0.38f, 0.42f, 0.39f, 0.41f)
    private val ordinaryPulse = listOf(60f, 61f, 59f, 60f, 62f)

    @Test
    fun `a night at the target scores full marks for its length`() {
        val part = sleepScore(night())!!.parts.single { it.part == SleepPart.DURATION }

        assertEquals(1f, part.standing, 0.001f)
    }

    @Test
    fun `an hour short of the target costs a quarter of the mark`() {
        val part = sleepScore(night(asleep = 7 * hour))!!
            .parts
            .single { it.part == SleepPart.DURATION }

        assertEquals(0.75f, part.standing, 0.001f)
    }

    @Test
    fun `half the target scores nothing for length`() {
        // Half a night is not three quarters of a good one, and below this there is
        // nothing left to distinguish.
        val part = sleepScore(night(asleep = 4 * hour))!!
            .parts
            .single { it.part == SleepPart.DURATION }

        assertEquals(0f, part.standing, 0.001f)
    }

    @Test
    fun `sleeping past the target is not scored down`() {
        // Nothing here could tell an unusually long night from a restful one, and a
        // penalty for it would be invented rather than measured.
        val long = sleepScore(night(asleep = 11 * hour))!!
        val exact = sleepScore(night())!!

        assertEquals(exact.score, long.score)
    }

    @Test
    fun `a short night cannot be talked up by the rest`() {
        // Duration is half the score on purpose. Four hours with a perfect everything
        // else must still read as a bad night.
        val short = sleepScore(
            night(asleep = 4 * hour, restful = (4 * hour * 0.9f).toInt(), awake = 0, resting = 45f),
            restfulHistory = ordinaryNights,
            restingHistory = ordinaryPulse,
        )!!

        assertTrue(short.score < 60, "four hours scored ${short.score}")
    }

    @Test
    fun `with no history behind it a night is judged on its length alone`() {
        val first = sleepScore(night(asleep = 6 * hour, resting = 60f))!!

        assertEquals(listOf(SleepPart.DURATION), first.parts.map { it.part })
        assertEquals(50, first.score)
    }

    @Test
    fun `a thin history is no history`() {
        // Three nights is an average of almost nothing, and scoring against it would
        // dress up a coin toss.
        val part = sleepScore(night(), restfulHistory = listOf(0.40f, 0.38f, 0.42f))!!
            .parts
            .map { it.part }

        assertEquals(listOf(SleepPart.DURATION), part)
    }

    @Test
    fun `a more restorative night than usual scores above one that is not`() {
        val better = sleepScore(night(restful = (8 * hour * 0.48f).toInt()), ordinaryNights)!!
        val worse = sleepScore(night(restful = (8 * hour * 0.32f).toInt()), ordinaryNights)!!

        assertTrue(better.score > worse.score, "${better.score} was not above ${worse.score}")
    }

    @Test
    fun `a lower resting pulse is the recovered direction`() {
        // The classic way to get this backwards, and the reason it has a test.
        val calm = sleepScore(night(resting = 56f), restingHistory = ordinaryPulse)!!
        val raised = sleepScore(night(resting = 66f), restingHistory = ordinaryPulse)!!

        assertTrue(calm.score > raised.score, "${calm.score} was not above ${raised.score}")
    }

    @Test
    fun `an unwatched night is not a perfect one, and not a broken one either`() {
        // Null awake time means nothing was measuring interruptions. Zero means there
        // were none. Scoring them alike would hand every phone without a second wearable
        // a free full mark.
        // Six hours, so length is not already full marks: with everything at a ceiling
        // the two would tie and the test would prove nothing.
        val unwatched = sleepScore(night(asleep = 6 * hour, awake = null))!!
        val unbroken = sleepScore(night(asleep = 6 * hour, awake = 0))!!

        assertTrue(unwatched.parts.none { it.part == SleepPart.CONTINUITY })
        assertTrue(unbroken.parts.any { it.part == SleepPart.CONTINUITY })
        assertTrue(unbroken.score > unwatched.score, "${unbroken.score} vs ${unwatched.score}")
    }

    @Test
    fun `a missing part is shared out, not counted as zero`() {
        // Only duration is present in both, and it is full marks in both. If continuity's
        // weight were counted as a zero rather than renormalised away, the first would
        // come out lower than a hundred.
        val alone = sleepScore(night(awake = null))!!

        assertEquals(100, alone.score)
    }

    @Test
    fun `a night mostly spent awake loses the continuity mark`() {
        val broken = sleepScore(night(asleep = 6 * hour, awake = 3 * hour))!!
        val part = broken.parts.single { it.part == SleepPart.CONTINUITY }

        assertEquals(0f, part.standing, 0.001f)
    }

    @Test
    fun `nineteen minutes awake in eight hours is still a good night`() {
        // Ninety-six per cent efficiency, which is past the point worth distinguishing.
        val part = sleepScore(night(awake = 19 * 60))!!.parts.single {
            it.part == SleepPart.CONTINUITY
        }

        assertEquals(1f, part.standing, 0.001f)
    }

    @Test
    fun `a night the watch did not record is no score at all`() {
        assertNull(sleepScore(night(asleep = 0)))
    }

    @Test
    fun `the target is the wearer's to move`() {
        val sevenHourTarget = sleepScore(night(asleep = 7 * hour), targetSeconds = 7 * hour)!!

        assertEquals(100, sevenHourTarget.score)
    }

    @Test
    fun `what each part was judged against is reported with it`() {
        // The screen has to be able to say "against your usual 40%", and inventing that
        // number a second time in the UI is how the two drift apart.
        val scored = sleepScore(night(), ordinaryNights)!!

        val composition = scored.parts.single { it.part == SleepPart.COMPOSITION }
        assertEquals(0.40f, composition.against, 0.005f)

        val duration = scored.parts.single { it.part == SleepPart.DURATION }
        assertEquals((8 * hour).toFloat(), duration.against, 0.5f)
    }

    @Test
    fun `a score is never outside nought to a hundred`() {
        val extreme = sleepScore(
            night(asleep = 14 * hour, restful = 13 * hour, awake = 0, resting = 30f),
            restfulHistory = ordinaryNights,
            restingHistory = ordinaryPulse,
        )

        assertNotNull(extreme)
        assertTrue(extreme!!.score in 0..100, "score was ${extreme.score}")
    }
}
