/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 *
 * Payload layout ported from Gadgetbridge (AGPL-3.0-or-later); see NOTICE.
 */
package dev.recmf.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * The icons the watch can draw. The codes are its own, worked out by observation rather
 * than from any specification, so anything unrecognised falls back to [CLOUDY] rather
 * than to a code that might mean something unexpected.
 */
enum class CmfWeatherCondition(val code: Byte) {
    SUNNY(1),
    CLOUDY(2),
    OVERCAST(3),
    SHOWERS(4),
    SNOW_SHOWERS(5),
    FOG(6),
    THUNDER_SHOWERS(9),
    SLEET(14),
    EXTREME_HEAT(19),
    EXTREME_COLD(20),
    STRONG_WIND(21),
    NIGHT_CLEAR(23),
    NIGHT_CLOUDY(24),
    HAZE(25),
    SUN_WITH_CLOUD(26),
    ;

    companion object {
        /**
         * Maps a WMO weather code — what open meteorological services report — onto the
         * watch's icons.
         */
        fun fromWmo(code: Int): CmfWeatherCondition = when (code) {
            0 -> SUNNY
            1, 2 -> SUN_WITH_CLOUD
            3 -> OVERCAST
            45, 48 -> FOG
            51, 53, 55, 61, 63, 65, 80, 81, 82 -> SHOWERS
            56, 57, 66, 67 -> SLEET
            71, 73, 75, 77, 85, 86 -> SNOW_SHOWERS
            95, 96, 99 -> THUNDER_SHOWERS
            else -> CLOUDY
        }
    }
}

/** A day's weather. Temperatures are whole degrees Celsius. */
data class WeatherDay(
    val condition: CmfWeatherCondition,
    val temperatureC: Int,
    val maxTemperatureC: Int,
    val minTemperatureC: Int,
    val humidityPercent: Int = 0,
    val airQualityIndex: Int = 0,
    val uvIndex: Int = 0,
    val windSpeed: Int = 0,
)

/** One hour of today's forecast; the watch shows only these two fields. */
data class WeatherHour(
    val temperatureC: Int,
    val condition: CmfWeatherCondition,
)

/** Sunrise and sunset as epoch seconds. */
data class SunTimes(
    val sunriseEpochSeconds: Long,
    val sunsetEpochSeconds: Long,
)

/**
 * Builds the `WEATHER_SET_1` payload: today plus six days, twenty-four hours, a place
 * name and a week of sun times, in one fixed-size block.
 *
 * Two things about it are easy to get wrong and are pinned by tests. Temperatures are
 * offset by 100 so that a byte can carry values below freezing — a raw −5 would read as
 * 251. And the sun times are **little-endian** while everything before them is
 * big-endian, in the middle of the same buffer.
 */
object CmfWeather {
    const val FORECAST_DAYS = 7
    const val HOURLY_ENTRIES = 24
    const val LOCATION_BYTES = 32

    private const val DAY_ENTRY_BYTES = 9

    /** A byte cannot hold −5, so every temperature is shifted up before it is written. */
    private const val TEMPERATURE_OFFSET = 100

    /** What the watch shows for a day it has no data for: an empty icon at −99 °C. */
    private val MISSING_DAY = byteArrayOf(0x00, 0x01, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00)

    val PAYLOAD_SIZE: Int =
        FORECAST_DAYS * DAY_ENTRY_BYTES + HOURLY_ENTRIES * 2 + LOCATION_BYTES + FORECAST_DAYS * 8

    /**
     * @param today the current conditions, whose max and min cover the whole day.
     * @param forecast up to six following days; short lists are padded with [MISSING_DAY].
     * @param hourly up to twenty-four hours; a short list repeats [today]'s conditions.
     * @param sun sunrise and sunset for today and the six following days.
     */
    fun payload(
        today: WeatherDay,
        forecast: List<WeatherDay>,
        hourly: List<WeatherHour>,
        location: String,
        sun: List<SunTimes>,
    ): ByteArray {
        val buf = ByteBuffer.allocate(PAYLOAD_SIZE).order(ByteOrder.BIG_ENDIAN)

        buf.putDay(today)
        for (index in 0 until FORECAST_DAYS - 1) {
            val day = forecast.getOrNull(index)
            if (day == null) buf.put(MISSING_DAY) else buf.putDay(day, useMaxAsCurrent = true)
        }

        for (index in 0 until HOURLY_ENTRIES) {
            val hour = hourly.getOrNull(index)
            buf.put(((hour?.temperatureC ?: today.temperatureC) + TEMPERATURE_OFFSET).toByte())
            buf.put((hour?.condition ?: today.condition).code)
        }

        // The watch scrolls anything longer, and the name must not be cut mid-character.
        val name = location.truncateUtf8(LOCATION_BYTES - 2)
        buf.put(name)
        buf.put(ByteArray(LOCATION_BYTES - name.size))

        // Deliberate: the sun times are little-endian in an otherwise big-endian payload.
        buf.order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until FORECAST_DAYS) {
            val times = sun.getOrNull(index)
            buf.putInt(times?.sunriseEpochSeconds?.toInt() ?: 0)
            buf.putInt(times?.sunsetEpochSeconds?.toInt() ?: 0)
        }

        return buf.array()
    }

    private fun ByteBuffer.putDay(day: WeatherDay, useMaxAsCurrent: Boolean = false) {
        put(day.condition.code)
        put(((if (useMaxAsCurrent) day.maxTemperatureC else day.temperatureC) + TEMPERATURE_OFFSET).toByte())
        put((day.maxTemperatureC + TEMPERATURE_OFFSET).toByte())
        put((day.minTemperatureC + TEMPERATURE_OFFSET).toByte())
        put(day.humidityPercent.coerceIn(0, 100).toByte())
        putShort(day.airQualityIndex.coerceIn(0, 0xFFFF).toShort())
        put(day.uvIndex.coerceIn(0, 255).toByte())
        put(day.windSpeed.coerceIn(0, 255).toByte())
    }

    /** Cuts on a character boundary: half a code point would render as rubbish. */
    private fun String.truncateUtf8(maxBytes: Int): ByteArray {
        val encoded = toByteArray(StandardCharsets.UTF_8)
        if (encoded.size <= maxBytes) return encoded

        var end = maxBytes
        while (end > 0 && (encoded[end].toInt() and 0xc0) == 0x80) end--
        return encoded.copyOf(end)
    }
}
