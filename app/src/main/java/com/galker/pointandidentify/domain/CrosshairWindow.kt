// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.0
package com.galker.pointandidentify.domain

import com.galker.pointandidentify.config.AppConfig
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.tan

/**
 * Geometry of the crosshair circle, shared by the drawing and by the target selection, so the target
 * "inside the crosshair" is exactly the target the user sees inside the circle.
 */
object CrosshairWindow {

    /** Crosshair scale for a zoom ratio: grows with zoom, never below 1, capped. */
    fun scale(zoomRatio: Double): Double =
        max(1.0, zoomRatio).pow(AppConfig.CROSSHAIR_ZOOM_EXPONENT).coerceAtMost(AppConfig.CROSSHAIR_SCALE_MAX)

    /**
     * Angular radius of the crosshair circle, deg.
     * The circle radius is r = RATIO * scale * W (W = shorter screen edge = view width in portrait), and the image
     * half-width W/2 spans tan(hfov / 2), so tan(angle) = r / (W / 2) * tan(hfov / 2) = 2 * RATIO * scale * tan(hfov / 2).
     * effectiveHfovDeg: visible horizontal FOV already divided by the zoom.
     */
    fun halfAngleDeg(effectiveHfovDeg: Double, zoomRatio: Double): Double {
        val t = 2.0 * AppConfig.CROSSHAIR_RADIUS_RATIO * scale(zoomRatio) * tan(Math.toRadians(effectiveHfovDeg / 2.0))
        return Math.toDegrees(atan(t))
    }
}
