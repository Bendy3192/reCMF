/*
 * reCMF — a third-party companion app for the CMF Watch Pro 2.
 * Copyright (C) 2026 reCMF contributors
 *
 * Ported from Gadgetbridge (Copyright (C) 2024 José Rebelo), AGPL-3.0-or-later.
 * See LICENSE and NOTICE at the repository root.
 */
package dev.recmf.protocol

/**
 * Every frame carries two 16-bit opcodes. `cmd1` is a "channel"-ish selector that is
 * `0xffff` for the vendor-specific commands and a real value for the generic ones;
 * `cmd2` is the command proper. Both are matched together — neither identifies a
 * command on its own.
 */
enum class CmfCommand(val cmd1: Int, val cmd2: Int) {
    ACTIVITY_DATA(0x0056, 0x0001),
    ACTIVITY_FETCH_1(0xffff, 0x8005),
    ACTIVITY_FETCH_2(0xffff, 0x9057),
    ACTIVITY_FETCH_ACK_1(0xffff, 0x0005),
    ACTIVITY_FETCH_ACK_2(0xffff, 0xa057),
    ALARMS_GET(0x0063, 0x0002),
    ALARMS_SET(0x0063, 0x0001),
    APP_NOTIFICATION(0x0065, 0x0001),
    AUTH_NONCE_REPLY(0xffff, 0x004c),
    AUTH_NONCE_REQUEST(0xffff, 0x804b),
    AUTH_PAIR_REPLY(0xffff, 0x0048),
    AUTH_PAIR_REQUEST(0xffff, 0x8047),
    AUTH_PHONE_NAME(0xffff, 0x8049),
    AUTH_WATCH_MAC(0xffff, 0x0049),
    AUTH_FAILED(0xffff, 0xa061),
    AUTHENTICATED_CONFIRM_REPLY(0xffff, 0x0004),
    AUTHENTICATED_CONFIRM_REQUEST(0xffff, 0x804d),
    /** The answer, not the question: asking for it is [BATTERY_GET]. */
    BATTERY(0x005c, 0x0001),

    /**
     * Gadgetbridge calls this TRIGGER_SYNC, which hides what it is.
     *
     * It is the `0x0002` half of the `0x005c` pair, and this protocol answers a `0x0002`
     * under the matching `0x0001` — the same shape as `SERIAL_NUMBER_GET` and the
     * reminders. So this is how a battery level is asked for, and [BATTERY] is what comes
     * back. Sending [BATTERY] itself, as reCMF did, is sending an answer as a question:
     * the watch has nothing to do with it and says nothing.
     */
    BATTERY_GET(0x005c, 0x0002),
    CALL_REMINDER(0xffff, 0x9066),
    CONTACTS_GET(0x00d5, 0x0002),
    CONTACTS_SET(0x00d5, 0x0001),
    DATA_CHUNK_REQUEST_AGPS(0xffff, 0xa05f),
    DATA_CHUNK_REQUEST_WATCHFACE(0xffff, 0xa064),
    DATA_CHUNK_WRITE_AGPS(0xffff, 0x905f),
    DATA_CHUNK_WRITE_WATCHFACE(0xffff, 0x9064),
    DATA_TRANSFER_AGPS_FINISH_ACK_1(0xffff, 0xa060),
    DATA_TRANSFER_AGPS_FINISH_ACK_2(0xffff, 0x9060),
    DATA_TRANSFER_AGPS_INIT_REPLY(0xffff, 0xa05e),
    DATA_TRANSFER_AGPS_INIT_REQUEST(0xffff, 0x905e),
    DATA_TRANSFER_WATCHFACE_FINISH_ACK_1(0xffff, 0xa065),
    DATA_TRANSFER_WATCHFACE_FINISH_ACK_2(0xffff, 0x9065),
    DATA_TRANSFER_WATCHFACE_INIT_1_REQUEST(0xffff, 0x8052),
    DATA_TRANSFER_WATCHFACE_INIT_1_REPLY(0xffff, 0x0052),
    DATA_TRANSFER_WATCHFACE_INIT_2_REPLY(0xffff, 0xa063),
    DATA_TRANSFER_WATCHFACE_INIT_2_REQUEST(0xffff, 0x9063),
    DO_NOT_DISTURB(0x0099, 0x0001),
    DO_NOT_DISTURB_GET(0x0099, 0x0002),
    FACTORY_RESET(0x009a, 0x0001),
    FIND_PHONE(0x005b, 0x0001),
    FIND_WATCH(0x005d, 0x0001),
    FIRMWARE_VERSION_GET(0xffff, 0x8006),
    FIRMWARE_VERSION_RET(0xffff, 0x0006),
    GOALS_SET(0x005e, 0x0001),
    GOALS_GET(0x005e, 0x0002),
    GPS_COORDS(0xffff, 0x906a),
    HEART_MONITORING_ALERTS(0xffff, 0x9059),
    HEART_MONITORING_ENABLED_GET(0x009b, 0x0002),
    HEART_MONITORING_ENABLED_SET(0x009b, 0x0001),
    HEART_RATE_RESTING(0x00da, 0x0001),
    HEART_RATE_MANUAL_AUTO(0x0053, 0x0001),
    HEART_RATE_WORKOUT(0x00e0, 0x0001),
    LANGUAGE_RET(0xffff, 0xa06b),
    LANGUAGE_SET(0xffff, 0x9058),
    MUSIC_BUTTON(0xffff, 0xa05d),
    MUSIC_INFO_ACK(0xffff, 0xa05c),
    MUSIC_INFO_SET(0xffff, 0x905c),
    SERIAL_NUMBER_GET(0x00de, 0x0002),
    SERIAL_NUMBER_RET(0x00de, 0x0001),
    SLEEP_DATA(0x0058, 0x0001),
    SPO2(0x0055, 0x0001),
    SPORTS_SET(0x00dc, 0x0001),
    SPORTS_GET(0x00dc, 0x0002),
    STANDING_REMINDER_GET(0x0060, 0x0002),
    STANDING_REMINDER_SET(0x0060, 0x0001),
    STRESS(0x009d, 0x0001),
    TIME_FORMAT(0x005f, 0x0001),
    TIME_FORMAT_GET(0x005f, 0x0002),
    TIME(0xffff, 0x8004),
    UNIT_LENGTH(0xffff, 0x9067),
    UNIT_TEMPERATURE(0xffff, 0x9068),
    WAKE_ON_WRIST_RAISE(0x0062, 0x0001),
    WAKE_ON_WRIST_RAISE_GET(0x0062, 0x0002),
    WATCHFACE(0x009f, 0x0001),
    WATER_REMINDER_GET(0x0061, 0x0002),
    WATER_REMINDER_SET(0x0061, 0x0001),
    WEATHER_SET_1(0xffff, 0x906b),
    WEATHER_SET_2(0x0066, 0x0001),
    WORKOUT_GPS(0xffff, 0xa05a),
    WORKOUT_SUMMARY(0x0057, 0x0001),
    ;

    /**
     * These four run before a session key exists (pairing) or carry their own
     * chunk framing (bulk uploads), so they go out in the clear.
     */
    val isEncrypted: Boolean
        get() = when (this) {
            AUTH_PAIR_REQUEST,
            AUTH_PAIR_REPLY,
            DATA_CHUNK_WRITE_AGPS,
            DATA_CHUNK_WRITE_WATCHFACE,
            -> false

            else -> true
        }

    companion object {
        private val byCodes: Map<Int, CmfCommand> =
            entries.associateBy { (it.cmd1 shl 16) or it.cmd2 }

        init {
            check(byCodes.size == entries.size) { "Two CmfCommand entries share the same opcode pair" }
        }

        fun fromCodes(cmd1: Int, cmd2: Int): CmfCommand? = byCodes[(cmd1 shl 16) or cmd2]

        /**
         * The command a frame acknowledges, if it is an acknowledgement.
         *
         * The watch confirms every setting it applies, and does so by a rule rather than
         * with a distinct opcode per command: a generic command answered as
         * `<cmd1>/0x0003`, and a vendor command `0xffff/0x9xxx` answered as
         * `0xffff/0xaxxx`. Recognising the rule turns a wall of unknown frames into
         * "the watch accepted this", which is the difference between a setting that was
         * sent and one that took effect.
         */
        fun acknowledgedBy(cmd1: Int, cmd2: Int): CmfCommand? = when {
            cmd2 == ACK_GENERIC -> fromCodes(cmd1, 0x0001)
            cmd1 == VENDOR && cmd2 in ACK_VENDOR_RANGE -> fromCodes(VENDOR, cmd2 - ACK_VENDOR_OFFSET)
            else -> null
        }

        /** `cmd1` of the vendor-specific commands, which have no channel of their own. */
        const val VENDOR = 0xffff

        private const val ACK_GENERIC = 0x0003
        private const val ACK_VENDOR_OFFSET = 0x1000
        private val ACK_VENDOR_RANGE = 0xa000..0xafff
    }
}
