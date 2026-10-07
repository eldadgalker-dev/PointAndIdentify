// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.6
package com.galker.pointandidentify.domain

import com.galker.pointandidentify.config.AppConfig
import com.galker.pointandidentify.geo.GeoMath
import kotlin.math.hypot
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
    val candidates: List<Candidate> = emptyList(), // everything inside the crosshair, visible first, at most AppConfig.CANDIDATES_MAX
    val all: List<Candidate> = emptyList()         // the same without the limit (the stabilizer looks held targets up here)
)

/**
 * Chooses the pointed target from precomputed evaluations. Cheap enough to run on every sensor
 * update: no terrain access here, LOS results are computed once per location change.
 */
object TargetSelector {

    /**
     * Only targets inside the crosshair circle are considered, in BOTH directions:
     *   horizontally : the bearing of the target (widened by its angular half-width, atan(radius / distance));
     *   vertically   : the target's apparent vertical extent, from its ground angle to its top angle (plus
     *                  VERTICAL_MARGIN_DEG), compared with the camera elevation.
     * The target is inside when the circle (radius = CrosshairWindow.halfAngleDeg) touches that angular rectangle.
     * A camera pointed at the sky or at the street therefore selects nothing, even when the bearing matches.
     * Without a camera elevation, or for a target without terrain data (no vertical angle), only the bearing is tested.
     * The live view shows [Selection.best] (visible only); [Selection.candidates] also holds hidden / unknown targets
     * for the list that opens on a tap on the crosshair.
     *
     * effectiveHfovDeg: visible horizontal FOV after zoom; zoomRatio: total zoom (the crosshair grows with it).
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
            val halfWidthDeg = Math.toDegrees(atan(e.target.targetKind.radiusM / max(e.distanceM, 1.0)))
            val dAz = GeoMath.angleDiffDeg(azimuthDeg, e.bearingDeg)
            val dx = max(0.0, dAz - halfWidthDeg)

            // Vertical extent of the target as seen from the observer; null when it cannot be computed.
            val top = e.elevationAngleDeg
            val ground = e.groundAngleDeg ?: top
            var dy = 0.0
            var dyCentre = 0.0
            if (cameraElevationDeg != null && top != null && ground != null) {
                val lo = min(top, ground) - AppConfig.VERTICAL_MARGIN_DEG
                val hi = max(top, ground) + AppConfig.VERTICAL_MARGIN_DEG
                dy = when {
                    cameraElevationDeg < lo -> lo - cameraElevationDeg
                    cameraElevationDeg > hi -> cameraElevationDeg - hi
                    else -> 0.0
                }
                dyCentre = cameraElevationDeg - (top + ground) / 2.0
            }
            if (hypot(dx, dy) > circleDeg) continue

            // Score: angular offset of the target centre, normalised by the window; closer targets win ties.
            val offset = hypot(dAz, dyCentre)
            val window = circleDeg + halfWidthDeg
            val score = offset / window + e.distanceM / AppConfig.MAX_TARGET_RANGE_M * 0.1
            val c = Candidate(e, offset, score)
            if (e.visibility == Visibility.VISIBLE) visible.add(c) else other.add(c)
        }

        // Visible targets always rank above hidden / unknown ones, each group by score.
        val all = visible.sortedBy { it.score } + other.sortedBy { it.score }
        val best = visible.minByOrNull { it.score }
        return Selection(best?.evaluation, best?.angleDiffDeg, all.take(AppConfig.CANDIDATES_MAX), all)
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
