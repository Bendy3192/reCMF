/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.recmf.R
import java.time.LocalDate
import java.time.format.TextStyle

/**
 * One measurement, opened from its tile.
 *
 * A tile answers "what does this read now". This answers the two questions that follow.
 *
 * The first is "is that a lot", and seven days drawn large enough to read a day off answer
 * it better than any band would — along with the lowest, average and highest that a strip
 * of bars cannot say.
 *
 * The second is "what is this even", and it needed asking. "Stress 50" is not information
 * to anybody, and for some of these figures the honest answer is that there is no standard
 * to hold them against — one of them is a field whose meaning reCMF inferred and never
 * confirmed. Saying so is the point: a sentence admitting what is not known is worth more
 * than a confident band invented to fill the space.
 *
 * It is deliberately still only seven days. That is what the app keeps, and a screen that
 * implied a month of history it cannot show would be worse than the tile it replaced.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetricDetailSheet(
    @DrawableRes icon: Int,
    label: String,
    value: String,
    explains: String,
    insight: AiInsight?,
    thinking: Boolean,
    onAskAgain: () -> Unit,
    week: List<DayValue>,
    format: (Float) -> String,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        // Scrollable, because the explanations are not all short: the stress one runs to a
        // paragraph, and without this the week and its figures would sit below the fold on
        // a small screen with no way to reach them.
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Text(label, style = MaterialTheme.typography.titleMedium)
            }

            Column {
                Text(value, style = MaterialTheme.typography.displaySmall)
                Text(
                    stringResource(R.string.metric_detail_today),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Above the week, not below it: someone who does not know what the number is
            // cannot read the chart of it either, and this is short enough to pass over.
            Text(
                explains,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Between what the figure is and what the week did: the explanation says what
            // the number means at all, and this says what yours has been doing.
            if (insight != null || thinking) {
                HorizontalDivider()
                MetricInsight(insight, thinking, onAskAgain)
            }

            val readings = week.mapNotNull { it.value }
            if (readings.size < 2) {
                // One day is not a week. Saying so is more use than a chart of one bar and
                // three statistics that all repeat the number already above them.
                Text(
                    stringResource(R.string.metric_detail_thin),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            HorizontalDivider()

            Text(
                stringResource(R.string.metric_detail_week),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            WeekChart(week)

            Statistic(R.string.metric_stat_max, format(readings.max()))
            Statistic(R.string.metric_stat_avg, format(readings.average().toFloat()))
            Statistic(R.string.metric_stat_min, format(readings.min()))
        }
    }
}

/**
 * What the assistant made of this figure, when there is one to be had.
 *
 * Not private to this file: sleep has a screen of its own rather than a tile, and it wants
 * exactly this block under it.
 *
 * Asked for on opening rather than behind a button, which is the whole point — somebody
 * who has turned this on wants the answer to be there, not to be a thing they request. The
 * cost of that is controlled by the cache underneath rather than by making them tap.
 *
 * The stars are shown while it thinks and never as a bar or a spinner. A wait of unknown
 * length that ends in a piece of writing wants a shape that says composing, not loading.
 */
@Composable
internal fun MetricInsight(
    insight: AiInsight?,
    thinking: Boolean,
    onAgain: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Sparkles(
                color = MaterialTheme.colorScheme.primary,
                // Still while there is an answer on the screen: an animation that never
                // stops is one the eye keeps going back to for no reason.
                size = if (thinking) 22.dp else 18.dp,
            )
            Text(
                stringResource(R.string.ai_insight),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        when {
            thinking && insight == null -> Text(
                stringResource(R.string.ai_insight_thinking),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            insight != null -> {
                Text(insight.text, style = MaterialTheme.typography.bodyMedium)

                if (insight.sources.isNotEmpty()) {
                    Text(
                        stringResource(R.string.ai_sources, insight.sources.joinToString(", ")),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(R.string.ai_insight_when, insight.atSeconds.asAgo()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onAgain, enabled = !thinking) {
                        Text(stringResource(R.string.action_ai_again))
                    }
                }
            }
        }
    }
}

/** When something happened, in the words somebody would use rather than a timestamp. */
@Composable
private fun Long.asAgo(): String {
    val minutes = ((System.currentTimeMillis() / 1000) - this) / 60
    return when {
        minutes < 1 -> stringResource(R.string.ago_now)
        minutes < 60 -> stringResource(R.string.ago_minutes, minutes.toInt())
        else -> stringResource(R.string.ago_hours, (minutes / 60).toInt())
    }
}

/**
 * The same seven days as the tile's strip, at a size where a single day can be picked out.
 *
 * Scaled to the week's own highest day for the same reason the strip is: this says how the
 * days sit against each other, and only the step ring has a target to sit against.
 */
@Composable
private fun WeekChart(days: List<DayValue>) {
    val peak = days.mapNotNull { it.value }.maxOrNull() ?: return
    val today = LocalDate.now()
    val bars = MaterialTheme.colorScheme.primary
    val labels = MaterialTheme.colorScheme.onSurfaceVariant

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(CHART_HEIGHT),
        ) {
            val gap = BAR_GAP.toPx()
            val slot = size.width / days.size
            val width = (slot - gap).coerceAtLeast(1f)
            val floor = EMPTY_BAR_HEIGHT.toPx()

            days.forEachIndexed { index, day ->
                // A day of zero and a day of nothing both come out as the flat line, and a
                // week spent indoors can peak at zero — dividing by it would raise every bar
                // to the top and read as a perfect week.
                val fraction = if (peak > 0f) (day.value ?: 0f) / peak else 0f
                val height = (floor + (size.height - floor) * fraction).coerceAtMost(size.height)

                drawRoundRect(
                    color = bars.copy(alpha = if (day.date == today) 1f else PAST),
                    topLeft = Offset(index * slot + gap / 2f, size.height - height),
                    size = Size(width, height),
                    cornerRadius = CornerRadius(width / 2f, width / 2f),
                )
            }
        }

        // Read from the composition rather than the process, so a language changed in
        // settings redraws these letters instead of leaving them in the old one.
        val locale = LocalLocale.current.platformLocale

        Row(Modifier.fillMaxWidth()) {
            days.forEach { day ->
                Text(
                    day.date.dayOfWeek.getDisplayName(TextStyle.NARROW, locale),
                    style = MaterialTheme.typography.labelSmall,
                    color = labels.copy(alpha = if (day.date == today) 1f else PAST),
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** One of the three figures under the chart. */
@Composable
private fun Statistic(@StringRes label: Int, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

/** Tall enough to compare two days that are close, short enough to leave room below. */
private val CHART_HEIGHT = 96.dp

/** The bar for a day with no reading, so an unworn day is visible as itself. */
private val EMPTY_BAR_HEIGHT = 3.dp

private val BAR_GAP = 8.dp

/** Behind today without disappearing: the past is context, not the subject. */
private const val PAST = 0.45f
