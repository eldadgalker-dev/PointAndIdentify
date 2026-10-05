// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 2.3
package com.galker.pointandidentify.domain

import com.galker.pointandidentify.config.AppConfig
import com.galker.pointandidentify.data.db.TargetEntity
import com.galker.pointandidentify.geo.GeoMath
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.max

// =============================================================
// Line-of-sight model
//   Frame      : observer tangent plane; x = horizontal distance from observer along the path
//   Terrain    : z_eff(x) = DEM(x) - drop(x), drop = x^2 / (2 * R / (1 - k))  (curvature + refraction)
//   Sight line : straight segment from h0 (observer eye) to h1_eff (target top minus drop(D))
//   Target top : ground (DEM or catalogue, per TargetKind) + structure height (catalogue or kind default)
//   Obstructed : any sample with z_eff(x) > line(x) + LOS_CLEARANCE_TOLERANCE_M (DEM noise), excluding the kind's footprint before the target
//   Unknown    : no obstruction found, but at least one sample had no terrain data
//   Elevation  : apparent vertical angle to the target top, atan2(h1_eff - h0, D)
// =============================================================

enum class Visibility { VISIBLE, OBSTRUCTED, UNKNOWN }

data class TargetEvaluation(
    val target: TargetEntity,
    val bearingDeg: Double,
    val distanceM: Double,
    val visibility: Visibility,
    val groundAltM: Double? = null,         // target ground or summit, m MSL
    val topAltM: Double? = null,            // ground + structure height, m MSL
    val elevationAngleDeg: Double? = null,  // apparent vertical angle from the observer eye
    val obstructionDistanceM: Double? = null,
    val minClearanceM: Double? = null,      // smallest (line - terrain) margin along the path, m
    val groundAngleDeg: Double? = null      // apparent vertical angle to the target's ground (elevationAngleDeg is its top)
)

/** Terrain lookup abstraction: returns metres MSL or null when unknown. */
fun interface TerrainSource {
    fun elevationM(lat: Double, lon: Double): Double?
}

class LineOfSightCalculator(private val terrain: TerrainSource) {

    fun evaluate(
        observerLat: Double,
        observerLon: Double,
        observerEyeAltM: Double,
        target: TargetEntity
    ): TargetEvaluation {
        val kind = target.targetKind
        val distance = GeoMath.distanceM(observerLat, observerLon, target.latitude, target.longitude)
        val bearing = GeoMath.bearingDeg(observerLat, observerLon, target.latitude, target.longitude)

        val dem = terrain.elevationM(target.latitude, target.longitude)
        val ground = (if (kind.catalogAltFirst) target.altitudeM ?: dem else dem ?: target.altitudeM)
            ?: return TargetEvaluation(target, bearing, distance, Visibility.UNKNOWN)
        val top = ground + (target.heightM ?: kind.defaultHeightM)

        val h0 = observerEyeAltM
        val h1Eff = top - GeoMath.curvatureDropM(distance)
        val elevation = Math.toDegrees(atan2(h1Eff - h0, max(distance, 1.0)))
        val groundAngle = Math.toDegrees(atan2(ground - GeoMath.curvatureDropM(distance) - h0, max(distance, 1.0)))

        // Sample count follows DEM resolution so narrow ridges are not skipped.
        val n = max(2, ceil(distance / AppConfig.DEM_SAMPLE_SPACING_M).toInt())
        var unknown = false
        var minClearance = Double.POSITIVE_INFINITY

        for (i in 1 until n) {
            val f = i.toDouble() / n
            val x = f * distance
            if (distance - x < kind.exclusionM) break

            // Linear lat/lon interpolation: lateral deviation from the great circle stays at metre level
            // up to the 50 km range, well below the 30 m DEM cell.
            val lat = observerLat + f * (target.latitude - observerLat)
            val lon = observerLon + f * (target.longitude - observerLon)
            val z = terrain.elevationM(lat, lon)
            if (z == null) {
                unknown = true
                continue
            }
            val zEff = z - GeoMath.curvatureDropM(x)
            val line = h0 + f * (h1Eff - h0)
            val clearance = line - zEff
            if (clearance < minClearance) minClearance = clearance
            // Terrain-model noise tolerance: a blocker within LOS_CLEARANCE_TOLERANCE_M of the line is not a real obstruction.
            if (clearance < -AppConfig.LOS_CLEARANCE_TOLERANCE_M) {
                return TargetEvaluation(
                    target, bearing, distance, Visibility.OBSTRUCTED,
                    ground, top, elevation, x, clearance, groundAngle
                )
            }
        }

        val visibility = if (unknown) Visibility.UNKNOWN else Visibility.VISIBLE
        val clearanceOut = if (minClearance.isFinite()) minClearance else null
        return TargetEvaluation(target, bearing, distance, visibility, ground, top, elevation, null, clearanceOut, groundAngle)
    }
}
