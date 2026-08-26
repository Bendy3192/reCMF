/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.weather

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches from Open-Meteo.
 *
 * This is the only thing reCMF sends anywhere other than the watch, and it sends only a
 * pair of coordinates the user typed in themselves — no identifier, no account, no key.
 */
class WeatherClient {

    suspend fun geocode(city: String, language: String): WeatherLocation? {
        val body = get(OpenMeteo.geocodingUrl(city, language)) ?: return null
        return runCatching { OpenMeteo.parseLocation(body) }
            .onFailure { Log.w(TAG, "Could not read the geocoding response", it) }
            .getOrNull()
    }

    suspend fun forecast(location: WeatherLocation, nowEpochSeconds: Long): WeatherSnapshot? {
        val body = get(OpenMeteo.forecastUrl(location)) ?: return null
        return runCatching { OpenMeteo.parseForecast(body, nowEpochSeconds) }
            .onFailure { Log.w(TAG, "Could not read the forecast response", it) }
            .getOrNull()
    }

    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MILLIS
                readTimeout = TIMEOUT_MILLIS
                requestMethod = "GET"
            }

            if (connection.responseCode !in 200..299) {
                Log.w(TAG, "Weather request failed with ${connection.responseCode}")
                return@withContext null
            }

            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            // No network, a captive portal, a provider outage — none of it is worth
            // more than a quiet skip until the next refresh.
            Log.i(TAG, "Weather request could not be made: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    private companion object {
        const val TAG = "WeatherClient"
        const val TIMEOUT_MILLIS = 15_000
    }
}
