// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.1
package com.galker.pointandidentify

import com.galker.pointandidentify.geo.GeoMath
import org.junit.Assert.assertEquals
import org.junit.Test

class LonRangesTest {
    @Test
    fun ordinaryBoxIsOneRange() {
        assertEquals(listOf(34.0 to 36.0), GeoMath.lonRanges(35.0, 1.0))
    }

    @Test
    fun boxAcrossEastAntimeridianIsSplit() {
        assertEquals(listOf(179.0 to 180.0, -180.0 to -179.5), GeoMath.lonRanges(179.75, 0.75))
    }

    @Test
    fun boxAcrossWestAntimeridianIsSplit() {
        assertEquals(listOf(-180.0 to -179.0, 179.5 to 180.0), GeoMath.lonRanges(-179.75, 0.75))
    }

    @Test
    fun hugeSpanCoversEverything() {
        assertEquals(listOf(-180.0 to 180.0), GeoMath.lonRanges(10.0, 200.0))
    }
}
