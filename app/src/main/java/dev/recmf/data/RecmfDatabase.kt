/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ActivitySampleEntity::class, HeartRateSampleEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class RecmfDatabase : RoomDatabase() {
    abstract fun sampleDao(): SampleDao

    companion object {
        @Volatile
        private var instance: RecmfDatabase? = null

        fun get(context: Context): RecmfDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                RecmfDatabase::class.java,
                "recmf.db",
            ).build().also { instance = it }
        }
    }
}
