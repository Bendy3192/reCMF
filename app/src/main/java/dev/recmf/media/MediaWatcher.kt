/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.media

import android.content.ComponentName
import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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

        // A session playing to a cast target carries its own volume, and this is how a
        // change to it arrives.
        override fun onAudioInfoChanged(info: MediaController.PlaybackInfo) = notifyChanged()

        // The session going away leaves the watch showing a track that is no longer
        // playing, so it counts as a change like any other.
        override fun onSessionDestroyed() = notifyChanged()
    }

    /**
     * How loud the phone's own music stream is, or null when there is no audio service.
     *
     * Read often enough — on every settings change — that it is worth keeping short.
     */
    private fun streamVolume(): Int? = audio?.getStreamVolume(AudioManager.STREAM_MUSIC)

    private var lastSeenStreamVolume: Int? = null

    /**
     * Notices the phone's volume moving.
     *
     * Android has no callback for the local stream below API 34, and the one it gained
     * there is for system apps. What it does have is the audio service writing every level
     * it settles on into [Settings.System], under a key that depends on where the sound is
     * routed — `volume_music_speaker`, `volume_music_bt_a2dp`, and so on. Watching one key
     * would work until the wearer plugged in headphones, so this watches the table and
     * asks what changed; unrelated settings churn is filtered by the comparison, not by
     * the URI.
     */
    private val volumeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            val now = streamVolume() ?: return
            if (now == lastSeenStreamVolume) return
            lastSeenStreamVolume = now
            notifyChanged()
        }
    }

    /**
     * Reports every change in what is playing, until [stop].
     *
     * Polling would not do: a track lasts minutes and the refresh timer is five, so half
     * of what the watch showed would be the song before. The callbacks fire on the change
     * itself.
     */
    fun start(onChange: () -> Unit) {
        this.onChange = onChange
        lastSeenStreamVolume = streamVolume()

        val listener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            rebind(controllers?.firstOrNull())
            notifyChanged()
        }

        context.contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI,
            // Descendants too: the level lives under a per-route key, not at the root.
            true,
            volumeObserver,
        )

        try {
            sessions?.addOnActiveSessionsChangedListener(listener, listenerComponent)
            activeSessionsListener = listener
            rebind(controller())
        } catch (e: SecurityException) {
            Log.i(TAG, "No access to media sessions: ${e.message}")
        }
    }

    fun stop() {
        context.contentResolver.unregisterContentObserver(volumeObserver)
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
        val volume = volumeOf(controller)

        return NowPlaying(
            state = controller?.playbackState.toMusicState(metadata != null),
            track = metadata?.text(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist = metadata?.text(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata?.text(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty(),
            volume = volume.first,
            maxVolume = volume.second,
        )
    }

    /** @return true when the press reached something. */
    fun press(button: MusicButton): Boolean = when (button) {
        MusicButton.PLAY -> transport { it.play() }
        MusicButton.PAUSE -> transport { it.pause() }
        MusicButton.NEXT -> transport { it.skipToNext() }
        MusicButton.PREVIOUS -> transport { it.skipToPrevious() }
        MusicButton.VOLUME_UP -> adjustVolume(up = true)
        MusicButton.VOLUME_DOWN -> adjustVolume(up = false)
    }

    private fun transport(press: (MediaController.TransportControls) -> Unit): Boolean {
        val controls = controller()?.transportControls ?: return false
        press(controls)
        return true
    }

    /**
     * The level the wearer is actually hearing, as `current to max`.
     *
     * A session casting to a speaker keeps its own volume and ignores the phone's music
     * stream, so showing the phone's level there would be showing a number the watch's
     * buttons cannot move.
     */
    private fun volumeOf(controller: MediaController?): Pair<Int, Int> {
        val remote = controller?.playbackInfo?.takeIf { it.isRemote() }
        if (remote != null) return remote.currentVolume to remote.maxVolume

        return (streamVolume() ?: 0) to (audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0)
    }

    /** @return true when something moved; false when there was nothing to move. */
    private fun adjustVolume(up: Boolean): Boolean {
        val direction = if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        val controller = controller()
        val remote = controller?.playbackInfo?.takeIf { it.isRemote() }

        if (controller != null && remote != null) {
            // A cast target that fixes its own volume — a TV on its remote, say. Telling
            // it to change would be ignored; saying so puts a line in the protocol log
            // instead of leaving the wearer pressing a dead button in silence.
            if (remote.volumeControl == VolumeProvider.VOLUME_CONTROL_FIXED) return false

            controller.adjustVolume(direction, 0)
            return true
        }

        val audio = audio ?: return false

        return try {
            // No flags: the watch is already showing the level, and a volume overlay on a
            // phone in a pocket is for nobody.
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
            true
        } catch (e: SecurityException) {
            // Do Not Disturb refuses volume changes to apps without notification-policy
            // access, which reCMF has no reason to hold. Refusing quietly beats crashing
            // the service over a button press.
            Log.i(TAG, "Volume refused: ${e.message}")
            false
        }
    }

    private fun MediaController.PlaybackInfo.isRemote(): Boolean =
        playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE

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
