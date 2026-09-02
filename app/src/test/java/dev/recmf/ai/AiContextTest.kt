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

        val row = AiContext.table(week + quiet).lines().last()

        assertTrue("-" in row, "an absent reading should be written as a dash: $row")
        assertFalse("0" in row.substringAfter("2026-08-29"), "an absent reading became a zero: $row")
    }

    @Test
    fun `a figure nothing ever measured is not a column of dashes`() {
        // Asked about blood oxygen with no oxygen column, the assistant answered — quite
        // correctly — that it had nothing to go on. A column of dashes would not have
        // helped it; the fix is that every metric with a tile reaches the table, and a
        // metric with no readings at all takes up no room at the same time.
        val table = AiContext.table(week)

        assertFalse("spo2_pct" in table, table)
        assertFalse("kcal" in table, table)
    }

    @Test
    fun `every measured figure reaches the table`() {
        val full = Day(
            date = "2026-08-29",
            restingHeartRate = 61,
            sleepMinutes = 431,
            restfulPercent = 38,
            stress = 44,
            steps = 9012,
            heartRateVariability = 42,
            bloodOxygen = 97,
            calories = 585,
            distanceMeters = 7800,
            climbs = 12,
        )

        val sent = AiContext.table(listOf(full))

        listOf("resting", "sleep_min", "restful_pct", "stress", "steps", "spo2_pct", "kcal", "distance_m", "climbs", "hrv_ms")
            .forEach { assertTrue(it in sent, "$it missing from the header") }
        listOf("97", "585", "7800", "12").forEach {
            assertTrue(it in sent.lines().last(), "$it missing from $sent")
        }
    }

    @Test
    fun `a wide column and a narrow one each take the room they need`() {
        val days = listOf(
            Day("2026-08-27", restingHeartRate = 61, steps = 9012),
            Day("2026-08-28", restingHeartRate = 60, steps = 11040),
        )

        val lines = AiContext.table(days).lines()
        val at = lines.first().indexOf("steps")

        lines.drop(1).forEach { row ->
            assertTrue(row.length > at && row[at] != ' ', "steps starts at $at but that is blank in: $row")
        }
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
        // The stress index has no published bands, and HRV is absent unless another
        // device supplied it. Both are places a model left to itself answers confidently
        // and wrongly.
        val prompt = AiContext.DEFAULT_SYSTEM_PROMPT

        assertTrue("Never quote a norm" in prompt)
        assertTrue("cannot measure heart-rate variability" in prompt)
        assertTrue("hrv_ms" in prompt, "the prompt must name the column that changes that")
        assertTrue("do not suggest anybody has a condition" in prompt)
    }

    @Test
    fun `variability is not a column on a phone that has none`() {
        // Every one of these days is a day the watch measured on its own. An empty column
        // of dashes would be something for the model to wonder about, and there is
        // nothing to wonder about.
        val table = AiContext.table(week)

        assertFalse("hrv_ms" in table, table)
    }

    @Test
    fun `variability gets its own column as soon as one day has it`() {
        val withSecondDevice = week + Day("2026-08-29", heartRateVariability = 42)

        val lines = AiContext.table(withSecondDevice).lines()
        val at = lines.first().indexOf("hrv_ms")

        assertTrue(at > 0, "no variability column in: ${lines.first()}")
        lines.drop(1).forEach { row ->
            assertTrue(
                row.length > at && row[at] != ' ',
                "column \"hrv_ms\" starts at $at but that is blank in: $row",
            )
        }
        assertTrue("42" in lines.last(), lines.last())
    }

    @Test
    fun `a day only the second device saw is still a day`() {
        // Written as a dash everywhere else, which is the honest reading: the watch was
        // off the wrist and something else was not.
        val row = AiContext.table(listOf(Day("2026-08-29", heartRateVariability = 42)))
            .lines()
            .last()

        assertTrue("42" in row, row)
        assertTrue("-" in row, row)
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
    fun `the variability column is explained where it appears`() {
        // A model shown this column with no legend correctly recalled that the watch
        // cannot measure variability, concluded the app had mislabelled something, and
        // advised ignoring the one genuinely measured figure on the page.
        val sent = AiContext.user("How am I?", week + Day("2026-08-29", heartRateVariability = 42))

        assertTrue("hrv_ms is RMSSD" in sent, sent)
        assertTrue("Health Connect" in sent, sent)
        assertTrue(sent.indexOf("hrv_ms is RMSSD") > sent.indexOf("2026-08-29"), "legend above the table")
    }

    @Test
    fun `nothing is explained that is not there`() {
        assertFalse("RMSSD" in AiContext.user("How am I?", week))
    }

    @Test
    fun `a month with a second device is still a paragraph`() {
        // The same claim as above, on the phone that sends the most: an extra column on
        // every row and a line saying what it is. Still nearer a paragraph than a corpus,
        // which is the whole argument for sending it all every time.
        val month = (1..30).map {
            Day("2026-08-%02d".format(it), 60, 420, 40, 45, 9000, 42)
        }

        val size = AiContext.user("How am I?", month).length

        assertTrue(size < 2560, "a month with variability came to $size characters")
    }

    @Test
    fun `the question names the column, because the metric name is translated`() {
        // A tile is labelled in whatever language the phone is read in and the table is
        // headed in English. Asked about "Пульс" with no column named, the assistant
        // matched it to the resting pulse — a different measurement — and spent a
        // paragraph reconciling two numbers that were never the same one.
        val asked = AiContext.aboutMetric("Пульс", "66 уд/мин", "hr_avg")

        assertTrue("Пульс" in asked, asked)
        assertTrue("`hr_avg`" in asked, asked)
    }

    @Test
    fun `a metric with no column of its own is not given one`() {
        val asked = AiContext.aboutMetric("Steps", "9012")

        assertFalse("column" in asked, asked)
        assertTrue(asked.startsWith("Today's Steps reads 9012."), asked)
    }

    @Test
    fun `the pulse and the resting pulse are two columns, not one`() {
        val day = Day("2026-08-29", restingHeartRate = 70, heartRate = 83)

        val table = AiContext.table(listOf(day))

        assertTrue("hr_avg" in table, table)
        assertTrue("resting" in table, table)
        assertTrue("83" in table.lines().last(), table)
        assertTrue("70" in table.lines().last(), table)
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
