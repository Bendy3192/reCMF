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
        val location = when (val result = latestRelease()) {
            is Fetched.Location -> result.url
            is Fetched.Problem -> return@withContext UpdateState.Failed(result.description)
        }

        val tag = UpdateCheck.tagOf(location)
            ?: return@withContext UpdateState.Failed("no release published yet")

        val update = UpdateCheck.update(tag, currentVersionCode)

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
            // No setSize: the size is not known until the download answers, and the
            // hint is only a hint — the session sizes itself from what is written.
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            )

            sessionId = installer.createSession(params)

            installer.openSession(sessionId).use { session ->
                val connection = (URL(update.apkUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = TIMEOUT_MILLIS
                    readTimeout = TIMEOUT_MILLIS
                    setRequestProperty("User-Agent", USER_AGENT)
                }

                try {
                    if (connection.responseCode !in 200..299) {
                        return@withContext UpdateState.Failed("download failed (${connection.responseCode})")
                    }

                    // Null when the server does not say, which leaves the progress
                    // indeterminate rather than wrong.
                    val total = connection.contentLengthLong.takeIf { it > 0 }

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

    /** Either where the latest release lives, or something specific enough to act on. */
    private sealed interface Fetched {
        data class Location(val url: String) : Fetched
        data class Problem(val description: String) : Fetched
    }

    /**
     * Asks where `/releases/latest` points, without going there.
     *
     * The redirect is the answer — see [UpdateCheck] for why this is the release page and
     * not the API — so following it would fetch a page of HTML and throw away the one
     * header worth having.
     *
     * Every failure says what it was. The first version reported "could not reach GitHub"
     * for a refusal, a rate limit, a missing release and a dead network alike — the same
     * unfalsifiable message that has cost a round of guessing every time it has appeared
     * in this app.
     */
    private fun latestRelease(): Fetched {
        val url = UpdateCheck.LATEST_RELEASE_URL
        var connection: HttpURLConnection? = null

        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = TIMEOUT_MILLIS
                readTimeout = TIMEOUT_MILLIS
                // What HttpURLConnection sends by default is not always accepted.
                setRequestProperty("User-Agent", USER_AGENT)
            }

            when (val code = connection.responseCode) {
                in 300..399 -> {
                    val location = connection.getHeaderField("Location")

                    if (location.isNullOrBlank()) {
                        Fetched.Problem("GitHub redirected without saying where")
                    } else {
                        // Resolved against the request, so a relative Location — which
                        // the standard allows and a proxy may produce — still names a
                        // whole URL by the time it is read.
                        Fetched.Location(URL(URL(url), location).toString())
                    }
                }

                404 -> Fetched.Problem("no releases visible (404) — is the repository private?")
                403, 429 -> Fetched.Problem("GitHub refused the request ($code)")
                else -> {
                    Log.i(TAG, "Release check answered $code")
                    Fetched.Problem("GitHub answered $code")
                }
            }
        } catch (e: IOException) {
            Log.i(TAG, "Release check could not be made", e)
            Fetched.Problem(e.message ?: "no network")
        } finally {
            connection?.disconnect()
        }
    }

    private companion object {
        const val TAG = "Updater"
        const val TIMEOUT_MILLIS = 30_000
        const val USER_AGENT = "reCMF"
        const val BUFFER = 64 * 1024
        const val APK_NAME = "recmf"
    }
}
