// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 2.1
package com.galker.pointandidentify.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Version 2 replaces the "settlements" table with "targets" (kind + structure height).
 * The table holds only derived data (bundled asset / remote file), so a destructive migration
 * is safe: TargetRepository re-seeds an empty table and resets its stored data version.
 */
@Database(entities = [TargetEntity::class], version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {

    abstract fun targetDao(): TargetDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "point.db"
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
