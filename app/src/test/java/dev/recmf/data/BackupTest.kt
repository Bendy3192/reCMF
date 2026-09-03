package dev.recmf.data

import dev.recmf.data.Backup.Setting
import dev.recmf.data.Backup.SettingType
import dev.recmf.data.Backup.Table
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackupTest {

    private val everySort = listOf(
        Setting("watch_metric", SettingType.BOOLEAN, true),
        Setting("auto_sync_seconds", SettingType.INT, 300),
        Setting("almanac_sent_at", SettingType.LONG, 1_788_354_830L),
        Setting("weather_city", SettingType.STRING, "Москва"),
        Setting("notification_blocked_packages", SettingType.STRING_SET, setOf("a.b", "c.d")),
    )

    @Test
    fun `every sort of setting survives the round trip`() {
        val back = Backup.read(Backup.write(Backup.Contents(settings = everySort)))!!

        assertEquals(everySort, back.settings)
    }

    @Test
    fun `the pairing key never reaches the file even when handed to it`() {
        // The rule that matters most here, enforced at the point of writing rather than
        // trusted to every caller.
        val withSecret = everySort + Setting("watch_auth_key_sealed", SettingType.STRING, "hunter2")

        val text = Backup.write(Backup.Contents(settings = withSecret))

        assertTrue("hunter2" !in text, "the sealed key was written to the backup")
        assertTrue("watch_auth_key_sealed" !in text)
    }

    @Test
    fun `the assistant's key is somebody's money and does not travel`() {
        // Sealed or not: a key in a file bound for a cloud drive buys tokens against
        // somebody's account. It is pasted in once on the new phone instead.
        val withKey = everySort + Setting("ai_key_sealed", SettingType.STRING, "pplx-secret")

        val text = Backup.write(Backup.Contents(settings = withKey))

        assertTrue("pplx-secret" !in text, "the assistant's key was written to the backup")
        assertTrue("ai_key_sealed" !in text)
    }

    @Test
    fun `a file carrying the assistant's key is stripped on the way in`() {
        val forged = """
            {"format":1,"app":1,"writtenAt":1,
             "settings":[{"key":"ai_key_sealed","type":"STRING","value":"pplx-secret"}],
             "tables":{}}
        """.trimIndent()

        assertEquals(emptyList<Setting>(), Backup.read(forged)!!.settings)
    }

    @Test
    fun `the wizard's flag is about this install, not about the wearer`() {
        // Restoring it would switch off the wizard from inside the wizard — the restore
        // is offered on its second step — and leave somebody looking at a home screen
        // they never asked for. Stripped both ways, so a file written before this rule
        // existed cannot do it either.
        val been = listOf(Setting("onboarding_done", SettingType.BOOLEAN, true))

        assertTrue("onboarding_done" !in Backup.write(Backup.Contents(settings = been)))

        val older = """
            {"format":1,"app":1,"writtenAt":1,
             "settings":[{"key":"onboarding_done","type":"BOOLEAN","value":true}],
             "tables":{}}
        """.trimIndent()

        assertEquals(emptyList<Setting>(), Backup.read(older)!!.settings)
    }

    @Test
    fun `the watch's address and name stay with the watch`() {
        val paired = listOf(
            Setting("watch_address", SettingType.STRING, "AA:BB:CC:DD:EE:FF"),
            Setting("watch_name", SettingType.STRING, "CMF Watch Pro 2-7219"),
        )

        val text = Backup.write(Backup.Contents(settings = paired))

        assertTrue("AA:BB:CC" !in text)
        assertEquals(emptyList<Setting>(), Backup.read(text)!!.settings)
    }

    @Test
    fun `a file carrying a secret is stripped on the way in too`() {
        // A backup written by some future version, or edited by hand. Reading is the last
        // gate before the value reaches the store, so it is checked there as well.
        val forged = """
            {"format":1,"app":1,"writtenAt":1,
             "settings":[{"key":"watch_auth_key_sealed","type":"STRING","value":"hunter2"}],
             "tables":{}}
        """.trimIndent()

        assertEquals(emptyList<Setting>(), Backup.read(forged)!!.settings)
    }

    @Test
    fun `tables survive with their columns and nulls`() {
        val tables = mapOf(
            "heart_rate_samples" to Table(
                columns = listOf("timestamp", "bpm", "syncedAt"),
                rows = listOf(listOf(1L, 62, null), listOf(2L, 64, 99L)),
            ),
        )

        val back = Backup.read(Backup.write(Backup.Contents(tables = tables)))!!
        val heart = back.tables.getValue("heart_rate_samples")

        assertEquals(listOf("timestamp", "bpm", "syncedAt"), heart.columns)
        assertEquals(2, heart.rows.size)
        assertNull(heart.rows[0][2])
        assertEquals(99, (heart.rows[1][2] as Number).toInt())
    }

    @Test
    fun `the app version and the moment are carried for a human reading the file`() {
        val back = Backup.read(
            Backup.write(Backup.Contents(versionCode = 373, writtenAtSeconds = 1_788_354_830L)),
        )!!

        assertEquals(373, back.versionCode)
        assertEquals(1_788_354_830L, back.writtenAtSeconds)
    }

    @Test
    fun `a file from another format is refused rather than half read`() {
        val future = """{"format":99,"settings":[],"tables":{}}"""

        assertNull(Backup.read(future))
    }

    @Test
    fun `something that is not a backup at all is refused`() {
        assertNull(Backup.read(""))
        assertNull(Backup.read("not json"))
        assertNull(Backup.read("""{"hello":"world"}"""))
    }

    @Test
    fun `an unreadable setting is dropped and the rest is kept`() {
        // One unknown key should not cost somebody their history.
        val mixed = """
            {"format":1,"app":1,"writtenAt":1,"settings":[
              {"key":"watch_metric","type":"BOOLEAN","value":true},
              {"key":"from_the_future","type":"COLOUR","value":"red"},
              {"type":"STRING","value":"nameless"}
            ],"tables":{}}
        """.trimIndent()

        val settings = Backup.read(mixed)!!.settings

        assertEquals(listOf(Setting("watch_metric", SettingType.BOOLEAN, true)), settings)
    }

    @Test
    fun `an empty backup is still a valid one`() {
        val back = Backup.read(Backup.write(Backup.Contents()))!!

        assertEquals(emptyList<Setting>(), back.settings)
        assertEquals(emptyMap<String, Table>(), back.tables)
    }
}
