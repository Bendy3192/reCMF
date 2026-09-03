/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.health

import kotlin.math.roundToInt

/**
 * What a body spends in a day, worked out here rather than asked of a model.
 *
 * The resting figure is a published equation in one line. Computing it locally is exact,
 * repeatable, testable, free and works with no network; handing arithmetic over somebody's
 * height and weight to a language model would be none of those things. So the app does the
 * sum and the assistant is left the part it is good at — saying what the number means and
 * what it does not.
 *
 * ## What this is not
 *
 * It is an estimate of what is spent, not a budget to eat to. reCMF does not propose a
 * deficit and the standing instructions forbid the assistant from proposing one either:
 * that is a dietary prescription, and an app that cheerfully hands one to somebody with a
 * low body mass or a history it knows nothing about does harm for the sake of a feature.
 * What is shown is maintenance, and a target is the wearer's own to set.
 */

/**
 * Which coefficient the equation takes.
 *
 * Every published resting-energy equation needs this, except the ones that need a body-fat
 * percentage nobody here has. It is optional all the same: the profile is deliberately not
 * a form of sensitive categories, and the honest answer when it is not given is the range
 * rather than a guess — which is what [restingEnergy] returns. Nothing infers it from a
 * name.
 */
enum class Sex { MALE, FEMALE }

/**
 * Resting energy in kilocalories a day, by Mifflin-St Jeor.
 *
 * `10·kg + 6.25·cm − 5·age`, plus five for one coefficient and minus a hundred and
 * sixty-one for the other. Chosen over Harris-Benedict because it is the one validated
 * against a modern population and the one most commonly quoted since.
 *
 * @return a single value as a degenerate range when [sex] is given, and the span between
 *   both coefficients — a hundred and sixty-six apart — when it is not. Null when a figure
 *   is missing or outside what a person is: an equation will happily return a number for a
 *   height of four centimetres, and printing it would be worse than printing nothing.
 */
fun restingEnergy(sex: Sex?, age: Int, heightCm: Int, weightKg: Int): IntRange? {
    if (age !in PLAUSIBLE_AGE || heightCm !in PLAUSIBLE_HEIGHT || weightKg !in PLAUSIBLE_WEIGHT) {
        return null
    }

    val common = 10.0 * weightKg + 6.25 * heightCm - 5.0 * age

    return when (sex) {
        Sex.MALE -> (common + MALE_OFFSET).roundToInt().let { it..it }
        Sex.FEMALE -> (common + FEMALE_OFFSET).roundToInt().let { it..it }
        null -> (common + FEMALE_OFFSET).roundToInt()..(common + MALE_OFFSET).roundToInt()
    }
}

/**
 * The day's whole spend: resting, plus what the watch says was moved.
 *
 * An activity multiplier is the usual second half of this — resting times one-point-two for
 * somebody sedentary, up to one-point-nine for somebody who trains twice a day — and it is
 * a guess about a person dressed up as arithmetic. There is a step counter on the wrist,
 * and the watch already reports what it makes of the movement, so the measured figure goes
 * in where the guess would have been.
 *
 * That figure is the watch's own estimate from step count and stride, which is a poor
 * absolute and a decent comparison between days. It is named as active calories on the
 * screen it comes from, so what is added here is what is shown there.
 *
 * The thermic effect of eating — roughly a tenth of what is eaten, spent digesting it — is
 * left out. It cannot be known without knowing what was eaten, and inventing a tenth would
 * be inventing.
 */
fun spentToday(resting: IntRange, activeKcal: Int): IntRange =
    (resting.first + activeKcal)..(resting.last + activeKcal)

/** Five up for one coefficient, a hundred and sixty-one down for the other. */
private const val MALE_OFFSET = 5.0
private const val FEMALE_OFFSET = -161.0

/**
 * What the equation may be asked about.
 *
 * Not medical bounds — bounds on what a filled-in form can be trusted to mean. Someone
 * half way through typing a height is at "1", and an equation asked about a person one
 * centimetre tall answers without complaint.
 */
private val PLAUSIBLE_AGE = 10..120
private val PLAUSIBLE_HEIGHT = 100..250
private val PLAUSIBLE_WEIGHT = 25..300
