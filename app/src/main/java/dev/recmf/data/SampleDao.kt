/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SampleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertActivity(samples: List<ActivitySampleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHeartRate(samples: List<HeartRateSampleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSpo2(samples: List<Spo2SampleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRestingHeartRate(samples: List<RestingHeartRateSampleEntity>)

    /**
     * Unsynced samples, oldest first and capped, so a long backlog is uploaded in
     * batches instead of being materialized into memory all at once.
     */
    @Query("SELECT * FROM activity_samples WHERE syncedAt IS NULL ORDER BY timestamp LIMIT :limit")
    suspend fun pendingActivity(limit: Int): List<ActivitySampleEntity>

    @Query("SELECT * FROM heart_rate_samples WHERE syncedAt IS NULL ORDER BY timestamp LIMIT :limit")
    suspend fun pendingHeartRate(limit: Int): List<HeartRateSampleEntity>

    @Query("SELECT * FROM spo2_samples WHERE syncedAt IS NULL ORDER BY timestamp LIMIT :limit")
    suspend fun pendingSpo2(limit: Int): List<Spo2SampleEntity>

    @Query(
        "SELECT * FROM resting_heart_rate_samples WHERE syncedAt IS NULL " +
            "ORDER BY timestamp LIMIT :limit",
    )
    suspend fun pendingRestingHeartRate(limit: Int): List<RestingHeartRateSampleEntity>

    @Query("UPDATE spo2_samples SET syncedAt = :now WHERE timestamp IN (:timestamps)")
    suspend fun markSpo2Synced(timestamps: List<Long>, now: Long)

    @Query(
        "UPDATE resting_heart_rate_samples SET syncedAt = :now WHERE timestamp IN (:timestamps)",
    )
    suspend fun markRestingHeartRateSynced(timestamps: List<Long>, now: Long)

    @Query("UPDATE activity_samples SET syncedAt = :now WHERE timestamp IN (:timestamps)")
    suspend fun markActivitySynced(timestamps: List<Long>, now: Long)

    @Query("UPDATE heart_rate_samples SET syncedAt = :now WHERE timestamp IN (:timestamps)")
    suspend fun markHeartRateSynced(timestamps: List<Long>, now: Long)

    /**
     * Steps are a cumulative daily counter, so today's figure is the highest reading of
     * the day — never the sum, which would multiply the day by the number of syncs.
     */
    @Query("SELECT COALESCE(MAX(steps), 0) FROM activity_samples WHERE timestamp >= :since")
    fun stepsSince(since: Long): Flow<Int>

    @Query("SELECT * FROM heart_rate_samples WHERE bpm BETWEEN 25 AND 250 ORDER BY timestamp DESC LIMIT 1")
    fun latestHeartRate(): Flow<HeartRateSampleEntity?>

    /**
     * The reading immediately before [timestamp], synced or not: converting a cumulative
     * counter into intervals needs the value it started from.
     */
    @Query("SELECT * FROM activity_samples WHERE timestamp < :timestamp ORDER BY timestamp DESC LIMIT 1")
    suspend fun activityBefore(timestamp: Long): ActivitySampleEntity?

    /**
     * Drops synced samples older than [before]. Health Connect is the long-term store;
     * this table is a staging buffer and must not grow without bound.
     */
    @Query("DELETE FROM activity_samples WHERE syncedAt IS NOT NULL AND timestamp < :before")
    suspend fun pruneActivity(before: Long): Int

    @Query("DELETE FROM heart_rate_samples WHERE syncedAt IS NOT NULL AND timestamp < :before")
    suspend fun pruneHeartRate(before: Long): Int

    @Query("DELETE FROM spo2_samples WHERE syncedAt IS NOT NULL AND timestamp < :before")
    suspend fun pruneSpo2(before: Long): Int

    @Query(
        "DELETE FROM resting_heart_rate_samples " +
            "WHERE syncedAt IS NOT NULL AND timestamp < :before",
    )
    suspend fun pruneRestingHeartRate(before: Long): Int
}
