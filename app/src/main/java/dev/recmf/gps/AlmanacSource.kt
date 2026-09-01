/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.gps

import android.util.Log
import dev.recmf.ble.ProtocolLog
import dev.recmf.protocol.CmfAgps
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches the satellite orbits the watch's own GPS needs, from the people who publish them.
 *
 * The watch's receiver is a MediaTek part, and MediaTek publishes its predicted orbits
 * openly: a month at a time, no account, no key, the same file for everyone. The official
 * app republishes a three-day slice of it through its own servers; reCMF cuts the slice
 * itself, which is why it needs nothing from Nothing.
 *
 * That address is not a guess. The watch's file was taken apart and its first record is
 * MediaTek's format exactly — 2304-byte slots of 32 satellites, six hours apart, the hour
 * counted from the GPS epoch in the first three bytes of every satellite record — and the
 * blocks line up with this file's, hour for hour. A file built here from it was accepted
 * by the watch.
 */
object AlmanacSource {

    /**
     * Plain HTTP, which is why the manifest exempts this one host.
     *
     * The server does not answer on HTTPS. What is exchanged is a public file, requested
     * with nothing identifying attached, so an observer learns only that something here
     * wants satellite orbits — against a fix that otherwise takes minutes in the open and
     * never at all between buildings.
     */
    private const val HOST = "http://epodownload.mediatek.com"

    /**
     * GPS **and GLONASS**, three days at a time, which is the file the official app sends.
     *
     * MediaTek's own GPS driver names these: `EPO_GPS_3_N.DAT` for GPS alone and
     * `EPO_GR_3_N.DAT` for the two together, N running one to ten. Three days of 56
     * satellites at 72 bytes a slot is 48384 bytes, which is exactly the size of the orbit
     * record in the file captured from the official app — GPS 1-32 followed by GLONASS
     * 65-88.
     *
     * Worth the extra request. Where GPS is jammed or simply blocked by buildings, GLONASS
     * may be the only constellation the receiver can hear, and a GPS-only almanac helps it
     * not at all.
     *
     * Which of the ten covers today is not something the file name says, so they are tried
     * in turn and the first that covers the next three days is used. In practice that is
     * the first.
     */
    private val COMBINED = (1..10).map { "$HOST/EPO_GR_3_$it.DAT" }

    /**
     * A month of GPS alone, and the fallback.
     *
     * This is the file reCMF used before it knew there was a better one, and the watch
     * accepted an almanac built from it. Kept because a GPS-only almanac is worth far more
     * than none, and one server path going missing should not cost the feature.
     */
    const val URL: String = "$HOST/EPO.DAT"

    /** A month of orbits is about 276 KB; well past that means something else answered. */
    private const val MAX_BYTES = 4 * 1024 * 1024

    private const val TIMEOUT_MILLIS = 30_000

    private const val TAG = "AlmanacSource"

    /**
     * Downloads the orbits and cuts the three days from now, ready to send to the watch.
     *
     * Returns null for anything that went wrong — no network, a server that answered with
     * something other than orbits, a file that does not reach three days ahead. There is
     * no half-answer worth having here: an almanac that stops short would leave the
     * receiver trusting orbits for hours the file never covered.
     */
    fun fetch(nowEpochSeconds: Long): ByteArray? {
        val nowHour = CmfAgps.hoursSinceGpsEpoch(nowEpochSeconds)

        for (url in COMBINED) {
            // A file that is not there ends the search rather than costing nine more
            // requests: the ten differ only in which three days they cover, so if the
            // first cannot be fetched at all, none of them can.
            val downloaded = download(url) ?: break

            val built = CmfAgps.buildFromEpo(downloaded, nowHour)
            if (built != null) {
                // Which constellations the watch was given is worth saying: it is the
                // difference between a fix where GPS is jammed and no fix at all, and
                // there is nothing else in the log that would show it.
                ProtocolLog.note("GPS data: fetched orbits for GPS and GLONASS")
                Log.i(TAG, "Built ${built.size} bytes from $url")
                return built
            }
            // It exists but covers other days. The next one may be today's.
        }

        val downloaded = download(URL) ?: return null
        val built = CmfAgps.buildFromEpo(downloaded, nowHour)
        if (built == null) {
            Log.w(TAG, "Downloaded ${downloaded.size} bytes that do not cover the next three days")
        } else {
            ProtocolLog.note("GPS data: fetched orbits for GPS only")
        }
        return built
    }

    private fun download(from: String): ByteArray? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(from).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MILLIS
                readTimeout = TIMEOUT_MILLIS
                requestMethod = "GET"
            }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.i(TAG, "Orbit server answered ${connection.responseCode} for $from")
                return null
            }

            val bytes = connection.inputStream.use { it.readBytes(MAX_BYTES) }
            if (bytes == null) Log.w(TAG, "Orbit file is larger than $MAX_BYTES bytes")
            bytes
        } catch (e: IOException) {
            Log.i(TAG, "Could not reach the orbit server", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** Reads at most [limit] bytes, or null if the stream had more. */
    private fun java.io.InputStream.readBytes(limit: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = read(buffer)
            if (read < 0) return out.toByteArray()
            if (out.size() + read > limit) return null
            out.write(buffer, 0, read)
        }
    }
}
