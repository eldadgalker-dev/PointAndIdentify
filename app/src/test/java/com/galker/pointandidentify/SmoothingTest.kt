// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.0
package com.galker.pointandidentify

import com.galker.pointandidentify.domain.AdaptiveAngleSmoother
import com.galker.pointandidentify.domain.HeadingFusion
import com.galker.pointandidentify.geo.GeoMath
import com.galker.pointandidentify.location.PositionFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SmoothingTest {

    private fun diff(a: Double, b: Double) = abs((((a - b + 540.0) % 360.0) + 360.0) % 360.0 - 180.0)

    @Test
    fun smallJitterIsSmoothedHard() {
        val s = AdaptiveAngleSmoother(circular = true)
        s.update(100.0)
        // +-1 degree tremor around 100: the output must stay within a quarter of that.
        var worst = 0.0
        for (i in 0 until 200) worst = maxOf(worst, diff(s.update(if (i % 2 == 0) 101.0 else 99.0), 100.0))
        assertTrue("worst deviation $worst", worst < 0.3)
    }

    @Test
    fun aRealTurnPassesAlmostUnfiltered() {
        val s = AdaptiveAngleSmoother(circular = true)
        s.update(100.0)
        val after = s.update(130.0) // a 30 degree step: alpha at its maximum
        assertTrue("after $after", after > 100.0 + 0.5 * 30.0)
    }

    @Test
    fun smootherWrapsAroundNorth() {
        val s = AdaptiveAngleSmoother(circular = true)
        s.update(359.0)
        val next = s.update(1.0) // 2 degrees to the east across north, not 358 degrees back
        assertTrue("next $next", diff(next, 0.0) < 2.0)
    }

    @Test
    fun linearSmootherDoesNotWrap() {
        val s = AdaptiveAngleSmoother(circular = false)
        s.update(-80.0)
        assertTrue(s.update(80.0) > -80.0)
    }

    @Test
    fun fusionFollowsTheGyroInTheShortTermAndTheCompassInTheLongTerm() {
        val f = HeadingFusion()
        // First sample: the offset is taken from the compass at once (gyro azimuth 10, true north 100 -> offset 90).
        assertEquals(100.0, f.fuse(10.0, 100.0, true, 0.0), 0.5)
        // The gyro turns 20 degrees while the compass is noisy (+-15): the fused heading follows the gyro.
        val turned = f.fuse(30.0, 135.0, true, 0.02)
        assertEquals(120.0, turned, 2.0)
        // A trusted compass that stays 10 degrees away pulls the offset over a few time constants.
        var h = 0.0
        for (i in 0 until 1000) h = f.fuse(30.0, 130.0, true, 0.02) // 20 s
        assertEquals(130.0, h, 1.0)
    }

    @Test
    fun anUntrustedCompassDoesNotMoveTheHeading() {
        val f = HeadingFusion()
        f.fuse(10.0, 100.0, true, 0.0)
        var h = 0.0
        for (i in 0 until 1000) h = f.fuse(10.0, 160.0, false, 0.02) // magnet nearby: reading ignored
        assertEquals(100.0, h, 0.5)
    }

    @Test
    fun positionFilterIsSteadierThanTheRawFixesWhenStanding() {
        val f = PositionFilter()
        val lat0 = 32.0
        val lon0 = 35.0
        var rawWorst = 0.0
        var filteredWorst = 0.0
        // Deterministic noise of about +-8 m (reported accuracy 8 m), alternating in both axes.
        for (i in 0 until 60) {
            val dLat = GeoMath.metersToLatDeg(if (i % 2 == 0) 8.0 else -8.0)
            val dLon = GeoMath.metersToLonDeg(if (i % 3 == 0) 8.0 else -8.0, lat0)
            val out = f.update(i * 1000L, lat0 + dLat, lon0 + dLon, 8.0, 0.0)
            if (i >= 20) {
                rawWorst = maxOf(rawWorst, GeoMath.distanceM(lat0, lon0, lat0 + dLat, lon0 + dLon))
                filteredWorst = maxOf(filteredWorst, GeoMath.distanceM(lat0, lon0, out.lat, out.lon))
            }
        }
        assertTrue("filtered $filteredWorst raw $rawWorst", filteredWorst < 0.6 * rawWorst)
    }

    @Test
    fun positionFilterFollowsAWalkingUser() {
        val f = PositionFilter()
        val lat0 = 32.0
        val lon0 = 35.0
        var last = f.update(0, lat0, lon0, 5.0, 1.5)
        var truth = lat0
        for (i in 1..30) { // 1.4 m/s north for 30 s: 42 m
            truth += GeoMath.metersToLatDeg(1.4)
            last = f.update(i * 1000L, truth, lon0, 5.0, 1.4)
        }
        assertTrue("lag ${GeoMath.distanceM(truth, lon0, last.lat, last.lon)}", GeoMath.distanceM(truth, lon0, last.lat, last.lon) < 6.0)
    }

    @Test
    fun aPoorFixBarelyMovesTheEstimate() {
        val f = PositionFilter()
        f.update(0, 32.0, 35.0, 4.0, 0.0)
        val out = f.update(1_000, 32.01, 35.0, 500.0, 0.0) // 1.1 km away, accuracy 500 m
        assertTrue(GeoMath.distanceM(32.0, 35.0, out.lat, out.lon) < 100.0)
    }
}
