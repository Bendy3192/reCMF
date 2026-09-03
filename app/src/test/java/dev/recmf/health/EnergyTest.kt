package dev.recmf.health

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EnergyTest {

    @Test
    fun `the equation is Mifflin-St Jeor and nothing else`() {
        // 10*80 + 6.25*180 - 5*30 + 5 = 1780.
        assertEquals(
            1780..1780,
            restingEnergy(Sex.MALE, age = 30, heightCm = 180, weightKg = 80),
        )

        // The same body with the other coefficient: 166 lower.
        assertEquals(
            1614..1614,
            restingEnergy(Sex.FEMALE, age = 30, heightCm = 180, weightKg = 80),
        )
    }

    @Test
    fun `not saying spans both answers rather than picking one`() {
        // A hundred and sixty-six apart is not a rounding error, and a single number
        // presented where there are two would be the app deciding something it was not
        // told. Nothing here infers it from a name.
        val span = restingEnergy(null, age = 30, heightCm = 180, weightKg = 80)!!

        assertEquals(1614, span.first)
        assertEquals(1780, span.last)
    }

    @Test
    fun `a half-typed profile is no profile`() {
        // Somebody part way through typing a height is at "1", and the equation answers
        // for a person one centimetre tall without complaint.
        assertNull(restingEnergy(Sex.MALE, age = 30, heightCm = 1, weightKg = 80))
        assertNull(restingEnergy(Sex.MALE, age = 30, heightCm = 180, weightKg = 0))
        assertNull(restingEnergy(Sex.MALE, age = 0, heightCm = 180, weightKg = 80))
    }

    @Test
    fun `the day's spend is resting plus what was actually moved`() {
        val resting = restingEnergy(Sex.MALE, age = 30, heightCm = 180, weightKg = 80)!!

        assertEquals(2365..2365, spentToday(resting, activeKcal = 585))
    }

    @Test
    fun `an unstated coefficient stays a span all the way through`() {
        // The uncertainty is carried rather than resolved somewhere in the middle: a range
        // that quietly became a number would be a guess wearing a measurement's clothes.
        val resting = restingEnergy(null, age = 30, heightCm = 180, weightKg = 80)!!

        val spent = spentToday(resting, activeKcal = 585)

        assertEquals(166, spent.last - spent.first)
        assertTrue(spent.first > resting.first, "the movement has to be in there")
    }

    @Test
    fun `a day without moving still spends the resting figure`() {
        val resting = restingEnergy(Sex.FEMALE, age = 45, heightCm = 165, weightKg = 62)!!

        assertEquals(resting, spentToday(resting, activeKcal = 0))
    }
}
