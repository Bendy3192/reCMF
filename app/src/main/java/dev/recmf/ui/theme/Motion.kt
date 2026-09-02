/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.ui.theme

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring

/**
 * The Material 3 Expressive motion scheme, written out rather than imported.
 *
 * `MotionScheme.expressive()` exists in the pinned `material3` 1.4.0 and cannot be reached
 * from here — see [ReCmfTheme] for the compiler's own account of why. What it would have
 * given us, though, is not machinery. It is twelve numbers: six springs, each a damping
 * ratio and a stiffness, that the library then hands to the same `spring()` this file
 * calls. So the scheme is transcribed instead, from the published androidx source
 * (`ExpressiveMotionTokens`, Apache-2.0), and applied by hand at the few places that
 * animate anything.
 *
 * That is a smaller claim than using Expressive and worth stating plainly: components from
 * the library would read a scheme off the theme and move correctly with no help. Nothing
 * here does that. What this gets is that the handful of animations reCMF *does* run move
 * on the specified curves instead of on whichever generic constant looked about right —
 * and it costs nothing and waits for nothing.
 *
 * The division is the useful part, and it is not about speed:
 *
 * **Spatial** springs move things — position, size, scale. They are underdamped on
 * purpose, so a thing that arrives slightly overshoots and settles, the way an object with
 * mass does. That overshoot is most of what "expressive" means.
 *
 * **Effects** springs change how a thing looks without moving it — colour, opacity,
 * elevation. They are critically damped, every one of them, because a colour that
 * overshoots does not read as weight. It reads as a flicker.
 *
 * Fast, default and slow within each are chosen by how far the change carries, not by how
 * urgent it feels: a whole ring filling is slow, a tile giving under a finger is fast.
 */
object Motion {

    /** Moving things: underdamped, so they arrive with a little weight behind them. */
    fun <T> spatialFast(): SpringSpec<T> = spring(SPATIAL_FAST_DAMPING, SPATIAL_FAST_STIFFNESS)

    fun <T> spatial(): SpringSpec<T> = spring(SPATIAL_DAMPING, SPATIAL_STIFFNESS)

    fun <T> spatialSlow(): SpringSpec<T> = spring(SPATIAL_SLOW_DAMPING, SPATIAL_SLOW_STIFFNESS)

    /** Changing appearance: critically damped, because colour has no momentum to show. */
    fun <T> effectsFast(): SpringSpec<T> = spring(EFFECTS_FAST_DAMPING, EFFECTS_FAST_STIFFNESS)

    fun <T> effects(): SpringSpec<T> = spring(EFFECTS_DAMPING, EFFECTS_STIFFNESS)

    fun <T> effectsSlow(): SpringSpec<T> = spring(EFFECTS_SLOW_DAMPING, EFFECTS_SLOW_STIFFNESS)
}

// The table itself. Kept as named constants rather than inlined into the calls above so
// that it reads as what it is — a transcription that can be checked against the source
// line by line — and so a wrong digit is findable.

private const val SPATIAL_FAST_DAMPING = 0.6f
private const val SPATIAL_FAST_STIFFNESS = 800f

private const val SPATIAL_DAMPING = 0.8f
private const val SPATIAL_STIFFNESS = 380f

private const val SPATIAL_SLOW_DAMPING = 0.8f
private const val SPATIAL_SLOW_STIFFNESS = 200f

private const val EFFECTS_FAST_DAMPING = 1.0f
private const val EFFECTS_FAST_STIFFNESS = 3800f

private const val EFFECTS_DAMPING = 1.0f
private const val EFFECTS_STIFFNESS = 1600f

private const val EFFECTS_SLOW_DAMPING = 1.0f
private const val EFFECTS_SLOW_STIFFNESS = 800f
