// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.2
package com.galker.pointandidentify.domain

// =============================================================
// Parameters: per-kind target geometry
//   defaultHeightM : visible structure above ground used as the LOS target top when the
//                    catalogue gives no height (m); 0 for summits (the point IS the ground)
//   radiusM        : nominal half-width used for the angular acceptance window (m)
//   exclusionM     : terrain ignored just before the target, its own footprint (m)
//   catalogAltFirst: true = catalogue altitude preferred over DEM (summits: DEM smooths peaks down)
// =============================================================
enum class TargetKind(
    val code: String,
    val defaultHeightM: Double,
    val radiusM: Double,
    val exclusionM: Double,
    val catalogAltFirst: Boolean
) {
    SETTLEMENT("settlement", 10.0, 400.0, 150.0, false),
    PEAK("peak", 0.0, 150.0, 150.0, true),
    TOWER("tower", 30.0, 30.0, 60.0, false),
    LIGHTHOUSE("lighthouse", 20.0, 30.0, 60.0, false),
    CHIMNEY("chimney", 50.0, 30.0, 60.0, false),
    WATER_TOWER("water_tower", 25.0, 20.0, 50.0, false),
    CASTLE("castle", 10.0, 100.0, 100.0, false),
    RUINS("ruins", 5.0, 80.0, 100.0, false),
    MONUMENT("monument", 10.0, 30.0, 60.0, false),
    PRIVATE("private", 10.0, 50.0, 50.0, false), // user-defined point, see data/PrivatePointStore.kt
    OTHER("other", 5.0, 50.0, 80.0, false);

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun of(code: String?): TargetKind = byCode[code] ?: OTHER
    }
}
