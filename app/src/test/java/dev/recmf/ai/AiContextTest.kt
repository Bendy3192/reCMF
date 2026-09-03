package dev.recmf.ai

import dev.recmf.ai.AiContext.Day
import dev.recmf.health.Readiness
import dev.recmf.health.ReadinessPart
import dev.recmf.health.ReadinessSignal
import dev.recmf.health.SleepPart
import dev.recmf.health.SleepScore
import dev.recmf.health.SleepScorePart
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

    // --- What reCMF worked out, which the table cannot say -------------------------

    private val readiness = Readiness(
        score = 52,
        parts = listOf(
            ReadinessPart(ReadinessSignal.RESTING_HEART_RATE, today = 66f, usual = 61f, standing = -0.6f),
            ReadinessPart(ReadinessSignal.SLEEP_DURATION, today = 402f, usual = 456f, standing = -0.4f),
            ReadinessPart(ReadinessSignal.SLEEP_QUALITY, today = 0.34f, usual = 0.39f, standing = -0.1f),
        ),
    )

    @Test
    fun `a readiness the app computed is in what the app sends`() {
        // The screenshot that started this: the coach offers "why is my readiness like
        // that" above the box, and the answer was that there is no such figure in the
        // data. There is. It is on the tab next door.
        val sent = AiContext.user("Why?", week, worked = AiContext.Worked(readiness = readiness))

        assertTrue("52" in sent, "the score itself was not sent")
        assertTrue("resting pulse: 66 bpm, usual 61" in sent, sent)
        assertTrue("sleep length: 6h 42m, usual 7h 36m" in sent, sent)
    }

    @Test
    fun `fifty is said to be usual, because a model will otherwise call it poor`() {
        // Out of a hundred, 52 reads as a bad mark. It is this person's own average
        // morning, and nothing in the number says so.
        val sent = AiContext.workedOut(AiContext.Worked(readiness = readiness))

        assertTrue("50 is exactly this person's own recent usual" in sent, sent)
    }

    @Test
    fun `each signal is given in its own unit`() {
        // Sleep arrives in minutes, quality as a share, variability in milliseconds. One
        // of them printed in another's unit is a wrong number stated confidently.
        val everything = Readiness(
            score = 60,
            parts = listOf(
                ReadinessPart(ReadinessSignal.HEART_RATE_VARIABILITY, 44f, 39f, 0.5f, fromWatch = false),
                ReadinessPart(ReadinessSignal.SLEEP_QUALITY, 0.34f, 0.39f, -0.1f),
                ReadinessPart(ReadinessSignal.STRESS, 47f, 41f, -0.5f),
            ),
        )

        val sent = AiContext.workedOut(AiContext.Worked(readiness = everything))

        assertTrue("44 ms" in sent, sent)
        assertTrue("34% of the night" in sent || "34%, usual 39%" in sent, sent)
        // Bare, because the row already says "stress index" — the unit in both halves
        // read as two different quantities.
        assertTrue("stress index: 47, usual 41" in sent, sent)
    }

    @Test
    fun `a borrowed reading says whose it is`() {
        // The table's columns are the watch's own. A readiness built on another
        // wearable's resting pulse will not match the resting column, and without this
        // the assistant explains the gap by inventing something about the data.
        val borrowed = Readiness(
            score = 40,
            parts = listOf(
                ReadinessPart(ReadinessSignal.RESTING_HEART_RATE, 66f, 61f, -0.6f, fromWatch = false),
            ),
        )

        val sent = AiContext.workedOut(AiContext.Worked(readiness = borrowed))

        assertTrue("another wearable" in sent, sent)
        assertTrue("resting pulse" in sent.substringAfter("Read from another wearable"), sent)
    }

    @Test
    fun `the sleep score is sent with what full marks means`() {
        // Readiness is a comparison with usual and this is not, so the two numbers on the
        // two tabs are on different scales. Sending both without saying that invites the
        // assistant to read a 71 and a 52 as one story.
        val sleep = SleepScore(
            score = 71,
            parts = listOf(
                SleepScorePart(SleepPart.DURATION, standing = 0.75f, measured = 402f * 60, against = 480f * 60),
                SleepScorePart(SleepPart.COMPOSITION, standing = 0.41f, measured = 0.34f, against = 0.39f),
            ),
        )

        val sent = AiContext.workedOut(AiContext.Worked(sleep = sleep))

        assertTrue("Sleep score 71 out of 100" in sent, sent)
        assertTrue("not a comparison with their usual" in sent, sent)
        assertTrue("length: 6h 42m against a target of 8h 0m" in sent, sent)
    }

    @Test
    fun `a night from the other wrist is named as one`() {
        val theirs = SleepScore(
            score = 80,
            parts = listOf(SleepScorePart(SleepPart.DURATION, 0.9f, 440f * 60, 480f * 60)),
            fromWatch = false,
        )

        assertTrue("measured by another wearable" in AiContext.workedOut(AiContext.Worked(sleep = theirs)))
    }

    @Test
    fun `today's row is named as a day still running`() {
        // Several times over now, a model has explained a low step count by inventing a
        // reason. The real one is that the day is four hours old.
        val sent = AiContext.workedOut(AiContext.Worked(today = "2026-09-03"))

        assertTrue("Today is 2026-09-03" in sent, sent)
        assertTrue("still running" in sent, sent)
    }

    @Test
    fun `the energy figure is the app's own, so two screens cannot disagree`() {
        val sent = AiContext.workedOut(AiContext.Worked(restingEnergy = 1614..1780))

        assertTrue("1614-1780 kcal" in sent, sent)
        assertTrue("span" in sent, "an unstated coefficient should still read as a span: $sent")

        val settled = AiContext.workedOut(AiContext.Worked(restingEnergy = 1780..1780))
        assertTrue("1780 kcal" in settled, settled)
        assertFalse("span" in settled, settled)
    }

    @Test
    fun `nothing worked out means nothing said about it`() {
        // A phone in its first week has no scores yet, and a heading with nothing under
        // it is tokens spent to tell the assistant that the app has nothing to add.
        assertEquals("", AiContext.workedOut(AiContext.Worked()))
        assertFalse("worked out" in AiContext.user("How am I?", week))
    }

    @Test
    fun `the scores sit below the table they were built from`() {
        val sent = AiContext.user(
            "How am I?",
            week,
            about = AiContext.about(AiContext.Profile(name = "Ivan"), 2026),
            worked = AiContext.Worked(readiness = readiness),
        )

        assertTrue(sent.indexOf("2026-08-28") < sent.indexOf("Readiness 52"), sent)
        assertTrue(sent.indexOf("Readiness 52") < sent.indexOf("Name: Ivan"), sent)
    }

    @Test
    fun `the target the score was measured against travels with it`() {
        // The sleep score's length part is scored against a target the wearer moved. An
        // assistant assuming the usual eight hours would explain a full mark as a
        // shortfall.
        val sent = AiContext.workedOut(AiContext.Worked(sleepTargetMinutes = 450, stepsGoal = 12000))

        assertTrue("7h 30m" in sent, sent)
        assertTrue("12000" in sent, sent)
    }

    @Test
    fun `training is in the picture, because a day's average hides it`() {
        // An hour at 150 and a day of errands can leave the same daily average. Asked
        // what to change, an assistant that cannot see the training is guessing at the
        // largest thing in the week.
        val sent = AiContext.workedOut(
            AiContext.Worked(
                workouts = listOf(AiContext.Session("2026-09-02", 47, 132, 161)),
            ),
        )

        assertTrue("2026-09-02: 47m, average 132 bpm, peak 161 bpm" in sent, sent)
        assertTrue("no kind" in sent, "the watch reports no activity type: $sent")
    }

    @Test
    fun `a conversation stops growing without end`() {
        // Every question resends the whole conversation, because the far side keeps
        // nothing. Left alone that is a bill that grows with every turn and a request
        // that eventually stops fitting at all.
        val turns = (1..50).map { "turn $it ${"x".repeat(500)}" }

        val kept = AiContext.lastWithin(turns, budget = 2_000) { it.length }

        assertTrue(kept.size < turns.size, "nothing was dropped")
        assertEquals(turns.last(), kept.last(), "the newest turn must always be sent")
        assertTrue(kept.sumOf { it.length } <= 2_000 + 505, kept.sumOf { it.length }.toString())
    }

    @Test
    fun `one turn longer than the whole budget is still asked`() {
        // Somebody who pastes an essay gets an answer, not silence.
        val essay = listOf("x".repeat(20_000))

        assertEquals(essay, AiContext.lastWithin(essay, budget = 100) { it.length })
    }

    @Test
    fun `a trimmed conversation says so rather than pretending`() {
        val whole = AiContext.coaching("", "English", week, "", trimmed = true)
        val short = AiContext.coaching("", "English", week, "", trimmed = false)

        assertTrue("earliest turns are not below" in whole, whole)
        assertFalse("earliest turns are not below" in short)
    }

    @Test
    fun `the metric question asks about the days that are actually there`() {
        // The sample tables keep a week. Asking what stands out "over the last few weeks"
        // of a pulse column is asking about evidence nobody has.
        val asked = AiContext.aboutMetric("Пульс", "66 bpm", "hr_avg")

        assertFalse("weeks" in asked, asked)
        assertTrue("run of days below" in asked, asked)
    }

}
