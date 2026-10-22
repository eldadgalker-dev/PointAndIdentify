// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.0
package com.galker.pointandidentify

import com.galker.pointandidentify.data.db.TargetEntity
import com.galker.pointandidentify.domain.FixSelection
import com.galker.pointandidentify.domain.FixSource
import com.galker.pointandidentify.domain.PointingAccuracy
import com.galker.pointandidentify.domain.PointingUncertainty
import com.galker.pointandidentify.domain.SourceFix
import com.galker.pointandidentify.domain.TargetEvaluation
import com.galker.pointandidentify.domain.TargetSelector
import com.galker.pointandidentify.domain.Visibility
import com.galker.pointandidentify.geo.GeoMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccuracyTest {

    @Test
    fun theSystemFixIsPrimaryAndGpsOverridesOnlyWhenClearlyBetter() {
        assertEquals(FixSource.SYSTEM, FixSelection.choose(SourceFix(8f, 500), SourceFix(7f, 500)))   // 7 > 0.7 * 8
        assertEquals(FixSource.GPS, FixSelection.choose(SourceFix(10f, 500), SourceFix(4f, 500)))     // 4 <= 0.7 * 10
        assertEquals(FixSource.SYSTEM, FixSelection.choose(SourceFix(10f, 500), null))
        assertEquals(FixSource.GPS, FixSelection.choose(null, SourceFix(30f, 500)))
    }

    @Test
    fun aStaleFixIsNeverOffered() {
        assertNull(FixSelection.choose(SourceFix(3f, 60_000), SourceFix(3f, 60_000)))
        assertEquals(FixSource.SYSTEM, FixSelection.choose(SourceFix(20f, 500), SourceFix(2f, 60_000)))
    }

    @Test
    fun headingSigmaUsesTheSensorEstimateWithinBoundsAndNeverTrustsAnUncalibratedCompass() {
        assertEquals(2.0, PointingUncertainty.headingSigmaDeg(2.0, true), 1e-9)
        assertEquals(1.5, PointingUncertainty.headingSigmaDeg(0.2, true), 1e-9)      // lower bound
        assertEquals(30.0, PointingUncertainty.headingSigmaDeg(90.0, true), 1e-9)    // upper bound
        assertEquals(4.0, PointingUncertainty.headingSigmaDeg(null, true), 1e-9)     // default
        assertEquals(10.0, PointingUncertainty.headingSigmaDeg(2.0, false), 1e-9)    // uncalibrated floor
    }

    @Test
    fun positionErrorMattersMostForNearTargets() {
        val acc = PointingAccuracy(headingSigmaDeg = 3.0, positionSigmaM = 50.0)
        val near = PointingUncertainty.azimuthSigmaDeg(500.0, acc)    // atan(50/500) = 5.7 deg adds to the 3 deg heading error
        val far = PointingUncertainty.azimuthSigmaDeg(50_000.0, acc)  // the position error is negligible
        assertEquals(6.46, near, 0.05)
        assertEquals(3.0, far, 0.01)
    }

    @Test
    fun theRayThroughATargetBeatsOneThatMissesIt() {
        fun eval(name: String, bearing: Double, distanceM: Double) = TargetEvaluation(
            target = TargetEntity(name = name, kind = "tower", latitude = 32.0, longitude = 35.0, altitudeM = 0.0, heightM = null),
            bearingDeg = bearing, distanceM = distanceM, visibility = Visibility.VISIBLE
        )
        // Aim 90 deg. A: through the target (tower half-width 0.34 deg at 5 km), B: 3 deg to the side, but nearer.
        val sel = TargetSelector.select(listOf(eval("B", 93.0, 1_000.0), eval("A", 90.1, 5_000.0)), 90.0, 50.0, 1.0)
        assertEquals("A", sel.best!!.target.name)
    }

    @Test
    fun destinationPointFollowsBearingAndDistance() {
        val (latN, lonN) = GeoMath.destination(32.0, 35.0, 0.0, 1_000.0)      // due north
        assertEquals(32.0 + 1_000.0 / 111_320.0, latN, 2e-5)
        assertEquals(35.0, lonN, 1e-9)
        val (latE, lonE) = GeoMath.destination(32.0, 35.0, 90.0, 1_000.0)     // due east
        assertEquals(32.0, latE, 1e-9)
        assertEquals(35.0 + GeoMath.metersToLonDeg(1_000.0, 32.0), lonE, 1e-9)
    }
}
