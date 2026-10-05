// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.4
package com.galker.pointandidentify.domain

import com.galker.pointandidentify.config.AppConfig
import com.galker.pointandidentify.geo.GeoMath
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/** One target inside the crosshair, with the angular offset of its bearing from the camera axis. */
data class Candidate(
    val evaluation: TargetEvaluation,
    val angleDiffDeg: Double,
    val score: Double // lower is better
)

data class Selection(
    val best: TargetEvaluation?,      // best VISIBLE target inside the crosshair; null when only hidden ones (or none) are there
    val angleDiffDeg: Double?,
    val candidates: List<Candidate> = emptyList() // everything inside the crosshair, visible first, at most AppConfig.CANDIDATES_MAX
)

/**
 * Chooses the pointed target from precomputed evaluations. Cheap enough to run on every sensor
 * update: no terrain access here, LOS results are computed once per location change.
 */
object TargetSelector {

    /**
     * Only targets inside the crosshair circle are considered: the camera-axis bearing must lie within the circle's
     * angular radius plus the target's own angular half-width (a large target overlaps the circle before its centre does).
     * The live view shows [Selection.best] (visible only); [Selection.candidates] also holds hidden / unknown targets
     * for the list that opens on a tap on the crosshair.
     *
     * effectiveHfovDeg: visible horizontal FOV after zoom; zoomRatio: total zoom (the crosshair grows with it).
     * cameraElevationDeg (optional): when given, a target whose apparent vertical angle is far from the crosshair
     * ranks slightly lower. Tie-breaker only, never a filter: the user may legitimately aim at a target's base.
     */
    fun select(
        evaluations: List<TargetEvaluation>,
        azimuthDeg: Double,
        effectiveHfovDeg: Double,
        zoomRatio: Double,
        cameraElevationDeg: Double? = null
    ): Selection {
        val circleDeg = CrosshairWindow.halfAngleDeg(effectiveHfovDeg, zoomRatio)
        val visible = ArrayList<Candidate>()
        val other = ArrayList<Candidate>()

        for (e in evaluations) {
            val diff = GeoMath.angleDiffDeg(azimuthDeg, e.bearingDeg)
            val window = windowDeg(e.distanceM, e.target.targetKind.radiusM, circleDeg)
            if (diff > window) continue
            // Score: angular error normalised by the window; closer targets win ties.
            var score = diff / window + e.distanceM / AppConfig.MAX_TARGET_RANGE_M * 0.1
            if (cameraElevationDeg != null && e.elevationAngleDeg != null) {
                val vMismatch = min(abs(cameraElevationDeg - e.elevationAngleDeg), AppConfig.VERTICAL_TIEBREAK_CAP_DEG)
                score += AppConfig.VERTICAL_TIEBREAK_WEIGHT * vMismatch / AppConfig.VERTICAL_TIEBREAK_CAP_DEG
            }
            val c = Candidate(e, diff, score)
            if (e.visibility == Visibility.VISIBLE) visible.add(c) else other.add(c)
        }

        // Visible targets always rank above hidden / unknown ones, each group by score.
        val ranked = (visible.sortedBy { it.score } + other.sortedBy { it.score }).take(AppConfig.CANDIDATES_MAX)
        val best = visible.minByOrNull { it.score }
        return Selection(best?.evaluation, best?.angleDiffDeg, ranked)
    }

    /**
     * Acceptance half-angle of one target: the crosshair circle's angular radius plus the target's angular
     * half-width, atan(radius / distance).
     * Example (settlement, 400 m, circle 4.8 deg): at 2 km -> 4.8 + 11.3 = 16.1 deg; at 15 km -> 4.8 + 1.5 = 6.3 deg.
     */
    fun windowDeg(distanceM: Double, radiusM: Double, circleDeg: Double): Double =
        circleDeg + Math.toDegrees(atan(radiusM / max(distanceM, 1.0)))

    /**
     * Horizontal FOV after zoom. Zoom crops the sensor image, so the half-angle tangent shrinks
     * by the zoom ratio: hfov_z = 2 * atan(tan(hfov / 2) / zoom). Ratios below 1 are treated as 1.
     */
    fun effectiveHfovDeg(baseHfovDeg: Double, zoomRatio: Double): Double {
        val z = max(zoomRatio, 1.0)
        return Math.toDegrees(2.0 * atan(tan(Math.toRadians(baseHfovDeg / 2.0)) / z))
    }
}
