/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.service

import android.content.Context
import android.util.Log
import dev.recmf.ble.ProtocolLog
import dev.recmf.data.ActivitySampleEntity
import dev.recmf.data.HeartRateSampleEntity
import dev.recmf.data.RestingHeartRateSampleEntity
import dev.recmf.data.Spo2SampleEntity
import dev.recmf.data.SampleDao
import dev.recmf.data.SettingsStore
import dev.recmf.health.CumulativeReading
import dev.recmf.health.HealthConnectSync
import dev.recmf.health.stepDeltas
import dev.recmf.protocol.ActivitySample
import dev.recmf.protocol.HeartRateSample
import dev.recmf.protocol.Spo2Sample
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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

    suspend fun storeSpo2(samples: List<Spo2Sample>) {
        val usable = samples.filter { it.isValid }
        if (usable.isEmpty()) return

        dao.insertSpo2(usable.map { Spo2SampleEntity(timestamp = it.timestamp, percent = it.percent) })
    }

    suspend fun storeRestingHeartRate(samples: List<HeartRateSample>) {
        val usable = samples.filter { it.isValid }
        if (usable.isEmpty()) return

        dao.insertRestingHeartRate(
            usable.map { RestingHeartRateSampleEntity(timestamp = it.timestamp, bpm = it.bpm) },
        )
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
            val spo2 = dao.pendingSpo2(BATCH)
            val resting = dao.pendingRestingHeartRate(BATCH)
            if (activity.isEmpty() && heartRate.isEmpty() && spo2.isEmpty() && resting.isEmpty()) {
                break
            }

            // The watch reports running totals, so what Health Connect stores is the
            // movement between readings — which needs the reading before this batch.
            //
            // Failing that, ask Health Connect what is already there for that day. The
            // staging table is wiped by a reinstall and Health Connect's records are not,
            // so without this the first reading after one is dropped and a whole morning
            // with it. Its own total is the sum of what is already stored, which is the
            // one number that cannot double-count.
            val first = activity.firstOrNull()
            val fromTable = first?.let { dao.activityBefore(it.timestamp) }?.toReading()
            val baseline = fromTable ?: first?.let { healthConnect.stepsAlreadyWritten(it.timestamp) }

            val deltas = stepDeltas(
                activity.map { it.toReading() },
                previous = baseline,
                previousIsRecordedTotal = fromTable == null && baseline != null,
            )

            // One line that settles where a shortfall lives.
            //
            // The app's front screen shows the watch's counter; Health Connect gets a sum
            // of differences. When those disagree the question is whether the differences
            // were computed short or arrived short, and nothing about either number on its
            // own can say. So both are printed side by side: the counter's own advance
            // across this batch, and what was written for it. Equal means the arithmetic
            // is right and the loss is past this point; unequal means it is here.
            if (activity.isNotEmpty()) {
                val advance = baseline?.let { activity.last().steps - it.steps }
                ProtocolLog.note(
                    "Health Connect: wrote ${deltas.sumOf { it.steps }} steps" +
                        (advance?.let { ", counter moved $it" } ?: ", no baseline") +
                        ", ${deltas.size} interval(s) over ${activity.size} reading(s)" +
                        ", ${clock(activity.first().timestamp)}–${clock(activity.last().timestamp)}",
                )
            }

            val result = healthConnect.write(deltas, heartRate, spo2, resting)
            if (result.acceptedNothing) {
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
            if (result.spo2Timestamps.isNotEmpty()) {
                dao.markSpo2Synced(result.spo2Timestamps, now)
            }
            if (result.restingHeartRateTimestamps.isNotEmpty()) {
                dao.markRestingHeartRateSynced(result.restingHeartRateTimestamps, now)
            }

            uploaded += deltas.size + result.heartRateTimestamps.size +
                result.spo2Timestamps.size + result.restingHeartRateTimestamps.size
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
    /** Wall-clock time of a sample, for a log line a human has to check. */
    private fun clock(epochSeconds: Long): String =
        DateTimeFormatter.ofPattern("HH:mm")
            .format(Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()))

    suspend fun prune() {
        val cutoff = Instant.now().minusSeconds(RETENTION_SECONDS).epochSecond
        val removed = dao.pruneActivity(cutoff) + dao.pruneHeartRate(cutoff) +
            dao.pruneSpo2(cutoff) + dao.pruneRestingHeartRate(cutoff)
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
