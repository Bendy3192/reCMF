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

/** A reminder as the watch holds it. */
data class ReminderState(
    val enabled: Boolean,
    val intervalMinutes: Int,
    val quietStartSeconds: Int,
    val quietEndSeconds: Int,
)

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

    /**
     * Reads a reminder back, in the layout it was written.
     *
     * Confirmed against a real watch: `STANDING_REMINDER_GET` and `WATER_REMINDER_GET`
     * both answered `00 003c 00000000 00000000` — off, every sixty minutes, no quiet
     * window — which is byte-for-byte what [reminder] builds. The reply arrives under the
     * matching SET opcode, so this is the same eleven bytes read the other way.
     */
    fun parseReminder(payload: ByteArray): ReminderState? {
        if (payload.size != REMINDER_PAYLOAD_SIZE) return null

        val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        return ReminderState(
            enabled = buf.get().toInt() != 0,
            intervalMinutes = buf.short.toInt() and 0xffff,
            quietStartSeconds = buf.int,
            quietEndSeconds = buf.int,
        )
    }

    const val REMINDER_PAYLOAD_SIZE: Int = 11

    /** The watch refuses a longer gap between nudges. */
    const val MAX_REMINDER_INTERVAL_MINUTES: Int = 180

    /**
     * `SPORTS_SET`: which exercises appear in the watch's own sport menu, in order.
     *
     * Sent as a count followed by the codes. An empty list would leave the watch with no
     * sports at all, so it falls back to the pair the official app also refuses to
     * remove.
     */
    fun sportTypes(types: List<CmfActivityType>): ByteArray {
        val chosen = types.distinct().takeIf { it.isNotEmpty() } ?: CmfActivityType.DEFAULT
        val capped = chosen.take(MAX_SPORT_TYPES)

        return ByteBuffer.allocate(capped.size + 1)
            .put(capped.size.toByte())
            .apply { capped.forEach { put(it.code) } }
            .array()
    }

    /** The watch's own menu holds no more than this. */
    const val MAX_SPORT_TYPES: Int = 36

    // region Read-back
    //
    // Every one of these was confirmed against a real watch: the `0x0002` half of each
    // pair was sent and the watch answered under the matching `0x0001` with the bytes
    // parsed below. They are the watch's own state, not an echo of what reCMF wrote —
    // the answers arrived before this connection had written anything.

    /** `WAKE_ON_WRIST_RAISE` read back: one byte, non-zero for on. Confirmed as `01`. */
    fun parseWakeOnWristRaise(payload: ByteArray): Boolean? =
        payload.firstOrNull()?.let { it != 0.toByte() }

    /**
     * `TIME_FORMAT` read back, with the same inversion the write uses: zero is 24-hour.
     *
     * Confirmed as `00` on a watch set to a 24-hour clock.
     */
    fun parseTimeFormat(payload: ByteArray): Boolean? =
        payload.firstOrNull()?.let { it == 0.toByte() }

    /** `DO_NOT_DISTURB` read back: one byte, non-zero for on. Confirmed as `00`. */
    fun parseDoNotDisturb(payload: ByteArray): Boolean? =
        payload.firstOrNull()?.let { it != 0.toByte() }

    /**
     * `SPORTS_SET` read back — the same count-then-codes shape [sportTypes] writes.
     *
     * Confirmed as `02 02 03`: two sports, outdoor and indoor running. A code this build
     * does not know is dropped rather than failing the whole list, since the watch's menu
     * is longer than the table here.
     */
    fun parseSportTypes(payload: ByteArray): List<CmfActivityType>? {
        val count = payload.firstOrNull()?.toInt()?.and(0xff) ?: return null
        if (payload.size < count + 1) return null

        return (1..count).mapNotNull { CmfActivityType.fromCode(payload[it]) }
    }

    /**
     * `GOALS_SET` read back: five little-endian 32-bit numbers, a byte, and seven flags.
     *
     * ```
     * steps:u32 | distance:u32 | calories:u32 | ?:u32 | activeMinutes:u32 | climbs:u8 | 7 bytes
     * ```
     *
     * Four of the six are confirmed against what the watch's own screen showed: 10000
     * steps, 400 calories, 30 active minutes, and — the byte after the numbers — 12
     * climbs. Distance is read as metres because 4000 is where it sat and nothing else
     * fits, but the wearer had never set it, so it is a default agreeing with itself
     * rather than a match.
     *
     * The fourth number is not identified. One capture reads 720, which would be twelve
     * hours in minutes and so might be a sleep goal, but a single default-looking number
     * is not evidence. It is carried through unnamed.
     *
     * The seven trailing bytes were all `01`. A flag per goal is the obvious reading and
     * there is nothing yet to test it against, so they too are carried rather than named.
     *
     * The *write* is a different shape — ten big-endian bytes — and the watch takes it
     * without complaint. This is the one command here whose two directions are not
     * symmetric, and the evidence says the write does not entirely land: reCMF had sent
     * 5000 metres and 300 calories, and the watch reported 4000 and 400.
     */
    fun parseGoals(payload: ByteArray): WatchGoals? {
        if (payload.size < GOAL_BYTES) return null

        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)

        return WatchGoals(
            steps = buffer.int,
            distanceMeters = buffer.int,
            calories = buffer.int,
            unidentified = buffer.int,
            activeMinutes = buffer.int,
            climbs = buffer.get().toInt() and 0xff,
        )
    }

    /** Five 32-bit numbers and the climb byte; the flags after them are not read. */
    private const val GOAL_BYTES = 5 * 4 + 1
    // endregion

    /** The goal fields are unsigned 16-bit; a larger target would wrap into a tiny one. */
    private fun Int.toUShortClamped(): Short = coerceIn(0, 0xFFFF).toShort()
}

/**
 * What the watch says its goals are.
 *
 * @param unidentified the fourth number, kept as read so the log can show it without this
 *   file pretending to know what it means.
 */
data class WatchGoals(
    val steps: Int,
    val distanceMeters: Int,
    val calories: Int,
    val unidentified: Int,
    val activeMinutes: Int,
    val climbs: Int,
)
