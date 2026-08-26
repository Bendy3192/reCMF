/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.weather

import dev.recmf.protocol.CmfWeather
import dev.recmf.protocol.CmfWeatherCondition
import dev.recmf.protocol.SunTimes
import dev.recmf.protocol.WeatherDay
import dev.recmf.protocol.WeatherHour
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** A place the forecast is for. Resolved once from a typed name and then stored. */
data class WeatherLocation(
    val name: String,
    val latitude: Double,
    val longitude: Double,
)

/** Everything the watch's weather screen needs, in the protocol's own shapes. */
data class WeatherSnapshot(
    val today: WeatherDay,
    val forecast: List<WeatherDay>,
    val hourly: List<WeatherHour>,
    val sun: List<SunTimes>,
)

/**
 * Open-Meteo, which needs no account and no key.
 *
 * The URL building and the parsing are separate from the fetching so that the part most
 * likely to be wrong — reading someone else's JSON — can be tested without a network.
 *
 * Parsing is deliberately forgiving: a missing field yields a sensible zero rather than an
 * exception, because a forecast that is partly there is still worth showing on a watch,
 * and because a provider changing one field should not take the feature down.
 */
object OpenMeteo {
    fun geocodingUrl(city: String, language: String): String =
        "https://geocoding-api.open-meteo.com/v1/search" +
            "?name=${city.urlEncoded()}&count=1&language=${language.urlEncoded()}&format=json"

    fun forecastUrl(location: WeatherLocation): String =
        "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${location.latitude}&longitude=${location.longitude}" +
            "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m,uv_index" +
            "&hourly=temperature_2m,weather_code" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,uv_index_max" +
            "&timezone=auto&forecast_days=${CmfWeather.FORECAST_DAYS}&timeformat=unixtime"

    /** @return null when the place was not found, which is a normal answer rather than an error. */
    fun parseLocation(json: String): WeatherLocation? {
        val results = JSONObject(json).optJSONArray("results") ?: return null
        val first = results.optJSONObject(0) ?: return null

        return WeatherLocation(
            name = first.optString("name").ifBlank { return null },
            latitude = first.optDouble("latitude").takeIf { !it.isNaN() } ?: return null,
            longitude = first.optDouble("longitude").takeIf { !it.isNaN() } ?: return null,
        )
    }

    /**
     * @param nowEpochSeconds used to pick the twenty-four hours that start now; the
     *   response covers whole days, and the watch wants what is coming, not what is past.
     */
    fun parseForecast(json: String, nowEpochSeconds: Long): WeatherSnapshot? {
        val root = JSONObject(json)
        val current = root.optJSONObject("current") ?: return null
        val daily = root.optJSONObject("daily") ?: return null

        val dailyMax = daily.optJSONArray("temperature_2m_max")
        val dailyMin = daily.optJSONArray("temperature_2m_min")
        val dailyCode = daily.optJSONArray("weather_code")
        val dailyUv = daily.optJSONArray("uv_index_max")

        val today = WeatherDay(
            condition = CmfWeatherCondition.fromWmo(current.optInt("weather_code", -1)),
            temperatureC = current.optDouble("temperature_2m", 0.0).roundToIntOrZero(),
            maxTemperatureC = dailyMax.doubleAt(0).roundToIntOrZero(),
            minTemperatureC = dailyMin.doubleAt(0).roundToIntOrZero(),
            humidityPercent = current.optInt("relative_humidity_2m", 0),
            uvIndex = current.optDouble("uv_index", 0.0).roundToIntOrZero(),
            windSpeed = current.optDouble("wind_speed_10m", 0.0).roundToIntOrZero(),
        )

        val forecast = (1 until CmfWeather.FORECAST_DAYS).mapNotNull { day ->
            if (dailyCode == null || day >= dailyCode.length()) {
                null
            } else {
                WeatherDay(
                    condition = CmfWeatherCondition.fromWmo(dailyCode.optInt(day, -1)),
                    temperatureC = dailyMax.doubleAt(day).roundToIntOrZero(),
                    maxTemperatureC = dailyMax.doubleAt(day).roundToIntOrZero(),
                    minTemperatureC = dailyMin.doubleAt(day).roundToIntOrZero(),
                    uvIndex = dailyUv.doubleAt(day).roundToIntOrZero(),
                )
            }
        }

        return WeatherSnapshot(
            today = today,
            forecast = forecast,
            hourly = parseHourly(root.optJSONObject("hourly"), nowEpochSeconds),
            sun = parseSun(daily),
        )
    }

    private fun parseHourly(hourly: JSONObject?, nowEpochSeconds: Long): List<WeatherHour> {
        val times = hourly?.optJSONArray("time") ?: return emptyList()
        val temps = hourly.optJSONArray("temperature_2m")
        val codes = hourly.optJSONArray("weather_code")

        // The hour that is running counts as upcoming, so round down to its start.
        val from = nowEpochSeconds - nowEpochSeconds % 3600

        return (0 until times.length())
            .filter { times.optLong(it, 0L) >= from }
            .take(CmfWeather.HOURLY_ENTRIES)
            .map { index ->
                WeatherHour(
                    temperatureC = temps.doubleAt(index).roundToIntOrZero(),
                    condition = CmfWeatherCondition.fromWmo(codes?.optInt(index, -1) ?: -1),
                )
            }
    }

    private fun parseSun(daily: JSONObject): List<SunTimes> {
        val sunrise = daily.optJSONArray("sunrise") ?: return emptyList()
        val sunset = daily.optJSONArray("sunset") ?: return emptyList()

        return (0 until minOf(sunrise.length(), sunset.length(), CmfWeather.FORECAST_DAYS)).map {
            SunTimes(sunrise.optLong(it, 0L), sunset.optLong(it, 0L))
        }
    }

    private fun JSONArray?.doubleAt(index: Int): Double =
        this?.optDouble(index, 0.0)?.takeIf { !it.isNaN() } ?: 0.0

    private fun Double.roundToIntOrZero(): Int = if (isNaN()) 0 else roundToInt()

    private fun String.urlEncoded(): String =
        java.net.URLEncoder.encode(this, Charsets.UTF_8.name())
}
