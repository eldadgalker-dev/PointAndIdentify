// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.0
package com.galker.pointandidentify.domain

import com.galker.pointandidentify.config.AppConfig
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

enum class CompassVerdict { CHECKING, OK, NO_SENSOR, UNCALIBRATED, INTERFERENCE, UNSTABLE }

data class CompassReport(
    val verdict: CompassVerdict,
    val fieldUt: Double? = null,   // smoothed magnetic field strength, uT
    val spreadDeg: Double? = null  // circular std-dev of the azimuth over the sampling window, deg
)

/**
 * Compass health rules, kept free of Android classes so they are unit-testable.
 * Checks, in order of severity:
 *   1. no samples at all                      -> NO_SENSOR
 *   2. field strength outside Earth's range   -> INTERFERENCE (metal / magnet nearby)
 *   3. sensor reports low accuracy            -> UNCALIBRATED (figure-8 motion needed)
 *   4. azimuth jitter above the limit         -> UNSTABLE (phone moved or noisy sensor)
 */
object CompassCheck {

    fun evaluate(azimuthsDeg: List<Double>, fieldUt: Double?, calibrated: Boolean): CompassReport {
        if (azimuthsDeg.isEmpty()) return CompassReport(CompassVerdict.NO_SENSOR, fieldUt, null)
        val spread = circularStdDevDeg(azimuthsDeg)
        val verdict = when {
            fieldUt != null && (fieldUt < AppConfig.COMPASS_FIELD_MIN_UT || fieldUt > AppConfig.COMPASS_FIELD_MAX_UT) ->
                CompassVerdict.INTERFERENCE
            !calibrated -> CompassVerdict.UNCALIBRATED
            spread > AppConfig.COMPASS_SPREAD_MAX_DEG -> CompassVerdict.UNSTABLE
            else -> CompassVerdict.OK
        }
        return CompassReport(verdict, fieldUt, spread)
    }

    /** Circular standard deviation: sqrt(-2 ln R), R = length of the mean unit vector. Wrap-safe at 359 -> 0. */
    fun circularStdDevDeg(anglesDeg: List<Double>): Double {
        if (anglesDeg.size < 2) return 0.0
        var sumSin = 0.0
        var sumCos = 0.0
        for (a in anglesDeg) {
            val r = Math.toRadians(a)
            sumSin += sin(r)
            sumCos += cos(r)
        }
        val n = anglesDeg.size.toDouble()
        val resultant = sqrt(sumSin * sumSin + sumCos * sumCos) / n
        if (resultant >= 1.0) return 0.0
        if (resultant <= 1e-9) return 180.0 // uniformly spread: no direction at all
        return Math.toDegrees(sqrt(-2.0 * ln(resultant)))
    }
}
