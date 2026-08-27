/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 *
 * Payload layout ported from Gadgetbridge (AGPL-3.0-or-later); see NOTICE.
 */
package dev.recmf.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** What the phone is doing, as the watch's first payload byte reports it. */
enum class MusicPlaybackState(val code: Byte) {
    /** Nothing is playing and nothing is loaded — the watch shows no track. */
    NOTHING(0),
    PAUSED(1),
    PLAYING(2),
}

/** A press on the watch's music screen. */
enum class MusicButton {
    PLAY,
    PAUSE,
    NEXT,
    PREVIOUS,
    VOLUME_UP,
    VOLUME_DOWN,
}

object CmfMusic {

    /** The width of the track and artist fields. */
    const val TEXT_BYTES: Int = 64

    const val PAYLOAD_SIZE: Int = 1 + 1 + 1 + TEXT_BYTES + TEXT_BYTES
    const val BUTTON_PAYLOAD_SIZE: Int = 2

    /**
     * `MUSIC_INFO_SET`: what is playing, how loud, and by whom.
     *
     * ```
     * state:u8 | volume:u8 | maxVolume:u8 | track:64 | artist:64
     * ```
     *
     * Both strings are cut to 63 bytes rather than 64, which is Gadgetbridge's choice and
     * worth keeping: it leaves a zero byte after the longest possible text, so a watch
     * reading the field as a C string cannot run past it into the next one.
     */
    fun payload(
        state: MusicPlaybackState,
        volume: Int,
        maxVolume: Int,
        track: String,
        artist: String,
    ): ByteArray {
        val buf = ByteBuffer.allocate(PAYLOAD_SIZE).order(ByteOrder.BIG_ENDIAN)

        buf.put(state.code)
        buf.put(volume.coerceIn(0, 255).toByte())
        buf.put(maxVolume.coerceIn(0, 255).toByte())
        buf.putPadded(track)
        buf.putPadded(artist)

        return buf.array()
    }

    /**
     * `MUSIC_BUTTON`: two little-endian bytes, an action and a direction.
     *
     * The pair is read as one number by Gadgetbridge, which hides the structure; taken as
     * two bytes it is plainly `what` and `which way`, and an unknown combination of known
     * halves is then recognisably unknown rather than silently mapped to something.
     */
    fun parseButton(payload: ByteArray): MusicButton? {
        if (payload.size < BUTTON_PAYLOAD_SIZE) return null

        val action = payload[0].toInt() and 0xff
        val forward = when (payload[1].toInt() and 0xff) {
            0 -> false
            1 -> true
            else -> return null
        }

        return when (action) {
            ACTION_PLAYBACK -> if (forward) MusicButton.PLAY else MusicButton.PAUSE
            ACTION_TRACK -> if (forward) MusicButton.NEXT else MusicButton.PREVIOUS
            ACTION_VOLUME -> if (forward) MusicButton.VOLUME_UP else MusicButton.VOLUME_DOWN
            else -> null
        }
    }

    private fun ByteBuffer.putPadded(text: String) {
        val bytes = text.truncateToUtf8Bytes(TEXT_BYTES - 1)
        put(bytes)
        put(ByteArray(TEXT_BYTES - bytes.size))
    }

    private const val ACTION_PLAYBACK = 0x01
    private const val ACTION_TRACK = 0x02
    private const val ACTION_VOLUME = 0x03
}
