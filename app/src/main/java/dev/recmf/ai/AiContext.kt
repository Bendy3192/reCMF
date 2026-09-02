/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.ai

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
        val notes: String = "",
    ) {
        /** Whether there is anything here worth sending. An empty profile is not a profile. */
        val filled: Boolean get() = name.isNotBlank() || birthYear > 0 ||
            heightCm > 0 || weightKg > 0 || notes.isNotBlank()
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
    fun user(question: String, days: List<Day>, about: String = ""): String = buildString {
        appendLine(question)
        appendLine()
        appendLine("Daily figures, oldest first:")
        appendLine(table(days))
        if (days.any { it.heartRateVariability != null }) {
            appendLine()
            appendLine(VARIABILITY_LEGEND)
        }
        if (about.isNotBlank()) {
            appendLine()
            appendLine(about.trim())
        }
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

    /** The question asked when somebody opens a metric. */
    fun aboutMetric(metric: String, todayValue: String): String =
        "Today's $metric is $todayValue. Is that ordinary for this person, and what would " +
            "you notice about the last few weeks of it?"

}
