/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.ai

import dev.recmf.health.Readiness
import dev.recmf.health.ReadinessPart
import dev.recmf.health.ReadinessSignal
import dev.recmf.health.Sex
import dev.recmf.health.SleepPart
import dev.recmf.health.SleepScore
import dev.recmf.health.SleepScorePart
import kotlin.math.roundToInt

/**
 * What gets said to the assistant, and nothing else does.
 *
 * Everything here is text a person could read, on purpose. The whole context of a request
 * is a system prompt, a small table of numbers and a question — a couple of kilobytes all
 * told — which means the payload preview the settings screen shows is not a separate
 * feature that might drift out of step with what is sent. It is the same string.
 *
 * ## No retrieval, no vector store, no memory on the far side
 *
 * A chat endpoint keeps nothing between calls, so every request carries its own context.
 * The reflex is to reach for retrieval — but retrieval solves "too much to fit", and there
 * is not too much here. A month of daily figures is about a kilobyte. The database is
 * already ours and already local; assembling the right paragraph is cheaper, exactly
 * reproducible, and can be shown to the wearer before it leaves.
 *
 * What does need keeping is the handful of things the numbers cannot say — an illness, a
 * training pattern, a subject somebody would rather not be advised about. Those are notes,
 * held as plain text and edited by hand, not embeddings.
 */
object AiContext {

    /** One day, as the assistant sees it. Nulls are days the watch said nothing about. */
    data class Day(
        val date: String,
        val restingHeartRate: Int? = null,
        val sleepMinutes: Int? = null,
        val restfulPercent: Int? = null,
        val stress: Int? = null,
        val steps: Int? = null,
        /**
         * RMSSD in milliseconds, and the one figure here the watch did not produce.
         *
         * It arrives from another wearable by way of Health Connect, so it is present on
         * the phones that have one and absent on the rest. Last in the row because that is
         * what it is: an extra, not part of what this watch reports.
         */
        val heartRateVariability: Int? = null,

        /** Blood oxygen, averaged over the day's readings. */
        val bloodOxygen: Int? = null,

        /** The watch's own estimates, all three worked out from step count and a stride. */
        val calories: Int? = null,
        val distanceMeters: Int? = null,
        val climbs: Int? = null,

        /**
         * The day's average pulse, which is not the same thing as [restingHeartRate].
         *
         * Its absence was noticed by a reader of the table rather than by anybody here:
         * asked about a pulse of 66, the assistant found only a resting column reading 70,
         * said so, and reconciled the two as best it could. There was no pulse column at
         * all.
         */
        val heartRate: Int? = null,
    )

    /** One column of the table, and how to get it out of a day. */
    private class Column(val header: String, val of: (Day) -> Int?)

    /**
     * Every figure a day can carry, in the order it is worth reading.
     *
     * The list exists because the alternative was writing each column out twice — once in
     * a header string and once in the row builder — and they drifted the moment a column
     * was added. Worse, they drifted silently: the table still lined up, and only the
     * numbers underneath the headings were wrong.
     *
     * Order is deliberate. What the watch measures directly comes first, what it estimates
     * from step count comes next, and the one figure from another device comes last.
     */
    private val COLUMNS: List<Column> = listOf(
        Column("hr_avg") { it.heartRate },
        Column("resting") { it.restingHeartRate },
        Column("sleep_min") { it.sleepMinutes },
        Column("restful_pct") { it.restfulPercent },
        Column("stress") { it.stress },
        Column("spo2_pct") { it.bloodOxygen },
        Column("steps") { it.steps },
        Column("climbs") { it.climbs },
        Column("distance_m") { it.distanceMeters },
        Column("kcal") { it.calories },
        Column("hrv_ms") { it.heartRateVariability },
    )

    /** The date column, which is always there and never varies in width. */
    private const val DATE_WIDTH = 12

    /** Blank between columns. Two is enough to read as a gap and cheap enough to send. */
    private const val GAP = 2


    /**
     * What somebody has said about themselves, for the coach to read.
     *
     * Four fields and a free-text note, rather than a form. Age, height and weight are the
     * ones that change how a figure should be read at all — a resting pulse means something
     * different at twenty and at sixty — and everything else somebody might want said about
     * themselves is theirs to write. Building a form of sensitive categories would mean
     * choosing which ones exist; a blank box does not.
     *
     * Sent only when the coach is switched on. The other opt-in sends numbers with nobody's
     * name against them, and that stays true.
     */
    data class Profile(
        val name: String = "",
        val birthYear: Int = 0,
        val heightCm: Int = 0,
        val weightKg: Int = 0,

        /**
         * Which coefficient the resting-energy equation takes, when it was given.
         *
         * The one field here that exists for arithmetic rather than for reading: every
         * published equation needs it, and without it the honest answer is the span
         * between both coefficients rather than one of them. Null is a first-class answer
         * and stays one — the range is not a degraded result, it is the true one when
         * nobody said.
         */
        val sex: Sex? = null,

        val notes: String = "",
    ) {
        /** Whether there is anything here worth sending. An empty profile is not a profile. */
        val filled: Boolean get() = name.isNotBlank() || birthYear > 0 ||
            heightCm > 0 || weightKg > 0 || sex != null || notes.isNotBlank()
    }

    /**
     * The profile as lines, or empty when there is nothing in it.
     *
     * Each field is written only when it has been given. A profile saying "height: 0" is
     * worse than one that does not mention height: the first is a claim about somebody and
     * the second is silence.
     *
     * @param thisYear so an age can be worked out without this function needing a clock,
     *   which is what lets it be tested.
     */
    fun about(profile: Profile, thisYear: Int): String {
        if (!profile.filled) return ""

        val lines = buildList {
            profile.name.takeIf { it.isNotBlank() }?.let { add("Name: ${it.trim()}") }
            profile.birthYear.takeIf { it in 1900..thisYear }?.let { add("Age: ${thisYear - it}") }
            profile.heightCm.takeIf { it > 0 }?.let { add("Height: $it cm") }
            profile.weightKg.takeIf { it > 0 }?.let { add("Weight: $it kg") }
            profile.sex?.let { add("Sex: ${it.name.lowercase()}") }
        }

        return buildString {
            if (lines.isNotEmpty()) {
                appendLine("About this person:")
                lines.forEach { appendLine(it) }
            }
            profile.notes.trim().takeIf { it.isNotBlank() }?.let {
                if (lines.isNotEmpty()) appendLine()
                appendLine("In their own words:")
                appendLine(it)
            }
        }.trimEnd()
    }

    /**
     * The prompt used until somebody writes their own.
     *
     * Most of it is about what the numbers are *not*, because that is where a model left to
     * itself will confidently go wrong. This watch's stress index has no published method
     * and no published bands, so there is nothing to compare it against and any figure
     * quoted as a norm for it would be invented. There is no heart-rate variability at all.
     * Distance is derived from step count and an assumed stride. Saying so in the prompt is
     * the same honesty the metric descriptions carry, and without it the assistant would
     * cheerfully undo them.
     */
    val DEFAULT_SYSTEM_PROMPT: String = """
        You are a careful assistant inside reCMF, a third-party app for a CMF Watch Pro 2.
        You are shown figures the watch reported. Explain them plainly and briefly.

        What the numbers are, and are not:
        - The stress figure is the watch's own 0-100 index. Its method and its bands are
          unpublished, so there is no standard to compare it against. Never quote a norm
          for it or call a value high or low in absolute terms. Only compare it to this
          person's own recent days.
        - The watch cannot measure heart-rate variability: it reports one pulse a minute
          with no intervals behind it. So unless an hrv_ms column appears in the table
          below, do not reason about HRV, recovery scores built on it, or anything
          derived from it. Where that column is present the figure is real RMSSD from
          another wearable on the same phone, and may be read as such — bearing in mind
          it comes from a different device than every other column.
        - Distance and calories are the watch's estimates, worked out from step count and
          an assumed stride. Treat them as good for comparing days and poor as absolutes.
        - Blood oxygen is measured at the wrist, which is far less reliable than a
          fingertip oximeter. A single low reading is usually a bad measurement.

        How to answer:
        - Compare against this person's own baseline, never against a population.
        - Say when the data is too thin to support a conclusion. That is a useful answer.
        - Do not diagnose, and do not suggest anybody has a condition. If a pattern looks
          worth a doctor's attention, say that plainly and briefly and stop there.
        - Be short. A few sentences. No headings, no lists unless asked.
    """.trimIndent()

    /**
     * The days as a small fixed-width table.
     *
     * A table rather than prose because it is a third of the tokens and reads back exactly:
     * somebody checking the preview against their own screen can find a row and compare it.
     * An empty cell is a day the watch reported nothing for, which is different from a zero
     * and is written differently.
     */
    fun table(days: List<Day>): String {
        if (days.isEmpty()) return "No days recorded yet."

        // Only the columns something actually filled. A watch that never took a blood
        // oxygen reading should not hand the assistant a column of dashes to reason about,
        // and a phone with one wearable should not be shown a variability heading at all.
        // The alternative was demonstrated: asked about a figure whose column was missing,
        // the assistant correctly answered that it had nothing, which is a true statement
        // about the wrong thing.
        val shown = COLUMNS.filter { column -> days.any { column.of(it) != null } }

        fun cell(value: Int?): String = value?.toString() ?: "-"

        // Measured rather than fixed, so a column of four-digit steps and a column of
        // two-digit pulses each take the room they need and no more.
        val widths = shown.map { column ->
            maxOf(column.header.length, days.maxOf { cell(column.of(it)).length }) + GAP
        }

        fun row(date: String, at: (Int) -> String) = buildString {
            append(date.padEnd(DATE_WIDTH))
            shown.indices.forEach { append(at(it).padEnd(widths[it])) }
        }.trimEnd()

        val header = row("date") { shown[it].header }
        val rows = days.map { day -> row(day.date) { cell(shown[it].of(day)) } }

        return (listOf(header) + rows).joinToString("\n")
    }

    /**
     * What the variability column is, said next to the column rather than only in the
     * prompt.
     *
     * The prompt is the wrong place for this on its own, and a model demonstrated why: it
     * read an hrv_ms column, correctly recalled that a CMF Watch Pro 2 cannot measure
     * beat-to-beat intervals, and concluded the app had mislabelled something — advising
     * the wearer to ignore the one genuinely measured figure on the page. That is good
     * reasoning from what it was given, and what it was given was a column nobody
     * explained.
     *
     * The prompt is also editable, and an edited one is theirs: reCMF must still be able
     * to say what its own columns mean without reaching into somebody's instructions. A
     * legend under the table does that, and sits where the question is actually being
     * read.
     */
    private val VARIABILITY_LEGEND: String =
        "hrv_ms is RMSSD in milliseconds, measured by another wearable on this phone and " +
            "read through Health Connect. It is genuine variability data. The CMF watch " +
            "cannot measure it; every other column in the table is the watch's own."

    /**
     * The whole of what is sent for one question, assembled in the order it is read.
     *
     * @param about the profile as [about] rendered it, or blank. Kept below the table and
     *   plainly headed, so a reader of the preview can see exactly which part came from the
     *   watch and which part is about them.
     */
    fun user(
        question: String,
        days: List<Day>,
        about: String = "",
        worked: Worked = Worked(),
    ): String = buildString {
        appendLine(question)
        appendLine()
        append(briefing(days, about, worked))
    }.trimEnd()

    /**
     * The figures and the profile, with no question attached.
     *
     * Split out for the coach, where the same context belongs in the standing instructions
     * rather than in a message: a conversation is a list of things the two sides said, and
     * a table of somebody's steps is not something they said. Putting it in a turn would
     * also mean either resending it as a fake user message every time or letting it go
     * stale after the first — and the figures move every day.
     */
    fun briefing(days: List<Day>, about: String = "", worked: Worked = Worked()): String =
        buildString {
            appendLine("Daily figures, oldest first:")
            appendLine(table(days))
            if (days.any { it.heartRateVariability != null }) {
                appendLine()
                appendLine(VARIABILITY_LEGEND)
            }
            workedOut(worked).takeIf { it.isNotBlank() }?.let {
                appendLine()
                appendLine(it)
            }
            if (about.isNotBlank()) {
                appendLine()
                appendLine(about.trim())
            }
        }.trimEnd()

    /**
     * What the coach is told before a word is exchanged.
     *
     * The same standing instructions the cards use, the same figures, plus the one thing
     * that differs: this is a conversation with somebody who can answer back, so it may
     * ask before it advises. The cards cannot ask — a tile has no reply box — which is
     * why that sentence lives here and not in the shared prompt.
     */
    fun coaching(
        prompt: String,
        language: String,
        days: List<Day>,
        about: String,
        worked: Worked = Worked(),
        /** True when older turns have been dropped from what is being sent. */
        trimmed: Boolean = false,
    ): String =
        buildString {
            appendLine(instructions(prompt, language))
            appendLine()
            appendLine(
                "This is a conversation, not a one-off answer. You may ask a short " +
                    "clarifying question instead of guessing, and you should when the " +
                    "figures alone cannot settle what was asked.",
            )
            if (trimmed) {
                appendLine()
                appendLine(
                    "This conversation is longer than what is being sent: the earliest " +
                        "turns are not below. If they refer to something said earlier " +
                        "that you cannot see, say so plainly rather than reconstructing " +
                        "it.",
                )
            }
            appendLine()
            append(briefing(days, about, worked))
        }.trimEnd()

    /**
     * The instructions, plus the language to answer in.
     *
     * Kept apart from the prompt rather than written into it, for two reasons. The prompt
     * is editable, and somebody who rewrites it should not have to remember to say what
     * language they speak — nor should their edit silently switch the answers back to
     * English. And the instructions themselves stay in English on purpose: they are
     * technical, models follow them best that way, and translating them into every
     * language reCMF is offered in would be a large surface to keep true.
     *
     * The language is named in English — "Russian", not "русский" — because that is what
     * the instruction around it is written in, and a model reading an English sentence
     * with one word of Cyrillic in it is being asked to guess.
     *
     * @param prompt the standing instructions, however they have been edited.
     * @param language the reader's language, named in English.
     */
    fun instructions(prompt: String, language: String): String = buildString {
        append(prompt.trim().ifEmpty { DEFAULT_SYSTEM_PROMPT })
        if (language.isNotBlank()) {
            append("\n\nAnswer in ")
            append(language)
            append(". Use the units and date format that language ordinarily uses.")
        }
    }

    /**
     * The tail of a list that fits a budget, newest kept.
     *
     * A chat endpoint remembers nothing, so every question resends the whole conversation.
     * Left alone that grows without limit: each turn costs money on every later request,
     * and eventually the request stops fitting the model's context at all — at which point
     * every answer fails and the only way out somebody has is to clear the conversation and
     * lose it. So the oldest turns stop being sent, while staying on the screen and in the
     * backup, where they cost nothing.
     *
     * Generic over the item so this can be tested without a wire format anywhere near it.
     *
     * @param budget the most characters worth of items to keep.
     * @param size how long one item is.
     * @return the newest items that fit, oldest first. Always at least one, since a
     *   question longer than the whole budget still has to be asked.
     */
    fun <T> lastWithin(items: List<T>, budget: Int, size: (T) -> Int): List<T> {
        var left = budget
        val kept = ArrayDeque<T>()

        for (item in items.asReversed()) {
            left -= size(item)
            if (left < 0 && kept.isNotEmpty()) break
            kept.addFirst(item)
        }

        return kept.toList()
    }

    /**
     * The question asked when somebody opens a metric.
     *
     * The column is named because the metric is not: a tile is labelled in whatever
     * language the phone is read in, and the table is headed in English, so "Пульс" has to
     * be matched to one of ten English headings by guesswork. It was guessed wrong — the
     * only heading that looked close was the resting pulse, which is a different
     * measurement, and the answer came back reconciling two numbers that were never the
     * same one.
     *
     * The sentence about a day's row exists for the same reading. Several tiles show the
     * newest reading rather than a summary of the day, and without this the difference
     * between them looks like a contradiction in the data.
     *
     * @param column the heading this metric appears under, or blank for a metric with no
     *   column of its own.
     */
    fun aboutMetric(metric: String, todayValue: String, column: String = ""): String =
        buildString {
            append("Today's $metric reads $todayValue.")
            if (column.isNotBlank()) {
                append(" That is the `$column` column in the table below, where each row is ")
                append("the whole of that day — so the newest reading can differ from it.")
            }
            append(" Is that ordinary for this person, and what would you notice about the ")
            // "The last few weeks" was asking about evidence that is not there. The
            // sample tables keep a week and only the nights keep a month, so a question
            // about weeks of a pulse column invites an answer about days nobody has.
            append("run of days below?")
        }

    /**
     * What reCMF worked out for itself, which no row of the table can say.
     *
     * The app computes a readiness score, a sleep score, and a resting-energy figure, and
     * shows all three on screens with a coach tab one tap away. None of it was ever sent.
     * Asked "why is my readiness like that" — a question this app puts on a chip above the
     * box, so it is the app asking it — the assistant answered, correctly and uselessly,
     * that there is no readiness in its data and the watch does not compute one.
     *
     * That is the same fault three times over now: the sleep column was missing once, the
     * pulse column was missing once, and each time the assistant reasoned impeccably from
     * a picture that was missing the thing being asked about. So this carries the derived
     * figures too, with the scale each one is on — a readiness of 52 is *this person's own
     * usual*, and a model told only "52 out of 100" will call it poor.
     *
     * It stays data rather than a rendered string so that the sentences are built in one
     * tested place instead of in the view model.
     */
    data class Worked(
        /** Today, so the last row of the table can be recognised as a day still running. */
        val today: String = "",
        val readiness: Readiness? = null,
        val sleep: SleepScore? = null,
        /** How long this person means to sleep, in minutes. Zero when they never said. */
        val sleepTargetMinutes: Int = 0,
        /** The step goal the watch is set to, or zero. */
        val stepsGoal: Int = 0,

        /**
         * Recent sessions the watch flagged as exercise, newest first.
         *
         * The one part of the app's own picture that a table of daily figures flattens
         * away entirely: an hour at 150 bpm and a day of errands can leave the same
         * average. Asked what to change, an assistant that cannot see the training is
         * guessing at the largest thing in the week.
         */
        val workouts: List<Session> = emptyList(),
        /**
         * Resting energy by Mifflin-St Jeor, or null when the profile cannot support it.
         *
         * Sent so that the coach and the wizard quote the same figure. Without it a model
         * asked what a day costs would work one out from the height and weight in the
         * profile, choose its own equation, and disagree with the number the app showed
         * on the first screen it ever displayed.
         */
        val restingEnergy: IntRange? = null,
    ) {
        val filled: Boolean get() = today.isNotBlank() || readiness != null || sleep != null ||
            sleepTargetMinutes > 0 || stepsGoal > 0 || restingEnergy != null ||
            workouts.isNotEmpty()
    }

    /**
     * One session, as much of it as this watch allows anybody to know.
     *
     * There is no kind here and there will not be one: the watch keeps no summary of a
     * session and answers no request for one, so what a workout *is* in reCMF is a run of
     * pulse the watch marked as taken during exercise. Sending a type would mean inventing
     * one, and an assistant told "a run" would coach a run.
     */
    data class Session(
        val date: String,
        val minutes: Int,
        val averageBpm: Int,
        val maxBpm: Int,
    )

    /**
     * The derived figures in words, under the table they were derived from.
     *
     * Every score is given with what it is out of *and* what its middle means, because
     * that is the part a model cannot infer and will otherwise assume. Every part is given
     * with the figure it was measured at and the figure it was judged against, so the
     * assistant can explain a score rather than re-deriving one of its own — and where a
     * reading came from a second wearable, that is said next to it, since the table's own
     * columns are the watch's.
     */
    fun workedOut(worked: Worked): String {
        if (!worked.filled) return ""

        return buildString {
            appendLine(
                "What reCMF worked out from the rows above, in the app, with plain " +
                    "arithmetic and no model involved. Explain these figures; do not " +
                    "recompute them or invent your own.",
            )

            worked.readiness?.let { readiness ->
                appendLine()
                appendLine(
                    "Readiness ${readiness.score} out of 100. 50 is exactly this person's " +
                        "own recent usual rather than a middling grade: 75 is a clearly " +
                        "better morning than their own average and 25 a clearly worse one. " +
                        "It is built from:",
                )
                readiness.parts.forEach { appendLine("- ${it.wording()}") }
                appendLine(
                    "A signal with no reading today, or with fewer than four days behind " +
                        "it, is left out entirely and the rest share its weight.",
                )

                val borrowed = readiness.parts.filterNot { it.fromWatch }
                if (borrowed.isNotEmpty()) {
                    appendLine(
                        "Read from another wearable through Health Connect rather than " +
                            "from the watch: " +
                            borrowed.joinToString(", ") { SIGNALS.getValue(it.signal) } +
                            ". Every column in the table is the watch's own, so those " +
                            "readings will not match the figures here — and that " +
                            "difference is the two devices, not the person.",
                    )
                }
            }

            worked.sleep?.let { sleep ->
                appendLine()
                appendLine(
                    "Sleep score ${sleep.score} out of 100 for the most recent night in " +
                        "the table. Unlike readiness this one is not a comparison with " +
                        "their usual: 100 is a night that met the target with an " +
                        "ordinary-for-them composition. It is built from:",
                )
                sleep.parts.forEach { appendLine("- ${it.wording()}") }

                if (!sleep.fromWatch) {
                    appendLine(
                        "That night was measured by another wearable, not by the CMF " +
                            "watch, which recorded nothing for it.",
                    )
                }
            }

            if (worked.workouts.isNotEmpty()) {
                appendLine()
                appendLine(
                    "Sessions the watch flagged as exercise, newest first. It reports no " +
                        "kind and no summary, so these are runs of pulse marked as taken " +
                        "during exercise and nothing more — do not assume what the " +
                        "activity was:",
                )
                worked.workouts.forEach {
                    appendLine(
                        "- ${it.date}: ${clock(it.minutes)}, average ${it.averageBpm} bpm, " +
                            "peak ${it.maxBpm} bpm",
                    )
                }
            }

            val notes = buildList {
                if (worked.sleepTargetMinutes > 0) {
                    add(
                        "This person's own sleep target is " +
                            "${clock(worked.sleepTargetMinutes)}; it is theirs to set and " +
                            "the sleep score's length part is measured against it.",
                    )
                }
                if (worked.stepsGoal > 0) {
                    add("Their step goal on the watch is ${worked.stepsGoal}.")
                }
                worked.restingEnergy?.let { energy ->
                    add(
                        "reCMF puts their resting energy at ${energy.readable()} kcal a " +
                            "day by the Mifflin-St Jeor equation" +
                            (if (energy.first != energy.last) {
                                ", a span rather than one figure because the coefficient " +
                                    "for sex was not given"
                            } else {
                                ""
                            }) +
                            ". The kcal column is the watch's estimate of movement on top " +
                            "of that, not the whole day. Quote this figure rather than " +
                            "working out your own.",
                    )
                }
                if (worked.today.isNotBlank()) {
                    add(
                        "Today is ${worked.today}. If a row for it appears above, that day " +
                            "is still running: steps, distance, climbs and kcal are " +
                            "counters that keep rising until midnight, so a low figure " +
                            "there is an unfinished day rather than a quiet one.",
                    )
                }
            }

            if (notes.isNotEmpty()) {
                appendLine()
                notes.forEach { appendLine(it) }
            }
        }.trimEnd()
    }

    /** One readiness signal, as "resting pulse: 66 bpm, usual 61 (worse than usual)". */
    private fun ReadinessPart.wording(): String = buildString {
        append(SIGNALS.getValue(signal))
        append(": ")
        append(reading(signal, today))
        append(", usual ")
        append(reading(signal, usual))
        append(" (")
        append(
            when {
                standing > NOTABLE -> "better than usual"
                standing < -NOTABLE -> "worse than usual"
                else -> "about usual"
            },
        )
        append(")")
    }

    /** One sleep part, in the unit it was measured in and the share of its mark it won. */
    private fun SleepScorePart.wording(): String {
        val mark = "${(standing * 100).roundToInt()}% of that part's mark"

        return when (part) {
            SleepPart.DURATION ->
                "length: ${clock((measured / 60).roundToInt())} against a target of " +
                    "${clock((against / 60).roundToInt())} ($mark)"

            SleepPart.COMPOSITION ->
                "deep and REM together: ${share(measured)} of the night, usual " +
                    "${share(against)} ($mark)"

            SleepPart.RESTORATION ->
                "resting pulse that morning: ${measured.roundToInt()} bpm, usual " +
                    "${against.roundToInt()} bpm ($mark)"

            SleepPart.CONTINUITY ->
                "time asleep out of time in bed: ${share(measured)}, full marks at " +
                    "${share(against)} ($mark)"
        }
    }

    /** A readiness reading in the unit that signal is measured in. */
    private fun reading(signal: ReadinessSignal, value: Float): String = when (signal) {
        ReadinessSignal.HEART_RATE_VARIABILITY -> "${value.roundToInt()} ms"
        ReadinessSignal.SLEEP_DURATION -> clock(value.roundToInt())
        ReadinessSignal.SLEEP_QUALITY -> share(value)
        ReadinessSignal.RESTING_HEART_RATE -> "${value.roundToInt()} bpm"
        // Bare, unlike the others: the signal is already named "stress index" beside it,
        // and the unit repeated in both halves reads as two different things.
        ReadinessSignal.STRESS -> value.roundToInt().toString()
    }

    /** What each signal is called, in the language the rest of the payload is written in. */
    private val SIGNALS = mapOf(
        ReadinessSignal.HEART_RATE_VARIABILITY to "heart-rate variability",
        ReadinessSignal.SLEEP_DURATION to "sleep length",
        ReadinessSignal.SLEEP_QUALITY to "restful share of the night",
        ReadinessSignal.RESTING_HEART_RATE to "resting pulse",
        ReadinessSignal.STRESS to "stress index",
    )

    /**
     * How far from usual is worth putting a word to.
     *
     * The same quarter of a spread the readiness card colours a row at, so the sentence
     * the assistant is given and the colour the wearer is looking at agree about which
     * days were ordinary.
     */
    private const val NOTABLE = 0.25f

    /** Minutes as hours and minutes, with no locale anywhere near it. */
    private fun clock(minutes: Int): String {
        val hours = minutes / 60
        val rest = minutes % 60
        return if (hours > 0) "${hours}h ${rest}m" else "${rest}m"
    }

    /** A 0-to-1 share as whole per cent. */
    private fun share(value: Float): String = "${(value * 100).roundToInt()}%"

    /** A range as one figure when both ends agree, and as a span when they do not. */
    private fun IntRange.readable(): String =
        if (first == last) first.toString() else "$first-$last"

}
