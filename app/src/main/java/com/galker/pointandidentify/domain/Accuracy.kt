// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.0
package com.galker.pointandidentify.domain

import com.galker.pointandidentify.config.AppConfig
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.max

/** Where a position fix comes from: the phone's own location service (fused) or the native GNSS (GPS) provider. */
enum class FixSource { SYSTEM, GPS }

/** What the selection needs to know about one source: reported accuracy (1 sigma, m) and age (ms). */
data class SourceFix(val accuracyM: Float, val ageMs: Long)

/**
 * Chooses the position source. The phone's location service (fused: GNSS + Wi-Fi + cell + inertial sensors) is the
 * primary source, as in the map and camera apps. The native GNSS fix replaces it only when it is fresh and clearly
 * more accurate (at most GPS_OVERRIDES_SYSTEM_RATIO of the system accuracy); a stale fix is never offered.
 */
object FixSelection {

    fun choose(system: SourceFix?, gps: SourceFix?): FixSource? {
        val s = system?.takeIf { it.ageMs <= AppConfig.LOCATION_SOURCE_MAX_AGE_MS }
        val g = gps?.takeIf { it.ageMs <= AppConfig.LOCATION_SOURCE_MAX_AGE_MS }
        return when {
            s == null && g == null -> null
            s == null -> FixSource.GPS
            g == null -> FixSource.SYSTEM
            g.accuracyM <= s.accuracyM * AppConfig.GPS_OVERRIDES_SYSTEM_RATIO -> FixSource.GPS
            else -> FixSource.SYSTEM
        }
    }
}

/**
 * Uncertainty of the pointing, all 1-sigma values:
 *   headingSigmaDeg : compass / rotation-vector heading error
 *   pitchSigmaDeg   : camera elevation error
 *   positionSigmaM  : horizontal position error of the observer
 *   altitudeSigmaM  : error of the observer eye altitude
 */
data class PointingAccuracy(
    val headingSigmaDeg: Double = AppConfig.HEADING_SIGMA_DEFAULT_DEG,
    val pitchSigmaDeg: Double = AppConfig.PITCH_SIGMA_DEG,
    val positionSigmaM: Double = AppConfig.LOCATION_DEFAULT_ACCURACY_M.toDouble(),
    val altitudeSigmaM: Double = AppConfig.ALTITUDE_SIGMA_DEFAULT_M
)

object PointingUncertainty {

    /** Heading error to use: the value the sensor reports (bounded), or a default; a not calibrated compass is never better than the fixed floor. */
    fun headingSigmaDeg(reportedDeg: Double?, calibrated: Boolean): Double {
        val base = reportedDeg?.coerceIn(AppConfig.HEADING_SIGMA_MIN_DEG, AppConfig.HEADING_SIGMA_MAX_DEG)
            ?: AppConfig.HEADING_SIGMA_DEFAULT_DEG
        return if (calibrated) base else max(base, AppConfig.HEADING_SIGMA_UNCALIBRATED_DEG)
    }

    /** Angular uncertainty of the bearing to a target at [distanceM]: heading error plus the angle that the position error subtends. */
    fun azimuthSigmaDeg(distanceM: Double, accuracy: PointingAccuracy): Double =
        hypot(accuracy.headingSigmaDeg, Math.toDegrees(atan(accuracy.positionSigmaM / max(distanceM, 1.0))))

    /** Angular uncertainty of the elevation angle to a target at [distanceM]: pitch error plus the angle that the altitude error subtends. */
    fun elevationSigmaDeg(distanceM: Double, accuracy: PointingAccuracy): Double =
        hypot(accuracy.pitchSigmaDeg, Math.toDegrees(atan(accuracy.altitudeSigmaM / max(distanceM, 1.0))))
}
