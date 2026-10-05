// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.3
package com.galker.pointandidentify

import com.galker.pointandidentify.data.db.TargetEntity
import com.galker.pointandidentify.domain.CompassCheck
import com.galker.pointandidentify.domain.CompassVerdict
import com.galker.pointandidentify.domain.CrosshairWindow
import com.galker.pointandidentify.domain.FindGuidance
import com.galker.pointandidentify.domain.PhonePose
import com.galker.pointandidentify.domain.TargetEvaluation
import com.galker.pointandidentify.domain.TargetSelector
import com.galker.pointandidentify.domain.Visibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetSelectorTest {

    private fun eval(
        name: String, bearing: Double, visibility: Visibility = Visibility.VISIBLE,
        topAngle: Double? = null, groundAngle: Double? = null
    ) = TargetEvaluation(
        target = TargetEntity(name = name, kind = "tower", latitude = 32.0, longitude = 35.0, altitudeM = 0.0, heightM = null),
        bearingDeg = bearing,
        distanceM = 3_000.0,
        visibility = visibility,
        elevationAngleDeg = topAngle,
        groundAngleDeg = groundAngle
    )

    @Test
    fun crosshairRadiusInDegrees() {
        // tan(angle) = 2 * 0.09 * tan(25 deg) -> 4.80 deg at hfov 50, zoom 1
        assertEquals(4.80, CrosshairWindow.halfAngleDeg(50.0, 1.0), 0.05)
    }

    @Test
    fun targetOutsideCrosshairIsRejected() {
        // Window = 4.8 deg circle + 0.57 deg tower half-width: a bearing 10 deg off the axis is outside.
        val s = TargetSelector.select(listOf(eval("B", 100.0)), 90.0, 50.0, 1.0)
        assertNull(s.best)
        assertTrue(s.candidates.isEmpty())
    }

    @Test
    fun liveTargetIsVisibleOnlyHiddenOnesStayInTheList() {
        val both = TargetSelector.select(
            listOf(eval("hidden", 90.1, Visibility.OBSTRUCTED), eval("visible", 91.5)), 90.0, 50.0, 1.0
        )
        assertEquals("visible", both.best!!.target.name)
        assertEquals(listOf("visible", "hidden"), both.candidates.map { it.evaluation.target.name })

        val onlyHidden = TargetSelector.select(listOf(eval("hidden", 90.1, Visibility.OBSTRUCTED)), 90.0, 50.0, 1.0)
        assertNull(onlyHidden.best)
        assertEquals(1, onlyHidden.candidates.size)
    }

    @Test
    fun cameraPointedAtTheSkyOrTheStreetSelectsNothing() {
        // A target 2.5 deg below the horizon, exactly on the bearing.
        val e = listOf(eval("T", 90.0, topAngle = -2.5, groundAngle = -2.5))
        // Camera 8.9 deg up (the sky): 11.4 deg away vertically, far outside the 4.8 deg circle.
        assertNull(TargetSelector.select(e, 90.0, 50.0, 1.0, 8.9).best)
        // Camera 13 deg down (the street): also outside.
        assertNull(TargetSelector.select(e, 90.0, 50.0, 1.0, -13.0).best)
        // Camera at the target's angle: selected.
        assertEquals("T", TargetSelector.select(e, 90.0, 50.0, 1.0, -2.0).best!!.target.name)
    }

    @Test
    fun tallTargetIsSelectedAnywhereAlongItsHeight() {
        // Structure seen from -4 deg (ground) to +1 deg (top): the camera may aim at any part of it.
        val e = listOf(eval("Tall", 90.0, topAngle = 1.0, groundAngle = -4.0))
        assertEquals("Tall", TargetSelector.select(e, 90.0, 50.0, 1.0, -3.0).best!!.target.name)
        assertNull(TargetSelector.select(e, 90.0, 50.0, 1.0, 12.0).best)
    }

    @Test
    fun withoutVerticalDataOnlyTheBearingIsTested() {
        val e = listOf(eval("NoTerrain", 90.0, Visibility.UNKNOWN))
        assertEquals(1, TargetSelector.select(e, 90.0, 50.0, 1.0, 40.0).candidates.size)
    }

    @Test
    fun zoomNarrowsTheWindow() {
        val e = listOf(eval("A", 95.0)) // 5 deg off the axis
        assertEquals("A", TargetSelector.select(e, 90.0, TargetSelector.effectiveHfovDeg(50.0, 1.0), 1.0).best!!.target.name)
        // zoom x20: hfov ~2.7 deg, crosshair ~0.6 deg + 0.57 deg half-width < 5 deg
        assertNull(TargetSelector.select(e, 90.0, TargetSelector.effectiveHfovDeg(50.0, 20.0), 20.0).best)
    }

    @Test
    fun effectiveHfovFollowsTangentLaw() {
        assertEquals(60.0, TargetSelector.effectiveHfovDeg(60.0, 1.0), 1e-9)
        assertEquals(32.2, TargetSelector.effectiveHfovDeg(60.0, 2.0), 0.05)
        assertEquals(60.0, TargetSelector.effectiveHfovDeg(60.0, 0.5), 1e-9) // zoom below 1 is ignored
    }

    @Test
    fun compassHealthyReading() {
        val r = CompassCheck.evaluate(listOf(90.0, 90.5, 89.5, 90.2), 45.0, true)
        assertEquals(CompassVerdict.OK, r.verdict)
    }

    @Test
    fun compassInterferenceDetectedByFieldStrength() {
        assertEquals(CompassVerdict.INTERFERENCE, CompassCheck.evaluate(listOf(90.0, 90.0), 120.0, true).verdict)
        assertEquals(CompassVerdict.INTERFERENCE, CompassCheck.evaluate(listOf(90.0, 90.0), 10.0, true).verdict)
    }

    @Test
    fun compassUncalibratedAndUnstableAndMissing() {
        assertEquals(CompassVerdict.UNCALIBRATED, CompassCheck.evaluate(listOf(90.0, 90.1), 45.0, false).verdict)
        assertEquals(CompassVerdict.UNSTABLE, CompassCheck.evaluate(listOf(0.0, 40.0, 80.0, 120.0), 45.0, true).verdict)
        assertEquals(CompassVerdict.NO_SENSOR, CompassCheck.evaluate(emptyList(), null, false).verdict)
    }

    @Test
    fun circularSpreadIsWrapSafe() {
        assertTrue(CompassCheck.circularStdDevDeg(listOf(359.0, 1.0, 0.0, 2.0, 358.0)) < 2.0)
    }

    @Test
    fun poseHasHysteresis() {
        assertTrue(PhonePose.isRaised(false, -10.0))   // clearly raised
        assertTrue(!PhonePose.isRaised(true, -80.0))   // clearly flat
        assertTrue(PhonePose.isRaised(true, -45.0))    // in between: keeps the previous mode
        assertTrue(!PhonePose.isRaised(false, -45.0))
    }

    @Test
    fun findArrowPointsTowardThePlace() {
        // Place 20 deg to the right, level with the camera: arrow points right (angle 0), not inside.
        val right = FindGuidance.guide(110.0, 0.0, 90.0, 0.0, 5.0)
        assertEquals(20.0, right.deltaAzimuthDeg, 1e-9)
        assertEquals(0.0, right.screenAngleRad, 1e-9)
        assertTrue(!right.inside)
        // Wrap-around: camera at 350, place at 10 -> 20 deg to the right.
        assertEquals(20.0, FindGuidance.guide(10.0, 0.0, 350.0, 0.0, 5.0).deltaAzimuthDeg, 1e-9)
        // Phone flat (camera -90), place on the horizon: arrow points up (pi/2).
        assertEquals(Math.PI / 2, FindGuidance.guide(90.0, 0.0, 90.0, -90.0, 5.0).screenAngleRad, 1e-9)
        // Inside the circle.
        assertTrue(FindGuidance.guide(92.0, 0.0, 90.0, 1.0, 5.0).inside)
    }
}
