/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Percentage
import dev.recmf.ble.ProtocolLog
import dev.recmf.data.HeartRateSampleEntity
import dev.recmf.data.RestingHeartRateSampleEntity
import dev.recmf.data.Spo2SampleEntity
import dev.recmf.protocol.CmfSleepStage
import dev.recmf.protocol.SleepSession
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
     * What reCMF has already written for the day [timestampSeconds] falls in, as a
     * cumulative reading.
     *
     * This is the answer to the one loss the staging table cannot cover. Health Connect
     * keeps reCMF's records across a reinstall; the app's own table does not, so on a
     * fresh install the first reading of the day has nothing to be differenced against
     * and is dropped — losing every step since midnight. Guessing instead and writing the
     * whole total would double whatever an earlier install had already recorded.
     *
     * Asking Health Connect settles it, because Health Connect is the thing that would be
     * double-counted. The sum of what is already there, ending where the last record
     * ends, *is* a cumulative reading for the day — so it slots straight into [stepDeltas]
     * as a baseline with no special case anywhere else.
     *
     * Only reCMF's own records are counted. The phone counts steps too, and so may
     * another watch; adding those in would subtract them from what the watch is owed.
     *
     * @return a baseline, or null when Health Connect cannot be read at all — in which
     *   case the caller is no worse off than before this existed.
     */
    suspend fun stepsAlreadyWritten(timestampSeconds: Long): CumulativeReading? {
        val client = this.client ?: return null

        val zone = ZoneId.systemDefault()
        val day = Instant.ofEpochSecond(timestampSeconds).atZone(zone).toLocalDate()
        val dayStart = day.atStartOfDay(zone).toInstant()

        return try {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = StepsRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(
                        dayStart,
                        Instant.ofEpochSecond(timestampSeconds),
                    ),
                    dataOriginFilter = setOf(DataOrigin(context.packageName)),
                ),
            )

            val written = response.records.sumOf { it.count }.toInt()

            // Ending where our last record ends, so the interval about to be written
            // starts where the last one stopped rather than overlapping it. With nothing
            // there, midnight and zero — which is exactly true.
            val lastEnd = response.records.maxOfOrNull { it.endTime.epochSecond }
                ?: dayStart.epochSecond

            CumulativeReading(
                timestamp = lastEnd,
                steps = written,
                distanceMeters = 0,
                calories = 0,
            )
        } catch (e: Exception) {
            // Reading is a courtesy here, not the job. Permission may have been revoked,
            // or the provider may be mid-update; either way the sync goes on.
            Log.i(TAG, "Could not read back what was already written", e)
            null
        }
    }

    /**
     * Writes the movement described by [steps] and the readings in [heartRate].
     *
     * [steps] are intervals, not the watch's cumulative totals — see [stepDeltas]. A
     * partial failure leaves the rest pending for the next run rather than losing it.
     */
    suspend fun write(
        steps: List<IntervalDelta>,
        heartRate: List<HeartRateSampleEntity>,
        spo2: List<Spo2SampleEntity> = emptyList(),
        restingHeartRate: List<RestingHeartRateSampleEntity> = emptyList(),
    ): WriteResult {
        val client = this.client ?: return WriteResult.unavailable()
        if (!hasPermissions()) return WriteResult.unavailable()

        val stepsWritten = insert(client, steps.map(::toStepsRecord))
        val heartRateWritten = insert(client, toHeartRateRecords(heartRate))
        val spo2Written = insert(client, spo2.map(::toSpo2Record))
        val restingWritten = insert(client, restingHeartRate.map(::toRestingHeartRateRecord))

        return WriteResult(
            stepsWritten = stepsWritten,
            heartRateTimestamps = if (heartRateWritten) heartRate.map { it.timestamp } else emptyList(),
            spo2Timestamps = if (spo2Written) spo2.map { it.timestamp } else emptyList(),
            restingHeartRateTimestamps =
                if (restingWritten) restingHeartRate.map { it.timestamp } else emptyList(),
        )
    }

    /**
     * Writes one night.
     *
     * A sleep session is not staged like the rest. The others arrive as a trickle of
     * readings that have to be differenced and batched; a night arrives once, complete,
     * some twenty minutes after the wearer got up, and either goes in whole or not at all.
     *
     * The stages are clamped into the session and the empty ones dropped, because Health
     * Connect refuses a stage that starts before its session or ends after it — and one
     * bad stage would cost the whole night rather than itself.
     */
    suspend fun writeSleep(session: SleepSession): Boolean {
        val client = this.client ?: return false
        if (!hasPermissions()) return false

        val start = Instant.ofEpochSecond(session.startTimestamp)
        val end = Instant.ofEpochSecond(session.wakeTimestamp)
        if (!end.isAfter(start)) return false

        val stages = session.stages.mapNotNull { stage ->
            val from = Instant.ofEpochSecond(stage.timestamp).coerceIn(start, end)
            val to = Instant.ofEpochSecond(stage.timestamp + stage.duration).coerceIn(start, end)
            if (!to.isAfter(from)) return@mapNotNull null

            SleepSessionRecord.Stage(startTime = from, endTime = to, stage = stage.stage.toHealthConnect())
        }

        val record = SleepSessionRecord(
            startTime = start,
            startZoneOffset = zoneOffsetAt(start),
            endTime = end,
            endZoneOffset = zoneOffsetAt(end),
            stages = stages,
            // Keyed on when the night began, so the same night delivered twice replaces
            // itself instead of stacking up.
            metadata = Metadata.autoRecorded(
                device = device,
                clientRecordId = "recmf-sleep-${session.startTimestamp}",
            ),
        )

        return insert(client, listOf(record))
    }

    private fun CmfSleepStage.toHealthConnect(): Int = when (this) {
        CmfSleepStage.DEEP -> SleepSessionRecord.STAGE_TYPE_DEEP
        CmfSleepStage.LIGHT -> SleepSessionRecord.STAGE_TYPE_LIGHT
        CmfSleepStage.REM -> SleepSessionRecord.STAGE_TYPE_REM

        // A code the watch uses and this app has not seen. Filed as unknown rather than
        // guessed into one of the three above, where it would quietly bias a night.
        CmfSleepStage.UNKNOWN -> SleepSessionRecord.STAGE_TYPE_UNKNOWN
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
            //
            // Said out loud, not just to logcat. A refusal here is invisible from the
            // outside — the records simply never appear — and the wearer cannot read
            // logcat. Half a day was spent looking for a leak in the arithmetic that may
            // well have been a refusal nobody could see.
            Log.e(TAG, "Health Connect rejected ${records.size} records", e)
            ProtocolLog.note("Health Connect refused ${records.size} record(s): ${e.message}")
            false
        }
    }

    private fun toStepsRecord(delta: IntervalDelta): StepsRecord {
        val start = Instant.ofEpochSecond(delta.startSeconds)
        val end = Instant.ofEpochSecond(delta.endSeconds)

        return StepsRecord(
            startTime = start,
            startZoneOffset = zoneOffsetAt(start),
            endTime = end,
            endZoneOffset = zoneOffsetAt(end),
            count = delta.steps.toLong(),
            // Keyed on the interval's end, which is the reading that produced it, so
            // re-uploading the same stretch replaces rather than adds.
            metadata = Metadata.autoRecorded(
                device = device,
                clientRecordId = "recmf-steps-${delta.endSeconds}",
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
                metadata = Metadata.autoRecorded(
                    device = device,
                    clientRecordId = "recmf-hr-${run.first().timestamp}",
                ),
            )
        }
    }

    /**
     * Health Connect models a saturation as a single instant, not an interval, so each
     * reading is its own record — there is no series form to group them into.
     */
    private fun toSpo2Record(sample: Spo2SampleEntity): OxygenSaturationRecord {
        val time = Instant.ofEpochSecond(sample.timestamp)

        return OxygenSaturationRecord(
            time = time,
            zoneOffset = zoneOffsetAt(time),
            percentage = Percentage(sample.percent.toDouble()),
            metadata = Metadata.autoRecorded(
                device = device,
                clientRecordId = "recmf-spo2-${sample.timestamp}",
            ),
        )
    }

    private fun toRestingHeartRateRecord(
        sample: RestingHeartRateSampleEntity,
    ): RestingHeartRateRecord {
        val time = Instant.ofEpochSecond(sample.timestamp)

        return RestingHeartRateRecord(
            time = time,
            zoneOffset = zoneOffsetAt(time),
            beatsPerMinute = sample.bpm.toLong(),
            metadata = Metadata.autoRecorded(
                device = device,
                clientRecordId = "recmf-resting-hr-${sample.timestamp}",
            ),
        )
    }

    /** Resolved per sample: a backlog can span a DST change or a flight. */
    private fun zoneOffsetAt(instant: Instant) = ZoneId.systemDefault().rules.getOffset(instant)

    data class WriteResult(
        val stepsWritten: Boolean,
        val heartRateTimestamps: List<Long>,
        val spo2Timestamps: List<Long> = emptyList(),
        val restingHeartRateTimestamps: List<Long> = emptyList(),
    ) {
        /** True when Health Connect took nothing at all, so nothing should be marked synced. */
        val acceptedNothing: Boolean
            get() = !stepsWritten &&
                heartRateTimestamps.isEmpty() &&
                spo2Timestamps.isEmpty() &&
                restingHeartRateTimestamps.isEmpty()

        companion object {
            fun unavailable() = WriteResult(stepsWritten = false, heartRateTimestamps = emptyList())
        }
    }

    companion object {
        private const val TAG = "HealthConnectSync"

        /** A longer gap than this starts a new series rather than stretching one. */
        private const val MAX_SERIES_GAP_SECONDS = 15 * 60L

        private const val INSERT_BATCH = 500

        private val VALID_BPM = 25..250

        val REQUIRED_PERMISSIONS: Set<String> = setOf(
            HealthPermission.getWritePermission(StepsRecord::class),
            HealthPermission.getWritePermission(HeartRateRecord::class),
            HealthPermission.getWritePermission(OxygenSaturationRecord::class),
            HealthPermission.getWritePermission(RestingHeartRateRecord::class),
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(OxygenSaturationRecord::class),
            HealthPermission.getReadPermission(RestingHeartRateRecord::class),
            HealthPermission.getWritePermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class),
        )
    }
}
