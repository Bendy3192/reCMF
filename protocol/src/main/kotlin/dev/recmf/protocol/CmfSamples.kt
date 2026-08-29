/*
 * reCMF — a third-party companion app for the CMF Watch Pro 2.
 * Copyright (C) 2026 reCMF contributors
 *
 * Ported from Gadgetbridge (Copyright (C) 2024 José Rebelo), AGPL-3.0-or-later.
 * See LICENSE and NOTICE at the repository root.
 */
package dev.recmf.protocol

/**
 * The watch's running totals at one moment.
 *
 * These are **cumulative for the day**, not per-interval: polled repeatedly, the watch
 * returns the same figures with fresh timestamps, and they agree with each other as daily
 * totals (7125 steps against 5550 m, observed on firmware 1.0.0.73). Anything that adds
 * these up multiplies the day by the number of times it synced.
 *
 * All timestamps in this package are epoch **seconds**, matching the wire format; the
 * conversion to `Instant` happens once, at the storage and Health Connect boundaries.
 */
data class ActivitySample(
    val timestamp: Long,
    val steps: Int,
    val distanceMeters: Int,
    val calories: Int,
)

data class HeartRateSample(
    val timestamp: Long,
    val bpm: Int,
) {
    /**
     * The watch emits 0 (and occasionally 255) for a minute it could not measure —
     * a wrist-off gap, not a reading of zero.
     */
    val isValid: Boolean get() = bpm in VALID_BPM_RANGE

    companion object {
        val VALID_BPM_RANGE = 25..250
    }
}

/**
 * A blood-oxygen reading, as a percentage.
 *
 * The watch records these all day when the SpO2 monitoring setting is on, and on demand
 * otherwise. A zero is the watch reporting that it could not get a reading, not a
 * measurement of zero saturation.
 */
data class Spo2Sample(
    val timestamp: Long,
    val percent: Int,
) {
    val isValid: Boolean get() = percent in VALID_RANGE

    companion object {
        val VALID_RANGE = 50..100
    }
}

/**
 * A stress reading on the watch's own 0-100 scale.
 *
 * The scale is the watch's, not a physical unit, so reCMF passes it through rather than
 * converting it into anything that would imply more precision than it has. Zero means no
 * reading was taken.
 */
data class StressSample(
    val timestamp: Long,
    val level: Int,
) {
    val isValid: Boolean get() = level in VALID_RANGE

    companion object {
        val VALID_RANGE = 1..100
    }
}

data class BatteryStatus(
    val levelPercent: Int,
    val isCharging: Boolean,
)

/** Progress of an activity backlog download, as reported by `ACTIVITY_FETCH_ACK_1`. */
enum class ActivityFetchState {
    /** The watch acknowledged step 1 and is ready for step 2. */
    READY,

    /** The backlog has been fully sent. */
    FINISHED,
}

/** The stages the watch distinguishes. Anything it does not name is [UNKNOWN]. */
enum class CmfSleepStage {
    DEEP,
    LIGHT,
    REM,
    UNKNOWN,
    ;

    companion object {
        fun fromCode(code: Int): CmfSleepStage = when (code) {
            1 -> DEEP
            2 -> LIGHT
            3 -> REM
            else -> UNKNOWN
        }
    }
}

/**
 * One stretch of one stage.
 *
 * [duration] is **seconds**, confirmed by a real night rather than assumed. Gadgetbridge
 * stores the figure without saying what it is, and reading the unit wrong would file a
 * night as either forty minutes or forty hours. The capture settles it: thirty-three
 * stages summed to 25380, and the session's own wake-minus-start was 25380 to the second.
 * Thirty-three numbers do not agree with an independent total by accident.
 */
data class SleepStageSample(
    val timestamp: Long,
    val duration: Int,
    val stage: CmfSleepStage,
)

/**
 * A night, as the watch reports it: when it began, when the wearer woke, and the stages
 * in between.
 *
 * The ten bytes between the two timestamps are unidentified. They are carried rather than
 * skipped so that a capture can be compared against them later.
 */
data class SleepSession(
    val startTimestamp: Long,
    val wakeTimestamp: Long,
    val metadata: ByteArray,
    val stages: List<SleepStageSample>,
) {
    /** Data classes compare arrays by identity, which would make two equal nights unequal. */
    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is SleepSession &&
                    startTimestamp == other.startTimestamp &&
                    wakeTimestamp == other.wakeTimestamp &&
                    metadata.contentEquals(other.metadata) &&
                    stages == other.stages
                )

    override fun hashCode(): Int {
        var result = startTimestamp.hashCode()
        result = 31 * result + wakeTimestamp.hashCode()
        result = 31 * result + metadata.contentHashCode()
        result = 31 * result + stages.hashCode()
        return result
    }
}
