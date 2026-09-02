/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Database(
    entities = [
        ActivitySampleEntity::class,
        HeartRateSampleEntity::class,
        Spo2SampleEntity::class,
        RestingHeartRateSampleEntity::class,
        StressSampleEntity::class,
        SleepSessionEntity::class,
        AiInsightEntity::class,
        CoachMessageEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
abstract class RecmfDatabase : RoomDatabase() {
    abstract fun sampleDao(): SampleDao

    companion object {
        @Volatile
        private var instance: RecmfDatabase? = null

        /**
         * Adds the SpO2 and resting-heart-rate tables.
         *
         * Written out rather than falling back to a destructive rebuild: this table is a
         * staging buffer, but what is staged is exactly the samples Health Connect has
         * not accepted yet, and dropping those loses readings nothing else holds.
         *
         * The SQL has to match what Room generates for the entities or it refuses to open
         * the database — column order follows the declaration, a non-null Long or Int is
         * `INTEGER NOT NULL`, and a nullable one is plain `INTEGER`.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `spo2_samples` (" +
                        "`timestamp` INTEGER NOT NULL, " +
                        "`percent` INTEGER NOT NULL, " +
                        "`syncedAt` INTEGER, " +
                        "PRIMARY KEY(`timestamp`))",
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `resting_heart_rate_samples` (" +
                        "`timestamp` INTEGER NOT NULL, " +
                        "`bpm` INTEGER NOT NULL, " +
                        "`syncedAt` INTEGER, " +
                        "PRIMARY KEY(`timestamp`))",
                )
            }
        }

        /**
         * Adds the stress table and the climb counter.
         *
         * Stress had nowhere to live: Health Connect has no record type for it, so it was
         * held in memory and lost with every restart. Climbs were being read off the watch
         * all along, inside what the parser called an unidentified tail.
         *
         * A default on the new column, because the rows already in the table were written
         * before anything read that number and there is nothing to back-fill them with.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `stress_samples` (" +
                        "`timestamp` INTEGER NOT NULL, " +
                        "`level` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`timestamp`))",
                )
                connection.execSQL(
                    "ALTER TABLE `activity_samples` ADD COLUMN `climbs` INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /**
         * Marks which heart-rate samples came from a workout.
         *
         * The watch sends workout pulse under its own command and this app stored it
         * exactly like the ordinary reading, which threw the distinction away. It is the
         * only evidence of a workout the watch offers — it keeps a session's pulse and no
         * summary of it — so without this there is nothing to build a session from.
         *
         * The rows already here cannot be back-filled: the command they arrived under was
         * not written down, and guessing from how closely they are spaced would invent
         * workouts. They default to false, which is the honest reading of "not known".
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE `heart_rate_samples` " +
                        "ADD COLUMN `duringWorkout` INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /**
         * Adds the nights table.
         *
         * Sleep had been kept as a single "last night" in settings, overwritten every
         * morning, because Health Connect takes each night as it arrives and the card only
         * ever showed the most recent one. Readiness needs a baseline, and sleep is the
         * strongest thing it has to work from, so nights accumulate now.
         *
         * Nothing to back-fill: the nights before this ran were written over as they came.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sleep_sessions` (" +
                        "`startTimestamp` INTEGER NOT NULL, " +
                        "`wakeTimestamp` INTEGER NOT NULL, " +
                        "`deepSeconds` INTEGER NOT NULL, " +
                        "`lightSeconds` INTEGER NOT NULL, " +
                        "`remSeconds` INTEGER NOT NULL, " +
                        "`unknownSeconds` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`startTimestamp`))",
                )
            }
        }

        /**
         * Adds the table the assistant's answers are kept in.
         *
         * Nothing to back-fill and nothing lost if it were dropped — every row in it can be
         * asked for again. It is a cache, and it exists so that opening a tile shows an
         * answer at once instead of billing somebody for one they already had.
         */
        /**
         * Adds the coach's conversation.
         *
         * The coach existed before this as a switch that added a paragraph about the
         * wearer to one-off questions. There was nowhere to talk to it, and nowhere to
         * keep what was said. This is that place.
         *
         * Nothing to back-fill: there was no conversation to lose.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `coach_messages` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`fromUser` INTEGER NOT NULL, " +
                        "`text` TEXT NOT NULL, " +
                        "`atSeconds` INTEGER NOT NULL)",
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ai_insights` (" +
                        "`metric` TEXT NOT NULL, " +
                        "`text` TEXT NOT NULL, " +
                        "`sources` TEXT NOT NULL, " +
                        "`atSeconds` INTEGER NOT NULL, " +
                        "`through` TEXT NOT NULL, " +
                        "PRIMARY KEY(`metric`))",
                )
            }
        }

        fun get(context: Context): RecmfDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                RecmfDatabase::class.java,
                "recmf.db",
            )
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                )
                .build()
                .also { instance = it }
        }
    }
}
