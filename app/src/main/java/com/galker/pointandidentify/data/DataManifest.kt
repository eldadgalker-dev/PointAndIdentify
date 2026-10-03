// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 2.1
package com.galker.pointandidentify.data

import org.json.JSONObject

/**
 * Index of all data files published under data/ in the repository.
 * Terrain lookup rule for a tile key:
 *   in [tiles]     -> land tile, download and interpolate
 *   in [seaTiles]  -> sea level (0 m), nothing to download
 *   neither        -> unknown (outside the processed region)
 * Legacy manifests without "sea_tiles" ([seaTilesListed] = false) fall back to the coverage
 * box rule: inside coverage and not in [tiles] = sea.
 */
data class DataManifest(
    val version: Long,
    val tileDeg: Double,
    val tileSamples: Int,
    val coverage: Coverage,
    val tiles: Map<String, TileEntry>,
    val seaTiles: Set<String>,
    val seaTilesListed: Boolean,
    val targets: TargetsEntry
) {
    data class Coverage(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double) {
        fun contains(lat: Double, lon: Double) = lat in minLat..maxLat && lon in minLon..maxLon
    }

    data class TileEntry(val key: String, val path: String, val sha256: String, val bytes: Long)

    data class TargetsEntry(val version: Int, val path: String, val sha256: String)

    companion object {
        fun parse(json: String): DataManifest {
            val root = JSONObject(json)
            val cov = root.getJSONObject("coverage")
            val tilesJson = root.getJSONObject("tiles")
            val tiles = HashMap<String, TileEntry>(tilesJson.length())
            val keys = tilesJson.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val t = tilesJson.getJSONObject(k)
                tiles[k] = TileEntry(k, t.getString("path"), t.getString("sha256"), t.getLong("bytes"))
            }
            val seaJson = root.optJSONArray("sea_tiles")
            val sea = HashSet<String>()
            if (seaJson != null) for (i in 0 until seaJson.length()) sea.add(seaJson.getString(i))
            // "targets" since data schema 2; "settlements" accepted from older manifests.
            val st = root.optJSONObject("targets") ?: root.getJSONObject("settlements")
            return DataManifest(
                version = root.getLong("version"),
                tileDeg = root.getDouble("tile_deg"),
                tileSamples = root.getInt("tile_samples"),
                coverage = Coverage(
                    cov.getDouble("min_lat"), cov.getDouble("min_lon"),
                    cov.getDouble("max_lat"), cov.getDouble("max_lon")
                ),
                tiles = tiles,
                seaTiles = sea,
                seaTilesListed = seaJson != null,
                targets = TargetsEntry(st.getInt("version"), st.getString("path"), st.getString("sha256"))
            )
        }
    }
}
