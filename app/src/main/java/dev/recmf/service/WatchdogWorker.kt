/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.recmf.ble.ConnectionState
import dev.recmf.data.SettingsStore
import java.util.concurrent.TimeUnit

/**
 * Brings the service back after the system kills it.
 *
 * `START_STICKY` covers most kills, but not all of them: a force-stop, a crash loop, or
 * an OOM kill under memory pressure can leave the service down indefinitely. WorkManager
 * survives all of those because it is backed by the platform's job scheduler, so a
 * periodic check is the one mechanism that reliably notices the service is gone.
 *
 * It deliberately does nothing when nothing is paired — a watchdog that restarts a
 * service the user turned off is a battery bug, not a feature.
 *
 * It doubles as the dependable refresh. Everything else that refreshes on a timer does so
 * on a coroutine delay, which does not advance while the phone is in deep sleep; this runs
 * on the job scheduler, which does.
 */
class WatchdogWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = SettingsStore(applicationContext).current()

        if (!settings.isPaired) {
            Log.i(TAG, "Nothing paired; cancelling the watchdog")
            cancel(applicationContext)
            return Result.success()
        }

        when (WatchStatus.state.value) {
            ConnectionState.IDLE -> {
                Log.i(TAG, "Service is not running; starting it")
                WatchService.start(applicationContext)
            }

            // Also the refresh that survives Doze. The service's own interval loop is a
            // coroutine delay, and those are measured against uptime, which stops while
            // the phone sleeps — so this is the only tick with a schedule anyone can name.
            ConnectionState.READY -> WatchService.syncNow(applicationContext)

            // Mid-handshake or backing off: the service is alive and already working on
            // it, and a restart would only throw away its progress.
            else -> Unit
        }

        return Result.success()
    }

    companion object {
        private const val TAG = "WatchdogWorker"
        private const val WORK_NAME = "recmf.watchdog"

        /**
         * The platform's floor for periodic work is 15 minutes; asking for less just
         * gets rounded up. 30 keeps the wake-ups cheap.
         */
        private const val INTERVAL_MINUTES = 30L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WatchdogWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                // KEEP, so re-opening the app does not reset the schedule and push the
                // next check-in another half hour out.
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
