/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.service

import android.content.Context
import android.util.Log
import dev.recmf.data.ActivitySampleEntity
import dev.recmf.data.HeartRateSampleEntity
import dev.recmf.data.SampleDao
import dev.recmf.data.SettingsStore
import dev.recmf.health.CumulativeReading
import dev.recmf.health.HealthConnectSync
import dev.recmf.health.stepDeltas
import dev.recmf.protocol.ActivitySample
import dev.recmf.protocol.HeartRateSample
import java.time.Instant

/**
 * Lands samples in the local database and moves them on to Health Connect.
 *
 * The database is a staging buffer, not an archive: samples are written as they arrive
 * (so a connection that drops mid-backlog loses nothing), uploaded in bounded batches,
 * and pruned once they are safely in Health Connect. Nothing here holds a whole sync in
 * memory, which is what lets a month-long backlog upload without the process growing.
 */
class SampleIngest(
    private val dao: SampleDao,
    private val settings: SettingsStore,
    context: Context,
) {
    private val healthConnect = HealthConnectSync(context)

    suspend fun storeActivity(samples: List<ActivitySample>) {
        if (samples.isEmpty()) return

        dao.insertActivity(
            samples.map {
                ActivitySampleEntity(
                    timestamp = it.timestamp,
                    steps = it.steps,
                    distanceMeters = it.distanceMeters,
                    calories = it.calories,
                )
            },
        )
    }

    suspend fun storeHeartRate(samples: List<HeartRateSample>) {
        // A zero here means the watch could not read a pulse that minute. Storing it
        // would drag every average down, so drop it at the door.
        val usable = samples.filter { it.isValid }
        if (usable.isEmpty()) return

        dao.insertHeartRate(usable.map { HeartRateSampleEntity(timestamp = it.timestamp, bpm = it.bpm) })
    }

    /**
     * Uploads everything still pending, in batches, stopping at the first batch that
     * fails so the rest stays pending for the next attempt.
     */
    suspend fun flushToHealthConnect() {
        if (!settings.current().healthConnectEnabled) return
        if (!healthConnect.hasPermissions()) {
            Log.i(TAG, "Health Connect permissions not granted; keeping samples pending")
            return
        }

        var uploaded = 0

        while (true) {
            val activity = dao.pendingActivity(BATCH)
            val heartRate = dao.pendingHeartRate(BATCH)
            if (activity.isEmpty() && heartRate.isEmpty()) break

            // The watch reports running totals, so what Health Connect stores is the
            // movement between readings — which needs the reading before this batch.
            val baseline = activity.firstOrNull()
                ?.let { dao.activityBefore(it.timestamp) }
                ?.toReading()

            val deltas = stepDeltas(activity.map { it.toReading() }, baseline)

            val result = healthConnect.write(deltas, heartRate)
            if (!result.stepsWritten && result.heartRateTimestamps.isEmpty()) {
                Log.w(TAG, "Health Connect accepted nothing; will retry on the next sync")
                return
            }

            val now = Instant.now().epochSecond
            if (result.stepsWritten && activity.isNotEmpty()) {
                // Every reading in the batch is accounted for, including the one that
                // only served as a baseline — its own interval was written last time.
                dao.markActivitySynced(activity.map { it.timestamp }, now)
            }
            if (result.heartRateTimestamps.isNotEmpty()) {
                dao.markHeartRateSynced(result.heartRateTimestamps, now)
            }

            uploaded += deltas.size + result.heartRateTimestamps.size
        }

        if (uploaded > 0) {
            val now = Instant.now().epochSecond
            settings.setLastSync(now)
            WatchStatus.lastSyncEpochSeconds.value = now
            Log.i(TAG, "Wrote $uploaded samples to Health Connect")
        }
    }

    private fun ActivitySampleEntity.toReading() = CumulativeReading(
        timestamp = timestamp,
        steps = steps,
        distanceMeters = distanceMeters,
        calories = calories,
    )

    /** Drops samples that have reached Health Connect and are older than the retention window. */
    suspend fun prune() {
        val cutoff = Instant.now().minusSeconds(RETENTION_SECONDS).epochSecond
        val removed = dao.pruneActivity(cutoff) + dao.pruneHeartRate(cutoff)
        if (removed > 0) Log.i(TAG, "Pruned $removed synced samples")
    }

    private companion object {
        const val TAG = "SampleIngest"

        /** Small enough that a batch is a modest allocation, large enough to be few round trips. */
        const val BATCH = 500

        /** A week of history stays queryable in-app after it has been handed off. */
        const val RETENTION_SECONDS = 7L * 24 * 60 * 60
    }
}
