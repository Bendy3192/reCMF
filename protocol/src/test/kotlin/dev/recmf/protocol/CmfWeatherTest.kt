package dev.recmf.protocol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CmfWeatherTest {
    private val today = WeatherDay(
        condition = CmfWeatherCondition.SUNNY,
        temperatureC = 21,
        maxTemperatureC = 25,
        minTemperatureC = 14,
        humidityPercent = 60,
        airQualityIndex = 42,
        uvIndex = 5,
        windSpeed = 3,
    )

    private fun payload(
        forecast: List<WeatherDay> = emptyList(),
        hourly: List<WeatherHour> = emptyList(),
        location: String = "Moscow",
        sun: List<SunTimes> = emptyList(),
    ) = CmfWeather.payload(today, forecast, hourly, location, sun)

    @Test
    fun `the payload is exactly the size the watch expects`() {
        assertEquals(199, CmfWeather.PAYLOAD_SIZE)
        assertEquals(199, payload().size)
    }

    @Test
    fun `today's entry leads, with temperatures offset by 100`() {
        val p = payload()

        assertEquals(CmfWeatherCondition.SUNNY.code, p[0])
        assertEquals(121, p[1].toInt()) // 21 C
        assertEquals(125, p[2].toInt()) // max 25 C
        assertEquals(114, p[3].toInt()) // min 14 C
        assertEquals(60, p[4].toInt())
        assertEquals(42, ByteBuffer.wrap(p, 5, 2).order(ByteOrder.BIG_ENDIAN).short.toInt())
        assertEquals(5, p[7].toInt())
        assertEquals(3, p[8].toInt())
    }

    @Test
    fun `a temperature below freezing survives the byte`() {
        // Without the offset a byte cannot carry -5 and the watch would read 251.
        val frost = today.copy(temperatureC = -5, maxTemperatureC = -1, minTemperatureC = -12)
        val p = CmfWeather.payload(frost, emptyList(), emptyList(), "Norilsk", emptyList())

        assertEquals(95, p[1].toInt())
        assertEquals(99, p[2].toInt())
        assertEquals(88, p[3].toInt())
    }

    @Test
    fun `days with no forecast are filled with the watch's empty marker`() {
        val p = payload()

        // Second day starts at byte 9 and there is no forecast for it.
        assertArrayEquals(byteArrayOf(0x00, 0x01, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00), p.copyOfRange(9, 18))
    }

    @Test
    fun `hours with no forecast repeat today's conditions`() {
        val hourlyStart = 7 * 9
        val p = payload(hourly = listOf(WeatherHour(18, CmfWeatherCondition.FOG)))

        assertEquals(118, p[hourlyStart].toInt())
        assertEquals(CmfWeatherCondition.FOG.code, p[hourlyStart + 1])

        // The second hour is missing, so it mirrors today.
        assertEquals(121, p[hourlyStart + 2].toInt())
        assertEquals(CmfWeatherCondition.SUNNY.code, p[hourlyStart + 3])
    }

    @Test
    fun `only the first 24 hours are sent`() {
        val p = payload(hourly = List(48) { WeatherHour(it, CmfWeatherCondition.CLOUDY) })

        assertEquals(199, p.size)
        val hourlyStart = 7 * 9
        assertEquals(100 + 23, p[hourlyStart + 23 * 2].toInt())
    }

    @Test
    fun `the sun times are little-endian in an otherwise big-endian payload`() {
        val sunrise = 0x01020304L
        val p = payload(sun = listOf(SunTimes(sunrise, 0x05060708L)))
        val sunStart = 7 * 9 + 24 * 2 + 32

        assertArrayEquals(byteArrayOf(0x04, 0x03, 0x02, 0x01), p.copyOfRange(sunStart, sunStart + 4))
        assertArrayEquals(byteArrayOf(0x08, 0x07, 0x06, 0x05), p.copyOfRange(sunStart + 4, sunStart + 8))
    }

    @Test
    fun `a Cyrillic place name is not cut mid-character`() {
        val p = payload(location = "Петропавловск-Камчатский")
        val nameStart = 7 * 9 + 24 * 2
        val name = p.copyOfRange(nameStart, nameStart + 32)

        val text = String(name.takeWhile { it != 0.toByte() }.toByteArray(), Charsets.UTF_8)
        assert("Петропавловск-Камчатский".startsWith(text)) { "'$text' is not a prefix" }
        assert('�' !in text) { "decoded to a replacement character: $text" }
    }

    @Test
    fun `WMO codes map onto the watch's icons`() {
        assertEquals(CmfWeatherCondition.SUNNY, CmfWeatherCondition.fromWmo(0))
        assertEquals(CmfWeatherCondition.OVERCAST, CmfWeatherCondition.fromWmo(3))
        assertEquals(CmfWeatherCondition.FOG, CmfWeatherCondition.fromWmo(45))
        assertEquals(CmfWeatherCondition.SHOWERS, CmfWeatherCondition.fromWmo(63))
        assertEquals(CmfWeatherCondition.SNOW_SHOWERS, CmfWeatherCondition.fromWmo(75))
        assertEquals(CmfWeatherCondition.THUNDER_SHOWERS, CmfWeatherCondition.fromWmo(95))
        assertEquals(CmfWeatherCondition.SLEET, CmfWeatherCondition.fromWmo(66))

        // An unknown code must not become an arbitrary icon.
        assertEquals(CmfWeatherCondition.CLOUDY, CmfWeatherCondition.fromWmo(1234))
    }
}
