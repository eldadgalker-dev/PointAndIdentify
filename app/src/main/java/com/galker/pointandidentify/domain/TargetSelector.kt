// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.2
package com.galker.pointandidentify.domain

import com.galker.pointandidentify.config.AppConfig
import com.galker.pointandidentify.geo.GeoMath
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min

data class Selection(
    val best: TargetEvaluation?,      // best target the camera points at (visible preferred)
    val angleDiffDeg: Double?
)

/**
 * Chooses the pointed target from precomputed evaluations. Cheap enough to run on every sensor
 * update: no terrain access here, LOS results are computed once per location change.
 */
object TargetSelector {

    fun select(evaluations: List<TargetEvaluation>, azimuthDeg: Double, hfovDeg: Double): Selection {
        var bestVisible: Pair<TargetEvaluation, Double>? = null
        var bestOther: Pair<TargetEvaluation, Double>? = null

        for (e in evaluations) {
            val diff = GeoMath.angleDiffDeg(azimuthDeg, e.bearingDeg)
            val tol = toleranceDeg(e.distanceM, e.target.targetKind.radiusM, hfovDeg)
            if (diff > tol) continue
            // Score: angular error normalised by tolerance; closer targets win ties.
            val score = diff / tol + e.distanceM / AppConfig.MAX_TARGET_RANGE_M * 0.1
            if (e.visibility == Visibility.VISIBLE) {
                if (bestVisible == null || score < bestVisible.second) bestVisible = e to score
            } else {
                if (bestOther == null || score < bestOther.second) bestOther = e to score
            }
        }

        val chosen = (bestVisible ?: bestOther)?.first
        return Selection(chosen, chosen?.let { GeoMath.angleDiffDeg(azimuthDeg, it.bearingDeg) })
    }

    /**
     * Acceptance half-angle: the larger of the compass error floor and the target's angular
     * half-width (atan(radius / distance)), clamped to half the camera horizontal FOV.
     * Example (settlement, 400 m): at 2 km -> 11.3 deg; at 15 km -> 1.5 deg -> floor 3 deg applies.
     */
    fun toleranceDeg(distanceM: Double, radiusM: Double, hfovDeg: Double): Double {
        val angular = Math.toDegrees(atan(radiusM / max(distanceM, 1.0)))
        return min(max(AppConfig.BASE_TOLERANCE_DEG, angular), hfovDeg / 2.0)
    }
}
