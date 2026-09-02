package dev.recmf.ai

import dev.recmf.ai.AiContext.Day
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AiContextTest {

    private val week = listOf(
        Day("2026-08-27", restingHeartRate = 61, sleepMinutes = 431, restfulPercent = 38, stress = 44, steps = 9012),
        Day("2026-08-28", restingHeartRate = 60, sleepMinutes = 402, restfulPercent = 41, stress = 39, steps = 11040),
    )

    @Test
    fun `a day the watch said nothing about is not a zero`() {
        // The difference between "did not move" and "was not worn" is the one thing a
        // table like this must not blur, since the model cannot ask.
        val quiet = Day("2026-08-29")

        val row = AiContext.table(listOf(quiet)).lines().last()

        assertTrue("-" in row, "an absent reading should be written as a dash: $row")
        assertFalse("0" in row.substringAfter("2026-08-29"), "an absent reading became a zero: $row")
    }

    @Test
    fun `every figure reaches the table`() {
        val row = AiContext.table(week).lines()[1]

        listOf("2026-08-27", "61", "431", "38", "44", "9012").forEach {
            assertTrue(it in row, "$it missing from $row")
        }
    }

    @Test
    fun `the columns line up with their headings`() {
        // The preview exists so somebody can check it against their own screen. Columns
        // two characters out of true make that harder than reading the app would be.
        val lines = AiContext.table(week).lines()
        val heading = lines.first()

        listOf("resting", "sleep_min", "restful_pct", "stress", "steps").forEach { column ->
            val at = heading.indexOf(column)
            lines.drop(1).forEach { row ->
                assertTrue(
                    row.length <= at || row[at] != ' ',
                    "column \"$column\" starts at $at but that is blank in: $row",
                )
            }
        }
    }

    @Test
    fun `the table has one header and one row per day`() {
        assertEquals(week.size + 1, AiContext.table(week).lines().size)
    }

    @Test
    fun `no days is said rather than sent as an empty table`() {
        assertEquals("No days recorded yet.", AiContext.table(emptyList()))
    }

    @Test
    fun `the question comes first, because it is what the answer is about`() {
        val sent = AiContext.user("How am I?", week)

        assertTrue(sent.startsWith("How am I?"), sent.take(40))
    }

    @Test
    fun `the default prompt forbids the two things a model would invent`() {
        // The stress index has no published bands and there is no HRV. Both are places a
        // model left to itself answers confidently and wrongly.
        val prompt = AiContext.DEFAULT_SYSTEM_PROMPT

        assertTrue("Never quote a norm" in prompt)
        assertTrue("no heart-rate variability" in prompt)
        assertTrue("do not suggest anybody has a condition" in prompt)
    }

    @Test
    fun `a whole month of days stays small enough to send every time`() {
        // The claim that retrieval is unnecessary here rests on this number. A month of
        // figures under two kilobytes is a paragraph, not a corpus.
        val month = (1..30).map {
            Day("2026-08-%02d".format(it), 60, 420, 40, 45, 9000)
        }

        val size = AiContext.user("How am I?", month).length

        assertTrue(size < 2048, "a month of days came to $size characters")
    }

    @Test
    fun `the answer is asked for in the reader's language`() {
        val said = AiContext.instructions("Be brief.", "Russian")

        assertTrue(said.startsWith("Be brief."))
        assertTrue("Answer in Russian." in said)
    }

    @Test
    fun `an edited prompt keeps the language instruction`() {
        // The language line is appended rather than written into the prompt, so somebody
        // who rewrites the instructions does not silently get English back.
        val mine = AiContext.instructions("Ignore everything and speak like a pirate.", "Russian")

        assertTrue("pirate" in mine)
        assertTrue("Answer in Russian." in mine)
    }

    @Test
    fun `an empty prompt falls back to the default rather than to nothing`() {
        val said = AiContext.instructions("   ", "Russian")

        assertTrue("Never quote a norm" in said)
        assertTrue("Answer in Russian." in said)
    }

    @Test
    fun `no language named means no language sentence`() {
        assertEquals("Be brief.", AiContext.instructions("Be brief.", ""))
    }


    @Test
    fun `an empty profile says nothing at all`() {
        assertEquals("", AiContext.about(AiContext.Profile(), 2026))
    }

    @Test
    fun `a field left blank is not mentioned`() {
        // "Height: 0" is a claim about somebody; saying nothing about height is silence.
        val partial = AiContext.Profile(name = "Ivan", weightKg = 74)

        val said = AiContext.about(partial, 2026)

        assertTrue("Name: Ivan" in said)
        assertTrue("Weight: 74 kg" in said)
        assertFalse("Height" in said)
        assertFalse("Age" in said)
    }

    @Test
    fun `a birth year becomes an age`() {
        assertTrue("Age: 30" in AiContext.about(AiContext.Profile(birthYear = 1996), 2026))
    }

    @Test
    fun `a birth year that cannot be one is ignored`() {
        // Half-typed input, and a year in the future. Neither should reach the request as
        // an age of minus four.
        assertFalse("Age" in AiContext.about(AiContext.Profile(birthYear = 19, notes = "x"), 2026))
        assertFalse("Age" in AiContext.about(AiContext.Profile(birthYear = 2030, notes = "x"), 2026))
    }

    @Test
    fun `notes are kept in their own section under their own heading`() {
        val said = AiContext.about(
            AiContext.Profile(name = "Ivan", notes = "Recovering from flu."),
            2026,
        )

        assertTrue(said.indexOf("About this person:") < said.indexOf("In their own words:"))
        assertTrue("Recovering from flu." in said)
    }

    @Test
    fun `notes alone are a profile`() {
        val said = AiContext.about(AiContext.Profile(notes = "Trains Tue and Thu."), 2026)

        assertTrue("In their own words:" in said)
        assertFalse("About this person:" in said)
    }

    @Test
    fun `the profile sits below the figures, never inside them`() {
        val days = listOf(AiContext.Day("2026-08-28", 61, 431, 38, 44, 9012))
        val about = AiContext.about(AiContext.Profile(name = "Ivan"), 2026)

        val sent = AiContext.user("How am I?", days, about)

        assertTrue(sent.indexOf("2026-08-28") < sent.indexOf("Name: Ivan"))
    }

}
