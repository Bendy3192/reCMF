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
    ],
    version = 2,
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

        fun get(context: Context): RecmfDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                RecmfDatabase::class.java,
                "recmf.db",
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}
