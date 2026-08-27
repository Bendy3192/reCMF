/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.recmf.BuildConfig
import dev.recmf.R
import dev.recmf.data.SettingsStore
import dev.recmf.ui.MainActivity
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Notices a new build without anyone pressing anything.
 *
 * reCMF has no store to tell the wearer a version is waiting, so the app has to ask. Once
 * a day is the right cadence for a project that ships a handful of builds a week: often
 * enough that a fix arrives the day it exists, rare enough to cost nothing.
 *
 * It announces a version once. "There is an update" stays true every day until it is
 * installed, and a phone that repeats it every day is a phone whose notifications people
 * learn to swipe away without reading — including the one that matters.
 *
 * There is no in-app switch for it. Android's own channel setting turns it off, which is
 * where people already look for "stop this app notifying me", and one more toggle in a
 * settings screen is one more thing to explain.
 */
class UpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = SettingsStore(applicationContext)

        val state = Updater(applicationContext).check(BuildConfig.VERSION_CODE)
        val update = (state as? UpdateState.Available)?.update ?: return Result.success()

        if (update.versionCode <= settings.lastAnnouncedVersion.first()) {
            Log.i(TAG, "Already announced ${update.versionCode}")
            return Result.success()
        }

        announce(update)
        settings.setLastAnnouncedVersion(update.versionCode)

        return Result.success()
    }

    private fun announce(update: AvailableUpdate) {
        val notifications = applicationContext.getSystemService<NotificationManager>()
            ?: return

        notifications.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.notification_channel_updates),
                // Low: a new build is worth seeing, not worth interrupting anything for.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description =
                    applicationContext.getString(R.string.notification_channel_updates_description)
            },
        )

        val open = PendingIntent.getActivity(
            applicationContext,
            REQUEST_OPEN,
            Intent(applicationContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val title = applicationContext.getString(R.string.update_notification_title, update.name)

        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_watch)
            .setContentTitle(title)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        // The changelog if there is one, so the wearer can see what they would be
        // updating for rather than taking it on trust.
        val notes = update.notes
        if (notes == null) {
            builder.setContentText(applicationContext.getString(R.string.update_notification_text))
        } else {
            builder
                .setContentText(notes.lineSequence().first())
                .setStyle(NotificationCompat.BigTextStyle().bigText(notes))
        }

        // Silently dropped when notifications are off, which is the wearer's answer.
        notifications.notify(NOTIFICATION_ID, builder.build())
    }

    companion object {
        private const val TAG = "UpdateWorker"
        private const val WORK_NAME = "recmf.update-check"
        private const val CHANNEL_ID = "recmf.updates"
        private const val NOTIFICATION_ID = 3
        private const val REQUEST_OPEN = 3

        private const val INTERVAL_HOURS = 24L

        /** Most of a day, so the check lands whenever the phone is most convenient. */
        private const val FLEX_HOURS = 6L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(
                INTERVAL_HOURS,
                TimeUnit.HOURS,
                FLEX_HOURS,
                TimeUnit.HOURS,
            )
                // No network, no point waking up: the check is two small requests and
                // both need one.
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                // KEEP: opening the app should not reset the schedule and push the next
                // check another day out.
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
