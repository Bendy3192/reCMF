/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.update

import android.content.Context
import android.content.pm.PackageInstaller
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** How far along an install has got, for something to show. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val update: AvailableUpdate) : UpdateState

    /** [percent] is null while the server has not said how big the file is. */
    data class Downloading(val percent: Int?) : UpdateState

    /** Handed to the system installer; the user still has to agree. */
    data object AwaitingConfirmation : UpdateState
    data class Failed(val reason: String) : UpdateState
}

/**
 * Fetches a newer build and hands it to Android's installer.
 *
 * The install cannot be silent. Android shows its own confirmation for every sideloaded
 * package, and there is no way around that short of owning the device — so this gets as
 * far as the system dialog and stops. What it removes is the trip to a browser, the
 * download, the file manager and the uninstall.
 *
 * Every APK is signed with the same key as the one already installed, which is what lets
 * it go over the top and keep the app's data. Without that this whole path would be a
 * slower way of wiping the settings.
 */
class Updater(private val context: Context) {

    suspend fun check(currentVersionCode: Int): UpdateState = withContext(Dispatchers.IO) {
        val body = get(UpdateCheck.LATEST_RELEASE_URL)
            ?: return@withContext UpdateState.Failed("could not reach GitHub")

        val update = runCatching { UpdateCheck.parse(body, currentVersionCode) }
            .onFailure { Log.w(TAG, "Could not read the release", it) }
            .getOrNull()

        if (update == null) UpdateState.UpToDate else UpdateState.Available(update)
    }

    /**
     * Streams the APK straight into an install session.
     *
     * Nothing is written to storage of our own: the bytes go from the connection into the
     * session, so there is no downloaded file to leave behind, to collide with a second
     * attempt, or to need a FileProvider to hand over.
     */
    suspend fun install(
        update: AvailableUpdate,
        onProgress: (Int?) -> Unit,
    ): UpdateState = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        var sessionId = -1

        try {
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            )
            if (update.sizeBytes > 0) params.setSize(update.sizeBytes)

            sessionId = installer.createSession(params)

            installer.openSession(sessionId).use { session ->
                val connection = (URL(update.apkUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = TIMEOUT_MILLIS
                    readTimeout = TIMEOUT_MILLIS
                }

                try {
                    if (connection.responseCode !in 200..299) {
                        return@withContext UpdateState.Failed("download failed (${connection.responseCode})")
                    }

                    // The release asset's own length, which is the one that matches what
                    // is arriving; the size from the API is a cross-check, not a source.
                    val total = connection.contentLengthLong.takeIf { it > 0 }
                        ?: update.sizeBytes.takeIf { it > 0 }

                    var written = 0L
                    connection.inputStream.use { input ->
                        session.openWrite(APK_NAME, 0, total ?: -1).use { output ->
                            val buffer = ByteArray(BUFFER)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                written += read
                                onProgress(total?.let { ((written * 100) / it).toInt() })
                            }
                            session.fsync(output)
                        }
                    }
                } finally {
                    connection.disconnect()
                }

                session.commit(InstallReceiver.pendingIntent(context).intentSender)
            }

            UpdateState.AwaitingConfirmation
        } catch (e: IOException) {
            if (sessionId != -1) runCatching { installer.abandonSession(sessionId) }
            Log.w(TAG, "Update download failed", e)
            UpdateState.Failed(e.message ?: "download failed")
        } catch (e: SecurityException) {
            // Raised when reCMF has not been allowed to install packages. That is a
            // system setting the user has to grant, not something to retry.
            if (sessionId != -1) runCatching { installer.abandonSession(sessionId) }
            Log.w(TAG, "Not allowed to install packages", e)
            UpdateState.Failed("not allowed to install apps")
        }
    }

    private fun get(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MILLIS
                readTimeout = TIMEOUT_MILLIS
                setRequestProperty("Accept", "application/vnd.github+json")
            }

            if (connection.responseCode !in 200..299) {
                Log.i(TAG, "Release check answered ${connection.responseCode}")
                null
            } else {
                connection.inputStream.bufferedReader().use { it.readText() }
            }
        } catch (e: IOException) {
            Log.i(TAG, "Release check could not be made: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    private companion object {
        const val TAG = "Updater"
        const val TIMEOUT_MILLIS = 30_000
        const val BUFFER = 64 * 1024
        const val APK_NAME = "recmf"
    }
}
