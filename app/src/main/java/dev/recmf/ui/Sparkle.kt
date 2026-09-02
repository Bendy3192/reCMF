/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Four-pointed stars, breathing out of step, for while the assistant is thinking.
 *
 * A spinner would say "loading". This says something is being composed, which is the
 * honest description of a wait whose length nobody can predict — and it is the one shape
 * that has come to mean exactly that.
 *
 * Drawn rather than pulled in: three concave diamonds and two animated floats. A library
 * for this would be a dependency to keep current forever, and the shape is eight lines.
 *
 * The satellites started smaller and were raised after rendering the thing out and looking
 * at it: below about a fifth of the main star they stop reading as stars at all and become
 * specks of dust beside it.
 *
 * The stars do not rotate. A four-pointed star turning looks like a loading spinner again,
 * which is the thing being avoided; scaling and fading reads as a shimmer instead.
 */
@Composable
fun Sparkles(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
) {
    val shimmer = rememberInfiniteTransition(label = "sparkles")

    // Three phases from two animations: the third star reuses the first's clock offset by
    // running its own a little slower, which reads as unsynchronised without a third
    // animation to keep in step.
    val first by shimmer.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "first",
    )
    val second by shimmer.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1700, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "second",
    )

    Canvas(modifier.size(size)) {
        val side = this.size.minDimension

        // Centre, upper right, lower left: a big one with two smaller ones drifting off it,
        // which is the arrangement that reads as a sparkle rather than as three stars.
        star(Offset(side * 0.36f, side * 0.54f), side * 0.32f, breath(first), color)
        star(Offset(side * 0.78f, side * 0.26f), side * 0.22f, breath(second), color)
        star(Offset(side * 0.72f, side * 0.80f), side * 0.18f, breath(first + 0.5f), color)
    }
}

/**
 * A phase turned into a size-and-brightness, peaking in the middle of its cycle.
 *
 * Wrapped rather than clamped so a star handed a phase past one simply starts again, which
 * is how the third gets its offset for free.
 */
private fun breath(phase: Float): Float {
    val wrapped = phase % 1f
    return if (wrapped < 0.5f) wrapped * 2f else (1f - wrapped) * 2f
}

/**
 * One four-pointed star: a diamond whose sides bow inwards.
 *
 * The control points sit at the centre, which is what makes the concave curve — a straight
 * diamond reads as a playing-card suit and not as a spark.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.star(
    at: Offset,
    radius: Float,
    amount: Float,
    color: Color,
) {
    // Never quite gone and never quite full: a star that vanishes leaves a hole where the
    // eye expects something, and one that fills reads as a blob.
    val scale = 0.45f + amount * 0.55f
    val reach = radius * scale

    val path = Path().apply {
        moveTo(at.x, at.y - reach)
        quadraticTo(at.x, at.y, at.x + reach, at.y)
        quadraticTo(at.x, at.y, at.x, at.y + reach)
        quadraticTo(at.x, at.y, at.x - reach, at.y)
        quadraticTo(at.x, at.y, at.x, at.y - reach)
        close()
    }

    drawPath(path, color.copy(alpha = 0.35f + amount * 0.65f))
}
