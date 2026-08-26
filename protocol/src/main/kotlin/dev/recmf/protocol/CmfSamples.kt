/*
 * reCMF — a third-party companion app for the CMF Watch Pro 2.
 * Copyright (C) 2026 reCMF contributors
 *
 * Ported from Gadgetbridge (Copyright (C) 2024 José Rebelo), AGPL-3.0-or-later.
 * See LICENSE and NOTICE at the repository root.
 */
package dev.recmf.protocol

/**
 * One minute of movement, as the watch buckets it.
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
