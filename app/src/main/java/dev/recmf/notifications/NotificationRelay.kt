/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.recmf.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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

    /**
     * Keys already sent, so a notification that updates itself buzzes the wrist once.
     *
     * Calls are why this exists: a ringing call reposts under the same key as it rings
     * and again when it is answered, and without this the watch would buzz for every
     * repost. Entries are dropped when the notification goes away, with a cap in case a
     * removal is missed — this must not grow for as long as the phone is on.
     */
    private val forwarded = LinkedHashSet<String>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var settings: SettingsStore

    /**
     * The silenced apps, kept in memory.
     *
     * [onNotificationPosted] is called on the main thread and has to answer immediately —
     * a notification is not something to hold while a database is read. So the set is
     * followed in the background and the callback consults the copy. A change made in the
     * settings screen lands here within a frame or two, which is soon enough for a switch
     * whose effect is the next notification.
     */
    @Volatile
    private var blocked: Set<String> = emptySet()

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        scope.launch { settings.notificationBlockedPackages.collect { blocked = it } }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!shouldForward(sbn)) return

        if (sbn.packageName in blocked) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

        // A notification with neither is a placeholder for something the app will update
        // in a moment; forwarding it just buzzes the wrist for nothing.
        if (title.isBlank() && body.isBlank()) return

        if (!remember(sbn.key)) return

        val isCall = sbn.notification.category == Notification.CATEGORY_CALL

        OutgoingNotifications.offer(
            WatchNotification(
                icon = if (isCall) NotificationIcon.TRUECALLER else NotificationIcon.forPackage(sbn.packageName),
                title = title.ifBlank { sbn.packageName },
                body = body,
                whenEpochSeconds = (if (sbn.postTime > 0) sbn.postTime else System.currentTimeMillis()) / 1000,
                isCall = isCall,
            ),
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        forwarded.remove(sbn.key)
    }

    /** @return false if this key has already been sent. */
    private fun remember(key: String): Boolean {
        if (!forwarded.add(key)) return false
        while (forwarded.size > MAX_REMEMBERED) {
            forwarded.remove(forwarded.first())
        }
        return true
    }

    /**
     * Filters out the notifications that would make the watch useless to wear: ongoing
     * ones (media players, downloads, our own foreground service), the silent
     * housekeeping ones, and group summaries that duplicate a message already forwarded.
     *
     * Calls are the exception, and were being caught twice over: a ringing call is an
     * ongoing notification and is not clearable, so the two filters that keep media
     * players off the wrist were also keeping every incoming call off it. Which is the
     * one notification whose whole value is arriving before the phone is picked up.
     */
    private fun shouldForward(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == packageName) return false

        val notification = sbn.notification
        if (notification.category == Notification.CATEGORY_CALL) return true

        if (!sbn.isClearable) return false
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return false
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        if (notification.flags and Notification.FLAG_LOCAL_ONLY != 0) return false

        return true
    }

    private companion object {
        /** A phone does not hold this many live notifications; the cap is for a missed removal. */
        const val MAX_REMEMBERED = 200
    }
}
