// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.1
package com.galker.pointandidentify.domain

import com.galker.pointandidentify.config.AppConfig
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Observer eye altitude, m MSL, in two flavours:
 *   bestM  - the unbiased estimate; it feeds every ANGLE (elevation to the target, crosshair match), where a bias shifts the answer.
 *   losM   - the cautious estimate; it feeds the OBSTRUCTION test only, where an optimistic (too high) eye would call hidden places visible.
 *   sigmaM - the standard deviation of bestM.
 */
data class EyeAltitude(val bestM: Double, val losM: Double, val sigmaM: Double)

/**
 * Combines the two independent altitude evidences (the terrain one is a prior that is wrong above ground level, hence the cases below):
 *   terrain = DEM ground + the height above ground chosen in Settings (sigma AppConfig.DEM_GROUND_SIGMA_M: terrain model and position error);
 *   sensor  = barometer + GPS (BaroGpsFusion, its own sigma).
 * Cases, decided by the difference d = sensor - terrain against its combined sigma:
 *   consistent (|d| within ALT_CONSISTENCY_SIGMAS): both agree, so they are averaged with inverse-variance weights - the result
 *            is more certain than either of them;
 *   elevated (d above the gate): a high floor or a roof; the terrain model cannot know it, so the sensor is the only evidence;
 *   lower   (d below the gate): GPS multipath or a terrain-model error; the sensor is not trusted to LOWER the observer.
 */
object EyeAltitudeModel {

    fun estimate(groundM: Double, userHeightM: Double, sensor: AltitudeEstimate?): EyeAltitude {
        val base = groundM + userHeightM
        val demSigma = AppConfig.DEM_GROUND_SIGMA_M
        if (sensor == null || sensor.sigmaM > AppConfig.ALT_AUTO_MAX_SIGMA_M) return EyeAltitude(base, base, demSigma)

        val cap = groundM + AppConfig.OBSERVER_HEIGHT_MAX_M
        // Cautious altitude for the obstruction test: the sensor lower bound may only RAISE the terrain-based eye.
        val los = max(base, sensor.altM - AppConfig.ALT_LOWER_BOUND_SIGMAS * sensor.sigmaM).coerceAtMost(cap)

        val d = sensor.altM - base
        val varSum = demSigma * demSigma + sensor.sigmaM * sensor.sigmaM
        val gate = AppConfig.ALT_CONSISTENCY_SIGMAS * sqrt(varSum)
        return when {
            d > gate -> EyeAltitude(sensor.altM.coerceIn(base, cap), los, sensor.sigmaM)
            d < -gate -> EyeAltitude(base, base, sqrt(varSum))
            else -> {
                val weightOfTerrain = sensor.sigmaM * sensor.sigmaM / varSum
                val best = (sensor.altM - d * weightOfTerrain).coerceAtMost(cap)
                EyeAltitude(best, los, sqrt(demSigma * demSigma * sensor.sigmaM * sensor.sigmaM / varSum))
            }
        }
    }
}
