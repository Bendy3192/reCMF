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
    /** Null until the sample has been written to Health Connect. */
    val syncedAt: Long? = null,
)

@Entity(tableName = "heart_rate_samples")
data class HeartRateSampleEntity(
    @PrimaryKey val timestamp: Long,
    val bpm: Int,
    val syncedAt: Long? = null,
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
