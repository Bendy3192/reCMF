/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dev.recmf.data.Backup.Setting
import dev.recmf.data.Backup.SettingType
import dev.recmf.data.Backup.Table
import kotlinx.coroutines.flow.first

/**
 * The phone half of a backup: reading the settings store and the tables, and putting them
 * back.
 *
 * [Backup] decides *what* a backup is and refuses to carry the pairing key; this only
 * knows how to get at the things it is allowed to have. The split is what lets the rules
 * that matter be tested without an Android device anywhere near them.
 *
 * ## Restoring merges rather than replaces
 *
 * A restore writes what the file holds over whatever is there and leaves everything else
 * alone. That is on purpose. The pairing key and the watch's address are not in the file
 * by design, so a wipe-then-write would clear the pairing of somebody who had already set
 * their new phone up — and the two sensible orders, pair-then-restore and
 * restore-then-pair, both have to work. Merging is the only behaviour that survives both.
 *
 * Rows merge on their primary key, which is a timestamp, so importing the same file twice
 * changes nothing the second time.
 */
class BackupStore(private val context: Context, private val dao: SampleDao) {

    /** Reads everything worth carrying. The excluded keys are dropped by [Backup.write]. */
    suspend fun collect(versionCode: Int, nowSeconds: Long): Backup.Contents = Backup.Contents(
        settings = settings(),
        tables = tables(),
        versionCode = versionCode,
        writtenAtSeconds = nowSeconds,
    )

    /**
     * Every preference in the store, typed.
     *
     * Walked rather than listed. A hand-written list of keys is a list that stops being
     * complete the first time somebody adds a setting without thinking of this file, and
     * the failure is silent: the backup simply comes back missing something.
     */
    private suspend fun settings(): List<Setting> =
        context.dataStore.data.first().asMap().mapNotNull { (key, value) ->
            val type = when (value) {
                is Boolean -> SettingType.BOOLEAN
                is Int -> SettingType.INT
                is Long -> SettingType.LONG
                is Float -> SettingType.FLOAT
                is Double -> SettingType.DOUBLE
                is String -> SettingType.STRING
                is Set<*> -> SettingType.STRING_SET
                // A kind of preference that did not exist when this was written. Skipped
                // rather than guessed at, and skipping one is better than failing the lot.
                else -> return@mapNotNull null
            }
            Setting(key.name, type, value)
        }.sortedBy { it.key }

    private suspend fun tables(): Map<String, Table> = mapOf(
        ACTIVITY to Table(
            listOf("timestamp", "steps", "distanceMeters", "calories", "climbs", "syncedAt"),
            dao.allActivity().map {
                listOf(it.timestamp, it.steps, it.distanceMeters, it.calories, it.climbs, it.syncedAt)
            },
        ),
        HEART_RATE to Table(
            listOf("timestamp", "bpm", "syncedAt", "duringWorkout"),
            dao.allHeartRate().map { listOf(it.timestamp, it.bpm, it.syncedAt, it.duringWorkout) },
        ),
        SPO2 to Table(
            listOf("timestamp", "percent", "syncedAt"),
            dao.allSpo2().map { listOf(it.timestamp, it.percent, it.syncedAt) },
        ),
        RESTING to Table(
            listOf("timestamp", "bpm", "syncedAt"),
            dao.allRestingHeartRate().map { listOf(it.timestamp, it.bpm, it.syncedAt) },
        ),
        STRESS to Table(
            listOf("timestamp", "level"),
            dao.allStress().map { listOf(it.timestamp, it.level) },
        ),
        SLEEP to Table(
            listOf(
                "startTimestamp", "wakeTimestamp",
                "deepSeconds", "lightSeconds", "remSeconds", "unknownSeconds",
            ),
            dao.allSleep().map {
                listOf(
                    it.startTimestamp, it.wakeTimestamp,
                    it.deepSeconds, it.lightSeconds, it.remSeconds, it.unknownSeconds,
                )
            },
        ),
        // The conversation with the coach. It is the one thing in this file the wearer
        // wrote rather than wore, which makes it the least replaceable: a month of steps
        // comes back from the watch and a conversation does not come back from anywhere.
        // The identity goes with it, because the order things were said in is the only
        // order they mean anything in.
        COACH to Table(
            listOf("id", "fromUser", "text", "atSeconds"),
            dao.allCoachMessages().map { listOf(it.id, it.fromUser, it.text, it.atSeconds) },
        ),
    )

    /**
     * Puts a backup back, and says how much of it landed.
     *
     * Columns are read by name from what the file declares rather than by position, so a
     * file written by a version with one column fewer still restores the columns it does
     * have. A row missing its primary key is dropped; a row missing anything else takes
     * the same default a fresh row would.
     */
    suspend fun restore(contents: Backup.Contents): Restored {
        context.dataStore.edit { prefs ->
            contents.settings
                .filterNot { it.key in Backup.NEVER_LEAVES }
                .forEach { setting -> prefs.put(setting) }
        }

        var rows = 0

        contents.tables[ACTIVITY]?.let { table ->
            val entities = table.each { row ->
                ActivitySampleEntity(
                    timestamp = row.long("timestamp") ?: return@each null,
                    steps = row.int("steps") ?: 0,
                    distanceMeters = row.int("distanceMeters") ?: 0,
                    calories = row.int("calories") ?: 0,
                    climbs = row.int("climbs") ?: 0,
                    syncedAt = row.long("syncedAt"),
                )
            }
            dao.insertActivity(entities)
            rows += entities.size
        }

        contents.tables[HEART_RATE]?.let { table ->
            val entities = table.each { row ->
                HeartRateSampleEntity(
                    timestamp = row.long("timestamp") ?: return@each null,
                    bpm = row.int("bpm") ?: 0,
                    syncedAt = row.long("syncedAt"),
                    duringWorkout = row.bool("duringWorkout") ?: false,
                )
            }
            dao.insertHeartRate(entities)
            rows += entities.size
        }

        contents.tables[SPO2]?.let { table ->
            val entities = table.each { row ->
                Spo2SampleEntity(
                    timestamp = row.long("timestamp") ?: return@each null,
                    percent = row.int("percent") ?: 0,
                    syncedAt = row.long("syncedAt"),
                )
            }
            dao.insertSpo2(entities)
            rows += entities.size
        }

        contents.tables[RESTING]?.let { table ->
            val entities = table.each { row ->
                RestingHeartRateSampleEntity(
                    timestamp = row.long("timestamp") ?: return@each null,
                    bpm = row.int("bpm") ?: 0,
                    syncedAt = row.long("syncedAt"),
                )
            }
            dao.insertRestingHeartRate(entities)
            rows += entities.size
        }

        contents.tables[STRESS]?.let { table ->
            val entities = table.each { row ->
                StressSampleEntity(
                    timestamp = row.long("timestamp") ?: return@each null,
                    level = row.int("level") ?: 0,
                )
            }
            dao.insertStress(entities)
            rows += entities.size
        }

        contents.tables[SLEEP]?.let { table ->
            val entities = table.each { row ->
                SleepSessionEntity(
                    startTimestamp = row.long("startTimestamp") ?: return@each null,
                    wakeTimestamp = row.long("wakeTimestamp") ?: 0,
                    deepSeconds = row.int("deepSeconds") ?: 0,
                    lightSeconds = row.int("lightSeconds") ?: 0,
                    remSeconds = row.int("remSeconds") ?: 0,
                    unknownSeconds = row.int("unknownSeconds") ?: 0,
                )
            }
            dao.insertSleep(entities)
            rows += entities.size
        }

        contents.tables[COACH]?.let { table ->
            val entities = table.each { row ->
                CoachMessageEntity(
                    id = row.long("id") ?: return@each null,
                    fromUser = row.bool("fromUser") ?: true,
                    text = row.text("text") ?: return@each null,
                    atSeconds = row.long("atSeconds") ?: 0,
                )
            }
            dao.insertCoachMessages(entities)
            rows += entities.size
        }

        return Restored(settings = contents.settings.count { it.key !in Backup.NEVER_LEAVES }, rows = rows)
    }

    /** What a restore actually put back, for a line the wearer can read. */
    data class Restored(val settings: Int, val rows: Int)

    private fun MutablePreferences.put(setting: Setting) {
        when (setting.type) {
            SettingType.BOOLEAN -> set(booleanPreferencesKey(setting.key), setting.value as Boolean)
            SettingType.INT -> set(intPreferencesKey(setting.key), setting.value as Int)
            SettingType.LONG -> set(longPreferencesKey(setting.key), setting.value as Long)
            SettingType.FLOAT -> set(floatPreferencesKey(setting.key), setting.value as Float)
            SettingType.DOUBLE -> set(doublePreferencesKey(setting.key), setting.value as Double)
            SettingType.STRING -> set(stringPreferencesKey(setting.key), setting.value as String)
            SettingType.STRING_SET -> {
                @Suppress("UNCHECKED_CAST")
                set(stringSetPreferencesKey(setting.key), setting.value as Set<String>)
            }
        }
    }

    /** Maps a table's rows through [build], dropping the ones it refuses. */
    private fun <T> Table.each(build: (Row) -> T?): List<T> =
        rows.mapNotNull { cells -> build(Row(columns, cells)) }

    /** One row, read by column name rather than by position. */
    private class Row(private val columns: List<String>, private val cells: List<Any?>) {
        private fun at(name: String): Any? = columns.indexOf(name).takeIf { it >= 0 }?.let(cells::getOrNull)

        fun long(name: String): Long? = (at(name) as? Number)?.toLong()
        fun int(name: String): Int? = (at(name) as? Number)?.toInt()
        fun bool(name: String): Boolean? = at(name) as? Boolean
        fun text(name: String): String? = at(name) as? String
    }

    private companion object {
        const val ACTIVITY = "activity_samples"
        const val HEART_RATE = "heart_rate_samples"
        const val SPO2 = "spo2_samples"
        const val RESTING = "resting_heart_rate_samples"
        const val STRESS = "stress_samples"
        const val SLEEP = "sleep_sessions"
        const val COACH = "coach_messages"
    }
}
