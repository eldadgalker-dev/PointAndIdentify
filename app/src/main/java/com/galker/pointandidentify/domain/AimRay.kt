// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.1
package com.galker.pointandidentify.domain

import com.galker.pointandidentify.config.AppConfig
import com.galker.pointandidentify.geo.GeoMath
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Where the camera axis meets the ground. For a camera pointing down this is the radius of the "current location
 * circle": the ground within that distance of the observer is what the user is looking down at.
 * The ray is cast over the terrain model, so the real height above the aimed ground counts, not the height above the
 * ground under the observer: on the edge of a 100 m hill the ray meets the valley floor far from the foot of the hill,
 * and lowering the camera shrinks the circle down to a few hundred metres.
 */
object AimRay {

    /**
     * Horizontal distance, m, at which the camera axis (azimuth, elevation) first meets the terrain; null when the camera
     * does not point down, when the ray meets no terrain within AIM_RAY_MAX_M, or when terrain data is missing on the way.
     * Terrain is lowered by the curvature + refraction drop, like in the line-of-sight model. The crossing is interpolated
     * between two samples, so the result is finer than the DEM_SAMPLE_SPACING_M step.
     */
    fun groundDistanceM(
        terrain: TerrainSource,
        lat: Double,
        lon: Double,
        eyeAltM: Double,
        azimuthDeg: Double,
        elevationDeg: Double
    ): Double? {
        if (elevationDeg >= -MIN_DOWN_DEG) return null
        val slope = tan(Math.toRadians(elevationDeg)) // negative
        val az = Math.toRadians(azimuthDeg)
        val dxLat = cos(az)
        val dxLon = sin(az)
        var previousX = 0.0
        val groundHere = terrain.elevationM(lat, lon) ?: return null
        // An eye at or below the model terrain (height 0 chosen, or DEM noise) would put the ground at distance 0.
        val eye = maxOf(eyeAltM, groundHere + AppConfig.AIM_MIN_EYE_ABOVE_GROUND_M)
        var previousGap = eye - groundHere // ray height minus terrain height
        var x = AppConfig.DEM_SAMPLE_SPACING_M
        while (x <= AppConfig.AIM_RAY_MAX_M) {
            val la = lat + GeoMath.metersToLatDeg(x * dxLat)
            val lo = lon + GeoMath.metersToLonDeg(x * dxLon, lat)
            val z = terrain.elevationM(la, lo) ?: return null
            val gap = eye + x * slope - (z - GeoMath.curvatureDropM(x))
            if (gap <= 0.0) {
                // Linear crossing between the last sample above the terrain and this one below it.
                val t = previousGap / (previousGap - gap)
                return previousX + t * (x - previousX)
            }
            previousX = x
            previousGap = gap
            x += AppConfig.DEM_SAMPLE_SPACING_M
        }
        return null
    }

    private const val MIN_DOWN_DEG = 0.05 // deg, a camera this close to the horizon is treated as not pointing down
}
