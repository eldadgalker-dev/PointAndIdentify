// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.4
package com.galker.pointandidentify.domain

import com.galker.pointandidentify.config.AppConfig
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.tan

/** How the phone is held, with hysteresis so the mode does not flicker around the threshold. */
object PhonePose {

    /**
     * Raised = the camera looks toward the horizon (aiming); flat = the phone lies face up (reading the screen).
     * Between FLAT_BELOW_DEG and RAISED_ABOVE_DEG the previous mode is kept.
     * Camera elevation: 0 = horizon, -90 = camera pointing straight down.
     */
    fun isRaised(previouslyRaised: Boolean, cameraElevationDeg: Double): Boolean = when {
        cameraElevationDeg > AppConfig.RAISED_ABOVE_DEG -> true
        cameraElevationDeg < AppConfig.FLAT_BELOW_DEG -> false
        else -> previouslyRaised
    }
}

/**
 * When the current-city display replaces the normal target selection.
 * The camera axis, ray-cast against the terrain, decides: ground met near the observer = the user looks at the place
 * he stands in. The plain "below the horizon" rule is geometry-blind (from a mountain, 8 degrees down is a settlement
 * five kilometres away), so it only acts when the ray found no ground at all and the camera points steeply down.
 */
object CityMode {

    fun active(lookingDown: Boolean, aimNear: Boolean, aimDistanceM: Double?, cameraElevationDeg: Double): Boolean =
        aimNear || (lookingDown && aimDistanceM == null && cameraElevationDeg < AppConfig.CITY_BLIND_BELOW_DEG)

    /** A visible target inside the crosshair that is not farther than the aimed ground point (plus a margin) wins. */
    fun yieldsToTarget(best: TargetEvaluation?, aimDistanceM: Double?): Boolean =
        best != null && best.visibility == Visibility.VISIBLE &&
            (aimDistanceM == null || best.distanceM <= aimDistanceM + AppConfig.CITY_YIELD_MARGIN_M)
}

/** Camera pointing well below the horizon (the street below, or a phone lying on a table). */
object LookingDown {

    /** Hysteresis between CITY_MODE_BELOW_DEG (enter) and CITY_MODE_EXIT_DEG (leave). */
    fun isLookingDown(previously: Boolean, cameraElevationDeg: Double): Boolean = when {
        cameraElevationDeg < AppConfig.CITY_MODE_BELOW_DEG -> true
        cameraElevationDeg > AppConfig.CITY_MODE_EXIT_DEG -> false
        else -> previously
    }
}

/**
 * Where to turn the camera to bring a chosen place into the crosshair.
 * deltaAzimuthDeg  : signed horizontal offset, positive = the place is to the right of the camera axis
 * deltaElevationDeg: signed vertical offset, positive = the place is above the camera axis
 * offsetDeg        : angular distance between the camera axis and the place
 * screenAngleRad   : direction of the offset on the screen, mathematical convention (0 = right, pi/2 = up)
 * inside           : the place is already inside the crosshair circle
 */
data class FindGuide(
    val deltaAzimuthDeg: Double,
    val deltaElevationDeg: Double,
    val offsetDeg: Double,
    val screenAngleRad: Double,
    val inside: Boolean
)

/** Where a direction offset from the camera axis appears on the screen (used for the Find dot). */
object FindProjection {

    /**
     * Pixel offset (right, down) from the screen centre of a place deltaAzimuthDeg to the right and deltaElevationDeg above
     * the camera axis; null when it is behind the camera. The preview (aspect PREVIEW_ASPECT, short side = hfovDeg) is scaled
     * to fill the screen, so one tangent unit = max(height, width / aspect) * aspect / (2 tan(hfov / 2)) * zoom pixels.
     */
    fun screenOffsetPx(
        deltaAzimuthDeg: Double, deltaElevationDeg: Double, hfovDeg: Double, zoomRatio: Double, widthPx: Int, heightPx: Int
    ): Pair<Float, Float>? {
        if (abs(deltaAzimuthDeg) >= MAX_ANGLE_DEG || abs(deltaElevationDeg) >= MAX_ANGLE_DEG) return null
        val filled = max(heightPx.toDouble(), widthPx / AppConfig.PREVIEW_ASPECT)
        val pxPerTan = filled * AppConfig.PREVIEW_ASPECT / (2.0 * tan(Math.toRadians(hfovDeg) / 2.0)) * zoomRatio
        val x = pxPerTan * tan(Math.toRadians(deltaAzimuthDeg))
        val y = -pxPerTan * tan(Math.toRadians(deltaElevationDeg))
        return x.toFloat() to y.toFloat()
    }

    private const val MAX_ANGLE_DEG = 80.0 // beyond this the flat projection is meaningless
}

object FindGuidance {

    /** Signed shortest rotation from azimuth [fromDeg] to [toDeg], range [-180, 180). */
    fun signedDiffDeg(fromDeg: Double, toDeg: Double): Double =
        (((toDeg - fromDeg + 540.0) % 360.0) + 360.0) % 360.0 - 180.0

    /**
     * targetElevationDeg: apparent vertical angle of the place from the observer; null (no terrain data) is taken as
     * the horizon, which is right for distant places and only an estimate for near ones.
     * circleDeg: angular radius of the crosshair circle (see CrosshairWindow).
     */
    fun guide(
        bearingDeg: Double,
        targetElevationDeg: Double?,
        cameraAzimuthDeg: Double,
        cameraElevationDeg: Double,
        circleDeg: Double
    ): FindGuide {
        val dAz = signedDiffDeg(cameraAzimuthDeg, bearingDeg)
        val dEl = (targetElevationDeg ?: 0.0) - cameraElevationDeg
        val offset = hypot(dAz, dEl)
        return FindGuide(dAz, dEl, offset, atan2(dEl, dAz), offset <= circleDeg)
    }
}
