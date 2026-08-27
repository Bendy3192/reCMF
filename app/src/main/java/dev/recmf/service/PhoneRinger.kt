/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import dev.recmf.R
import dev.recmf.ui.MainActivity

/**
 * Makes the phone findable from the wrist.
 *
 * The point of the feature is a phone the wearer *cannot hear*, so this deliberately does
 * not go through the notification sound: it plays on the alarm stream, which the silent
 * ringer does not touch. A find-phone that stays quiet because the phone was on silent
 * would be a find-phone for the one case that never happens.
 *
 * What it will not do is turn the volume up. Alarm volume is the wearer's own setting, and
 * an app that overrides it is one bad trigger away from a fright in a quiet room — the
 * vibration is there for exactly that case. Do Not Disturb, likewise, is left to say no:
 * with alarms allowed it rings, and with everything blocked it does not.
 *
 * It stops on its own after [RING_MILLIS]. A phone that keeps ringing in someone else's
 * bag until the battery dies is not helping anyone find it.
 */
class PhoneRinger(private val context: Context) {

    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val stopSoon = Runnable { stop() }

    private val notifications: NotificationManager? = context.getSystemService()

    private val vibrator: Vibrator? =
        context.getSystemService<VibratorManager>()?.defaultVibrator

    val isRinging: Boolean
        get() = player != null

    /** Starts, or restarts the clock on a ring already in progress. */
    fun start() {
        createChannel()

        handler.removeCallbacks(stopSoon)
        handler.postDelayed(stopSoon, RING_MILLIS)

        notifications?.notify(NOTIFICATION_ID, buildNotification())
        vibrate()

        if (player != null) return

        player = try {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(context, ringtone())
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            // A missing or unreadable ringtone must not take the service down with it —
            // the vibration and the notification are still worth having. MediaPlayer
            // throws IOException, IllegalStateException, IllegalArgumentException and
            // SecurityException here depending on which step fails, so this catches the
            // failure rather than enumerating the ways to fail.
            Log.w(TAG, "Could not play the find-phone tone", e)
            null
        }
    }

    fun stop() {
        handler.removeCallbacks(stopSoon)

        player?.let { playing ->
            runCatching {
                playing.stop()
                playing.release()
            }
        }
        player = null

        vibrator?.cancel()
        notifications?.cancel(NOTIFICATION_ID)
    }

    /**
     * The alarm tone, falling back through the ringtone to the notification sound.
     *
     * A phone with no alarm tone set is unusual but not impossible, and the fallbacks cost
     * two lines.
     */
    private fun ringtone(): Uri =
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

    private fun vibrate() {
        val vibrator = vibrator ?: return
        val effect = VibrationEffect.createWaveform(PATTERN, REPEAT_FROM)

        // Declared as an alarm so Do Not Disturb weighs it as one, on the versions that
        // can be told. Below that it is an ordinary vibration, which is still a buzzing
        // phone.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(
                effect,
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
            )
        } else {
            vibrator.vibrate(effect)
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_find_phone),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notification_channel_find_phone_description)
            // Both off: the tone and the buzzing are ours, and a channel that added its
            // own would talk over them and outlive the stop.
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }

        notifications?.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val silence = PendingIntent.getService(
            context,
            REQUEST_SILENCE,
            WatchService.stopRingingIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        // Tapping the body has to open an activity: a notification that starts a service
        // or a receiver instead is a trampoline, which Android 12 forbids and lint calls
        // out. So the tap opens reCMF, and reCMF silences the ringing on the way in —
        // same outcome for whoever just found the phone, by the route the platform wants.
        val openAndSilence = PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            MainActivity.silenceIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_watch)
            .setContentTitle(context.getString(R.string.find_phone_title))
            .setContentText(context.getString(R.string.find_phone_text))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openAndSilence)
            .addAction(0, context.getString(R.string.find_phone_silence), silence)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
    }

    private companion object {
        const val TAG = "PhoneRinger"
        const val CHANNEL_ID = "recmf.findphone"
        const val NOTIFICATION_ID = 2
        const val REQUEST_SILENCE = 1
        const val REQUEST_OPEN = 2

        /** Long enough to search a room, short enough not to be a nuisance. */
        const val RING_MILLIS = 30_000L

        /** Buzz, gap, buzz — starting with a gap of nothing, as the pattern requires. */
        val PATTERN = longArrayOf(0, 700, 500)

        /** Repeat the whole pattern, for as long as the tone plays. */
        const val REPEAT_FROM = 0
    }
}
