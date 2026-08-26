/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 *
 * Payload layouts ported from Gadgetbridge (AGPL-3.0-or-later); see NOTICE.
 */
package dev.recmf.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The continuous measurements the watch can be told to run.
 *
 * They share one command and are selected by the first payload byte, so the watch treats
 * them as one facility with three switches rather than three settings.
 */
enum class MonitoringChannel(val selector: Byte) {
    HEART_RATE(0x01),
    SPO2(0x02),
    STRESS(0x04),
}

/**
 * Builders for the watch's configuration payloads.
 *
 * Pure, and therefore testable — which is the point: a wrong byte here does not fail, it
 * silently configures the watch to something other than what the user asked for, and the
 * only way to notice is to look at the watch.
 */
object CmfSettings {

    /** `HEART_MONITORING_ENABLED_SET`: which measurement, and whether it runs. */
    fun monitoring(channel: MonitoringChannel, enabled: Boolean): ByteArray =
        byteArrayOf(channel.selector, if (enabled) 1 else 0)

    /**
     * `GOALS_SET`: the three daily targets, big-endian, each behind two bytes we have not
     * identified.
     */
    fun goals(steps: Int, distanceMeters: Int, calories: Int): ByteArray =
        ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN)
            .put(0).put(0)
            .putShort(steps.toUShortClamped())
            .put(0).put(0)
            .putShort(distanceMeters.toUShortClamped())
            .putShort(calories.toUShortClamped())
            .array()

    /** `WAKE_ON_WRIST_RAISE`: light the screen when the wrist is turned. */
    fun wakeOnWristRaise(enabled: Boolean): ByteArray = byteArrayOf(if (enabled) 1 else 0)

    /** `TIME_FORMAT`: note the inversion — zero is the 24-hour clock. */
    fun timeFormat(use24Hour: Boolean): ByteArray = byteArrayOf(if (use24Hour) 0 else 1)

    /**
     * `UNIT_LENGTH` and `UNIT_TEMPERATURE` take the same payload, and the watch expects
     * both to be set together.
     */
    fun measurementSystem(metric: Boolean): ByteArray = byteArrayOf(0x01, if (metric) 0 else 1)

    /**
     * `HEART_MONITORING_ALERTS`: thresholds that make the watch buzz.
     *
     * A zero threshold means "no alert". With all four unset the watch wants a single
     * zero byte rather than a payload full of zeroes — and an unset high threshold inside
     * an otherwise-populated payload is sent as 255, not 0, since 0 would mean "alert
     * above zero beats per minute".
     */
    fun heartAlerts(
        restingHigh: Int = 0,
        activeHigh: Int = 0,
        low: Int = 0,
        spo2Low: Int = 0,
    ): ByteArray {
        if (restingHigh == 0 && activeHigh == 0 && low == 0 && spo2Low == 0) {
            return byteArrayOf(0)
        }

        return ByteBuffer.allocate(9).order(ByteOrder.BIG_ENDIAN)
            .put(0x01)
            .put(low.toByte())
            .put(if (restingHigh != 0) restingHigh.toByte() else 255.toByte())
            .put(if (activeHigh != 0) activeHigh.toByte() else 255.toByte())
            .put(spo2Low.toByte())
            .put(ByteArray(4)) // unidentified
            .array()
    }

    /**
     * `STANDING_REMINDER_SET` and `WATER_REMINDER_SET`: the same eleven bytes for both.
     *
     * The quiet window is seconds since midnight, and is sent as zeroes when the reminder
     * is off — the watch reads a window of nothing as "no quiet hours" rather than as
     * "quiet from midnight to midnight".
     *
     * @param intervalMinutes how long between nudges; the watch rejects anything above
     *   [MAX_REMINDER_INTERVAL_MINUTES].
     */
    fun reminder(
        enabled: Boolean,
        intervalMinutes: Int,
        quietStartSeconds: Int = 0,
        quietEndSeconds: Int = 0,
    ): ByteArray {
        val interval = intervalMinutes.coerceIn(0, MAX_REMINDER_INTERVAL_MINUTES)
        val quiet = enabled && quietEndSeconds != quietStartSeconds

        return ByteBuffer.allocate(11).order(ByteOrder.BIG_ENDIAN)
            .put(if (enabled) 1 else 0)
            .putShort(interval.toShort())
            .putInt(if (quiet) quietStartSeconds else 0)
            .putInt(if (quiet) quietEndSeconds else 0)
            .array()
    }

    /** The watch refuses a longer gap between nudges. */
    const val MAX_REMINDER_INTERVAL_MINUTES: Int = 180

    /** The goal fields are unsigned 16-bit; a larger target would wrap into a tiny one. */
    private fun Int.toUShortClamped(): Short = coerceIn(0, 0xFFFF).toShort()
}
