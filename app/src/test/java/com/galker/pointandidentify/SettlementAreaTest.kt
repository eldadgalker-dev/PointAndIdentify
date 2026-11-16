// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.0
package com.galker.pointandidentify

import com.galker.pointandidentify.domain.SettlementArea
import com.galker.pointandidentify.domain.SettlementAreas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettlementAreaTest {

    /** Closed rectangle ring (lat, lon pairs). */
    private fun box(south: Double, west: Double, north: Double, east: Double) =
        doubleArrayOf(south, west, south, east, north, east, north, west, south, west)

    @Test
    fun aPointIsInsideOnlyWithinTheOutline() {
        val city = SettlementArea("City", 32.05, 34.75, listOf(box(32.0, 34.7, 32.1, 34.8)))
        assertTrue(city.contains(32.05, 34.75))
        assertTrue(city.contains(32.099, 34.701))
        assertFalse(city.contains(32.101, 34.75))
        assertFalse(city.contains(32.05, 34.801))
        assertFalse(city.contains(31.0, 34.75))
    }

    @Test
    fun aConcaveOutlineLeavesItsNotchOutside() {
        // An L shape: the square 32.0-32.1 x 34.7-34.8 without its north-east quarter.
        val ring = doubleArrayOf(32.0, 34.7, 32.0, 34.8, 32.05, 34.8, 32.05, 34.75, 32.1, 34.75, 32.1, 34.7, 32.0, 34.7)
        val town = SettlementArea("Town", 32.02, 34.72, listOf(ring))
        assertTrue(town.contains(32.02, 34.78))
        assertTrue(town.contains(32.08, 34.72))
        assertFalse(town.contains(32.08, 34.78)) // inside the bounding box, outside the outline
    }

    @Test
    fun severalRingsAreOneSettlementAndAnInnerRingIsAHole() {
        val twoParts = SettlementArea("Twin", 32.0, 34.7, listOf(box(32.0, 34.7, 32.1, 34.8), box(32.3, 34.7, 32.4, 34.8)))
        assertTrue(twoParts.contains(32.05, 34.75))
        assertTrue(twoParts.contains(32.35, 34.75))
        assertFalse(twoParts.contains(32.2, 34.75))
        val holed = SettlementArea("Ring", 32.0, 34.7, listOf(box(32.0, 34.7, 32.1, 34.8), box(32.04, 34.74, 32.06, 34.76)))
        assertFalse(holed.contains(32.05, 34.75))
        assertTrue(holed.contains(32.02, 34.72))
    }

    @Test
    fun theSmallestOutlineAroundThePointWins() {
        // Field finding 2026-11-16: the aimed point lay in the large city, the centre of the small neighbour was nearer.
        val large = SettlementArea("Large", 32.085, 34.782, listOf(box(32.0, 34.7, 32.15, 34.8)))
        val neighbour = SettlementArea("Neighbour", 32.07, 34.81, listOf(box(32.06, 34.8, 32.08, 34.82)))
        val enclave = SettlementArea("Enclave", 32.02, 34.72, listOf(box(32.01, 34.71, 32.03, 34.73)))
        val areas = SettlementAreas(listOf(large, neighbour, enclave))
        assertEquals(3, areas.size)
        assertEquals("Large", areas.find(32.0701, 34.7935)?.name)
        assertEquals("Neighbour", areas.find(32.07, 34.805)?.name)
        assertEquals("Enclave", areas.find(32.02, 34.72)?.name)
        assertNull(areas.find(32.2, 34.75))
    }

    @Test
    fun aBrokenOutlineContainsNothing() {
        val broken = SettlementArea("Broken", 32.0, 34.7, listOf(doubleArrayOf(32.0, 34.7, 32.1, 34.8)))
        assertTrue(broken.isEmpty)
        assertFalse(broken.contains(32.05, 34.75))
        assertEquals(0, SettlementAreas(listOf(broken)).size)
        assertNull(SettlementAreas.EMPTY.find(32.0, 34.7))
    }
}
