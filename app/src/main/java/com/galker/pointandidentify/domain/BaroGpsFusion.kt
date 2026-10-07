// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.1
package com.galker.pointandidentify.domain

import com.galker.pointandidentify.config.AppConfig
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/** Observer altitude, m MSL, with its standard deviation. */
data class AltitudeEstimate(val altM: Double, val sigmaM: Double)

/**
 * Altitude from barometer + GPS.
 *   GPS altitude is absolute but noisy (typically +-10..30 m, worse indoors).
 *   Barometric (pressure) altitude is precise for changes (about 1 m: floors, lifts, stairs) but drifts with the weather.
 * Model: altitude = pressureAltitude + offset. The offset is a slowly drifting value, estimated by a
 * one-dimensional Kalman filter from GPS: z = gpsAltitude - pressureAltitude.
 *   predict : variance grows with time (weather drift, ALT_BARO_DRIFT_M_PER_SQRT_S)
 *   update  : weighted by the GPS vertical accuracy; implausible jumps (indoor GPS) are rejected by innovation gating
 * The reported sigma never falls below ALT_MIN_SIGMA_M: GPS errors are correlated in time, so the filter alone is overconfident.
 */
class BaroGpsFusion {

    private var offsetM = 0.0
    private var varianceM2 = 0.0
    private var lastUpdateMs = 0L
    private var initialized = false

    @Synchronized
    fun reset() {
        initialized = false
    }

    /** One GPS fix together with the pressure measured at the same time. */
    @Synchronized
    fun update(nowMs: Long, gpsAltM: Double, gpsSigmaM: Double, pressureHpa: Double) {
        val z = gpsAltM - pressureAltitudeM(pressureHpa)
        val r = gpsSigmaM.coerceIn(AppConfig.ALT_GPS_SIGMA_MIN_M, AppConfig.ALT_GPS_SIGMA_MAX_M).pow(2)
        if (!initialized) {
            offsetM = z
            // Never start more certain than ALT_INITIAL_SIGMA_MIN_M: with a confident but wrong first fix the
            // innovation gate would otherwise reject every correct fix for hours.
            varianceM2 = maxOf(r, AppConfig.ALT_INITIAL_SIGMA_MIN_M.pow(2))
            lastUpdateMs = nowMs
            initialized = true
            return
        }
        val dtSeconds = ((nowMs - lastUpdateMs) / 1000.0).coerceAtLeast(0.0)
        lastUpdateMs = nowMs
        varianceM2 += AppConfig.ALT_BARO_DRIFT_M_PER_SQRT_S.pow(2) * dtSeconds
        // Gating: a GPS altitude far from the prediction (indoors, multipath) is ignored; the barometer keeps the track.
        if (abs(z - offsetM) > AppConfig.ALT_GATE_SIGMAS * sqrt(varianceM2 + r)) return
        val gain = varianceM2 / (varianceM2 + r)
        offsetM += gain * (z - offsetM)
        varianceM2 *= (1.0 - gain)
    }

    /** Current altitude for the present pressure; null before the first GPS + pressure pair. */
    @Synchronized
    fun estimate(pressureHpa: Double): AltitudeEstimate? {
        if (!initialized) return null
        val sigma = sqrt(maxOf(varianceM2, AppConfig.ALT_MIN_SIGMA_M.pow(2)) + AppConfig.ALT_BARO_NOISE_M.pow(2))
        return AltitudeEstimate(pressureAltitudeM(pressureHpa) + offsetM, sigma)
    }

    companion object {
        /** International barometric formula against the standard sea-level pressure; relative changes are what matters. */
        fun pressureAltitudeM(pressureHpa: Double): Double =
            44330.0 * (1.0 - (pressureHpa / AppConfig.SEA_LEVEL_PRESSURE_HPA).pow(1.0 / 5.255))
    }
}
