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

    // region History
    //
    // The staging table has held a week of readings all along and nothing has ever read
    // it back: the screen showed the newest number of each kind and the rest sat there.
    // These are the queries that turn it into something to look at. Ascending, because a
    // chart is drawn left to right and reversing a list in the UI is work the database
    // has already been asked to do.

    /**
     * Heart rate over a window. Bounded by the same sanity range as the latest reading —
     * a zero means the watch could not get a pulse, and a chart that dips to the floor
     * every time a wrist moved is worse than one with a gap.
     */
    @Query(
        "SELECT * FROM heart_rate_samples WHERE timestamp >= :since AND bpm BETWEEN 25 AND 250 " +
            "ORDER BY timestamp",
    )
    fun heartRateSince(since: Long): Flow<List<HeartRateSampleEntity>>

    /**
     * The cumulative step counter over a window, as the watch reported it.
     *
     * Deliberately not differenced here. A daily counter that resets at midnight tells a
     * chart two different stories depending on whether it is drawn as a total or as
     * movement, and picking which is the caller's business, not SQL's.
     */
    @Query("SELECT * FROM activity_samples WHERE timestamp >= :since ORDER BY timestamp")
    fun activitySince(since: Long): Flow<List<ActivitySampleEntity>>

    @Query("SELECT * FROM spo2_samples WHERE timestamp >= :since AND percent > 0 ORDER BY timestamp")
    fun spo2Since(since: Long): Flow<List<Spo2SampleEntity>>

    /**
     * The newest reading of the day, for the row above the chart.
     *
     * The row used to come from a value held in memory by the service, which is empty
     * until a sample happens to arrive — so after any restart the card read "—" for blood
     * oxygen while the chart underneath it was full of blood oxygen. The table is what
     * both should agree with.
     */
    @Query(
        "SELECT * FROM spo2_samples WHERE timestamp >= :since AND percent > 0 " +
            "ORDER BY timestamp DESC LIMIT 1",
    )
    fun latestSpo2Since(since: Long): Flow<Spo2SampleEntity?>

    @Query(
        "SELECT * FROM resting_heart_rate_samples WHERE timestamp >= :since AND bpm BETWEEN 25 AND 250 " +
            "ORDER BY timestamp DESC LIMIT 1",
    )
    fun latestRestingHeartRateSince(since: Long): Flow<RestingHeartRateSampleEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStress(samples: List<StressSampleEntity>)

    @Query("SELECT * FROM stress_samples WHERE timestamp >= :since ORDER BY timestamp")
    fun stressSince(since: Long): Flow<List<StressSampleEntity>>

    @Query("SELECT * FROM stress_samples WHERE timestamp >= :since ORDER BY timestamp DESC LIMIT 1")
    fun latestStressSince(since: Long): Flow<StressSampleEntity?>

    /** Stress goes to no one, so age is the only reason to drop it. */
    @Query("DELETE FROM stress_samples WHERE timestamp < :before")
    suspend fun pruneStress(before: Long): Int

    @Query(
        "SELECT * FROM resting_heart_rate_samples WHERE timestamp >= :since AND bpm BETWEEN 25 AND 250 " +
            "ORDER BY timestamp",
    )
    fun restingHeartRateSince(since: Long): Flow<List<RestingHeartRateSampleEntity>>
    // endregion

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
