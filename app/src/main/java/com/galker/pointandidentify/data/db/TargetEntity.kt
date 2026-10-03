// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.1
package com.galker.pointandidentify.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.galker.pointandidentify.domain.TargetKind

/**
 * Anything the camera can be pointed at: settlement, summit, tower, castle...
 * altitudeM: catalogue ground/summit altitude (m MSL), optional.
 * heightM  : structure height above ground (m), optional; TargetKind default applies when null.
 */
@Entity(tableName = "targets", indices = [Index("latitude"), Index("longitude")])
data class TargetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: String,
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Double?,
    val heightM: Double?
) {
    val targetKind: TargetKind get() = TargetKind.of(kind)
}
