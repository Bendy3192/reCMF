/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.health

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * How good last night was, as one number out of a hundred.
 *
 * The same shape as the sleep scores in Fitbit, Oura and Whoop, built from what this watch
 * actually reports rather than from what theirs do. Those scores agree on three things —
 * how long, how much of it restored anything, and whether the body settled — and this
 * keeps all three, adding the fourth only when another device on the phone can supply it.
 *
 * ## Why this one has an absolute target where [readiness] refuses to have any
 *
 * Readiness compares only against your own recent days, on the grounds that the figures it
 * reads have no published norms: the watch's stress index has neither a published method
 * nor published bands, so any threshold quoted for it would be invented.
 *
 * Sleep duration is the one figure here where that argument does not apply. Seven to nine
 * hours for an adult is a published consensus, not a guess, and a score that called five
 * hours "normal for you" because you always sleep five would be worse than useless — it
 * would be reassuring about the one thing worth not being reassured about. So duration is
 * scored against a target, and the target is yours to change.
 *
 * Composition and restoration have no such target: this watch's split between deep and
 * REM is not something reCMF can check against anything, and a resting pulse means
 * different things in different bodies. Those stay baseline-relative, exactly as readiness
 * scores them.
 *
 * ## The fourth component
 *
 * The CMF watch labels every stretch of the night deep, light, REM or unrecognised. There
 * is no waking state in its vocabulary, so how broken a night was is a question it cannot
 * answer. Another wearable on the same phone may answer it — Health Connect has a waking
 * stage and Fitbit fills it — and where that happens continuity is scored too. Where it
 * does not, the remaining three decide the whole score between them and nothing is
 * silently counted as average.
 */

/** One thing a night is judged on. */
enum class SleepPart {
    /** How long you were asleep, against a target rather than against your own habit. */
    DURATION,

    /**
     * The share of the night that was deep or REM.
     *
     * The two restorative stages together rather than separately, because this watch's
     * split between them is not something reCMF has any way to check.
     */
    COMPOSITION,

    /** Resting pulse that morning, against your own. Lower is the recovered direction. */
    RESTORATION,

    /**
     * How much of the time in bed was spent awake.
     *
     * The one part this watch cannot supply: it has no waking stage. Present only when a
     * second wearable wrote the night to Health Connect.
     */
    CONTINUITY,
}

/**
 * How much each part counts.
 *
 * Duration carries half, which is where every published sleep score puts it and is also
 * the part with the firmest ground under it. Composition comes next. Continuity is worth
 * the least — not because a broken night does not matter, but because it arrives from a
 * different device than everything above it, and a component measured elsewhere should
 * not be the one that decides the number.
 *
 * A missing part's weight is shared out among the rest rather than counted as zero.
 */
private val WEIGHTS = mapOf(
    SleepPart.DURATION to 0.50f,
    SleepPart.COMPOSITION to 0.25f,
    SleepPart.RESTORATION to 0.15f,
    SleepPart.CONTINUITY to 0.10f,
)

/**
 * How far short of the target a night has to fall before its length scores nothing.
 *
 * Half. A ramp straight down from the target to zero sleep was the first thing written
 * here, and a test written against it caught what it does: four hours, with every other
 * part at full marks, came out at seventy-five. Half a night's sleep is not three quarters
 * of a good night — sleep debt does not accumulate in a straight line, and the published
 * concern about short sleep is about the bottom of the range, not proportional across it.
 *
 * Below half the target the mark is simply nought, and grading it finer would read
 * precision into what is already as bad as this scale goes. That is the same judgement
 * [readiness] makes when it stops distinguishing beyond two spreads.
 */
private const val WORST_SHORTFALL_SHARE = 0.5f

/**
 * The share of time in bed spent asleep at which continuity scores full marks, and the
 * share at which it scores nothing.
 *
 * Sleep efficiency is the one figure in this file with a conventional line through it:
 * eighty-five per cent is the usual boundary for a normal night in sleep medicine. That
 * line sits half way up this scale, which is what it should do — it is a boundary, not a
 * target, and a night just the wrong side of it is not a nought.
 */
private const val BEST_EFFICIENCY = 0.95f
private const val WORST_EFFICIENCY = 0.75f

/** How far from usual still counts as reportable, in spreads. As in [readiness]. */
private const val FURTHEST = 2f

/** The least a baseline may be assumed to vary, as a share of its own average. */
private const val LEAST_SPREAD_SHARE = 0.02f

/** What a night is, reduced to the figures a score asks of it. */
data class Night(
    /** Deep, light and REM together, as the watch saw them. */
    val asleepSeconds: Int,

    /** Deep and REM together. */
    val restfulSeconds: Int,

    /**
     * Time awake between falling asleep and getting up, when something could measure it.
     *
     * Null is not zero. Zero is a night nothing interrupted; null is a night nothing was
     * watching for interruptions, and the two must not score the same.
     */
    val awakeSeconds: Int? = null,

    /** That morning's resting pulse, when the watch worked one out. */
    val restingHeartRate: Float? = null,

    /**
     * Whether whatever recorded this broke the night into stages at all.
     *
     * A Health Connect session may be nothing but a start and an end — some apps write
     * sleep that way — and a night with no stages has a length and nothing else. Without
     * this flag such a night is indistinguishable from one that was entirely light sleep,
     * and [preferMeasured] would hand it to the score in place of a properly staged one.
     */
    val staged: Boolean = true,

    /**
     * Whether this is the CMF watch's own account of the night, or another device's.
     *
     * Carried so a night is never compared against a baseline of nights measured by
     * something else. Two wearables disagree systematically about how long somebody slept —
     * they start the clock differently and call the edges differently — so a run of
     * Fitbit nights averaging eight hours makes a seven-hour night from the CMF watch look
     * like a short one when it may be nothing of the kind. That is a device difference
     * wearing the clothes of a bad night, and it is exactly the sort of precise-looking
     * number this app refuses elsewhere.
     */
    val fromWatch: Boolean = true,
)

/**
 * Which account of one night to score, when two devices both have one.
 *
 * Usually only one does: you wear one thing to bed. But two wearables on the same phone
 * both write to Health Connect, and reCMF writes its own nights there too, so a night with
 * two records is a real case and picking wrongly is worse than picking either — it means
 * scoring a wrist that was in a drawer.
 *
 * The watch's own night wins whenever it recorded one, and takes from the other only what
 * it could never measure itself: how much of the night was spent awake.
 *
 * The first rule here was that whichever device produced stages was the one on the wrist,
 * which is true of a watch in a drawer — it reports nothing, or a length — and quietly
 * wrong when both were worn. Then the other device's night won, and the screen showed one
 * night in its picture and scored a different one underneath: seven hours thirty-six above,
 * seven fourteen below, with nothing to say they came from different wrists. Both numbers
 * were right and the screen was not.
 *
 * So the watch is preferred where it has anything to prefer. Where it has nothing at all —
 * a night spent wearing only the other device — the other device's night is the night, and
 * the caller is told so.
 *
 * The resting pulse is always kept from [own], whichever night wins. It is not part of the
 * night at all — it is the morning's figure, chosen by its own rule — and swapping it for
 * something another app computed differently would silently change what the restoration
 * mark means.
 */
fun preferMeasured(own: Night?, elsewhere: Night?): Night? {
    if (elsewhere == null) return own
    if (own == null) return elsewhere

    return if (own.staged) {
        own.copy(awakeSeconds = elsewhere.awakeSeconds)
    } else {
        elsewhere.copy(restingHeartRate = own.restingHeartRate)
    }
}

/** One part's account of the night, in the unit it was measured in. */
data class SleepScorePart(
    val part: SleepPart,
    /** 0 to 1, where 1 is as good as this part is scored. */
    val standing: Float,
    /** What was measured: seconds for duration, a share for composition, bpm for pulse. */
    val measured: Float,
    /** What it was judged against — the target for duration, your own average otherwise. */
    val against: Float,
)

/** The night's standing, and the parts it was built from. */
data class SleepScore(
    /** 0 to 100. */
    val score: Int,
    val parts: List<SleepScorePart>,

    /**
     * Whether the night scored is the watch's own.
     *
     * False on a night spent wearing something else, when the watch recorded nothing and
     * the other device's night is the only one there is. Worth saying on the screen: the
     * picture above the score is drawn from the watch's last night, which on such a
     * morning is a different night entirely.
     */
    val fromWatch: Boolean = true,
)

/**
 * Scores one night.
 *
 * @param night last night, as the watch reported it.
 * @param restfulHistory the restful share of the nights before it, as fractions.
 * @param restingHistory the resting pulses of the days before it.
 * @param targetSeconds how long this person means to sleep. Eight hours by default, which
 *   is the middle of the published range rather than either end of it.
 * @param leastNights how many past nights a baseline-relative part needs before it may
 *   contribute. Four, as in [readiness], for the same reason.
 * @return null when there was no night to speak of. Duration alone is enough for a score,
 *   so this is only ever the answer when the watch reported nothing at all.
 */
fun sleepScore(
    night: Night,
    restfulHistory: List<Float> = emptyList(),
    restingHistory: List<Float> = emptyList(),
    targetSeconds: Int = 8 * 60 * 60,
    leastNights: Int = 4,
): SleepScore? {
    if (night.asleepSeconds <= 0 || targetSeconds <= 0) return null

    val parts = buildList {
        // Full marks at the target and no further. Sleeping past it is not scored down,
        // because there is nothing here that could tell an unusually long night from a
        // restful one, and inventing a penalty would be inventing. Below it the mark
        // falls to nothing by half the target, rather than by no sleep at all.
        val ofTarget = night.asleepSeconds.toFloat() / targetSeconds
        add(
            SleepScorePart(
                part = SleepPart.DURATION,
                standing = ((ofTarget - WORST_SHORTFALL_SHARE) / (1f - WORST_SHORTFALL_SHARE))
                    .coerceIn(0f, 1f),
                measured = night.asleepSeconds.toFloat(),
                against = targetSeconds.toFloat(),
            ),
        )

        val restful = night.restfulSeconds.toFloat() / night.asleepSeconds
        against(restful, restfulHistory, leastNights, higherIsBetter = true)?.let { standing ->
            add(
                SleepScorePart(
                    SleepPart.COMPOSITION,
                    standing,
                    restful,
                    restfulHistory.average().toFloat(),
                ),
            )
        }

        night.restingHeartRate?.let { pulse ->
            against(pulse, restingHistory, leastNights, higherIsBetter = false)?.let { standing ->
                add(
                    SleepScorePart(
                        SleepPart.RESTORATION,
                        standing,
                        pulse,
                        restingHistory.average().toFloat(),
                    ),
                )
            }
        }

        night.awakeSeconds?.let { awake ->
            val inBed = night.asleepSeconds + awake
            if (inBed > 0) {
                val efficiency = night.asleepSeconds.toFloat() / inBed
                val span = BEST_EFFICIENCY - WORST_EFFICIENCY
                add(
                    SleepScorePart(
                        part = SleepPart.CONTINUITY,
                        standing = ((efficiency - WORST_EFFICIENCY) / span).coerceIn(0f, 1f),
                        measured = efficiency,
                        against = BEST_EFFICIENCY,
                    ),
                )
            }
        }
    }

    val total = parts.sumOf { WEIGHTS.getValue(it.part).toDouble() }
    val standing = parts.sumOf { it.standing * WEIGHTS.getValue(it.part).toDouble() } / total

    return SleepScore(
        score = (100 * standing).roundToInt().coerceIn(0, 100),
        parts = parts,
        fromWatch = night.fromWatch,
    )
}

/**
 * Where one reading sits against the nights behind it, as 0 to 1.
 *
 * The same comparison readiness makes, rescaled: readiness wants a signed standing around
 * a middle of "usual", and a sleep score wants a share of full marks. Exactly usual comes
 * out as half, which is the honest reading — an ordinary night is an ordinary night, and a
 * score that called it perfect would have nowhere left to put a good one.
 *
 * @return null when there is no baseline worth comparing against, which leaves the part
 *   out of the score rather than guessing at it.
 */
private fun against(
    now: Float,
    history: List<Float>,
    leastNights: Int,
    higherIsBetter: Boolean,
): Float? {
    val past = history.takeIf { it.size >= leastNights } ?: return null

    val usual = past.average().toFloat()
    val spread = maxOf(past.spread(usual), abs(usual) * LEAST_SPREAD_SHARE)
    if (spread <= 0f) return null

    val away = ((now - usual) / spread).coerceIn(-FURTHEST, FURTHEST)
    val favourable = if (higherIsBetter) away else -away

    return (favourable / FURTHEST + 1f) / 2f
}

/**
 * The nights that may be used as a baseline for [tonight], keyed as they came in.
 *
 * Only nights measured by the same device. A baseline exists to say what usual looks like
 * for this person on this wrist, and half a fortnight of one device's nights mixed with
 * half of another's says what usual looks like for neither.
 *
 * Fewer than a handful left is not a problem to work around: the caller drops the signal,
 * the remaining ones share out its weight, and the score says less rather than something
 * that is not so.
 */
fun <K> comparable(nights: Map<K, Night>, tonight: Night): Map<K, Night> =
    nights.filterValues { it.fromWatch == tonight.fromWatch }

/** Population standard deviation: these are all the nights there are. */
private fun List<Float>.spread(mean: Float): Float =
    sqrt(sumOf { val d = it - mean; (d * d).toDouble() } / size).toFloat()
