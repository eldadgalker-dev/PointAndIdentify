// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.2
package com.galker.pointandidentify.domain

import com.galker.pointandidentify.config.AppConfig
import kotlin.math.atan2
import kotlin.math.hypot

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
