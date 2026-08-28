/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The charts on the health screen.
 *
 * Each one shows a single measurement, which decides most of the design for us: with one
 * series there is no palette to assign, nothing to tell apart, and so no legend — the
 * heading above the chart names what it is. Colour comes from the theme, which on this
 * app is the wallpaper's, so it is chosen fresh on every phone and cannot be checked here;
 * what *can* be guaranteed is that no meaning is carried by colour alone, and that every
 * number and label wears a text colour rather than the mark's.
 *
 * Nothing draws its own axes. A line of times under the plot is enough for a day, and a
 * grid drawn over seven bars is furniture around a fact that needed no furniture.
 */

/** Height that fits under a row of metrics without taking the screen. */
private val CHART_HEIGHT: Dp = 72.dp

/** Thin, as marks should be: the data is the ink, not the stroke. */
private val LINE_WIDTH: Dp = 2.dp

/** Big enough to see and to aim at. */
private val DOT_RADIUS: Dp = 4.dp

/** A gap of surface between neighbours, so bars read as separate marks. */
private val BAR_GAP: Dp = 2.dp

/**
 * A measurement over time, drawn as a line.
 *
 * @param values one point per reading, already in order. Gaps in time are drawn as gaps
 *   in the line rather than joined across: a wrist that was not being read is not a
 *   straight run of the last known pulse.
 */
@Composable
fun LineChart(
    points: List<ChartPoint>,
    color: Color,
    modifier: Modifier = Modifier,
    minimumSpan: Float = 0f,
) {
    if (points.size < 2) return

    val (minValue, span) = points.scale(minimumSpan)

    val firstTime = points.first().atSeconds.toFloat()
    val timeSpan = (points.last().atSeconds - points.first().atSeconds).toFloat().takeIf { it > 0f } ?: 1f

    Canvas(modifier.fillMaxWidth().height(CHART_HEIGHT)) {
        val stroke = LINE_WIDTH.toPx()
        val usable = size.height - stroke

        fun at(point: ChartPoint) = Offset(
            x = (point.atSeconds - firstTime) / timeSpan * size.width,
            y = stroke / 2 + (1f - (point.value - minValue) / span) * usable,
        )

        val path = Path()
        var drawing = false

        points.forEachIndexed { index, point ->
            val previous = points.getOrNull(index - 1)
            val broken = previous != null && point.atSeconds - previous.atSeconds > GAP_SECONDS

            if (!drawing || broken) {
                path.moveTo(at(point).x, at(point).y)
                drawing = true
            } else {
                path.lineTo(at(point).x, at(point).y)
            }
        }

        drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
    }
}

/**
 * Totals per bucket, drawn as bars from a common baseline.
 *
 * Bars start at zero, always. A bar chart's whole claim is that length is quantity, and a
 * baseline anywhere else makes twice as long mean something other than twice as much.
 */
@Composable
fun BarChart(
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    if (values.isEmpty()) return

    val maxValue = values.max().takeIf { it > 0f } ?: return

    Canvas(modifier.fillMaxWidth().height(CHART_HEIGHT)) {
        val gap = BAR_GAP.toPx()
        val slot = size.width / values.size
        val width = (slot - gap).coerceAtLeast(1f)

        // Rounded at the data end only. A bar rounded at the baseline floats off it.
        val radius = CornerRadius(minOf(width / 2f, 4.dp.toPx()))

        values.forEachIndexed { index, value ->
            val height = value / maxValue * size.height
            if (height <= 0f) return@forEachIndexed

            drawRoundRect(
                color = color,
                topLeft = Offset(index * slot + gap / 2f, size.height - height),
                size = Size(width, height),
                cornerRadius = radius,
            )
        }
    }
}

/**
 * Readings that happen a few times a day, drawn as dots.
 *
 * A line through four points pretends to know what happened between them. Blood oxygen is
 * measured now and then, not continuously, and dots say so.
 */
@Composable
fun DotChart(
    points: List<ChartPoint>,
    color: Color,
    modifier: Modifier = Modifier,
    range: ClosedFloatingPointRange<Float>? = null,
) {
    if (points.isEmpty()) return

    val minValue = range?.start ?: points.minOf { it.value }
    val span = range?.let { it.endInclusive - it.start }
        ?: (points.maxOf { it.value } - points.minOf { it.value }).takeIf { it > 0f }
        ?: 1f

    val firstTime = points.first().atSeconds.toFloat()
    val timeSpan = (points.last().atSeconds - points.first().atSeconds).toFloat().takeIf { it > 0f } ?: 1f

    Canvas(modifier.fillMaxWidth().height(CHART_HEIGHT)) {
        val radius = DOT_RADIUS.toPx()
        val usable = size.height - radius * 2

        points.forEach { point ->
            drawCircle(
                color = color,
                radius = radius,
                center = Offset(
                    x = (point.atSeconds - firstTime) / timeSpan * size.width,
                    y = radius + (1f - (point.value - minValue) / span) * usable,
                ),
            )
        }
    }
}

/** Space held for a chart that has nothing to draw, so the card does not jump. */
@Composable
fun EmptyChart(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(CHART_HEIGHT))
}

/**
 * The bottom of the plot and how much it spans, given what has to fit.
 *
 * A range taken straight from the data makes every chart look dramatic: two blood-oxygen
 * readings a percent apart end up at opposite edges of the box, which is a picture of
 * noise drawn as if it were news. [minimumSpan] is the smallest difference worth showing
 * as the full height; anything narrower is centred inside it instead.
 */
private fun List<ChartPoint>.scale(minimumSpan: Float): Pair<Float, Float> {
    val low = minOf { it.value }
    val high = maxOf { it.value }
    val span = high - low

    if (span >= minimumSpan && span > 0f) return low to span

    val wanted = maxOf(minimumSpan, 1f)
    return (low + span / 2f - wanted / 2f) to wanted
}

/** One reading: when, and how much. */
data class ChartPoint(val atSeconds: Long, val value: Float)

/**
 * Longer than this between readings and the line is broken rather than drawn through.
 *
 * Twenty minutes is four missed refreshes — long enough that something was off, short
 * enough not to punch holes in an ordinary day.
 */
private const val GAP_SECONDS = 20 * 60

