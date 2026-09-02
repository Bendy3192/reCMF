/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.ui

import androidx.annotation.StringRes
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
import dev.recmf.health.SleepPart
import dev.recmf.health.SleepScore
import dev.recmf.health.SleepScorePart
import dev.recmf.protocol.CmfSleepStage
import dev.recmf.protocol.SleepSession
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * The night, in the three things worth asking about it.
 *
 * How long, when, and how it was spent — in that order, because that is the order they get
 * asked in. The score is a separate card below, and stayed off this one deliberately: this
 * card is what the watch reported, and a score is a judgement about it.
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

/**
 * Last night out of a hundred.
 *
 * Kept apart from the card above, which shows what the watch reported. This one is a
 * judgement, and the parts it was made of are listed under it for the same reason
 * readiness lists its own: a single number nobody can take apart is a number nobody has
 * any reason to believe.
 *
 * Each row says what was measured and what it was measured against, and both come out of
 * the scoring rather than being worked out again here. Computing "your usual 40%" a second
 * time in a composable is how a screen and the number above it quietly drift apart.
 */
@Composable
fun SleepScoreCard(score: SleepScore?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.sleep_score),
                style = MaterialTheme.typography.titleMedium,
            )

            if (score == null) {
                Text(
                    stringResource(R.string.sleep_score_thin),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(score.score.toString(), style = MaterialTheme.typography.displaySmall)
                Text(
                    stringResource(score.score.verdict()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }

            HorizontalDivider()

            score.parts.forEach { part ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        stringResource(part.part.label()),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        part.reading(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (part.standing < POORLY) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            HorizontalDivider()

            Text(
                stringResource(R.string.sleep_score_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Said only where it applies. On a phone with one wearable there is no waking
            // stage to explain, and a sentence about a device that is not there would be
            // an invitation to go looking for a setting that does not exist.
            if (score.parts.any { it.part == SleepPart.CONTINUITY }) {
                Text(
                    stringResource(R.string.sleep_score_elsewhere),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * How each part reads on its own row, in the unit it was measured in.
 *
 * The "x, usually y" phrasing and the percentage are readiness's strings rather than
 * copies of them. Three of these four parts are the same quantities readiness weighs, and
 * two identical sentences in two files are two sentences to keep in step in every language
 * the app is offered in.
 */
@Composable
private fun SleepScorePart.reading(): String = when (part) {
    SleepPart.DURATION -> stringResource(
        R.string.sleep_score_of_target,
        readableDuration(measured.toLong()),
        readableDuration(against.toLong()),
    )

    SleepPart.COMPOSITION -> stringResource(
        R.string.readiness_against,
        stringResource(R.string.readiness_share, (measured * 100).roundToInt()),
        stringResource(R.string.readiness_share, (against * 100).roundToInt()),
    )

    SleepPart.RESTORATION -> stringResource(
        R.string.readiness_against,
        measured.roundToInt().toString(),
        against.roundToInt().toString(),
    )

    // No "usually": what continuity is scored on is an endpoint of a scale, not this
    // person's own habit, and printing it as one would say something untrue.
    SleepPart.CONTINUITY -> stringResource(
        R.string.readiness_share,
        (measured * 100).roundToInt(),
    )
}

@StringRes
private fun SleepPart.label(): Int = when (this) {
    SleepPart.DURATION -> R.string.readiness_part_sleep_duration
    SleepPart.COMPOSITION -> R.string.readiness_part_sleep_quality
    SleepPart.RESTORATION -> R.string.readiness_part_resting_heart_rate
    SleepPart.CONTINUITY -> R.string.sleep_part_continuity
}

/**
 * The word for a score.
 *
 * Bands rather than a smooth scale, because the number is not precise enough to justify
 * one: a night at 71 and a night at 74 differ by less than the measurement error in any
 * of the parts they are made of.
 */
@StringRes
private fun Int.verdict(): Int = when {
    this >= 85 -> R.string.sleep_score_excellent
    this >= 70 -> R.string.sleep_score_good
    this >= 50 -> R.string.sleep_score_fair
    this >= 30 -> R.string.sleep_score_poor
    else -> R.string.sleep_score_bad
}

/** Below this a part is worth colouring, because it is what pulled the night down. */
private const val POORLY = 0.4f
