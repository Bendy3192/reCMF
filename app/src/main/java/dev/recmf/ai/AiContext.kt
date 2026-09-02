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
    )

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
        - There is no heart-rate variability. The watch reports one pulse per minute with
          no intervals, so do not reason about HRV, recovery scores built on it, or
          anything derived from it.
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

        val header = "date        resting  sleep_min  restful_pct  stress  steps"
        val rows = days.map { day ->
            buildString {
                append(day.date.padEnd(12))
                append(day.restingHeartRate.cell(9))
                append(day.sleepMinutes.cell(11))
                append(day.restfulPercent.cell(13))
                append(day.stress.cell(8))
                append(day.steps.cell(7))
            }.trimEnd()
        }

        return (listOf(header) + rows).joinToString("\n")
    }

    /**
     * The whole of what is sent for one question, assembled in the order it is read.
     *
     * @param notes the things the numbers cannot say, or blank. Kept apart from the table
     *   so that a reader of the preview can see exactly which part is data and which part
     *   is something they typed about themselves.
     */
    fun user(question: String, days: List<Day>, notes: String = ""): String = buildString {
        appendLine(question)
        appendLine()
        appendLine("Daily figures, oldest first:")
        appendLine(table(days))
        if (notes.isNotBlank()) {
            appendLine()
            appendLine("Things this person has noted about themselves:")
            appendLine(notes.trim())
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

    private fun Int?.cell(width: Int): String = (this?.toString() ?: "-").padEnd(width)
}
