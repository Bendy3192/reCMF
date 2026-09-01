/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.ui

import androidx.annotation.DrawableRes
import dev.recmf.R
import dev.recmf.protocol.CmfActivityType

/**
 * A picture for every exercise the watch knows.
 *
 * The drawings are this project's own, in the same hand as the metric icons. The official
 * app has a set of its own and the watch carries another in its firmware; both are
 * somebody else's artwork, and neither belongs in a repository that hands its contents on
 * under a licence of its own. So these are drawn rather than lifted, which also means they
 * match the rest of the app instead of sitting oddly beside it.
 *
 * There are a hundred and sixteen exercises and nothing like a hundred and sixteen
 * drawings — most of them are variations that would look identical at 24dp anyway, so
 * they share. What matters is that **every one of them gets something**: an exercise with
 * no picture would read as a fault in the app rather than as a gap in a drawing set, so
 * the fallback is a heart rather than a blank or a question mark.
 *
 * The set grows from the middle outwards: the exercises people actually do first, the odd
 * ones as they earn it.
 */
@DrawableRes
fun CmfActivityType.iconRes(): Int = when (this) {
    CmfActivityType.INDOOR_RUNNING,
    CmfActivityType.OUTDOOR_RUNNING,
    CmfActivityType.CROSS_COUNTRY_RUNNING,
    CmfActivityType.TREADMILL,
    CmfActivityType.TRACK_AND_FIELD,
    CmfActivityType.PARKOUR,
    -> R.drawable.ic_sport_run

    CmfActivityType.OUTDOOR_WALKING,
    CmfActivityType.INDOOR_WALKING,
    CmfActivityType.STAIR_STEPPER,
    CmfActivityType.STAIRS,
    CmfActivityType.COOLDOWN,
    -> R.drawable.ic_sport_walk

    CmfActivityType.OUTDOOR_CYCLING,
    CmfActivityType.INDOOR_CYCLING,
    CmfActivityType.DYNAMIC_CYCLE,
    CmfActivityType.HAND_CYCLING,
    -> R.drawable.ic_sport_cycle

    CmfActivityType.MOUNTAIN_HIKE,
    CmfActivityType.HIKING,
    CmfActivityType.ROCK_CLIMBING,
    CmfActivityType.SKIING,
    CmfActivityType.SNOWBOARDING,
    CmfActivityType.CROSS_COUNTRY_SKIING,
    CmfActivityType.SNOW_SPORTS,
    CmfActivityType.LUGE,
    -> R.drawable.ic_sport_hike

    CmfActivityType.STRENGTH_TRAINING,
    CmfActivityType.DUMBBELL,
    CmfActivityType.SMITH_MACHINE,
    CmfActivityType.CROSSFIT,
    CmfActivityType.FUNCTIONAL_TRAINING,
    CmfActivityType.PHYSICAL_TRAINING,
    CmfActivityType.CORE_TRAINING,
    CmfActivityType.CROSS_TRAINING,
    CmfActivityType.CROSS_TRAINER,
    CmfActivityType.FREE_TRAINING,
    CmfActivityType.FITNESS_EXERCISES,
    CmfActivityType.HIIT,
    CmfActivityType.PUSH_UPS,
    CmfActivityType.PULL_UPS,
    CmfActivityType.SIT_UPS,
    CmfActivityType.PLANK,
    CmfActivityType.HORIZONTAL_BAR,
    CmfActivityType.PARALLEL_BAR,
    CmfActivityType.BATTLE_ROPE,
    CmfActivityType.ROWER,
    -> R.drawable.ic_sport_strength

    CmfActivityType.YOGA,
    CmfActivityType.PILATES,
    CmfActivityType.TAI_CHI,
    CmfActivityType.FLEXIBILITY,
    CmfActivityType.MIND_AND_BODY,
    CmfActivityType.GYMNASTICS,
    -> R.drawable.ic_sport_yoga

    // Everything else, for now. Not a failure to be fixed one day so much as an honest
    // statement that darts and dodgeball have not been drawn yet.
    else -> R.drawable.ic_sport_generic
}
