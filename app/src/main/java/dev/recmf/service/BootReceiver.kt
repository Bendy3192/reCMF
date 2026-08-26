/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.recmf.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Reconnects after a reboot or an app update, so the user does not have to open the app
 * to get their watch syncing again.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val appContext = context.applicationContext
        val pending = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (SettingsStore(appContext).current().isPaired) {
                    WatchService.start(appContext)
                    WatchdogWorker.schedule(appContext)
                } else {
                    WatchdogWorker.cancel(appContext)
                }
            } finally {
                // Without this the receiver is killed before the coroutine runs.
                pending.finish()
            }
        }
    }
}
