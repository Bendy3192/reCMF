/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.floor

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
 * A scale is drawn only where it is the content. In a line the shape carries the meaning —
 * it climbed, it settled — and a grid over that is furniture around a fact that needed no
 * furniture. In a field of dots there is no shape: the whole reading is *which level*, and
 * without a number against it a chart of four rows is a pattern to be decoded rather than
 * a thing to be read. So [DotChart] rules and labels its levels, and nothing else does.
 */

/** Height that fits under a row of metrics without taking the screen. */
private val CHART_HEIGHT: Dp = 72.dp

/** Thin, as marks should be: the data is the ink, not the stroke. */
private val LINE_WIDTH: Dp = 2.dp

/** Big enough to see and to aim at. */
private val DOT_RADIUS: Dp = 4.dp

/** A gap of surface between neighbours, so bars read as separate marks. */
private val BAR_GAP: Dp = 2.dp

/** Hairline: a rule is there to be read past, not looked at. */
private val GRID_WIDTH: Dp = 1.dp

/** Air between the plot and the numbers down its side. */
private val LABEL_GAP: Dp = 6.dp

/**
 * How many rules fit before they stop being levels and start being hatching.
 *
 * Five across seventy-two points leaves them a comfortable distance apart. Past that the
 * step widens instead — two percent, then five — so the grid thins out rather than closing
 * up as the day's spread grows.
 */
private const val MOST_LEVELS = 5

/** Step sizes worth landing a rule on, narrowest first. Nobody reads a grid of sevens. */
private val LEVEL_STEPS = listOf(1f, 2f, 5f, 10f, 20f, 50f, 100f)

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
 *
 * The scale is the day's own, widened to [minimumSpan] so that readings a percent apart do
 * not land at opposite edges. A fixed 90-100 was tried first and was worse: a day that
 * never left 97-99 came out as a straight row of dots across the middle, which is a chart
 * saying nothing at all.
 *
 * Which is where a scale of its own became necessary. Blood oxygen arrives in whole
 * percent, so a day settles onto three or four levels, and *which level* is the entire
 * content of the picture — a reader who cannot put a number to a row is left decoding a
 * pattern. So each whole value inside the range gets a rule, and the outermost two are
 * named. Pass [label] to turn that on; without it the dots are drawn bare, as before.
 *
 * @param label how to write a level, given its value — usually a unit stuck to a number.
 *   The two it is called for are the top and bottom rules, not the readings.
 */
@Composable
fun DotChart(
    points: List<ChartPoint>,
    color: Color,
    modifier: Modifier = Modifier,
    minimumSpan: Float = 0f,
    label: ((Float) -> String)? = null,
) {
    if (points.isEmpty()) return

    val (minValue, span) = points.scale(minimumSpan)
    val rules = if (label == null) emptyList() else levels(minValue, span, MOST_LEVELS)

    val firstTime = points.first().atSeconds.toFloat()
    val timeSpan = (points.last().atSeconds - points.first().atSeconds).toFloat().takeIf { it > 0f } ?: 1f

    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = MaterialTheme.typography.labelSmall
    val measurer = rememberTextMeasurer()

    Canvas(modifier.fillMaxWidth().height(CHART_HEIGHT)) {
        val radius = DOT_RADIUS.toPx()
        val usable = size.height - radius * 2

        fun heightOf(value: Float) = radius + (1f - (value - minValue) / span) * usable

        // Only the outermost rules are named. Between them the spacing does the talking,
        // and four numbers stacked down seventy-two points would be a list, not an axis.
        val named = listOfNotNull(rules.lastOrNull(), rules.firstOrNull()).distinct()
        val texts = label
            ?.let { write -> named.associateWith { measurer.measure(write(it), labelStyle) } }
            .orEmpty()

        // The numbers get a column of their own and the plot stops short of it. A dot
        // sitting under a label is a reading nobody can see and a number nobody can read.
        val gutter = texts.values.maxOfOrNull { it.size.width + LABEL_GAP.toPx() } ?: 0f
        val plot = (size.width - gutter).coerceAtLeast(radius * 2)

        rules.forEach { value ->
            drawLine(
                color = gridColor,
                start = Offset(0f, heightOf(value)),
                end = Offset(plot, heightOf(value)),
                strokeWidth = GRID_WIDTH.toPx(),
            )
        }

        texts.forEach { (value, text) ->
            drawText(
                textLayoutResult = text,
                color = labelColor,
                topLeft = Offset(
                    x = size.width - text.size.width,
                    y = heightOf(value) - text.size.height / 2f,
                ),
            )
        }

        // Inset by the radius at both ends, as the vertical already was: a dot centred on
        // the edge is drawn as a half moon, and the first and last reading of the day were
        // exactly the two getting that treatment.
        points.forEach { point ->
            drawCircle(
                color = color,
                radius = radius,
                center = Offset(
                    x = radius + (point.atSeconds - firstTime) / timeSpan * (plot - radius * 2),
                    y = heightOf(point.value),
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

/**
 * The round values to rule the plot at, given what it spans.
 *
 * Widens the step until the lines are few enough to read as levels rather than as
 * hatching, and gives up rather than drawing a grid so coarse it says nothing.
 *
 * @return every multiple of the chosen step inside the range, ascending; empty when no
 *   step in [LEVEL_STEPS] lands at most [most] lines inside it.
 */
private fun levels(minValue: Float, span: Float, most: Int): List<Float> {
    val top = minValue + span

    for (step in LEVEL_STEPS) {
        val first = ceil(minValue / step) * step
        if (first > top) continue

        val count = floor((top - first) / step).toInt() + 1
        if (count <= most) return List(count) { first + it * step }
    }

    return emptyList()
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

