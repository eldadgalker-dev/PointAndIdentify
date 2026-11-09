// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.0
package com.galker.pointandidentify

import com.galker.pointandidentify.data.db.TargetEntity
import com.galker.pointandidentify.domain.AltitudeEstimate
import com.galker.pointandidentify.domain.EyeAltitudeModel
import com.galker.pointandidentify.domain.Visibility
import com.galker.pointandidentify.domain.apparentAngleDeg
import com.galker.pointandidentify.domain.reangled
import com.galker.pointandidentify.domain.LineOfSightCalculator
import com.galker.pointandidentify.domain.TerrainSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EyeAltitudeTest {

    @Test
    fun withoutASensorTheTerrainEyeIsUsed() {
        val e = EyeAltitudeModel.estimate(100.0, 1.7, null)
        assertEquals(101.7, e.bestM, 1e-9)
        assertEquals(101.7, e.losM, 1e-9)
    }

    @Test
    fun agreeingSensorAndTerrainAreAveragedAndMoreCertainThanEither() {
        val sensor = AltitudeEstimate(104.7, 4.1)               // 3 m above the terrain eye, well inside the gate
        val e = EyeAltitudeModel.estimate(100.0, 1.7, sensor)
        assertTrue(e.bestM > 101.7 && e.bestM < 104.7)           // between the two, not biased to the lower bound
        assertTrue(e.sigmaM < 4.1)
        assertTrue(e.losM >= 101.7)                              // the obstruction eye is never lower than the terrain eye
    }

    @Test
    fun aHighFloorUsesTheSensorWithoutTheCautiousBias() {
        val sensor = AltitudeEstimate(130.0, 4.1)                // 28 m above the terrain eye: elevated
        val e = EyeAltitudeModel.estimate(100.0, 1.7, sensor)
        assertEquals(130.0, e.bestM, 1e-9)                       // angles use the unbiased value
        assertTrue(e.losM < 130.0 - 4.0)                         // the obstruction test stays cautious
        assertEquals(4.1, e.sigmaM, 1e-9)
    }

    @Test
    fun aSensorFarBelowTheTerrainIsNotTrustedToLowerTheObserver() {
        val e = EyeAltitudeModel.estimate(100.0, 1.7, AltitudeEstimate(70.0, 4.1))
        assertEquals(101.7, e.bestM, 1e-9)
        assertTrue(e.sigmaM > 6.0)                               // and the doubt is reported
    }

    @Test
    fun anUncertainSensorIsIgnored() {
        val e = EyeAltitudeModel.estimate(100.0, 1.7, AltitudeEstimate(130.0, 30.0))
        assertEquals(101.7, e.bestM, 1e-9)
    }

    @Test
    fun reangledMatchesAFreshEvaluationAtTheNewEye() {
        val flat = TerrainSource { _, _ -> 100.0 }
        val calc = LineOfSightCalculator(flat)
        val t = TargetEntity(name = "T", kind = "tower", latitude = 31.01, longitude = 35.0, altitudeM = null, heightM = 30.0)
        val atTen = calc.evaluate(31.0, 35.0, 110.0, t)
        val atTwelve = calc.evaluate(31.0, 35.0, 112.0, t)
        assertEquals(Visibility.VISIBLE, atTen.visibility)
        assertEquals(atTwelve.elevationAngleDeg!!, atTen.reangled(112.0).elevationAngleDeg!!, 1e-9)
        assertEquals(atTwelve.groundAngleDeg!!, atTen.reangled(112.0).groundAngleDeg!!, 1e-9)
    }

    @Test
    fun theAngleEyeDoesNotChangeTheObstructionResult() {
        val hill = TerrainSource { lat, _ -> if (lat in 31.004..31.006) 160.0 else 100.0 }
        val calc = LineOfSightCalculator(hill)
        val t = TargetEntity(name = "T", kind = "tower", latitude = 31.01, longitude = 35.0, altitudeM = null, heightM = 10.0)
        val a = calc.evaluate(31.0, 35.0, 101.7, t)
        val b = calc.evaluate(31.0, 35.0, 101.7, t, 140.0)
        assertEquals(a.visibility, b.visibility)
        assertTrue(b.elevationAngleDeg!! < a.elevationAngleDeg!!)   // a higher eye sees the top at a lower angle
        assertEquals(apparentAngleDeg(b.topAltM!!, 140.0, b.distanceM), b.elevationAngleDeg!!, 1e-9)
    }
}
