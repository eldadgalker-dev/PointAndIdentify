// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.2
package com.galker.pointandidentify.geo

import com.galker.pointandidentify.config.AppConfig
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Pure geodesy helpers on a spherical Earth (adequate for ranges below ~50 km). */
object GeoMath {

    private const val DEG_TO_RAD = Math.PI / 180.0
    private const val RAD_TO_DEG = 180.0 / Math.PI
    const val METERS_PER_DEG_LAT = 111_320.0 // m per degree of latitude (mean)

    /** Great-circle distance by the haversine formula, metres. */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = lat1 * DEG_TO_RAD
        val p2 = lat2 * DEG_TO_RAD
        val dp = (lat2 - lat1) * DEG_TO_RAD
        val dl = (lon2 - lon1) * DEG_TO_RAD
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2.0 * AppConfig.EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Initial great-circle bearing from point 1 to point 2, degrees in [0, 360). */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = lat1 * DEG_TO_RAD
        val p2 = lat2 * DEG_TO_RAD
        val dl = (lon2 - lon1) * DEG_TO_RAD
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return normalizeDeg(atan2(y, x) * RAD_TO_DEG)
    }

    /** Wraps any angle into [0, 360). */
    fun normalizeDeg(deg: Double): Double {
        val r = deg % 360.0
        val n = if (r < 0) r + 360.0 else r
        return if (n >= 360.0) 0.0 else n // r + 360 rounds to exactly 360 for a tiny negative r
    }

    /** Smallest absolute difference between two azimuths, degrees in [0, 180]. */
    fun angleDiffDeg(a: Double, b: Double): Double {
        val d = abs(normalizeDeg(a) - normalizeDeg(b))
        return if (d > 180.0) 360.0 - d else d
    }

    /**
     * Height drop of the Earth surface below the observer's tangent plane at distance x,
     * reduced by atmospheric refraction: drop = x^2 / (2 * R_eff), R_eff = R / (1 - k).
     * Example: x = 15 km, k = 0.13 -> ~15.4 m.
     */
    fun curvatureDropM(distanceM: Double): Double {
        val rEff = AppConfig.EARTH_RADIUS_M / (1.0 - AppConfig.REFRACTION_K)
        return distanceM * distanceM / (2.0 * rEff)
    }

    /** Degrees of longitude spanned by a metric distance at the given latitude. */
    fun metersToLonDeg(meters: Double, atLat: Double): Double =
        meters / (METERS_PER_DEG_LAT * cos(atLat * DEG_TO_RAD))

    fun metersToLatDeg(meters: Double): Double = meters / METERS_PER_DEG_LAT
}
