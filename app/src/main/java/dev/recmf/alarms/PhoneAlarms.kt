/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.alarms

import android.content.Context
import android.net.Uri
import android.util.Log
import dev.recmf.protocol.CmfAlarm
import dev.recmf.protocol.CmfAlarms
import dev.recmf.protocol.CmfWeekday
import java.io.BufferedReader
import java.util.concurrent.TimeUnit

/**
 * The alarms set in the phone's own clock, so the watch can mirror them.
 *
 * Android has no public way to list another app's alarms. `AlarmManager.getNextAlarmClock`
 * gives the next one and nothing else — no repeat days, no others behind it — which is not
 * enough to fill a watch that holds eight. The clock does keep them in a content provider,
 * and that provider answers; it is simply not open to ordinary apps.
 *
 * So this asks politely first and falls back to `su`. On a phone without root the polite
 * ask is all there is, and this returns nothing rather than pretending.
 */
object PhoneAlarms {

    /**
     * AOSP's clock, and the one CrDroid ships. Other clocks keep their alarms elsewhere.
     *
     * Held as text and turned into a [Uri] where it is used, not here: the parsing below is
     * ordinary Kotlin and is tested as such, and a `Uri.parse` in this object's setup would
     * drag Android into a test that has no business needing it.
     */
    private const val PROVIDER = "content://com.android.deskclock/alarms"

    private const val TAG = "PhoneAlarms"

    /** One row of that provider, before it is anything to do with a watch. */
    data class Row(val hour: Int, val minute: Int, val days: Int, val enabled: Boolean)

    fun read(context: Context): List<CmfAlarm>? =
        (throughResolver(context) ?: throughRoot())?.let(::toWatchAlarms)

    /**
     * The way that needs no root, and works only if the provider will talk to us.
     *
     * Kept first and kept trying: a clock that opens up in some future Android, or a ROM
     * that ships a friendlier one, should not need reCMF to be rooted for this.
     */
    private fun throughResolver(context: Context): List<Row>? = try {
        context.contentResolver.query(Uri.parse(PROVIDER), null, null, null, null)?.use { cursor ->
            val hour = cursor.getColumnIndex("hour")
            val minute = cursor.getColumnIndex("minutes")
            val days = cursor.getColumnIndex("daysofweek")
            val enabled = cursor.getColumnIndex("enabled")
            if (hour < 0 || minute < 0 || days < 0 || enabled < 0) {
                null
            } else {
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            Row(
                                hour = cursor.getInt(hour),
                                minute = cursor.getInt(minute),
                                days = cursor.getInt(days),
                                enabled = cursor.getInt(enabled) != 0,
                            ),
                        )
                    }
                }
            }
        }
    } catch (e: SecurityException) {
        Log.i(TAG, "The clock's provider is not open to us; trying root", e)
        null
    } catch (e: IllegalArgumentException) {
        Log.i(TAG, "No clock provider at that address", e)
        null
    }

    /**
     * The way that needs root: the same query, run by the shell command Android ships.
     *
     * `content query` prints rows as text, which is a poor interface — but it is the one
     * that exists, it needs nothing installed, and [parseRows] is tested against real
     * output rather than an idea of it.
     */
    private fun throughRoot(): List<Row>? = try {
        val process = ProcessBuilder("su", "-c", "content query --uri $PROVIDER")
            .redirectErrorStream(true)
            .start()
        val text = process.inputStream.bufferedReader().use(BufferedReader::readText)
        if (!process.waitFor(ROOT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroy()
            null
        } else {
            parseRows(text).takeIf { process.exitValue() == 0 }
        }
    } catch (e: Exception) {
        // No su, a denied prompt, a shell that is not there: all the same answer, which is
        // that the phone's alarms cannot be read on this device.
        Log.i(TAG, "Could not read the clock's alarms as root", e)
        null
    }

    /**
     * `content query` output, which looks like this:
     *
     * ```
     * Row: 0 _id=2, hour=6, minutes=10, daysofweek=31, enabled=1, vibrate=1, label=, …
     * ```
     *
     * A row missing any of the four fields is dropped rather than guessed at. An empty
     * table prints "No result found." and yields nothing, which is not the same as failing.
     */
    fun parseRows(text: String): List<Row> = text.lineSequence()
        .filter { it.startsWith("Row:") }
        .mapNotNull { line ->
            val fields = line.substringAfter("Row:")
                .split(", ")
                .mapNotNull { field ->
                    val at = field.indexOf('=')
                    if (at < 0) null else field.substring(0, at).trim().substringAfterLast(' ') to
                        field.substring(at + 1)
                }
                .toMap()

            val hour = fields["hour"]?.toIntOrNull()
            val minute = fields["minutes"]?.toIntOrNull()
            val days = fields["daysofweek"]?.toIntOrNull()
            val enabled = fields["enabled"]?.toIntOrNull()
            if (hour == null || minute == null || days == null || enabled == null) {
                null
            } else {
                Row(hour, minute, days, enabled != 0)
            }
        }
        .toList()

    /**
     * The phone's alarms as the watch's.
     *
     * The repeat masks need no conversion: the clock numbers Monday as bit 0 and the watch
     * numbers Monday as 1, and so on to Sunday, so the two are the same number. That is
     * worth stating because it looks like an omission — read off a real phone, a weekday
     * alarm is 31 and a weekend one is 96, which is what those bits mean on both sides.
     *
     * The watch holds [CmfAlarms.MAX_ALARMS] and no more, so a phone with more than that
     * loses some. Enabled ones go first and the earliest goes first within that, because
     * an alarm that is switched off is the one nobody misses.
     */
    fun toWatchAlarms(rows: List<Row>): List<CmfAlarm> = rows
        .sortedWith(compareByDescending<Row> { it.enabled }.thenBy { it.hour * 60 + it.minute })
        .take(CmfAlarms.MAX_ALARMS)
        .map { row ->
            CmfAlarm(
                hour = row.hour,
                minute = row.minute,
                enabled = row.enabled,
                days = CmfWeekday.entries.filter { row.days and it.bit != 0 }.toSet(),
            )
        }

    /** Long enough for the root prompt to be answered, short enough not to hang a sync. */
    private const val ROOT_TIMEOUT_SECONDS = 20L
}
