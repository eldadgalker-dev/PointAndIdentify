// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.2
package com.galker.pointandidentify

import kotlin.math.tan
import kotlin.math.atan
import com.galker.pointandidentify.data.db.TargetEntity
import com.galker.pointandidentify.domain.Candidate
import com.galker.pointandidentify.domain.CityPicker
import com.galker.pointandidentify.domain.FindProjection
import com.galker.pointandidentify.domain.SelectionStabilizer
import com.galker.pointandidentify.domain.Selection
import com.galker.pointandidentify.domain.TargetEvaluation
import com.galker.pointandidentify.domain.Visibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StabilityTest {

    private fun eval(name: String, bearing: Double = 90.0, distanceM: Double = 3_000.0) = TargetEvaluation(
        target = TargetEntity(name = name, kind = "settlement", latitude = 32.0 + name.hashCode() % 100 / 1000.0, longitude = 35.0, altitudeM = 0.0, heightM = null),
        bearingDeg = bearing,
        distanceM = distanceM,
        visibility = Visibility.VISIBLE
    )

    private fun selection(vararg scored: Pair<TargetEvaluation, Double>): Selection {
        val candidates = scored.map { Candidate(it.first, 0.0, it.second) }
        val best = candidates.minByOrNull { it.score }
        return Selection(best?.evaluation, best?.angleDiffDeg, candidates)
    }

    private val a = eval("A")
    private val b = eval("B")

    @Test
    fun aSlightlyBetterNeighbourDoesNotReplaceTheShownTarget() {
        val s = SelectionStabilizer()
        assertEquals("A", s.stabilize(0, selection(a to 1.0, b to 1.2)).best?.target?.name)
        // B is better now, but only by 10 percent: A stays, even after a long time.
        assertEquals("A", s.stabilize(100, selection(a to 1.0, b to 0.9)).best?.target?.name)
        assertEquals("A", s.stabilize(5_000, selection(a to 1.0, b to 0.9)).best?.target?.name)
    }

    @Test
    fun aClearlyBetterTargetReplacesItOnlyAfterTheHoldTime() {
        val s = SelectionStabilizer()
        s.stabilize(0, selection(a to 1.0, b to 1.5))
        assertEquals("A", s.stabilize(100, selection(a to 1.0, b to 0.3)).best?.target?.name)
        assertEquals("A", s.stabilize(500, selection(a to 1.0, b to 0.3)).best?.target?.name)
        assertEquals("B", s.stabilize(800, selection(a to 1.0, b to 0.3)).best?.target?.name)
    }

    @Test
    fun aClearlyBetterTargetThatDisappearsInBetweenRestartsTheHoldTime() {
        val s = SelectionStabilizer()
        s.stabilize(0, selection(a to 1.0, b to 1.5))
        s.stabilize(100, selection(a to 1.0, b to 0.3))
        s.stabilize(300, selection(a to 1.0, b to 0.9)) // no longer clearly better: the challenger is dropped
        assertEquals("A", s.stabilize(900, selection(a to 1.0, b to 0.3)).best?.target?.name) // timer restarted at 900
    }

    @Test
    fun aTargetThatLeavesTheCrosshairStaysBrieflyThenGoes() {
        val s = SelectionStabilizer()
        s.stabilize(0, selection(a to 1.0))
        assertEquals("A", s.stabilize(200, selection()).best?.target?.name)
        assertNull(s.stabilize(700, selection()).best)
    }

    @Test
    fun cityStraightDownIsTheNearestSettlementWhateverTheAzimuth() {
        // Field finding 2026-11-13: 350 m from the nearest settlement, pointing down showed places 9 to 15 km away.
        val near = eval("Near", bearing = 200.0, distanceM = 350.0)
        val far = eval("Far", bearing = 90.0, distanceM = 12_000.0)
        val list = listOf(far, near)
        for (azimuth in listOf(0.0, 88.0, 90.0, 180.0, 270.0, 359.0)) {
            assertEquals("Near", CityPicker.pick(list, azimuth, 0.0, null)?.target?.name)
            // No ground point known (no terrain on the way): the point under the observer is used.
            assertEquals("Near", CityPicker.pick(list, azimuth, null, null)?.target?.name)
        }
    }

    @Test
    fun cityIsTheSettlementNearestToTheAimedGroundPoint() {
        val east = eval("East", bearing = 90.0, distanceM = 1_000.0)
        val behind = eval("Behind", bearing = 270.0, distanceM = 200.0) // nearest to the observer
        val list = listOf(east, behind)
        // Aimed 900 m to the east: 100 m from East, 1100 m from Behind.
        assertEquals(100.0, CityPicker.distanceToAimM(east, 90.0, 900.0), 0.01)
        assertEquals(1_100.0, CityPicker.distanceToAimM(behind, 90.0, 900.0), 0.01)
        assertEquals("East", CityPicker.pick(list, 90.0, 900.0, null)?.target?.name)
        // Lowering the camera brings the aimed point to 100 m: the nearer place, never a farther one.
        assertEquals("Behind", CityPicker.pick(list, 90.0, 100.0, null)?.target?.name)
        // A point at a right angle: plane distance sqrt(1000^2 + 900^2).
        assertEquals(1_345.36, CityPicker.distanceToAimM(east, 0.0, 900.0), 0.01)
    }

    @Test
    fun cityKeepsThePreviousSettlementAgainstSmallChanges() {
        val first = eval("First", bearing = 90.0, distanceM = 1_000.0)
        val second = eval("Second", bearing = 270.0, distanceM = 1_000.0)
        val list = listOf(first, second)
        val held = CityPicker.pick(list, 90.0, 500.0, null)
        assertEquals("First", held?.target?.name)
        // Straight down both are equally far: the shown one stays.
        assertEquals("First", CityPicker.pick(list, 270.0, 0.0, held)?.target?.name)
        // Aimed 50 m towards Second: nearer by 100 m only, below the switch margin.
        assertEquals("First", CityPicker.pick(list, 270.0, 50.0, held)?.target?.name)
        // Aimed 200 m towards Second: nearer by 400 m, the change is taken.
        assertEquals("Second", CityPicker.pick(list, 270.0, 200.0, held)?.target?.name)
        // A previous place that left the search range does not hold the choice.
        assertEquals("Second", CityPicker.pick(listOf(second), 90.0, 500.0, held)?.target?.name)
    }

    @Test
    fun findDotProjectionFollowsTheCameraOptics() {
        // 1080 x 2160 screen (narrower than the 3:4 preview: the height fills), hfov 60, zoom 1: 1403 px per tangent unit.
        val up10 = FindProjection.screenOffsetPx(0.0, 10.0, 60.0, 1.0, 1080, 2160)
        assertNotNull(up10)
        assertEquals(0f, up10!!.first, 0.01f)
        assertEquals(-247.4f, up10.second, 1.0f) // up is negative y
        // Wider than the preview: the width fills; the half width corresponds to half the field of view.
        val edge = FindProjection.screenOffsetPx(30.0, 0.0, 60.0, 1.0, 2000, 1000)
        assertEquals(1000f, edge!!.first, 1.0f)
        // Twice the zoom doubles the offset of a small angle.
        val z2 = FindProjection.screenOffsetPx(0.0, 5.0, 60.0, 2.0, 1080, 2160)!!
        val z1 = FindProjection.screenOffsetPx(0.0, 5.0, 60.0, 1.0, 1080, 2160)!!
        assertEquals(z1.second * 2f, z2.second, 0.5f)
        assertNull(FindProjection.screenOffsetPx(85.0, 0.0, 60.0, 1.0, 1080, 2160))
    }

    @Test
    fun findDotUsesTheFullSensorFovAndAppliesTheScreenCropItself() {
        // 1080 x 2400: the visible fraction is (1080 / 2400) / 0.75 = 0.6, so tan(visible / 2) = tan(sensor / 2) * 0.6
        // and one tangent unit is W / (2 tan(visible / 2)) pixels. Passing the SENSOR fov must give exactly that.
        val sensor = 60.0
        val visible = Math.toDegrees(2.0 * atan(tan(Math.toRadians(sensor / 2.0)) * 0.6))
        val expectedUp10 = 1080.0 / (2.0 * tan(Math.toRadians(visible / 2.0))) * tan(Math.toRadians(10.0))
        val p = FindProjection.screenOffsetPx(0.0, 10.0, sensor, 1.0, 1080, 2400)!!
        assertEquals(-expectedUp10.toFloat(), p.second, 1.0f)
    }
}
