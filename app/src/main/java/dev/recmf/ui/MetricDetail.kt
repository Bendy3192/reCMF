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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
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
 * A tile answers "what does this read now". This answers the question that follows, which is
 * always some form of "is that a lot": the same seven days drawn large enough to read a day
 * off, and the three figures — lowest, average, highest — that a strip of bars cannot say.
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
    week: List<DayValue>,
    format: (Float) -> String,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
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
