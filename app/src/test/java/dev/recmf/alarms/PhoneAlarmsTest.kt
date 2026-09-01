/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.alarms

import dev.recmf.protocol.CmfWeekday
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PhoneAlarmsTest {

    /** Real output, off a real phone, with four alarms set to cover the repeat cases. */
    private val output = """
        Row: 0 _id=1, hour=11, minutes=10, daysofweek=0, enabled=1, vibrate=1, label=, ringtone=content://settings/system/alarm_alert, delete_after_use=0, incvol=0
        Row: 1 _id=2, hour=6, minutes=10, daysofweek=31, enabled=1, vibrate=1, label=, ringtone=content://settings/system/alarm_alert, delete_after_use=0, incvol=0
        Row: 2 _id=3, hour=14, minutes=10, daysofweek=96, enabled=1, vibrate=1, label=, ringtone=content://settings/system/alarm_alert, delete_after_use=0, incvol=0
        Row: 3 _id=4, hour=3, minutes=10, daysofweek=4, enabled=1, vibrate=1, label=, ringtone=content://settings/system/alarm_alert, delete_after_use=0, incvol=0
    """.trimIndent()

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
        val rows = PhoneAlarms.parseRows("Row: 0 _id=1, hour=7, enabled=1\n")

        assertTrue(rows.isEmpty())
    }

    @Test
    fun `the repeat masks carry over without conversion`() {
        // The clock numbers Monday as bit 0 and the watch numbers Monday as 1, so 31 is
        // Monday to Friday on both sides and 96 is the weekend on both sides.
        val alarms = PhoneAlarms.toWatchAlarms(PhoneAlarms.parseRows(output))

        val weekday = alarms.first { it.hour == 6 }
        assertEquals(
            setOf(
                CmfWeekday.MONDAY, CmfWeekday.TUESDAY, CmfWeekday.WEDNESDAY,
                CmfWeekday.THURSDAY, CmfWeekday.FRIDAY,
            ),
            weekday.days,
        )

        val weekend = alarms.first { it.hour == 14 }
        assertEquals(setOf(CmfWeekday.SATURDAY, CmfWeekday.SUNDAY), weekend.days)

        val once = alarms.first { it.hour == 11 }
        assertEquals(emptySet<CmfWeekday>(), once.days)

        val single = alarms.first { it.hour == 3 }
        assertEquals(setOf(CmfWeekday.WEDNESDAY), single.days)
    }

    @Test
    fun `a phone with more alarms than the watch holds keeps the ones that ring`() {
        val rows = List(6) { PhoneAlarms.Row(hour = 20 - it, minute = 0, days = 0, enabled = false) } +
            List(6) { PhoneAlarms.Row(hour = it, minute = 30, days = 0, enabled = true) }

        val alarms = PhoneAlarms.toWatchAlarms(rows)

        assertEquals(8, alarms.size)
        // The six that are on, earliest first, then the earliest two of the rest.
        assertTrue(alarms.take(6).all { it.enabled })
        assertEquals(listOf(0, 1, 2, 3, 4, 5), alarms.take(6).map { it.hour })
    }
}
