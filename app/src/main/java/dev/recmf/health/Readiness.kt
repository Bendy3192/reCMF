/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.health

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * How today sits against the wearer's own recent days.
 *
 * ## Why this is not a Whoop score
 *
 * Whoop, Garmin and Fitbit build readiness mostly on **heart-rate variability** — the
 * beat-to-beat variation that tracks how much the parasympathetic system is holding the
 * brakes on. This watch does not give it to us. Its heart data is one `bpm` per minute
 * with no intervals behind it, and nothing in any capture carries RR data. So the input
 * those scores lean on hardest is simply absent, and a number here pretending to be theirs
 * would be a different quantity wearing their name.
 *
 * It can, however, arrive from somewhere else. Another wearable on the same phone may
 * publish RMSSD to Health Connect, and where it does this score reads it and weighs it
 * highest — which is the one thing that turns this from an honest approximation into the
 * same quantity those scores are built on.
 *
 * Without it, what the watch gives is enough for the honest version of the same idea:
 * resting heart rate, how long you slept and how much of it was deep or REM, and the
 * stress index.
 * Each of those, on its own, says very little. Against your own recent days they say quite
 * a lot — which is the one comparison available anyway, since there is no published norm
 * for this watch's stress index and the healthy range for resting heart rate is so wide
 * that a population figure would tell almost nobody anything.
 *
 * ## Why arithmetic and not a model
 *
 * This is deliberately plain maths with no language model in it. A model asked to score
 * the same day twice will answer differently, and neither answer can be checked; this can
 * be unit-tested, runs with no key, costs nothing and works for everyone. A model is a
 * good narrator for a number somebody else computed, and a poor place to compute it.
 *
 * ## Reading the number
 *
 * 50 is *exactly your usual*, not a middling grade. The scale is your own recent spread,
 * so 75 means a clearly better morning than your own recent average and 25 a clearly
 * worse one — for you, not against anybody else.
 */

/** A thing the watch measures that says something about how recovered somebody is. */
enum class ReadinessSignal {
    /**
     * Beat-to-beat variation, as RMSSD, and the input every published readiness model
     * leans on hardest.
     *
     * This watch cannot produce it — one pulse a minute, no intervals — so it arrives, if
     * at all, from another device by way of Health Connect. Absent, the score is built from
     * the rest and says so; present, it is the strongest thing in it.
     */
    HEART_RATE_VARIABILITY,

    /** Time asleep. The strongest input the watch itself can offer. */
    SLEEP_DURATION,

    /** The share of the night spent in deep or REM sleep, rather than light or awake. */
    SLEEP_QUALITY,

    /** Up against your own baseline is the classic sign of illness, drink or a hard day. */
    RESTING_HEART_RATE,

    /** The watch's own index. Meaningless absolutely, informative against yourself. */
    STRESS,
}

/**
 * How much each signal counts.
 *
 * Variability carries the most where it exists, and sleep the most where it does not.
 * Those are the two inputs every published readiness model agrees about, in that order,
 * and the stress index — which has no published meaning at all — carries the least.
 *
 * When a signal is missing its weight is shared out among the others rather than counted
 * as zero. That is what lets the same scoring serve a phone with a second device on it and
 * a phone without one: no variability simply means the remaining four decide it between
 * them, exactly as they did before there was a fifth.
 */
private val WEIGHTS = mapOf(
    ReadinessSignal.HEART_RATE_VARIABILITY to 0.30f,
    ReadinessSignal.SLEEP_DURATION to 0.25f,
    ReadinessSignal.SLEEP_QUALITY to 0.15f,
    ReadinessSignal.RESTING_HEART_RATE to 0.20f,
    ReadinessSignal.STRESS to 0.10f,
)

/**
 * The signals where a bigger number is a worse morning.
 *
 * Variability is not among them: higher RMSSD is the recovered direction, which is the
 * opposite of the pulse it is derived from and the classic way to get this backwards.
 */
private val LOWER_IS_BETTER = setOf(ReadinessSignal.RESTING_HEART_RATE, ReadinessSignal.STRESS)

/**
 * How far from usual still counts as reportable, in spreads.
 *
 * Beyond two, a day is simply "well outside your usual" and pretending to grade it finer
 * reads precision into what is much more likely to be a bad measurement.
 */
private const val FURTHEST = 2f

/**
 * The least a baseline may be assumed to vary, as a share of its own average.
 *
 * Without this, a run of identical readings makes the spread zero and the next day
 * scores infinitely good or infinitely bad. Two per cent is about a beat on a resting
 * pulse and eight minutes on a night's sleep, which is roughly the point below which a
 * change is not worth telling somebody about.
 */
private const val LEAST_SPREAD_SHARE = 0.02f

/** One signal's account of the day, in the units it was measured in. */
data class ReadinessPart(
    val signal: ReadinessSignal,
    val today: Float,
    val usual: Float,
    /** -1 well below your usual, 0 exactly usual, +1 well above. Already direction-aware. */
    val standing: Float,
    /** False when the reading came from another device rather than from the watch. */
    val fromWatch: Boolean = true,
)

/** A run of daily readings, and which device produced all of them. */
data class Sourced<K>(val readings: Map<K, Float>, val fromWatch: Boolean)

/**
 * Picks one device's account of a daily figure and uses it whole.
 *
 * Whole, rather than taking whichever device happened to have a reading on each day. A
 * baseline is a comparison against your own recent days, and days measured by two
 * different devices are not comparable: the same night in this app and in another read as
 * two and a half hours of deep sleep and one and a quarter, which is a classifier
 * difference the size of a bad night. Resting pulse has no stages to give that difference
 * away, which makes mixing there quieter and no more correct.
 *
 * The other device wins when it has today's reading and enough history of its own to be
 * judged against — the second condition being what stops the choice flipping between
 * devices as coverage comes and goes, since a source that changes mid-window destroys the
 * very comparison it was chosen for.
 *
 * @param today the key the score is being computed for.
 * @param leastDays how much history the other device needs before it may take over.
 */
fun <K : Comparable<K>> onlyOneSource(
    own: Map<K, Float>,
    elsewhere: Map<K, Float>,
    today: K,
    leastDays: Int,
): Sourced<K> {
    val theirHistory = elsewhere.keys.count { it < today }
    val theirs = elsewhere.containsKey(today) && theirHistory >= leastDays

    return if (theirs) Sourced(elsewhere, fromWatch = false) else Sourced(own, fromWatch = true)
}

/** The day's standing, and the parts it was built from. */
data class Readiness(
    /** 0 to 100, where 50 is exactly your usual. */
    val score: Int,
    val parts: List<ReadinessPart>,
)

/**
 * Scores today against the days behind it.
 *
 * @param today each signal's reading for today, in its own unit. Signals absent here are
 *   left out of the score entirely.
 * @param history the days before today, per signal, in any order. A signal with fewer than
 *   [leastDays] behind it has no baseline worth comparing against and is left out.
 * @param elsewhere which signals arrived from a device other than the watch, so the screen
 *   can say so. It changes nothing about the arithmetic.
 * @param leastDays how many past days a signal needs before it may contribute. Four is
 *   enough to have an average that is not simply yesterday, and low enough that a new
 *   wearer sees something inside a week.
 * @return null when nothing had both a reading today and a baseline to judge it by, which
 *   is the honest answer for a first week rather than a score built out of one day.
 */
fun readiness(
    today: Map<ReadinessSignal, Float>,
    history: Map<ReadinessSignal, List<Float>>,
    elsewhere: Set<ReadinessSignal> = emptySet(),
    leastDays: Int = 4,
): Readiness? {
    val parts = ReadinessSignal.entries.mapNotNull { signal ->
        val now = today[signal] ?: return@mapNotNull null
        val past = history[signal].orEmpty().takeIf { it.size >= leastDays } ?: return@mapNotNull null

        val usual = past.average().toFloat()
        val spread = maxOf(past.spread(usual), abs(usual) * LEAST_SPREAD_SHARE)
        if (spread <= 0f) return@mapNotNull null

        val away = ((now - usual) / spread).coerceIn(-FURTHEST, FURTHEST)
        val favourable = if (signal in LOWER_IS_BETTER) -away else away

        ReadinessPart(
            signal = signal,
            today = now,
            usual = usual,
            standing = favourable / FURTHEST,
            fromWatch = signal !in elsewhere,
        )
    }

    if (parts.isEmpty()) return null

    // Weights are renormalised over the signals that actually turned up, so a missing one
    // dilutes nothing: three signals decide the whole score between them rather than the
    // fourth silently voting "average".
    val total = parts.sumOf { WEIGHTS.getValue(it.signal).toDouble() }
    val standing = parts.sumOf { it.standing * WEIGHTS.getValue(it.signal).toDouble() } / total

    return Readiness(
        score = (50 + 50 * standing).roundToInt().coerceIn(0, 100),
        parts = parts,
    )
}

/** Population standard deviation: these are all the days there are, not a sample of them. */
private fun List<Float>.spread(mean: Float): Float =
    sqrt(sumOf { val d = it - mean; (d * d).toDouble() } / size).toFloat()
