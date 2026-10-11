// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.0
package com.galker.pointandidentify

import com.galker.pointandidentify.location.PositionFilter
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PositionFilterTest {
    @Test
    fun fixAcrossAntimeridianStaysNearIt() {
        val f = PositionFilter()
        f.update(0L, 10.0, 179.9, 5.0, null)
        val p = f.update(1000L, 10.0, -179.9, 5.0, null)
        assertTrue("lon ${p.lon}", abs(abs(p.lon) - 180.0) < 0.5)
    }
}
