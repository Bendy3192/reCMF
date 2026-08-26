/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import dev.recmf.data.ActivitySampleEntity
import dev.recmf.data.HeartRateSampleEntity
import java.time.Instant
import java.time.ZoneId

/** Whether Health Connect can be used on this device at all. */
enum class HealthConnectAvailability {
    AVAILABLE,

    /** Installed but needs an update before the client can talk to it. */
    UPDATE_REQUIRED,

    /** Not present — on Android 13 and earlier it is a separate app the user installs. */
    NOT_INSTALLED,
}

/**
 * Writes samples into Health Connect so anything else on the phone — PoisonFit included —
 * reads the watch's data through the platform rather than through reCMF.
 *
 * Records are marked auto-recorded rather than manually entered, which is what tells
 * Health Connect this came off a device and not out of a text field.
 *
 * Every record carries a `clientRecordId` derived from its timestamp. That is what makes
 * re-uploading a backlog idempotent: the watch resends the same minutes freely, and
 * without a stable id each resend would add a duplicate day of steps.
 */
class HealthConnectSync(private val context: Context) {

    private val client: HealthConnectClient? by lazy {
        if (availability() == HealthConnectAvailability.AVAILABLE) {
            HealthConnectClient.getOrCreate(context)
        } else {
            null
        }
    }

    private val device by lazy {
        Device(
            manufacturer = "CMF by Nothing",
            model = "Watch Pro 2",
            type = Device.TYPE_WATCH,
        )
    }

    fun availability(): HealthConnectAvailability =
        when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                HealthConnectAvailability.UPDATE_REQUIRED

            else -> HealthConnectAvailability.NOT_INSTALLED
        }

    suspend fun hasPermissions(): Boolean {
        val granted = client?.permissionController?.getGrantedPermissions() ?: return false
        return granted.containsAll(REQUIRED_PERMISSIONS)
    }

    /**
     * Writes [activity] and [heartRate] and returns the timestamps that landed, so the
     * caller can mark exactly those rows synced. A partial failure leaves the rest
     * pending for the next run rather than losing them.
     */
    suspend fun write(
        activity: List<ActivitySampleEntity>,
        heartRate: List<HeartRateSampleEntity>,
    ): WriteResult {
        val client = this.client ?: return WriteResult.unavailable()
        if (!hasPermissions()) return WriteResult.unavailable()

        val steps = activity.filter { it.steps > 0 }.map(::toStepsRecord)
        val beats = toHeartRateRecords(heartRate)

        val stepsWritten = insert(client, steps)
        val heartRateWritten = insert(client, beats)

        return WriteResult(
            // A zero-step minute is real data with nothing for Health Connect to store;
            // count it as handled so it does not stay pending forever.
            activityTimestamps = if (stepsWritten) activity.map { it.timestamp } else emptyList(),
            heartRateTimestamps = if (heartRateWritten) heartRate.map { it.timestamp } else emptyList(),
        )
    }

    private suspend fun insert(
        client: HealthConnectClient,
        records: List<Record>,
    ): Boolean {
        if (records.isEmpty()) return true

        return try {
            records.chunked(INSERT_BATCH).forEach { client.insertRecords(it) }
            true
        } catch (e: Exception) {
            // Health Connect throws a variety of remote-process exceptions; none of them
            // should take down the sync service.
            Log.e(TAG, "Health Connect rejected ${records.size} records", e)
            false
        }
    }

    private fun toStepsRecord(sample: ActivitySampleEntity): StepsRecord {
        val start = Instant.ofEpochSecond(sample.timestamp)
        val end = start.plusSeconds(BUCKET_SECONDS)

        return StepsRecord(
            startTime = start,
            startZoneOffset = zoneOffsetAt(start),
            endTime = end,
            endZoneOffset = zoneOffsetAt(end),
            count = sample.steps.toLong(),
            metadata = Metadata.autoRecordedWithId(
                clientRecordId = "recmf-steps-${sample.timestamp}",
                device = device,
            ),
        )
    }

    /**
     * Health Connect models heart rate as a series, so consecutive minutes go into one
     * record instead of one record each — far fewer rows for the same data.
     */
    private fun toHeartRateRecords(samples: List<HeartRateSampleEntity>): List<HeartRateRecord> {
        val usable = samples.filter { it.bpm in VALID_BPM }.sortedBy { it.timestamp }
        if (usable.isEmpty()) return emptyList()

        val runs = mutableListOf<MutableList<HeartRateSampleEntity>>()
        for (sample in usable) {
            val current = runs.lastOrNull()
            val previous = current?.last()
            if (current == null || previous == null ||
                sample.timestamp - previous.timestamp > MAX_SERIES_GAP_SECONDS
            ) {
                runs.add(mutableListOf(sample))
            } else {
                current.add(sample)
            }
        }

        return runs.map { run ->
            val start = Instant.ofEpochSecond(run.first().timestamp)
            val end = Instant.ofEpochSecond(run.last().timestamp).plusSeconds(1)

            HeartRateRecord(
                startTime = start,
                startZoneOffset = zoneOffsetAt(start),
                endTime = end,
                endZoneOffset = zoneOffsetAt(end),
                samples = run.map {
                    HeartRateRecord.Sample(
                        time = Instant.ofEpochSecond(it.timestamp),
                        beatsPerMinute = it.bpm.toLong(),
                    )
                },
                metadata = Metadata.autoRecordedWithId(
                    clientRecordId = "recmf-hr-${run.first().timestamp}",
                    device = device,
                ),
            )
        }
    }

    /** Resolved per sample: a backlog can span a DST change or a flight. */
    private fun zoneOffsetAt(instant: Instant) = ZoneId.systemDefault().rules.getOffset(instant)

    data class WriteResult(
        val activityTimestamps: List<Long>,
        val heartRateTimestamps: List<Long>,
    ) {
        val wroteNothing: Boolean get() = activityTimestamps.isEmpty() && heartRateTimestamps.isEmpty()

        companion object {
            fun unavailable() = WriteResult(emptyList(), emptyList())
        }
    }

    companion object {
        private const val TAG = "HealthConnectSync"

        /** The watch buckets activity per minute. */
        private const val BUCKET_SECONDS = 60L

        /** A longer gap than this starts a new series rather than stretching one. */
        private const val MAX_SERIES_GAP_SECONDS = 15 * 60L

        private const val INSERT_BATCH = 500

        private val VALID_BPM = 25..250

        val REQUIRED_PERMISSIONS: Set<String> = setOf(
            HealthPermission.getWritePermission(StepsRecord::class),
            HealthPermission.getWritePermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
        )
    }
}
