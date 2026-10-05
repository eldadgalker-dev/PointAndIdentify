// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.0
package com.galker.pointandidentify

import com.galker.pointandidentify.domain.AimRay
import com.galker.pointandidentify.domain.TerrainSource
import com.galker.pointandidentify.geo.GeoMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AimRayTest {

    private val lat0 = 32.0
    private val lon0 = 35.0

    /** Terrain as a function of the horizontal distance from the observer. */
    private fun terrain(height: (Double) -> Double) =
        TerrainSource { lat, lon -> height(GeoMath.distanceM(lat0, lon0, lat, lon)) }

    @Test
    fun flatGroundCircleGrowsWithHeight() {
        val flat = terrain { 0.0 }
        // 100 m above flat ground, 21.8 deg down: tan(21.8) = 0.4 -> 250 m (a circle of 500 m diameter).
        val d100 = AimRay.groundDistanceM(flat, lat0, lon0, 100.0, 90.0, -21.8)!!
        assertEquals(250.0, d100, 5.0)
        // Twice as high, same angle: twice the distance.
        val d200 = AimRay.groundDistanceM(flat, lat0, lon0, 200.0, 90.0, -21.8)!!
        assertEquals(500.0, d200, 10.0)
    }

    @Test
    fun hillEdgeUsesTheValleyFloorNotTheGroundUnderTheObserver() {
        // The observer stands on a hilltop 100 m high; the ground drops to 0 m just beyond the edge.
        val hill = terrain { x -> if (x < 5.0) 100.0 else 0.0 }
        val d = AimRay.groundDistanceM(hill, lat0, lon0, 101.7, 90.0, -21.8)!!
        assertEquals(254.0, d, 8.0) // the circle can be lowered to about 500 m in diameter
    }

    @Test
    fun noGroundWhenNotPointingDownOrOutOfRange() {
        val flat = terrain { 0.0 }
        assertNull(AimRay.groundDistanceM(flat, lat0, lon0, 100.0, 90.0, 5.0))
        assertNull(AimRay.groundDistanceM(flat, lat0, lon0, 100.0, 90.0, -0.3)) // meets the ground beyond 5 km
    }

    @Test
    fun missingTerrainDataGivesNoAnswer() {
        assertNull(AimRay.groundDistanceM(TerrainSource { _, _ -> null }, lat0, lon0, 100.0, 90.0, -20.0))
    }
}
