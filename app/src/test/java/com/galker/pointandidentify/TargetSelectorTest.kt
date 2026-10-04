// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.0
package com.galker.pointandidentify

import com.galker.pointandidentify.data.db.TargetEntity
import com.galker.pointandidentify.domain.CompassCheck
import com.galker.pointandidentify.domain.CompassVerdict
import com.galker.pointandidentify.domain.TargetEvaluation
import com.galker.pointandidentify.domain.TargetSelector
import com.galker.pointandidentify.domain.Visibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetSelectorTest {

    private fun eval(name: String, bearing: Double, visibility: Visibility = Visibility.VISIBLE) = TargetEvaluation(
        target = TargetEntity(name = name, kind = "tower", latitude = 32.0, longitude = 35.0, altitudeM = 0.0, heightM = null),
        bearingDeg = bearing,
        distanceM = 3_000.0,
        visibility = visibility
    )

    @Test
    fun targetOutsideToleranceIsRejected() {
        // Tower tolerance floor is 3 deg: a bearing 5 deg off the axis is outside the window.
        val s = TargetSelector.select(listOf(eval("B", 95.0)), 90.0, 60.0)
        assertNull(s.best)
        assertTrue(s.candidates.isEmpty())
    }

    @Test
    fun visibleTargetBeatsCloserHiddenOne() {
        val s = TargetSelector.select(
            listOf(eval("hidden", 90.1, Visibility.OBSTRUCTED), eval("visible", 91.5)), 90.0, 60.0
        )
        assertEquals("visible", s.best!!.target.name)
        assertEquals(2, s.candidates.size)
    }

    @Test
    fun zoomNarrowsTheWindow() {
        val e = listOf(eval("A", 92.0)) // 2 deg off the axis
        assertEquals("A", TargetSelector.select(e, 90.0, TargetSelector.effectiveHfovDeg(60.0, 1.0)).best!!.target.name)
        // zoom x20: hfov ~3.3 deg, half-window ~1.65 deg < 2 deg
        assertNull(TargetSelector.select(e, 90.0, TargetSelector.effectiveHfovDeg(60.0, 20.0)).best)
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
}
