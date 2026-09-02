/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.ai

import android.util.Log
import dev.recmf.data.AiSettings
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Asking the assistant, over the same plain `HttpURLConnection` the weather uses.
 *
 * No HTTP library, for the same reason there is not one anywhere else here: one POST with
 * two headers does not need one, and a dependency is a thing to keep current forever.
 */
class AiClient {

    /** What came back, in the shapes the screen has words for. */
    sealed interface Answer {
        data class Said(val text: String) : Answer

        /** The provider answered and said no. [reason] is its own wording where it gave one. */
        data class Refused(val code: Int, val reason: String?) : Answer

        /** Never got there: no network, a captive portal, a host that does not resolve. */
        data class Unreachable(val why: String) : Answer

        /** It answered, and there was nothing in the answer. */
        data object Unreadable : Answer

        /** Nothing was asked, because the settings are not filled in. */
        data object NotConfigured : Answer
    }

    suspend fun ask(settings: AiSettings, system: String, user: String): Answer {
        val url = AiEndpoint.of(settings.baseUrl) ?: return Answer.NotConfigured
        val key = settings.key?.takeIf { it.isNotBlank() } ?: return Answer.NotConfigured
        if (settings.model.isBlank()) return Answer.NotConfigured

        return post(url, key, AiChat.body(settings.model, system, user))
    }

    private suspend fun post(url: String, key: String, body: String): Answer =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MILLIS
                    // Generously longer than the connect timeout: a model composing a few
                    // sentences is thinking, not hanging, and cutting it off at ten seconds
                    // would fail most requests that were going to succeed.
                    readTimeout = READ_TIMEOUT_MILLIS
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer $key")
                }

                connection.outputStream.use { it.write(body.toByteArray()) }

                val code = connection.responseCode
                if (code !in 200..299) {
                    // The error stream, not the input stream, and never logged: a provider's
                    // refusal can quote back what was sent, and what was sent is somebody's
                    // health data.
                    val text = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    Log.w(TAG, "The assistant refused with $code")
                    return@withContext Answer.Refused(code, text?.let(AiChat::error))
                }

                val text = connection.inputStream.bufferedReader().use { it.readText() }
                AiChat.reply(text)?.let { Answer.Said(it) } ?: Answer.Unreadable
            } catch (e: IOException) {
                // The message and not the stack: this one is shown to somebody, and the
                // useful part of it is "unable to resolve host" rather than a trace.
                Log.i(TAG, "The assistant could not be reached: ${e.message}")
                Answer.Unreachable(e.message ?: e.javaClass.simpleName)
            } finally {
                connection?.disconnect()
            }
        }

    private companion object {
        const val TAG = "AiClient"
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val READ_TIMEOUT_MILLIS = 60_000
    }
}
