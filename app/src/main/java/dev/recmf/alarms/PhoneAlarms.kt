/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.alarms

import android.content.Context
import androidx.core.net.toUri
import android.util.Log
import dev.recmf.protocol.CmfAlarm
import dev.recmf.protocol.CmfAlarms
import dev.recmf.protocol.CmfWeekday
import java.io.BufferedReader
import java.time.DayOfWeek
import java.time.LocalDateTime
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
     * Held as text and turned into a `Uri` where it is used, not here: the parsing below is
     * ordinary Kotlin and is tested as such, and a `Uri.parse` in this object's setup would
     * drag Android into a test that has no business needing it.
     */
    private const val PROVIDER = "content://com.android.deskclock/alarms"

    private const val TAG = "PhoneAlarms"

    /** One row of that provider, before it is anything to do with a watch. */
    data class Row(val hour: Int, val minute: Int, val days: Int, val enabled: Boolean)

    fun read(context: Context, now: LocalDateTime = LocalDateTime.now()): List<CmfAlarm>? =
        (throughResolver(context) ?: throughRoot())?.let { toWatchAlarms(it, now) }

    /**
     * The way that needs no root, and works only if the provider will talk to us.
     *
     * Kept first and kept trying: a clock that opens up in some future Android, or a ROM
     * that ships a friendlier one, should not need reCMF to be rooted for this.
     */
    private fun throughResolver(context: Context): List<Row>? = try {
        context.contentResolver.query(PROVIDER.toUri(), null, null, null, null)?.use { cursor ->
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
     * The phone's alarms as the watch's, nearest first.
     *
     * The repeat masks need no conversion: the clock numbers Monday as bit 0 and the watch
     * numbers Monday as 1, and so on to Sunday, so the two are the same number. That is
     * worth stating because it looks like an omission — read off a real phone, a weekday
     * alarm is 31 and a weekend one is 96, which is what those bits mean on both sides.
     *
     * An alarm switched off on the phone is left out entirely rather than sent as a
     * disabled one. Mirroring it faithfully would be defensible, but it is not what the
     * list is for: the watch holds [CmfAlarms.MAX_ALARMS] and a morning can hold more than
     * that, so a slot spent on something that will not ring is a slot taken from something
     * that will. It also makes the watch's own screen mean one thing — what is going to
     * wake you — instead of two.
     *
     * What survives is then ordered by **when each alarm will next ring**, which is what
     * makes the right one drop out when there are still too many: one that has already
     * gone off this morning is now tomorrow's and yields its place to the ones still to
     * come. Sorting by time of day, which this used to do, kept a spent six o'clock ahead
     * of a seven o'clock that had not rung yet.
     */
    fun toWatchAlarms(rows: List<Row>, now: LocalDateTime = LocalDateTime.now()): List<CmfAlarm> =
        rows
            .mapNotNull { row -> nextRing(row, now)?.let { row to it } }
            .sortedBy { (_, ringsAt) -> ringsAt }
            .take(CmfAlarms.MAX_ALARMS)
            .map { (row, _) ->
                CmfAlarm(
                    hour = row.hour,
                    minute = row.minute,
                    enabled = true,
                    days = weekdays(row.days),
                )
            }

    /**
     * When an alarm will next go off, or null if it never will.
     *
     * A one-off is today if it is still to come and tomorrow otherwise. A repeating one is
     * the first of its days from today onwards, today included when the time has not passed.
     */
    fun nextRing(row: Row, now: LocalDateTime): LocalDateTime? {
        if (!row.enabled) return null

        val today = now.toLocalDate()
        val days = weekdays(row.days)

        if (days.isEmpty()) {
            val candidate = today.atTime(row.hour, row.minute)
            return if (candidate.isAfter(now)) candidate else candidate.plusDays(1)
        }

        // Seven days and not eight: a repeat that includes today but has already rung comes
        // round again next week, which the eighth step would find a day early.
        for (ahead in 0..6) {
            val date = today.plusDays(ahead.toLong())
            if (weekdayOf(date.dayOfWeek) !in days) continue
            val candidate = date.atTime(row.hour, row.minute)
            if (candidate.isAfter(now)) return candidate
        }
        return today.plusWeeks(1).atTime(row.hour, row.minute)
    }

    private fun weekdays(mask: Int): Set<CmfWeekday> =
        CmfWeekday.entries.filter { mask and it.bit != 0 }.toSet()

    /** Both sides run Monday to Sunday in that order, so this is the same index twice. */
    private fun weekdayOf(day: DayOfWeek): CmfWeekday = CmfWeekday.entries[day.value - 1]

    /** Long enough for the root prompt to be answered, short enough not to hang a sync. */
    private const val ROOT_TIMEOUT_SECONDS = 20L
}
