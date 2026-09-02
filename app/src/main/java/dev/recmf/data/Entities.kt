/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One minute of movement.
 *
 * The timestamp is the primary key, so re-downloading a backlog the watch already sent
 * overwrites rather than duplicates — the watch resends freely and this is what keeps
 * the table from growing on every sync.
 */
@Entity(tableName = "activity_samples")
data class ActivitySampleEntity(
    @PrimaryKey val timestamp: Long,
    val steps: Int,
    val distanceMeters: Int,
    val calories: Int,

    /** Flights climbed; see [dev.recmf.protocol.ActivitySample.climbs] for how sure that is. */
    val climbs: Int = 0,

    /** Null until the sample has been written to Health Connect. */
    val syncedAt: Long? = null,
)

@Entity(tableName = "heart_rate_samples")
data class HeartRateSampleEntity(
    @PrimaryKey val timestamp: Long,
    val bpm: Int,
    val syncedAt: Long? = null,

    /**
     * Whether the watch sent this as workout pulse rather than as its ordinary reading.
     *
     * The two arrive under different commands and used to be stored identically, which
     * threw away the only evidence this watch gives that a workout happened at all — it
     * keeps the pulse of a session and no summary of it. A run of these, close together,
     * *is* the workout.
     *
     * A timestamp that arrives both ways keeps whichever came last, since the row is
     * replaced wholesale. Workout pulse comes seconds apart and the ordinary reading
     * minutes apart, so a collision is rare, and losing the flag on one sample of a run
     * does not lose the run.
     */
    val duringWorkout: Boolean = false,
)

/**
 * A blood-oxygen reading.
 *
 * Same staging contract as the others: the timestamp is the key so a resent backlog
 * overwrites, and [syncedAt] marks what Health Connect already has.
 */
@Entity(tableName = "spo2_samples")
data class Spo2SampleEntity(
    @PrimaryKey val timestamp: Long,
    val percent: Int,
    val syncedAt: Long? = null,
)

/**
 * A resting heart rate, kept apart from [HeartRateSampleEntity].
 *
 * Health Connect models these as different things — a resting rate is a daily summary
 * figure, not a moment in a series — and mixing them would put the watch's resting
 * estimate into the middle of the live pulse graph.
 */
@Entity(tableName = "resting_heart_rate_samples")
data class RestingHeartRateSampleEntity(
    @PrimaryKey val timestamp: Long,
    val bpm: Int,
    val syncedAt: Long? = null,
)

/**
 * Stress, which has nowhere else to live.
 *
 * Every other measurement ends up in Health Connect, which has no record type for stress
 * — so before this table there was nowhere at all, and the figure vanished with the
 * process that read it. Kept here, it survives a restart and can be drawn like the rest.
 * Nothing marks it synced, because there is nothing to sync it to.
 */
@Entity(tableName = "stress_samples")
data class StressSampleEntity(
    @PrimaryKey val timestamp: Long,
    val level: Int,
)

/**
 * A night, kept because readiness needs more than the last one.
 *
 * Sleep used to survive only as "last night" in settings, overwritten every morning. That
 * is enough for a card that shows when you went to bed and enough for Health Connect,
 * which takes each night as it arrives — but sleep is the single strongest input any
 * readiness model has, and a baseline cannot be built from one night. So nights accumulate
 * here now.
 *
 * The stages are summed into seconds per stage rather than kept one by one: what the score
 * asks is how long the night was and how much of it was deep or REM, and a table of every
 * stretch would be a great deal of rows to answer two questions. The stages themselves
 * still reach Health Connect in full, from the frame, before this is written.
 *
 * Nothing marks it synced. Health Connect gets the night from the frame, not from here.
 */
@Entity(tableName = "sleep_sessions")
data class SleepSessionEntity(
    @PrimaryKey val startTimestamp: Long,
    val wakeTimestamp: Long,
    val deepSeconds: Int,
    val lightSeconds: Int,
    val remSeconds: Int,
    /** Stretches the watch labelled with a code we do not recognise. Counted, not guessed. */
    val unknownSeconds: Int,
) {
    /** Deep, light and REM together: time actually asleep, as the watch saw it. */
    val asleepSeconds: Int get() = deepSeconds + lightSeconds + remSeconds

    /**
     * The share of the night that was deep or REM, or null when there was no night to speak of.
     *
     * The two restorative stages taken together rather than separately: the watch's split
     * between them is not something reCMF has any way to check, and their sum is the part
     * every sleep model agrees matters.
     */
    val restfulShare: Float? get() =
        asleepSeconds.takeIf { it > 0 }?.let { (deepSeconds + remSeconds).toFloat() / it }
}

/**
 * What the assistant said about one measurement, kept so it is not asked twice.
 *
 * Keyed on the metric rather than on metric-and-day: only the newest answer about a figure
 * is ever wanted, and keeping the old ones would be a growing table nobody reads.
 *
 * The cache is the whole cost control. Opening a tile is meant to show an answer straight
 * away, and without this every tap would be a paid request — six tiles glanced at five
 * times in a day is thirty of them.
 *
 * @param atSeconds when it was asked, which is what staleness is judged on.
 * @param through the newest day it was told about, as that day's own date. A cached answer
 *   whose data has since moved is out of date even if it is minutes old, and one whose data
 *   has not moved is still good however old it is. A date rather than a hash of one, so a
 *   row can be read and understood by somebody looking at the table.
 */
@Entity(tableName = "ai_insights")
data class AiInsightEntity(
    @PrimaryKey val metric: String,
    val text: String,
    /** URLs, one per line, or empty. Most models cannot cite and that is not an error. */
    val sources: String,
    val atSeconds: Long,
    val through: String,
)

/**
 * What the watch has counted since a given moment — in practice, since midnight.
 *
 * Not a table: a projection of [ActivitySampleEntity], four running totals read in one
 * query rather than four.
 */
data class DailyTotals(
    val steps: Int = 0,
    val distanceMeters: Int = 0,
    val calories: Int = 0,
    val climbs: Int = 0,
)
