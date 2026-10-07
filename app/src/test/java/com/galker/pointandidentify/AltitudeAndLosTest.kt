// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.1
package com.galker.pointandidentify

import com.galker.pointandidentify.data.db.TargetEntity
import com.galker.pointandidentify.domain.BaroGpsFusion
import com.galker.pointandidentify.domain.LineOfSightCalculator
import com.galker.pointandidentify.domain.TerrainSource
import com.galker.pointandidentify.domain.Visibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AltitudeAndLosTest {

    private fun converged(): BaroGpsFusion {
        val f = BaroGpsFusion()
        // GPS says 50 m (sigma 10 m) at constant pressure 1000 hPa, one fix every 2 s for a minute.
        for (i in 0..30) f.update(i * 2_000L, 50.0, 10.0, 1000.0)
        return f
    }

    @Test
    fun pressureAltitudeReference() {
        assertEquals(0.0, BaroGpsFusion.pressureAltitudeM(1013.25), 1e-9)
        assertEquals(110.9, BaroGpsFusion.pressureAltitudeM(1000.0), 0.5)
    }

    @Test
    fun fusionConvergesToGpsAndBecomesCertain() {
        val est = converged().estimate(1000.0)
        assertNotNull(est)
        assertEquals(50.0, est!!.altM, 0.5)
        assertTrue(est.sigmaM <= 6.0)
    }

    @Test
    fun barometerTracksChangesFasterThanGps() {
        val f = converged()
        // Pressure falls by 1.2 hPa (about +10 m, e.g. up the stairs) while GPS is not updated.
        val expected = 50.0 + BaroGpsFusion.pressureAltitudeM(998.8) - BaroGpsFusion.pressureAltitudeM(1000.0)
        assertEquals(expected, f.estimate(998.8)!!.altM, 0.5)
    }

    @Test
    fun wildGpsAltitudeIsGatedOut() {
        val f = converged()
        f.update(70_000L, 200.0, 10.0, 1000.0) // indoor GPS jump
        assertEquals(50.0, f.estimate(1000.0)!!.altM, 1.0)
    }

    private val settlement = TargetEntity(
        name = "T", kind = "settlement", latitude = 32.0, longitude = 35.02, altitudeM = 0.0, heightM = null
    )

    @Test
    fun smallBlockerIsTerrainNoise() {
        // 7 m wall halfway: the line is ~1.2 m below its top, within the 5 m tolerance -> still visible.
        val wall = TerrainSource { _, lon -> if (lon in 35.0095..35.0105) 7.0 else 0.0 }
        assertEquals(Visibility.VISIBLE, LineOfSightCalculator(wall).evaluate(32.0, 35.0, 1.7, settlement).visibility)
    }

    @Test
    fun largeBlockerStillBlocks() {
        val wall = TerrainSource { _, lon -> if (lon in 35.0095..35.0105) 12.0 else 0.0 }
        assertEquals(Visibility.OBSTRUCTED, LineOfSightCalculator(wall).evaluate(32.0, 35.0, 1.7, settlement).visibility)
    }

    @Test
    fun aConfidentButWrongFirstFixDoesNotLockTheFilter() {
        val f = BaroGpsFusion()
        f.update(0L, 90.0, 5.0, 1000.0)           // first fix: 40 m too high, reports 5 m accuracy
        for (i in 1..100) f.update(i * 2_000L, 50.0, 5.0, 1000.0) // all later fixes are right
        assertEquals(50.0, f.estimate(1000.0)!!.altM, 8.0)
    }
}
