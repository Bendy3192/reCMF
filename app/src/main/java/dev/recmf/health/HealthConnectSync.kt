/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
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
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Percentage
import dev.recmf.ble.ProtocolLog
import dev.recmf.data.HeartRateSampleEntity
import dev.recmf.data.RestingHeartRateSampleEntity
import dev.recmf.data.Spo2SampleEntity
import dev.recmf.protocol.CmfSleepStage
import dev.recmf.protocol.SleepSession
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.time.LocalDate
import java.time.ZoneId
import kotlin.reflect.KClass

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

    /**
     * Null whenever Health Connect cannot be talked to, for any reason at all.
     *
     * `getOrCreate` throws rather than returning null when the provider is missing or
     * unusable, and the availability check above does not cover every way that happens —
     * a provider disabled by the user, or one that fails to bind, still reports as
     * installed. Every caller in this class already treats a null client as "there is no
     * Health Connect here", which is also the right answer when creating it fails, so the
     * throw is turned into that rather than being allowed out. It would otherwise reach
     * whoever asked, and one of the askers is a view model's constructor.
     */
    private val client: HealthConnectClient? by lazy {
        if (availability() == HealthConnectAvailability.AVAILABLE) {
            runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull()
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

    suspend fun hasPermissions(): Boolean = grantedPermissions().containsAll(REQUIRED_PERMISSIONS)

    /** Whether the newer measurements may be written; false is not a failure. */
    suspend fun hasExtraPermissions(): Boolean =
        grantedPermissions().containsAll(EXTRA_PERMISSIONS)

    /**
     * Nothing granted is the honest reading of an unanswerable question, and the safe one:
     * every caller uses this to decide whether to read or to ask, and both are harmless
     * where the truth is unknown. Reading it can fail — the provider is a separate app and
     * the call crosses a process boundary — and a permission check is never worth crashing
     * over.
     */
    private suspend fun grantedPermissions(): Set<String> =
        runCatching { client?.permissionController?.getGrantedPermissions() }
            .getOrNull() ?: emptySet()

    /**
     * What is actually in Health Connect, and which app put it there.
     *
     * Written because the alternative was guessing. Whether the Google Health app publishes
     * heart-rate variability to Health Connect — and what else a second wearable quietly
     * contributes — is not something the documentation settles, and reCMF is in the
     * fortunate position of being able to simply look. One reading on the wearer's own
     * phone, with their own devices, beats any amount of reading about it.
     *
     * Reports the origin package of each record rather than only a count, because "there is
     * heart rate here" is a different fact from "there is heart rate here that reCMF did
     * not write". The second is the one that matters for a second source.
     *
     * @param sinceDays how far back to look. A fortnight is enough to tell a live feed from
     *   a dormant one without reading a year of samples to find out.
     */
    suspend fun survey(sinceDays: Long = 14): List<Held> {
        val client = this.client ?: return emptyList()
        val granted = grantedPermissions()
        val from = Instant.now().minus(sinceDays, ChronoUnit.DAYS)
        val window = TimeRangeFilter.after(from)

        return SURVEYED.map { (label, type) ->
            if (!granted.contains(HealthPermission.getReadPermission(type))) {
                return@map Held(label, Held.State.NOT_PERMITTED)
            }

            runCatching {
                val records = client.readRecords(ReadRecordsRequest(type, window)).records
                if (records.isEmpty()) {
                    Held(label, Held.State.EMPTY)
                } else {
                    Held(
                        label = label,
                        state = Held.State.PRESENT,
                        count = records.size,
                        // Health Connect returns a page, not the lot. A count equal to the
                        // page size means "at least this", and saying so is the difference
                        // between a diagnostic and a number that looks exact and is not.
                        capped = records.size >= PAGE,
                        // Whoever wrote the newest one. Several apps can write the same
                        // type, and the newest is the one worth naming.
                        writtenBy = records.maxByOrNull { it.metadata.lastModifiedTime }
                            ?.metadata?.dataOrigin?.packageName.orEmpty(),
                    )
                }
            }.getOrElse { Held(label, Held.State.REFUSED) }
        }
    }

    /**
     * Heart-rate variability from every app on this phone except reCMF.
     *
     * The exclusion is the whole of the correctness here. reCMF writes steps, pulse, sleep
     * and more into Health Connect, so an unfiltered read would hand the app its own
     * records back as though a second device had produced them — double counting, and a
     * loop where the app confirms itself. HRV happens to be the one type reCMF never
     * writes, which makes the filter free here; it is applied anyway, because the next
     * type read will not be so lucky and this is where the habit belongs.
     *
     * @return one RMSSD per day, averaged where a day has several, keyed on the local date
     *   the reading was taken. Empty when nothing is permitted, present or readable — all
     *   of which mean the same thing to a score that simply leaves the signal out.
     */
    suspend fun heartRateVariability(sinceDays: Long, zone: ZoneId): Map<LocalDate, Float> {
        val client = this.client ?: return emptyMap()

        if (!grantedPermissions().containsAll(HRV_PERMISSIONS)) return emptyMap()

        val from = Instant.now().minus(sinceDays, ChronoUnit.DAYS)

        return runCatching {
            client.readRecords(
                ReadRecordsRequest(
                    HeartRateVariabilityRmssdRecord::class,
                    TimeRangeFilter.after(from),
                ),
            ).records
                .filterNot { it.metadata.dataOrigin.packageName == ourPackage }
                .groupBy { it.time.atZone(zone).toLocalDate() }
                .mapValues { (_, day) ->
                    day.map { it.heartRateVariabilityMillis }.average().toFloat()
                }
        }.getOrElse { emptyMap() }
    }

    /** This app, so its own records can be told from everybody else's. */
    private val ourPackage: String get() = context.packageName

    /** One record type, as the survey found it. */
    data class Held(
        val label: String,
        val state: State,
        val count: Int = 0,
        val writtenBy: String = "",
        /** True when the count hit the page size and the real number is larger. */
        val capped: Boolean = false,
    ) {
        enum class State {
            /** Records exist in the window. */
            PRESENT,

            /** Permitted and readable, and there is nothing there. */
            EMPTY,

            /** The permission was never granted, so this says nothing either way. */
            NOT_PERMITTED,

            /** Health Connect refused the read, which is different from finding nothing. */
            REFUSED,
        }
    }

    /** Whether this granted set allows writing one particular kind of record. */
    private fun Set<String>.mayWrite(type: KClass<out Record>): Boolean =
        contains(HealthPermission.getWritePermission(type))

    /** So the note about them is made once a run rather than once a sync. */
    private var extrasNoted = false

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
        val upTo = Instant.ofEpochSecond(timestampSeconds)

        return try {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = StepsRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(dayStart, upTo),
                    dataOriginFilter = setOf(DataOrigin(context.packageName)),
                ),
            )

            val written = response.records.sumOf { it.count }.toInt()

            // Ending where our last record ends, so the interval about to be written
            // starts where the last one stopped rather than overlapping it. With nothing
            // there, midnight and zero — which is exactly true.
            val lastEnd = response.records.maxOfOrNull { it.endTime.epochSecond }
                ?: dayStart.epochSecond

            // The other two counters need the same treatment for the same reason. Left at
            // zero they would make the first reading after a reinstall look like a day's
            // walking, and the day's distance would be written twice.
            val distance = client.readRecords(
                ReadRecordsRequest(
                    recordType = DistanceRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(dayStart, upTo),
                    dataOriginFilter = setOf(DataOrigin(context.packageName)),
                ),
            ).records.sumOf { it.distance.inMeters }.toInt()

            val calories = client.readRecords(
                ReadRecordsRequest(
                    recordType = ActiveCaloriesBurnedRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(dayStart, upTo),
                    dataOriginFilter = setOf(DataOrigin(context.packageName)),
                ),
            ).records.sumOf { it.energy.inKilocalories }.toInt()

            CumulativeReading(
                timestamp = lastEnd,
                steps = written,
                distanceMeters = distance,
                calories = calories,
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
        val granted = grantedPermissions()
        if (!granted.containsAll(REQUIRED_PERMISSIONS)) return WriteResult.unavailable()

        val stepsWritten = insert(client, steps.map(::toStepsRecord))

        // Separate inserts so one refusal costs one measurement. These are a courtesy on
        // top of the steps — every other app on the phone reads distance from Health
        // Connect, and without these it reads zero however far the wearer walked — and
        // losing the steps because a distance record was rejected would be a poor trade.
        //
        // Checked one permission at a time rather than all of them together. Grouping them
        // means the day a new optional measurement is added, every phone that granted the
        // old group stops recording the old measurements too — which is the trap this set
        // exists to avoid in the first place.
        if (granted.mayWrite(DistanceRecord::class)) {
            insert(client, steps.filter { it.distanceMeters > 0 }.map(::toDistanceRecord))
        }
        if (granted.mayWrite(ActiveCaloriesBurnedRecord::class)) {
            insert(client, steps.filter { it.activeCalories > 0 }.map(::toActiveCaloriesRecord))
        }

        if (!granted.containsAll(EXTRA_PERMISSIONS) && steps.isNotEmpty() && !extrasNoted) {
            // Said out loud once, because the alternative is a wearer wondering why every
            // other app shows zero kilometres while reCMF's own screen shows the walk.
            extrasNoted = true
            ProtocolLog.note(
                "Health Connect: distance, calories or workouts are not permitted — " +
                    "turn Health Connect off and on again to be asked",
            )
        }

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
     * Writes the sessions worked out from the watch's workout pulse.
     *
     * Kept apart from [write] because these are not staged samples: they are derived from
     * samples, they change as a session goes on, and the same session is written again
     * with a later end rather than added to. The record is keyed on where the session
     * started, so a second write replaces the first.
     *
     * The type is "other workout" and not a guess. The watch knows perfectly well whether
     * this was a run or a rowing machine and does not say — it answers a request for
     * workout summaries with nothing at all — and filing a walk as a run because most
     * workouts are runs would put a wrong fact in a health record to avoid an honest gap.
     *
     * @return true when Health Connect took them, or when there was nothing to write.
     */
    suspend fun writeWorkouts(sessions: List<LongRange>): Boolean {
        if (sessions.isEmpty()) return true

        val client = this.client ?: return false
        if (!grantedPermissions().mayWrite(ExerciseSessionRecord::class)) return false

        return insert(client, sessions.map(::toExerciseRecord))
    }

    private fun toExerciseRecord(session: LongRange): ExerciseSessionRecord {
        val start = Instant.ofEpochSecond(session.first)
        val end = Instant.ofEpochSecond(session.last)

        return ExerciseSessionRecord(
            startTime = start,
            startZoneOffset = zoneOffsetAt(start),
            endTime = end,
            endZoneOffset = zoneOffsetAt(end),
            exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT,
            metadata = Metadata.autoRecorded(
                device = device,
                // Keyed on the start, so a session written again as it goes on replaces
                // itself instead of stacking up as overlapping exercise.
                clientRecordId = "recmf-workout-${session.first}",
            ),
        )
    }

    private fun toDistanceRecord(delta: IntervalDelta): DistanceRecord {
        val start = Instant.ofEpochSecond(delta.startSeconds)
        val end = Instant.ofEpochSecond(delta.endSeconds)

        return DistanceRecord(
            startTime = start,
            startZoneOffset = zoneOffsetAt(start),
            endTime = end,
            endZoneOffset = zoneOffsetAt(end),
            distance = Length.meters(delta.distanceMeters.toDouble()),
            metadata = Metadata.autoRecorded(
                device = device,
                clientRecordId = "recmf-distance-${delta.endSeconds}",
            ),
        )
    }

    /**
     * Active rather than total: the watch counts against a daily goal of a few hundred,
     * which is a figure that excludes simply being alive. Written as total it would be
     * read as a resting metabolism of four hundred kilocalories a day.
     */
    private fun toActiveCaloriesRecord(delta: IntervalDelta): ActiveCaloriesBurnedRecord {
        val start = Instant.ofEpochSecond(delta.startSeconds)
        val end = Instant.ofEpochSecond(delta.endSeconds)

        return ActiveCaloriesBurnedRecord(
            startTime = start,
            startZoneOffset = zoneOffsetAt(start),
            endTime = end,
            endZoneOffset = zoneOffsetAt(end),
            energy = Energy.kilocalories(delta.activeCalories.toDouble()),
            metadata = Metadata.autoRecorded(
                device = device,
                clientRecordId = "recmf-calories-${delta.endSeconds}",
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

        /** What one `readRecords` returns at most, which is what caps a survey's counts. */
        private const val PAGE = 1000

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

        /**
         * Distance and active calories, which arrived after people were already using the
         * app.
         *
         * Deliberately not in [REQUIRED_PERMISSIONS]. That set is what [hasPermissions]
         * tests before writing anything at all, so adding to it would stop every phone
         * that granted the old set from recording steps, heart rate or sleep — a working
         * install broken by a new feature it never asked for. Here they are asked for,
         * used when granted, and skipped when not.
         */
        val EXTRA_PERMISSIONS: Set<String> = setOf(
            HealthPermission.getWritePermission(DistanceRecord::class),
            HealthPermission.getReadPermission(DistanceRecord::class),
            HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
            HealthPermission.getWritePermission(ExerciseSessionRecord::class),
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        )

        /**
         * Heart-rate variability, read and never written.
         *
         * reCMF has none of its own: the watch it talks to reports one pulse a minute with
         * no intervals behind it, so nothing here can produce an RMSSD. It is asked for
         * because another device on the same phone might, and HRV is the input every
         * readiness score leans on hardest and the one this app has never had.
         *
         * Its own set, and out of the required one, for the same reason distance was: a
         * phone that granted the older permissions must keep working without granting this.
         */
        val HRV_PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
        )

        /** What the survey looks for, in the order it is worth reading. */
        private val SURVEYED: List<Pair<String, KClass<out Record>>> = listOf(
            "Heart rate variability" to HeartRateVariabilityRmssdRecord::class,
            "Heart rate" to HeartRateRecord::class,
            "Resting heart rate" to RestingHeartRateRecord::class,
            "Sleep" to SleepSessionRecord::class,
            "Blood oxygen" to OxygenSaturationRecord::class,
            "Steps" to StepsRecord::class,
            "Distance" to DistanceRecord::class,
            "Active calories" to ActiveCaloriesBurnedRecord::class,
            "Exercise" to ExerciseSessionRecord::class,
        )

        /** Everything worth asking for at once, which is what the dialog offers. */
        val ALL_PERMISSIONS: Set<String> = REQUIRED_PERMISSIONS + EXTRA_PERMISSIONS + HRV_PERMISSIONS
    }
}
