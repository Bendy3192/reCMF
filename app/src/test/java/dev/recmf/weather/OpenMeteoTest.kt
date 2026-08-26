package dev.recmf.weather

import dev.recmf.protocol.CmfWeatherCondition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OpenMeteoTest {
    /** 2026-08-26 12:00:00 UTC, and the hourly fixture starts at 10:00. */
    private val now = 1_787_745_600L
    private val hourZero = now - 2 * 3600

    private val forecastJson = """
        {
          "current": {
            "time": $now,
            "temperature_2m": 21.4,
            "relative_humidity_2m": 61,
            "weather_code": 3,
            "wind_speed_10m": 5.6,
            "uv_index": 4.2
          },
          "hourly": {
            "time": [${(0..5).joinToString { (hourZero + it * 3600).toString() }}],
            "temperature_2m": [15.0, 16.4, 18.9, 20.1, 21.4, 22.0],
            "weather_code": [0, 1, 3, 61, 95, 75]
          },
          "daily": {
            "time": [${(0..6).joinToString { (hourZero + it * 86400).toString() }}],
            "weather_code": [3, 61, 0, 95, 75, 45, 1],
            "temperature_2m_max": [25.4, 19.8, 27.0, 22.2, 3.4, -1.6, 30.0],
            "temperature_2m_min": [14.2, 11.0, 15.5, 13.1, -4.9, -12.3, 18.0],
            "sunrise": [${(0..6).joinToString { (hourZero + it * 86400 + 3600).toString() }}],
            "sunset": [${(0..6).joinToString { (hourZero + it * 86400 + 50000).toString() }}],
            "uv_index_max": [5.0, 3.0, 7.0, 2.0, 1.0, 0.5, 8.0]
          }
        }
    """.trimIndent()

    @Test
    fun `current conditions are read and rounded`() {
        val snapshot = requireNotNull(OpenMeteo.parseForecast(forecastJson, now))

        assertEquals(CmfWeatherCondition.OVERCAST, snapshot.today.condition)
        assertEquals(21, snapshot.today.temperatureC)
        assertEquals(25, snapshot.today.maxTemperatureC)
        assertEquals(14, snapshot.today.minTemperatureC)
        assertEquals(61, snapshot.today.humidityPercent)
        assertEquals(6, snapshot.today.windSpeed)
        assertEquals(4, snapshot.today.uvIndex)
    }

    @Test
    fun `the hourly run starts at the current hour, not at midnight`() {
        // The response covers whole days; the watch should show what is coming.
        val snapshot = requireNotNull(OpenMeteo.parseForecast(forecastJson, now))

        assertEquals(21, snapshot.hourly.first().temperatureC)
        assertEquals(CmfWeatherCondition.THUNDER_SHOWERS, snapshot.hourly.first().condition)
        assertEquals(2, snapshot.hourly.size)
    }

    @Test
    fun `an hour that is already running still counts as upcoming`() {
        // Half past the hour is still that hour's weather.
        val snapshot = requireNotNull(OpenMeteo.parseForecast(forecastJson, now + 1800))

        assertEquals(21, snapshot.hourly.first().temperatureC)
    }

    @Test
    fun `six following days are read, today excluded`() {
        val snapshot = requireNotNull(OpenMeteo.parseForecast(forecastJson, now))

        assertEquals(6, snapshot.forecast.size)
        assertEquals(CmfWeatherCondition.SHOWERS, snapshot.forecast[0].condition)
        assertEquals(20, snapshot.forecast[0].maxTemperatureC)
        assertEquals(11, snapshot.forecast[0].minTemperatureC)
    }

    @Test
    fun `temperatures below freezing round toward the nearest degree, not toward zero`() {
        val snapshot = requireNotNull(OpenMeteo.parseForecast(forecastJson, now))

        assertEquals(-2, snapshot.forecast[4].maxTemperatureC) // -1.6
        assertEquals(-12, snapshot.forecast[4].minTemperatureC) // -12.3
    }

    @Test
    fun `sun times are read for the whole week`() {
        val snapshot = requireNotNull(OpenMeteo.parseForecast(forecastJson, now))

        assertEquals(7, snapshot.sun.size)
        assertEquals(hourZero + 3600, snapshot.sun.first().sunriseEpochSeconds)
        assertEquals(hourZero + 50000, snapshot.sun.first().sunsetEpochSeconds)
    }

    @Test
    fun `a partial response still yields what it does contain`() {
        // A provider dropping a field should not take the whole feature down.
        val minimal = """{"current":{"weather_code":0},"daily":{"weather_code":[0]}}"""
        val snapshot = requireNotNull(OpenMeteo.parseForecast(minimal, now))

        assertEquals(CmfWeatherCondition.SUNNY, snapshot.today.condition)
        assertEquals(0, snapshot.today.temperatureC)
        assertTrue(snapshot.hourly.isEmpty())
        assertTrue(snapshot.sun.isEmpty())
    }

    @Test
    fun `a response with no forecast at all is refused`() {
        assertNull(OpenMeteo.parseForecast("""{"error":true}""", now))
    }

    @Test
    fun `a place is resolved to coordinates`() {
        val json = """
            {"results":[{"name":"Москва","latitude":55.75222,"longitude":37.61556,"country":"Россия"}]}
        """.trimIndent()

        val place = requireNotNull(OpenMeteo.parseLocation(json))

        assertEquals("Москва", place.name)
        assertEquals(55.75222, place.latitude)
        assertEquals(37.61556, place.longitude)
    }

    @Test
    fun `a place that does not exist is not an error`() {
        assertNull(OpenMeteo.parseLocation("""{"generationtime_ms":0.1}"""))
        assertNull(OpenMeteo.parseLocation("""{"results":[]}"""))
    }

    @Test
    fun `the request asks for a full week and unix timestamps`() {
        val url = OpenMeteo.forecastUrl(WeatherLocation("Москва", 55.75222, 37.61556))

        assertTrue("forecast_days=7" in url, url)
        assertTrue("timeformat=unixtime" in url, url)
        assertTrue("timezone=auto" in url, url)
    }

    @Test
    fun `a city name with spaces and Cyrillic is encoded`() {
        val url = OpenMeteo.geocodingUrl("Нижний Новгород", "ru")

        assertTrue(" " !in url, url)
        assertTrue("%D0" in url, url)
    }
}
