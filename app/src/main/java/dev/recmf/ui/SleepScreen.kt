/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.recmf.R
import dev.recmf.protocol.CmfSleepStage
import dev.recmf.protocol.SleepSession
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The night, in the three things worth asking about it.
 *
 * How long, when, and how it was spent — in that order, because that is the order they get
 * asked in. What is deliberately not here is a score. Every app that draws one is running
 * an algorithm over the same stages this screen shows, and reCMF has no such algorithm; a
 * number invented to fill the space would be the one piece of made-up data in an app whose
 * whole point is that its figures come off the watch.
 *
 * The watch reports one night per morning and never repeats it, so this card shows one
 * night. Nights are stored as nights now — readiness needed them — so the run of them is
 * what the assistant reads below, even though the picture here is still of last night
 * alone.
 */
@Composable
fun SleepCard(
    session: SleepSession?,
    insight: AiInsight?,
    thinking: Boolean,
    onAskAgain: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.metric_sleep), style = MaterialTheme.typography.titleMedium)

            if (session == null) {
                Text(
                    stringResource(R.string.sleep_waiting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            val slept = session.stages.sumOf { it.duration }

            Text(readableDuration(slept.toLong()), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(
                    R.string.metric_sleep_value,
                    CLOCK.format(Instant.ofEpochSecond(session.startTimestamp)),
                    CLOCK.format(Instant.ofEpochSecond(session.wakeTimestamp)),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // The night in order, at its own scale: every stretch the watch reported, laid
            // end to end. Short stretches stay visible because a night is mostly short
            // stretches — a bar that swallowed them would show four blocks and a lie.
            StageTimeline(session)

            HorizontalDivider()

            // Deep, light, REM — the order they are usually read in, and not the order the
            // codes happen to be numbered. A stage the watch never reported is left out
            // rather than listed as zero.
            ORDERED_STAGES.forEach { stage ->
                val total = session.stages.filter { it.stage == stage }.sumOf { it.duration }
                if (total > 0) StageRow(stage, total, slept)
            }

            // Sleep is the one measurement with a screen instead of a tile, so it was the
            // one the assistant could not be asked about — the figures were reaching it all
            // along in every request, with nothing on screen to ask the question.
            if (insight != null || thinking) {
                HorizontalDivider()
                MetricInsight(insight, thinking, onAskAgain)
            }
        }
    }
}

/**
 * The night as it ran, left to right.
 *
 * Drawn from the stage list rather than from timestamps: the durations sum to the night
 * exactly — that is how the parse was confirmed — so laying them end to end is the same
 * picture with nothing to reconcile.
 */
@Composable
private fun StageTimeline(session: SleepSession) {
    val total = session.stages.sumOf { it.duration }.toFloat()
    if (total <= 0f) return

    val colors = session.stages.map { it.stage.color() }

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(TIMELINE_HEIGHT)
            .clip(RoundedCornerShape(4.dp)),
    ) {
        var x = 0f
        session.stages.forEachIndexed { index, stretch ->
            // Widths are taken from the running edge rather than accumulated, so rounding
            // cannot leave a hairline of background between two neighbouring stretches.
            val next = (x + size.width * (stretch.duration / total)).coerceAtMost(size.width)
            drawRect(
                color = colors[index],
                topLeft = Offset(x, 0f),
                size = Size((next - x).coerceAtLeast(1f), size.height),
            )
            x = next
        }
    }
}

/** One stage: its colour, its name, how long it lasted and what share of the night it was. */
@Composable
private fun StageRow(stage: CmfSleepStage, seconds: Int, night: Int) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(12.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(stage.color()),
        )

        Text(
            stringResource(stage.label),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            stringResource(R.string.value_percent, (100f * seconds / night).toInt()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(readableDuration(seconds.toLong()), style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * A stage's colour, from the wallpaper palette like everything else here.
 *
 * Never on its own: the timeline is the only place colour carries the meaning, and every
 * band in it is named in the rows underneath with its own swatch beside the name.
 */
@Composable
private fun CmfSleepStage.color(): Color = when (this) {
    CmfSleepStage.DEEP -> MaterialTheme.colorScheme.primary
    CmfSleepStage.LIGHT -> MaterialTheme.colorScheme.secondary
    CmfSleepStage.REM -> MaterialTheme.colorScheme.tertiary
    CmfSleepStage.UNKNOWN -> MaterialTheme.colorScheme.surfaceVariant
}

private val CmfSleepStage.label: Int
    get() = when (this) {
        CmfSleepStage.DEEP -> R.string.sleep_stage_deep
        CmfSleepStage.LIGHT -> R.string.sleep_stage_light
        CmfSleepStage.REM -> R.string.sleep_stage_rem
        CmfSleepStage.UNKNOWN -> R.string.sleep_stage_unknown
    }

/** Deep first, as every sleep report reads it. */
private val ORDERED_STAGES = listOf(
    CmfSleepStage.DEEP,
    CmfSleepStage.LIGHT,
    CmfSleepStage.REM,
    CmfSleepStage.UNKNOWN,
)


private val TIMELINE_HEIGHT = 40.dp

private val CLOCK: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
