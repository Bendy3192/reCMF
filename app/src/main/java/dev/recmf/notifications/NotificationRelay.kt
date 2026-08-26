/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Notifications waiting to be sent to the watch.
 *
 * The listener service and the connection service are separate processes-worth of
 * lifecycle that never overlap reliably, so they meet here rather than binding. Bounded
 * and drop-oldest: if the watch is away, a day of notifications must not accumulate — and
 * a notification the user has already dealt with is not worth buzzing their wrist for
 * later anyway.
 */
object OutgoingNotifications {
    private val _pending = MutableSharedFlow<WatchNotification>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val pending: SharedFlow<WatchNotification> = _pending.asSharedFlow()

    fun offer(notification: WatchNotification) {
        _pending.tryEmit(notification)
    }
}

/**
 * Forwards phone notifications to the watch.
 *
 * Android only delivers to this service once the user grants notification access in
 * system settings, which cannot be requested with a permission dialog — the app has to
 * send them there.
 */
class NotificationRelay : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!shouldForward(sbn)) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

        // A notification with neither is a placeholder for something the app will update
        // in a moment; forwarding it just buzzes the wrist for nothing.
        if (title.isBlank() && body.isBlank()) return

        OutgoingNotifications.offer(
            WatchNotification(
                icon = NotificationIcon.forPackage(sbn.packageName),
                title = title.ifBlank { sbn.packageName },
                body = body,
                whenEpochSeconds = (if (sbn.postTime > 0) sbn.postTime else System.currentTimeMillis()) / 1000,
            ),
        )
    }

    /**
     * Filters out the notifications that would make the watch useless to wear: ongoing
     * ones (media players, downloads, our own foreground service), the silent
     * housekeeping ones, and group summaries that duplicate a message already forwarded.
     */
    private fun shouldForward(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == packageName) return false
        if (!sbn.isClearable) return false

        val notification = sbn.notification
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return false
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        if (notification.flags and Notification.FLAG_LOCAL_ONLY != 0) return false

        return true
    }
}
