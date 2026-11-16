// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.0
package com.galker.pointandidentify.domain

import kotlin.math.abs

/**
 * Outline of one settlement (municipal boundary or mapped built-up area), from the "areas" list of targets.json.
 * Field finding 2026-11-16 (Tel Aviv, 600 m from the Givatayim border): a settlement taken as its centre point gave
 * the wrong town, and a different one on every shot, because the centre of the neighbouring small town was nearer to
 * the aimed ground point than the centre of the large city the point lies in.
 *
 * Units: degrees (WGS84). rings: closed rings as flat arrays (lat, lon, lat, lon, ...), first point = last point.
 * Several rings are parts of the same settlement; the even-odd rule over all of them also handles a hole.
 * name, latitude and longitude are those of the settlement item the outline belongs to.
 */
class SettlementArea(val name: String, val latitude: Double, val longitude: Double, rings: List<DoubleArray>) {

    private val rings: List<DoubleArray> = rings.filter { it.size >= 8 && it.size % 2 == 0 } // 3 corners + the closing point

    // Bounding box, deg; NaN when there is no ring, so that every comparison in contains() fails.
    private var minLat = Double.NaN
    private var maxLat = Double.NaN
    private var minLon = Double.NaN
    private var maxLon = Double.NaN

    init {
        var first = true
        for (r in this.rings) {
            var i = 0
            while (i + 1 < r.size) {
                val lat = r[i]
                val lon = r[i + 1]
                if (first || lat < minLat) minLat = lat
                if (first || lat > maxLat) maxLat = lat
                if (first || lon < minLon) minLon = lon
                if (first || lon > maxLon) maxLon = lon
                first = false
                i += 2
            }
        }
    }

    /** Size for ordering only (shoelace sum in square degrees): the smallest outline around a point is the most specific. */
    val sizeDeg2: Double = this.rings.sumOf { r ->
        var twice = 0.0
        var i = 0
        while (i + 3 < r.size) {
            twice += r[i + 1] * r[i + 2] - r[i + 3] * r[i]
            i += 2
        }
        abs(twice) / 2.0
    }

    val isEmpty: Boolean get() = rings.isEmpty()

    /** True when the point lies inside the outline (ray casting, even-odd). A NaN bound (no ring) contains nothing. */
    fun contains(lat: Double, lon: Double): Boolean {
        if (!(lat >= minLat && lat <= maxLat && lon >= minLon && lon <= maxLon)) return false
        var inside = false
        for (r in rings) {
            var i = 0
            while (i + 3 < r.size) {
                val y1 = r[i]
                val x1 = r[i + 1]
                val y2 = r[i + 2]
                val x2 = r[i + 3]
                // The edge crosses the latitude of the point (so y1 != y2), east of the point.
                if ((y1 > lat) != (y2 > lat) && lon < x1 + (lat - y1) * (x2 - x1) / (y2 - y1)) inside = !inside
                i += 2
            }
        }
        return inside
    }
}

/** All settlement outlines; answers "which settlement is this ground point in". */
class SettlementAreas(areas: List<SettlementArea>) {

    // Smallest first: a village mapped inside a larger outline is found before the outline around it.
    private val bySize = areas.filterNot { it.isEmpty }.sortedBy { it.sizeDeg2 }

    val size: Int get() = bySize.size

    /** The smallest outline that contains the point, null when the point is in no mapped settlement. */
    fun find(lat: Double, lon: Double): SettlementArea? = bySize.firstOrNull { it.contains(lat, lon) }

    companion object {
        val EMPTY = SettlementAreas(emptyList())
    }
}
