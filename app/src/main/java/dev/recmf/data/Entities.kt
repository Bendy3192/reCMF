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
