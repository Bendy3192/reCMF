/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.LocalIndication
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.TextStyle

/**
 * The two-column grid of measurements on the health screen.
 *
 * A tile answers three questions in the order they get asked: what is this, what does it
 * read now, and is that usual for me. The last one is the reason the grid exists — a
 * number on its own is only ever a number, and a week beside it is the cheapest context
 * there is. Seven days is also exactly what the app keeps, so the strip is not a promise
 * of history it cannot show.
 *
 * Colour is tonal and comes from the wallpaper palette like everything else here, so a
 * tile's accent is a way of telling neighbours apart and never a way of saying something:
 * every tile carries an icon and a name, and reads the same in one colour.
 */

/** How tall a week strip is drawn. Enough to read a shape, not enough to be a chart. */
private val STRIP_HEIGHT = 28.dp

/** The bar for a day with no reading, so an unworn day is visible as itself. */
private val EMPTY_BAR_HEIGHT = 2.dp

/** Enough separation to read seven bars as seven, at any width a phone offers. */
private val STRIP_BAR_GAP = 4.dp

/**
 * The tonal role a tile wears.
 *
 * Four, cycled down the grid, because Material gives four container colours that are
 * distinguishable at this size in every wallpaper palette. Assignment is by position in
 * the grid rather than by meaning — see the note above.
 */
enum class TileAccent { PRIMARY, SECONDARY, TERTIARY, NEUTRAL }

/** The tile's ground. */
@Composable
private fun TileAccent.container(): Color = when (this) {
    TileAccent.PRIMARY -> MaterialTheme.colorScheme.primaryContainer
    TileAccent.SECONDARY -> MaterialTheme.colorScheme.secondaryContainer
    TileAccent.TERTIARY -> MaterialTheme.colorScheme.tertiaryContainer
    TileAccent.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant
}

/** Everything drawn on that ground: text, icon and bars alike. */
@Composable
private fun TileAccent.content(): Color = when (this) {
    TileAccent.PRIMARY -> MaterialTheme.colorScheme.onPrimaryContainer
    TileAccent.SECONDARY -> MaterialTheme.colorScheme.onSecondaryContainer
    TileAccent.TERTIARY -> MaterialTheme.colorScheme.onTertiaryContainer
    TileAccent.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * One measurement, with its week under it.
 *
 * @param value already formatted, because only the caller knows whether its number is
 *   kilometres to one decimal or a whole count.
 * @param week seven days oldest first, or empty to draw no strip at all. A single day is
 *   not a trend and gets no strip either.
 * @param onClick opens the measurement's own screen. Every tile has one, so the whole card
 *   is the target rather than some corner of it.
 */
@Composable
fun MetricTile(
    @DrawableRes icon: Int,
    label: String,
    value: String,
    accent: TileAccent,
    week: List<DayValue>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = accent.content()

    // Pressed, the tile gives a little and springs back rather than only flashing a
    // ripple. A ripple says the tap was received; giving under the finger says the thing
    // itself is soft, which is the difference between a screen that responds and one that
    // reacts. Two per cent, because the tile is small and any more reads as a wobble.
    val pressed = remember { MutableInteractionSource() }
    val isPressed by pressed.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "tile press",
    )

    Card(
        // Clipped before it is made clickable, so the ripple stops at the card's corners
        // instead of filling the rectangle behind them.
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CardDefaults.shape)
            .clickable(
                interactionSource = pressed,
                indication = LocalIndication.current,
                onClick = onClick,
            ),
        colors = CardDefaults.cardColors(containerColor = accent.container()),
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // The reading is the reason the tile exists, so it is given the weight to
            // say so: a size up, and heavy enough to be read across a room rather than
            // sitting at the same volume as its own label.
            //
            // And it arrives rather than appears. A sync brings numbers that are minutes
            // old; without this they simply *are* different the next time the screen is
            // looked at, and there is nothing anywhere to say the watch has just been
            // heard from. A figure that changes in front of you is the whole difference
            // between a readout and a report.
            AnimatedContent(targetState = value, label = "reading") { reading ->
                Text(
                    reading,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = content,
                    maxLines = 1,
                )
            }

            // Two days is the least that can show a direction. One bar under a number is
            // decoration, and the tile is better without it.
            if (week.count { it.value != null } >= 2) {
                Spacer(Modifier.height(2.dp))
                WeekStrip(week, content)
            }
        }
    }
}

/**
 * Seven days as seven bars, oldest on the left, today on the right.
 *
 * Scaled to the week's own highest day, so the strip says how today sits against the days
 * around it and never how it sits against a target — that is the ring's job, and it is the
 * only measurement here that has a target at all.
 *
 * Today is drawn solid and the days behind it are faded. A day the watch reported nothing
 * for keeps its column and gets a flat line: the difference between "did not move" and
 * "was not worn" is the whole reason the strip is worth drawing.
 */
@Composable
private fun WeekStrip(days: List<DayValue>, color: Color) {
    val peak = days.mapNotNull { it.value }.maxOrNull() ?: return
    val today = LocalDate.now()

    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(STRIP_HEIGHT),
        ) {
            val gap = STRIP_BAR_GAP.toPx()
            val slot = size.width / days.size
            val width = (slot - gap).coerceAtLeast(1f)
            val empty = EMPTY_BAR_HEIGHT.toPx()
            val radius = CornerRadius(width / 2f, width / 2f)

            days.forEachIndexed { index, day ->
                // A day of zero and a day of nothing both come out as the flat line; only
                // a day with a reading above zero gets height. Peak can itself be zero on
                // a week spent indoors, and dividing by it would put every bar at the top.
                val fraction = if (peak > 0f) (day.value ?: 0f) / peak else 0f
                val height = (empty + (size.height - empty) * fraction).coerceAtMost(size.height)

                drawRoundRect(
                    color = color.copy(alpha = if (day.date == today) 1f else FADED),
                    topLeft = Offset(index * slot + gap / 2f, size.height - height),
                    size = Size(width, height),
                    cornerRadius = radius,
                )
            }
        }

        // The locale is read from the composition rather than from the process. They are
        // the same until the moment they are not — a language changed in settings redraws
        // this row, and Locale.getDefault() would have left it in the old language until
        // something else happened to recompose it.
        val locale = LocalLocale.current.platformLocale

        Row(Modifier.fillMaxWidth()) {
            days.forEach { day ->
                Text(
                    day.date.dayOfWeek.getDisplayName(TextStyle.NARROW, locale),
                    style = MaterialTheme.typography.labelSmall,
                    color = color.copy(alpha = if (day.date == today) 1f else FADED),
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Behind today without disappearing: the past is context, not the subject. */
private const val FADED = 0.45f
