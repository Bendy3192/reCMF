/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.media

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log
import androidx.core.content.getSystemService
import dev.recmf.notifications.NotificationRelay
import dev.recmf.protocol.MusicButton
import dev.recmf.protocol.MusicPlaybackState

/** What is playing right now, in the terms the watch is told about. */
data class NowPlaying(
    val state: MusicPlaybackState,
    val track: String,
    val artist: String,
    val volume: Int,
    val maxVolume: Int,
)

/**
 * Reads what the phone is playing, and presses its buttons on the watch's behalf.
 *
 * Reaching media sessions needs no permission of its own — it is gated on notification
 * listener access, which reCMF already holds to forward notifications. That is why this
 * asks [MediaSessionManager] using the notification listener's own component name: the
 * permission is checked against that, not against the app in general.
 *
 * Everything here tolerates having no session at all. A phone with nothing playing is the
 * normal case, not an error.
 */
class MediaWatcher(private val context: Context) {

    private val sessions: MediaSessionManager? = context.getSystemService()
    private val audio: AudioManager? = context.getSystemService()

    private val listenerComponent = ComponentName(context, NotificationRelay::class.java)

    /**
     * The session the watch should be shown, or null when there is none.
     *
     * The first active session is the one Android itself considers foremost, which is the
     * one whose buttons a headset would reach — so it is the one a watch should reach too.
     */
    private fun controller(): MediaController? = try {
        sessions?.getActiveSessions(listenerComponent)?.firstOrNull()
    } catch (e: SecurityException) {
        // Notification access has not been granted, or was revoked. Nothing to do about
        // it here: the notification card already asks for it.
        Log.i(TAG, "No access to media sessions: ${e.message}")
        null
    }

    private var activeSessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var watched: MediaController? = null
    private var onChange: (() -> Unit)? = null

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = notifyChanged()
        override fun onPlaybackStateChanged(state: PlaybackState?) = notifyChanged()

        // The session going away leaves the watch showing a track that is no longer
        // playing, so it counts as a change like any other.
        override fun onSessionDestroyed() = notifyChanged()
    }

    /**
     * Reports every change in what is playing, until [stop].
     *
     * Polling would not do: a track lasts minutes and the refresh timer is five, so half
     * of what the watch showed would be the song before. The callbacks fire on the change
     * itself.
     *
     * Volume is the exception — Android offers no callback for it that works below API 34,
     * so a level changed on the phone reaches the watch on the next track change or
     * refresh. A level changed *from* the watch is sent back immediately by the caller,
     * which is the case that matters.
     */
    fun start(onChange: () -> Unit) {
        this.onChange = onChange

        val listener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            rebind(controllers?.firstOrNull())
            notifyChanged()
        }

        try {
            sessions?.addOnActiveSessionsChangedListener(listener, listenerComponent)
            activeSessionsListener = listener
            rebind(controller())
        } catch (e: SecurityException) {
            Log.i(TAG, "No access to media sessions: ${e.message}")
        }
    }

    fun stop() {
        activeSessionsListener?.let { sessions?.removeOnActiveSessionsChangedListener(it) }
        activeSessionsListener = null
        rebind(null)
        onChange = null
    }

    /** Follows one session at a time — the one the watch is being shown. */
    private fun rebind(controller: MediaController?) {
        if (controller?.sessionToken == watched?.sessionToken) return

        watched?.unregisterCallback(controllerCallback)
        watched = controller
        controller?.registerCallback(controllerCallback)
    }

    private fun notifyChanged() {
        onChange?.invoke()
    }

    fun nowPlaying(): NowPlaying {
        val controller = controller()
        val metadata = controller?.metadata

        return NowPlaying(
            state = controller?.playbackState.toMusicState(metadata != null),
            track = metadata?.text(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist = metadata?.text(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata?.text(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty(),
            volume = audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0,
            maxVolume = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0,
        )
    }

    /** @return true when the press reached something. */
    fun press(button: MusicButton): Boolean {
        if (button == MusicButton.VOLUME_UP || button == MusicButton.VOLUME_DOWN) {
            val direction = if (button == MusicButton.VOLUME_UP) {
                AudioManager.ADJUST_RAISE
            } else {
                AudioManager.ADJUST_LOWER
            }

            // No flags: the watch is already showing the level, and a volume overlay on a
            // phone in a pocket is for nobody.
            audio?.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0) ?: return false
            return true
        }

        val transport = controller()?.transportControls ?: return false

        when (button) {
            MusicButton.PLAY -> transport.play()
            MusicButton.PAUSE -> transport.pause()
            MusicButton.NEXT -> transport.skipToNext()
            MusicButton.PREVIOUS -> transport.skipToPrevious()
            else -> return false
        }

        return true
    }

    private fun MediaMetadata.text(key: String): String? =
        getText(key)?.toString()?.takeIf { it.isNotBlank() }

    /**
     * @param hasTrack whether anything is loaded at all. A stopped session with a track
     *   still on screen is paused as far as the watch is concerned; a session with
     *   nothing loaded is [MusicPlaybackState.NOTHING], which is what clears the display.
     */
    private fun PlaybackState?.toMusicState(hasTrack: Boolean): MusicPlaybackState = when {
        this == null || !hasTrack -> MusicPlaybackState.NOTHING
        state == PlaybackState.STATE_PLAYING -> MusicPlaybackState.PLAYING
        else -> MusicPlaybackState.PAUSED
    }

    private companion object {
        const val TAG = "MediaWatcher"
    }
}
