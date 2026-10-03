// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.3
package com.galker.pointandidentify

import com.galker.pointandidentify.data.db.TargetEntity
import com.galker.pointandidentify.data.dem.DemTileRepository
import com.galker.pointandidentify.domain.LineOfSightCalculator
import com.galker.pointandidentify.domain.TerrainSource
import com.galker.pointandidentify.domain.Visibility
import com.galker.pointandidentify.geo.GeoMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GeoMathTest {

    @Test
    fun curvatureDropAt15km() {
        // 15000^2 / (2 * 6371008.8 / 0.87) = 15.36 m
        assertEquals(15.36, GeoMath.curvatureDropM(15_000.0), 0.01)
    }

    @Test
    fun curvatureDropAt50km() {
        // 50000^2 / (2 * 6371008.8 / 0.87) = 170.69 m
        assertEquals(170.69, GeoMath.curvatureDropM(50_000.0), 0.01)
    }

    @Test
    fun tileKeysAreUniqueAcrossSignsAndNeighbours() {
        assertEquals(DemTileRepository.intKey(326, 353), DemTileRepository.parseKey("326_353"))
        assertNotEquals(DemTileRepository.intKey(326, 353), DemTileRepository.intKey(326, 354))
        assertNotEquals(DemTileRepository.intKey(-1, 0), DemTileRepository.intKey(0, -1))
    }

    @Test
    fun angleDiffWrapsAroundNorth() {
        assertEquals(4.0, GeoMath.angleDiffDeg(358.0, 2.0), 1e-9)
    }

    @Test
    fun bearingDueEast() {
        assertEquals(90.0, GeoMath.bearingDeg(32.0, 35.0, 32.0, 35.1), 0.1)
    }

    @Test
    fun ridgeBlocksTarget() {
        // Flat terrain at 0 m with a 200 m wall halfway; observer and target at ground level.
        val ridge = TerrainSource { _, lon -> if (lon in 35.049..35.051) 200.0 else 0.0 }
        val los = LineOfSightCalculator(ridge)
        val target = TargetEntity(name = "T", kind = "settlement", latitude = 32.0, longitude = 35.1, altitudeM = 0.0, heightM = null)
        assertEquals(Visibility.OBSTRUCTED, los.evaluate(32.0, 35.0, 1.7, target).visibility)
    }

    @Test
    fun flatTerrainVisibleAtShortRange() {
        val flat = TerrainSource { _, _ -> 0.0 }
        val los = LineOfSightCalculator(flat)
        val target = TargetEntity(name = "T", kind = "settlement", latitude = 32.0, longitude = 35.02, altitudeM = 0.0, heightM = null)
        assertEquals(Visibility.VISIBLE, los.evaluate(32.0, 35.0, 1.7, target).visibility)
    }

    @Test
    fun peakUsesCatalogueSummitAltitude() {
        // DEM says 0 m everywhere, catalogue summit 500 m at ~9.4 km: top must be 500 m and visible.
        val flat = TerrainSource { _, _ -> 0.0 }
        val peak = TargetEntity(name = "P", kind = "peak", latitude = 32.0, longitude = 35.1, altitudeM = 500.0, heightM = null)
        val e = LineOfSightCalculator(flat).evaluate(32.0, 35.0, 1.7, peak)
        assertEquals(500.0, e.topAltM!!, 1e-9)
        assertEquals(Visibility.VISIBLE, e.visibility)
    }
}
