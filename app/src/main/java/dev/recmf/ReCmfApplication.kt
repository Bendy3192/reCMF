/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf

import android.app.Application
import dev.recmf.data.SettingsStore
import dev.recmf.service.WatchService
import dev.recmf.service.WatchdogWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ReCmfApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // A cold start after a kill lands here before any activity does, so this is the
        // earliest point at which the connection can be brought back.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            if (SettingsStore(this@ReCmfApplication).current().isPaired) {
                WatchService.start(this@ReCmfApplication)
                WatchdogWorker.schedule(this@ReCmfApplication)
            }
        }
    }
}
