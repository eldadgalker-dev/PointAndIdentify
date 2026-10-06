// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.0
package com.galker.pointandidentify

import com.galker.pointandidentify.data.db.TargetEntity
import com.galker.pointandidentify.domain.CityMode
import com.galker.pointandidentify.domain.TargetEvaluation
import com.galker.pointandidentify.domain.Visibility
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CityModeTest {

    private fun eval(distanceM: Double, visibility: Visibility = Visibility.VISIBLE) = TargetEvaluation(
        target = TargetEntity(name = "T", kind = "tower", latitude = 32.0, longitude = 35.0, altitudeM = 0.0, heightM = null),
        bearingDeg = 90.0,
        distanceM = distanceM,
        visibility = visibility
    )

    @Test
    fun groundMetNearTheObserverShowsTheCurrentPlace() {
        assertTrue(CityMode.active(lookingDown = false, aimNear = true, aimDistanceM = 300.0, cameraElevationDeg = -20.0))
    }

    @Test
    fun lookingDownFromAMountainDoesNotHideDistantTargets() {
        // 8 degrees down from a summit: the ray meets no ground within the ray limit (aim = null), the old rule fired here.
        assertFalse(CityMode.active(lookingDown = true, aimNear = false, aimDistanceM = null, cameraElevationDeg = -8.0))
    }

    @Test
    fun groundFarAwayDoesNotShowTheCurrentPlace() {
        assertFalse(CityMode.active(lookingDown = true, aimNear = false, aimDistanceM = 4_000.0, cameraElevationDeg = -10.0))
    }

    @Test
    fun steeplyDownWithoutTerrainDataStillShowsTheCurrentPlace() {
        // No terrain data: the ray cannot be cast, so a camera pointing steeply down is taken as "looking at the feet".
        assertTrue(CityMode.active(lookingDown = true, aimNear = false, aimDistanceM = null, cameraElevationDeg = -60.0))
    }

    @Test
    fun visibleTargetNearTheAimedGroundBeatsTheCurrentPlace() {
        assertTrue(CityMode.yieldsToTarget(eval(500.0), 500.0))
        assertTrue(CityMode.yieldsToTarget(eval(600.0), 500.0)) // within the margin
        assertTrue(CityMode.yieldsToTarget(eval(2_000.0), null))
    }

    @Test
    fun farOrHiddenOrMissingTargetDoesNotBeatTheCurrentPlace() {
        assertFalse(CityMode.yieldsToTarget(eval(3_000.0), 500.0)) // lowering the camera must never select a farther target
        assertFalse(CityMode.yieldsToTarget(eval(500.0, Visibility.OBSTRUCTED), 500.0))
        assertFalse(CityMode.yieldsToTarget(null, 500.0))
    }
}
