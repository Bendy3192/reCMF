/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log

/**
 * Where the installer reports back to.
 *
 * Its real job is [PackageInstaller.STATUS_PENDING_USER_ACTION]: a sideloaded install
 * always needs the user to agree, and the system asks by handing back an intent for the
 * app to start. Without this the session would sit committed and nothing would appear.
 */
class InstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }

                // Started from a receiver, so it needs its own task.
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                confirm?.let(context::startActivity)
            }

            PackageInstaller.STATUS_SUCCESS ->
                Log.i(TAG, "Update installed")

            else ->
                Log.w(
                    TAG,
                    "Install did not complete: " +
                        intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
                )
        }
    }

    companion object {
        private const val TAG = "InstallReceiver"
        private const val REQUEST = 0

        fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST,
            Intent(context, InstallReceiver::class.java).setPackage(context.packageName),
            // Mutable because the installer fills in the status extras before it fires.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }
}
