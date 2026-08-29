/*
 * reCMF — a third-party companion app for the CMF Watch Pro 2.
 * Copyright (C) 2026 reCMF contributors
 *
 * Ported from Gadgetbridge (Copyright (C) 2024 José Rebelo), AGPL-3.0-or-later.
 * See LICENSE and NOTICE at the repository root.
 */
package dev.recmf.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Decoders for the payloads reCMF acts on. Payload bodies are little-endian, unlike the
 * big-endian frame header.
 *
 * Every parser returns an empty list or null on a malformed payload rather than
 * throwing: these bytes come off a radio link, and one bad notification must not take
 * down the sync.
 */
object CmfParsers {
    const val ACTIVITY_RECORD_SIZE: Int = 32

    /**
     * Heart rate, SpO2 and stress all arrive as this: a timestamp and one value, in eight
     * little-endian bytes.
     */
    const val PAIR_RECORD_SIZE: Int = 8

    /** Resting heart rate is the odd one out: a timestamp and a single byte. */
    const val RESTING_RECORD_SIZE: Int = 5

    /** Two timestamps and ten bytes that have not been identified. */
    const val SLEEP_METADATA_SIZE: Int = 10
    const val SLEEP_HEADER_SIZE: Int = 4 + 4 + SLEEP_METADATA_SIZE
    const val SLEEP_STAGE_SIZE: Int = 8


    /**
     * `ACTIVITY_DATA`: a run of 32-byte records — timestamp, steps, distance, calories,
     * climbs, then 12 bytes we have not identified.
     */
    fun parseActivity(payload: ByteArray): List<ActivitySample> {
        if (payload.isEmpty() || payload.size % ACTIVITY_RECORD_SIZE != 0) return emptyList()

        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val out = ArrayList<ActivitySample>(payload.size / ACTIVITY_RECORD_SIZE)

        while (buf.remaining() >= ACTIVITY_RECORD_SIZE) {
            val sample = ActivitySample(
                timestamp = buf.int.toUnsignedLong(),
                steps = buf.int,
                distanceMeters = buf.int,
                calories = buf.int,
                // The fifth number was written off as part of an unidentified tail until
                // it was watched for a day: 9 in the morning, 11 by evening, 1 after
                // midnight. It counts something that accumulates and resets with the
                // date, and the goal block counts climbs alongside steps and calories in
                // the same size of number. Named on that, and on nothing stronger.
                climbs = buf.int,
            )
            buf.position(buf.position() + 12) // still unidentified, and always zero so far
            out.add(sample)
        }

        return out
    }

    /** `HEART_RATE_MANUAL_AUTO` / `HEART_RATE_WORKOUT`: 8-byte timestamp+bpm pairs. */
    fun parseHeartRate(payload: ByteArray): List<HeartRateSample> =
        parsePairs(payload) { timestamp, value -> HeartRateSample(timestamp, value) }

    /**
     * `SPO2`: 8-byte timestamp+percentage pairs, the same shape as the heart-rate records.
     */
    fun parseSpo2(payload: ByteArray): List<Spo2Sample> =
        parsePairs(payload) { timestamp, value -> Spo2Sample(timestamp, value) }

    /** `STRESS`: 8-byte timestamp+level pairs, on the watch's own 0-100 scale. */
    fun parseStress(payload: ByteArray): List<StressSample> =
        parsePairs(payload) { timestamp, value -> StressSample(timestamp, value) }

    /**
     * The watch's common record shape: a little-endian epoch second, then a value.
     *
     * A payload that is not a whole number of records is rejected outright rather than
     * read up to the last complete one — a partial record means the layout is not what we
     * think it is, and reading the prefix anyway would turn that into plausible garbage.
     */
    private inline fun <T> parsePairs(payload: ByteArray, make: (Long, Int) -> T): List<T> {
        if (payload.isEmpty() || payload.size % PAIR_RECORD_SIZE != 0) return emptyList()

        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val out = ArrayList<T>(payload.size / PAIR_RECORD_SIZE)

        while (buf.remaining() >= PAIR_RECORD_SIZE) {
            out.add(make(buf.int.toUnsignedLong(), buf.int))
        }

        return out
    }

    /**
     * `HEART_RATE_RESTING`: five bytes — a little-endian epoch second, then one byte of
     * beats per minute.
     *
     * Read off a real Watch Pro 2 rather than ported: Gadgetbridge decodes the opcode but
     * leaves the payload as a TODO, so there was nothing to port. Five-byte records at
     * `0fce8f6a 4f` — 2026-08-27 08:41:35 local, 79 bpm — with the timestamp tracking the
     * fetch to the second across four consecutive syncs.
     *
     * Note the one byte: the paired records elsewhere spend four on the value, and reading
     * this as one of those would consume the next record's timestamp as part of this one.
     */
    fun parseRestingHeartRate(payload: ByteArray): List<HeartRateSample> {
        if (payload.isEmpty() || payload.size % RESTING_RECORD_SIZE != 0) return emptyList()

        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val out = ArrayList<HeartRateSample>(payload.size / RESTING_RECORD_SIZE)

        while (buf.remaining() >= RESTING_RECORD_SIZE) {
            out.add(
                HeartRateSample(
                    timestamp = buf.int.toUnsignedLong(),
                    bpm = buf.get().toInt() and 0xff,
                ),
            )
        }

        return out
    }

    /**
     * `SLEEP_DATA`: a session header, then one record per stretch of sleep stage.
     *
     * The header is the start and wake timestamps and ten bytes nobody has identified;
     * each stage is a timestamp, a duration and a stage code, in eight bytes.
     *
     * **Ported, not observed.** Unlike the resting heart rate, Gadgetbridge does decode
     * this, so this follows its reading — but the resting rate is exactly the case where
     * a payload turned out not to match what the opcode suggested, so treat this as
     * unconfirmed until a real night has been through it.
     */
    fun parseSleep(payload: ByteArray): SleepSession? {
        if (payload.size < SLEEP_HEADER_SIZE) return null
        if ((payload.size - SLEEP_HEADER_SIZE) % SLEEP_STAGE_SIZE != 0) return null

        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)

        val start = buf.int.toUnsignedLong()
        val wake = buf.int.toUnsignedLong()
        val metadata = ByteArray(SLEEP_METADATA_SIZE).also(buf::get)

        val stages = ArrayList<SleepStageSample>((payload.size - SLEEP_HEADER_SIZE) / SLEEP_STAGE_SIZE)
        while (buf.remaining() >= SLEEP_STAGE_SIZE) {
            stages.add(
                SleepStageSample(
                    timestamp = buf.int.toUnsignedLong(),
                    duration = buf.short.toInt() and 0xffff,
                    stage = CmfSleepStage.fromCode(buf.short.toInt() and 0xffff),
                ),
            )
        }

        return SleepSession(start, wake, metadata, stages)
    }

    /** `BATTERY`: level percentage then a charging flag. */
    fun parseBattery(payload: ByteArray): BatteryStatus? {
        if (payload.size < 2) return null
        return BatteryStatus(
            levelPercent = (payload[0].toInt() and 0xff).coerceIn(0, 100),
            isCharging = payload[1] == 0x01.toByte(),
        )
    }

    /** `ACTIVITY_FETCH_ACK_1`: 0x01 to proceed to step 2, 0x02 once the backlog is drained. */
    fun parseFetchState(payload: ByteArray): ActivityFetchState? = when (payload.firstOrNull()) {
        0x01.toByte() -> ActivityFetchState.READY
        0x02.toByte() -> ActivityFetchState.FINISHED
        else -> null
    }

    /** `FIRMWARE_VERSION_RET`: one byte per version component, e.g. `1.0.0.51`. */
    fun parseFirmwareVersion(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        return payload.joinToString(".") { (it.toInt() and 0xff).toString() }
    }

    /** `SERIAL_NUMBER_RET`: a length byte followed by that many ASCII characters. */
    fun parseSerialNumber(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val length = payload[0].toInt() and 0xff
        if (payload.size != length + 1) return null
        return String(payload, 1, length, StandardCharsets.UTF_8)
    }

    /**
     * `TIME` payload: epoch seconds and the UTC offset in milliseconds, big-endian —
     * this one command does not follow the little-endian body convention.
     */
    fun buildTimePayload(epochSeconds: Long, utcOffsetMillis: Int): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            .putInt(epochSeconds.toInt())
            .putInt(utcOffsetMillis)
            .array()

    /**
     * Wire timestamps are unsigned 32-bit, so they stay positive past 2038 — where a
     * signed read would wrap into a negative epoch and land the sample in 1901.
     */
    private fun Int.toUnsignedLong(): Long = toLong() and 0xffffffffL
}
