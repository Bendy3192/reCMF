/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.alarms

import dev.recmf.protocol.CmfWeekday
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class PhoneAlarmsTest {

    /** Real output, off a real phone, with four alarms set to cover the repeat cases. */
    private val output = """
        Row: 0 _id=1, hour=11, minutes=10, daysofweek=0, enabled=1, vibrate=1, label=, ringtone=content://settings/system/alarm_alert, delete_after_use=0, incvol=0
        Row: 1 _id=2, hour=6, minutes=10, daysofweek=31, enabled=1, vibrate=1, label=, ringtone=content://settings/system/alarm_alert, delete_after_use=0, incvol=0
        Row: 2 _id=3, hour=14, minutes=10, daysofweek=96, enabled=1, vibrate=1, label=, ringtone=content://settings/system/alarm_alert, delete_after_use=0, incvol=0
        Row: 3 _id=4, hour=3, minutes=10, daysofweek=4, enabled=1, vibrate=1, label=, ringtone=content://settings/system/alarm_alert, delete_after_use=0, incvol=0
    """.trimIndent()

    /** Any fixed moment will do; the repeating cases build their masks from its own weekday. */
    private val now: LocalDateTime = LocalDateTime.of(2026, 9, 2, 7, 0)

    /** The bit for a day, as both the clock and the watch number them. */
    private fun bitFor(offsetDays: Int): Int =
        CmfWeekday.entries[now.plusDays(offsetDays.toLong()).dayOfWeek.value - 1].bit

    @Test
    fun `the shell's rows read back as the alarms they came from`() {
        val rows = PhoneAlarms.parseRows(output)

        assertEquals(4, rows.size)
        assertEquals(PhoneAlarms.Row(11, 10, 0, enabled = true), rows[0])
        assertEquals(PhoneAlarms.Row(6, 10, 31, enabled = true), rows[1])
        assertEquals(PhoneAlarms.Row(14, 10, 96, enabled = true), rows[2])
        assertEquals(PhoneAlarms.Row(3, 10, 4, enabled = true), rows[3])
    }

    @Test
    fun `an empty table is nothing rather than a failure`() {
        // What the shell prints when no alarms are set, which is not an error and must not
        // read as one — it means the watch should hold no alarms either.
        assertEquals(emptyList<PhoneAlarms.Row>(), PhoneAlarms.parseRows("No result found.\n"))
    }

    @Test
    fun `a row missing a field it needs is dropped rather than guessed at`() {
        assertTrue(PhoneAlarms.parseRows("Row: 0 _id=1, hour=7, enabled=1\n").isEmpty())
    }

    @Test
    fun `the repeat masks carry over without conversion`() {
        // The clock numbers Monday as bit 0 and the watch numbers Monday as 1, so 31 is
        // Monday to Friday on both sides and 96 is the weekend on both sides. Confirmed
        // against the phone this output came from: its 03:10 alarm carries mask 4 and the
        // clock shows it on Wednesday.
        val alarms = PhoneAlarms.toWatchAlarms(PhoneAlarms.parseRows(output), now)

        assertEquals(
            setOf(
                CmfWeekday.MONDAY, CmfWeekday.TUESDAY, CmfWeekday.WEDNESDAY,
                CmfWeekday.THURSDAY, CmfWeekday.FRIDAY,
            ),
            alarms.first { it.hour == 6 }.days,
        )
        assertEquals(setOf(CmfWeekday.SATURDAY, CmfWeekday.SUNDAY), alarms.first { it.hour == 14 }.days)
        assertEquals(emptySet<CmfWeekday>(), alarms.first { it.hour == 11 }.days)
        assertEquals(setOf(CmfWeekday.WEDNESDAY), alarms.first { it.hour == 3 }.days)
    }

    @Test
    fun `an alarm that is off never rings again`() {
        val off = PhoneAlarms.Row(hour = 6, minute = 0, days = 0, enabled = false)

        assertNull(PhoneAlarms.nextRing(off, now))
    }

    @Test
    fun `a one-off is today while the time is still to come and tomorrow once it is not`() {
        val later = PhoneAlarms.Row(hour = 9, minute = 0, days = 0, enabled = true)
        val gone = PhoneAlarms.Row(hour = 6, minute = 0, days = 0, enabled = true)

        assertEquals(now.toLocalDate().atTime(9, 0), PhoneAlarms.nextRing(later, now))
        assertEquals(now.toLocalDate().plusDays(1).atTime(6, 0), PhoneAlarms.nextRing(gone, now))
    }

    @Test
    fun `a repeat that has already rung today comes round on its next day`() {
        // Set for today and tomorrow, at a time this morning that has been and gone.
        val row = PhoneAlarms.Row(
            hour = 6,
            minute = 0,
            days = bitFor(0) or bitFor(1),
            enabled = true,
        )

        assertEquals(now.toLocalDate().plusDays(1).atTime(6, 0), PhoneAlarms.nextRing(row, now))
    }

    @Test
    fun `a repeat set only for today comes round next week`() {
        val row = PhoneAlarms.Row(hour = 6, minute = 0, days = bitFor(0), enabled = true)

        assertEquals(now.toLocalDate().plusWeeks(1).atTime(6, 0), PhoneAlarms.nextRing(row, now))
    }

    @Test
    fun `a morning of ten alarms sends the eight that ring soonest`() {
        // The case this ordering exists for: a cascade of wake-ups, some already rung and
        // some switched off, against a watch with eight slots.
        val rows = (0 until 10).map { index ->
            PhoneAlarms.Row(hour = 6, minute = index * 5, days = 0, enabled = true)
        }

        val alarms = PhoneAlarms.toWatchAlarms(rows, now)

        // Every one of them is in the past at 07:00, so they are all tomorrow's and the
        // earliest eight win.
        assertEquals(8, alarms.size)
        assertEquals(listOf(0, 5, 10, 15, 20, 25, 30, 35), alarms.map { it.minute })
    }

    @Test
    fun `the ones still to come today beat the ones already rung`() {
        val rung = PhoneAlarms.Row(hour = 6, minute = 0, days = 0, enabled = true)
        val coming = PhoneAlarms.Row(hour = 8, minute = 0, days = 0, enabled = true)

        val alarms = PhoneAlarms.toWatchAlarms(listOf(rung, coming), now)

        assertEquals(listOf(8, 6), alarms.map { it.hour })
    }

    @Test
    fun `an alarm switched off gives up its slot`() {
        val off = PhoneAlarms.Row(hour = 5, minute = 0, days = 0, enabled = false)
        val on = (0 until 8).map { PhoneAlarms.Row(hour = 9 + it, minute = 0, days = 0, enabled = true) }

        val alarms = PhoneAlarms.toWatchAlarms(listOf(off) + on, now)

        assertEquals(8, alarms.size)
        assertTrue(alarms.all { it.enabled })
    }
}
