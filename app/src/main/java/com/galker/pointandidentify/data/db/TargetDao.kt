// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.1
package com.galker.pointandidentify.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface TargetDao {

    @Query("SELECT COUNT(*) FROM targets")
    suspend fun count(): Int

    /** Bounding-box prefilter; exact range filtering is done in code. */
    @Query(
        "SELECT * FROM targets WHERE latitude BETWEEN :minLat AND :maxLat " +
            "AND longitude BETWEEN :minLon AND :maxLon"
    )
    suspend fun inBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<TargetEntity>

    @Insert
    suspend fun insertAll(items: List<TargetEntity>)

    @Query("DELETE FROM targets")
    suspend fun deleteAll()

    /** Atomic replacement: readers never observe a partially loaded table. */
    @Transaction
    suspend fun replaceAll(items: List<TargetEntity>) {
        deleteAll()
        insertAll(items)
    }
}
