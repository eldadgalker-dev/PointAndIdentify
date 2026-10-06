// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.0
package com.galker.pointandidentify.domain

import com.galker.pointandidentify.config.AppConfig
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Low-pass filter whose strength depends on the size of the change: tiny changes (hand tremor, sensor noise) are
 * smoothed hard, a real turn passes almost unfiltered. circular = true for angles that wrap at 360 degrees.
 */
class AdaptiveAngleSmoother(private val circular: Boolean) {

    private var value = 0.0
    private var initialized = false

    fun reset() {
        initialized = false
    }

    fun update(input: Double): Double {
        if (!initialized) {
            value = input
            initialized = true
            return value
        }
        val delta = if (circular) signedDiffDeg(value, input) else input - value
        val alpha = AppConfig.SMOOTH_ALPHA_MIN +
            (AppConfig.SMOOTH_ALPHA_MAX - AppConfig.SMOOTH_ALPHA_MIN) * min(abs(delta) / AppConfig.SMOOTH_FAST_DEG, 1.0)
        value += alpha * delta
        if (circular) value = ((value % 360.0) + 360.0) % 360.0
        return value
    }

    private fun signedDiffDeg(from: Double, to: Double) = (((to - from + 540.0) % 360.0) + 360.0) % 360.0 - 180.0
}

/**
 * Heading from two sources. The gyroscope-only rotation vector is smooth and fast but has an arbitrary zero and drifts;
 * the magnetic rotation vector is absolute (true north) but noisy and easily disturbed by metal. The result is the
 * gyroscope azimuth plus a slowly adapting offset towards the magnetic azimuth (time constant HEADING_MAG_TAU_S). The
 * offset only follows the magnetic reading while it is trusted (field strength in the Earth range, sensor calibrated).
 */
class HeadingFusion {

    private var offsetSin = 0.0
    private var offsetCos = 1.0
    private var initialized = false

    fun reset() {
        initialized = false
    }

    /** gameAzDeg: gyroscope azimuth; magTrueAzDeg: magnetic azimuth in true north (null before the first sample). */
    fun fuse(gameAzDeg: Double, magTrueAzDeg: Double?, magTrusted: Boolean, dtSeconds: Double): Double {
        if (magTrueAzDeg != null) {
            val diff = Math.toRadians(magTrueAzDeg - gameAzDeg)
            if (!initialized) {
                offsetSin = sin(diff)
                offsetCos = cos(diff)
                initialized = true
            } else if (magTrusted) {
                val alpha = 1.0 - exp(-dtSeconds.coerceIn(0.0, MAX_DT_S) / AppConfig.HEADING_MAG_TAU_S)
                offsetSin += alpha * (sin(diff) - offsetSin)
                offsetCos += alpha * (cos(diff) - offsetCos)
            }
        }
        val offsetDeg = Math.toDegrees(atan2(offsetSin, offsetCos))
        return (((gameAzDeg + offsetDeg) % 360.0) + 360.0) % 360.0
    }

    private companion object {
        const val MAX_DT_S = 0.5 // a longer gap (sensor paused) must not make one sample dominate
    }
}
