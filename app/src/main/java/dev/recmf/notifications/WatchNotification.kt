/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 *
 * Payload layout ported from Gadgetbridge (AGPL-3.0-or-later); see NOTICE.
 */
package dev.recmf.notifications

import dev.recmf.protocol.truncateToUtf8Bytes
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The icon the watch draws beside a notification. The codes are the watch's own; anything
 * it does not know shows as [UNKNOWN].
 */
enum class NotificationIcon(val code: Byte) {
    GENERIC_SMS(0),
    WHATSAPP(8),
    SNAPCHAT(9),
    WHATSAPP_BUSINESS(10),
    TRUECALLER(11),
    TELEGRAM(12),
    FACEBOOK_MESSENGER(13),
    IMO(14),
    CALLAPP(15),
    FACEBOOK(17),
    INSTAGRAM(18),
    TIKTOK(19),
    LINE(20),
    DISCORD(21),
    GOOGLE_VOICE(22),
    GMAIL(27),
    OUTLOOK(29),
    UNKNOWN(255.toByte()),
    ;

    companion object {
        private val BY_PACKAGE = mapOf(
            "com.whatsapp" to WHATSAPP,
            "com.whatsapp.w4b" to WHATSAPP_BUSINESS,
            "com.snapchat.android" to SNAPCHAT,
            "com.truecaller" to TRUECALLER,
            "org.telegram.messenger" to TELEGRAM,
            "org.telegram.messenger.web" to TELEGRAM,
            "org.thunderdog.challegram" to TELEGRAM,
            "nekox.messenger" to TELEGRAM,
            "com.facebook.orca" to FACEBOOK_MESSENGER,
            "com.imo.android.imoim" to IMO,
            "com.callapp.contacts" to CALLAPP,
            "com.facebook.katana" to FACEBOOK,
            "com.instagram.android" to INSTAGRAM,
            "com.zhiliaoapp.musically" to TIKTOK,
            "com.ss.android.ugc.trill" to TIKTOK,
            "jp.naver.line.android" to LINE,
            "com.discord" to DISCORD,
            "com.google.android.apps.googlevoice" to GOOGLE_VOICE,
            "com.google.android.gm" to GMAIL,
            "com.microsoft.office.outlook" to OUTLOOK,
            "com.google.android.apps.messaging" to GENERIC_SMS,
            "com.android.messaging" to GENERIC_SMS,
            "com.samsung.android.messaging" to GENERIC_SMS,
        )

        fun forPackage(packageName: String): NotificationIcon = BY_PACKAGE[packageName] ?: UNKNOWN
    }
}

/**
 * A notification on its way to the watch.
 *
 * Holds no Android types, so the wire format is unit-testable — which matters more than
 * usual here, because a malformed payload shows up as garbled text on a watch face rather
 * than as an error anywhere.
 */
data class WatchNotification(
    val icon: NotificationIcon,
    val title: String,
    val body: String,
    val whenEpochSeconds: Long,
    /**
     * Whether this is a ringing phone, which changes when it may be delivered.
     *
     * Not part of the payload — the watch is told the same bytes either way. It exists
     * because "only when the screen is off" is a rule about the user looking at the
     * phone, and an incoming call turns the screen on by itself.
     */
    val isCall: Boolean = false,
) {
    /**
     * ```
     * icon:u8 | 00 | when:u32 | titleLength:u8 | title | body
     * ```
     *
     * Big-endian, and both strings are truncated to what the watch accepts.
     */
    fun toPayload(): ByteArray {
        val titleBytes = title.truncateToUtf8Bytes(MAX_TITLE_BYTES)
        val bodyBytes = body.truncateToUtf8Bytes(MAX_BODY_BYTES)

        return ByteBuffer.allocate(HEADER_SIZE + titleBytes.size + bodyBytes.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(icon.code)
            .put(0)
            .putInt(whenEpochSeconds.toInt())
            .put(titleBytes.size.toByte())
            .put(titleBytes)
            .put(bodyBytes)
            .array()
    }

    companion object {
        /** icon, one unidentified byte, timestamp, title length. */
        const val HEADER_SIZE = 7
        const val MAX_TITLE_BYTES = 20
        const val MAX_BODY_BYTES = 128

    }
}
