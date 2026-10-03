// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 2.1
package com.galker.pointandidentify.data.dem

import android.content.Context
import android.util.Log
import android.util.LruCache
import com.galker.pointandidentify.config.AppConfig
import com.galker.pointandidentify.data.DataManifest
import com.galker.pointandidentify.data.ManifestRepository
import com.galker.pointandidentify.data.net.HttpClient
import com.galker.pointandidentify.geo.GeoMath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import kotlin.math.floor

// =============================================================
// Tile format (produced by tools/build_dem_tiles.py)
//   Key        : "<latIndex>_<lonIndex>", index = floor(deg * TILE_INDEX_SCALE), e.g. "326_353"
//   Extent     : south = latIndex / 10, west = lonIndex / 10, edge = TILE_DEG
//   Payload    : gzip of TILE_SAMPLES x TILE_SAMPLES int16 little-endian, metres MSL
//   Row order  : row 0 = north edge, increasing southwards; col 0 = west edge
//   Edges      : shared with neighbours (361 samples) so interpolation never crosses tiles
// =============================================================

/**
 * Downloads only the tiles around the observer (nearest first, in parallel), caches them on disk
 * and serves elevations. Lookups use integer tile keys to avoid per-sample string allocation,
 * which matters at 50 km range (~1.5 M lookups per full LOS pass).
 */
class DemTileRepository(
    context: Context,
    private val http: HttpClient,
    private val manifestRepository: ManifestRepository
) {

    private val tileDir = File(context.filesDir, "tiles").apply { mkdirs() }
    private val memory = LruCache<Int, ShortArray>(AppConfig.TILE_MEMORY_CACHE)
    private val fetchMutex = Mutex()

    /** Integer-keyed view of one manifest; rebuilt only when the manifest object changes. */
    private class TileIndex(val manifest: DataManifest) {
        val land = HashMap<Int, DataManifest.TileEntry>(manifest.tiles.size * 2)
        val sea = HashSet<Int>(manifest.seaTiles.size * 2)

        init {
            manifest.tiles.values.forEach { e -> parseKey(e.key)?.let { land[it] = e } }
            manifest.seaTiles.forEach { k -> parseKey(k)?.let { sea.add(it) } }
        }
    }

    @Volatile
    private var index: TileIndex? = null

    data class FetchStatus(val required: Int, val available: Int)

    private val _status = MutableStateFlow(FetchStatus(0, 0))
    val status: StateFlow<FetchStatus> = _status

    /**
     * Ensures all land tiles within [radiusM] of the given point are cached locally.
     * Downloads run with bounded parallelism, nearest tiles first, and update [status] as they land.
     */
    suspend fun ensureTilesAround(lat: Double, lon: Double, radiusM: Double): FetchStatus =
        fetchMutex.withLock {
            withContext(Dispatchers.IO) {
                val manifest = manifestRepository.manifest.value ?: manifestRepository.refresh()
                if (manifest == null) return@withContext FetchStatus(0, 0).also { _status.value = it }
                val idx = indexFor(manifest)

                val entries = tileKeysAround(lat, lon, radiusM)
                    .mapNotNull { idx.land[it] }
                    .sortedBy { tileCenterDistanceM(it.key, lat, lon) }
                val available = AtomicInteger(entries.count { cachedFile(it).exists() })
                _status.value = FetchStatus(entries.size, available.get())

                val missing = entries.filterNot { cachedFile(it).exists() }
                val gate = Semaphore(AppConfig.TILE_DOWNLOAD_PARALLELISM)
                coroutineScope {
                    missing.map { entry ->
                        async {
                            gate.withPermit {
                                if (download(entry)) {
                                    _status.value = FetchStatus(entries.size, available.incrementAndGet())
                                }
                            }
                        }
                    }.awaitAll()
                }
                FetchStatus(entries.size, available.get()).also { _status.value = it }
            }
        }

    /**
     * Terrain elevation in metres MSL by bilinear interpolation.
     * Returns 0.0 for a listed sea tile, null when unknown (no manifest, outside the processed
     * region, or a land tile that is not cached yet).
     */
    fun elevationM(lat: Double, lon: Double): Double? {
        val manifest = manifestRepository.manifest.value ?: return null
        val idx = indexFor(manifest)

        val latIdx = floor(lat * AppConfig.TILE_INDEX_SCALE).toInt()
        val lonIdx = floor(lon * AppConfig.TILE_INDEX_SCALE).toInt()
        val key = intKey(latIdx, lonIdx)
        val entry = idx.land[key]
        if (entry == null) {
            if (key in idx.sea) return 0.0
            // Legacy manifest: coverage box rule (inside coverage and not land = sea).
            return if (!manifest.seaTilesListed && manifest.coverage.contains(lat, lon)) 0.0 else null
        }
        val grid = loadGrid(key, entry) ?: return null

        val n = AppConfig.TILE_SAMPLES
        val north = (latIdx + 1).toDouble() / AppConfig.TILE_INDEX_SCALE
        val west = lonIdx.toDouble() / AppConfig.TILE_INDEX_SCALE

        // Fractional grid coordinates: row grows southwards, col grows eastwards.
        val rowF = ((north - lat) * AppConfig.SAMPLES_PER_DEG).coerceIn(0.0, (n - 1).toDouble())
        val colF = ((lon - west) * AppConfig.SAMPLES_PER_DEG).coerceIn(0.0, (n - 1).toDouble())
        val r0 = floor(rowF).toInt().coerceAtMost(n - 2)
        val c0 = floor(colF).toInt().coerceAtMost(n - 2)
        val fr = rowF - r0
        val fc = colF - c0

        val z00 = grid[r0 * n + c0].toDouble()
        val z01 = grid[r0 * n + c0 + 1].toDouble()
        val z10 = grid[(r0 + 1) * n + c0].toDouble()
        val z11 = grid[(r0 + 1) * n + c0 + 1].toDouble()
        val top = z00 + (z01 - z00) * fc
        val bottom = z10 + (z11 - z10) * fc
        return top + (bottom - top) * fr
    }

    private fun indexFor(manifest: DataManifest): TileIndex {
        val current = index
        if (current != null && current.manifest === manifest) return current
        return TileIndex(manifest).also { index = it }
    }

    /** All tile keys whose extent intersects the square bounding the radius. */
    private fun tileKeysAround(lat: Double, lon: Double, radiusM: Double): List<Int> {
        val dLat = GeoMath.metersToLatDeg(radiusM)
        val dLon = GeoMath.metersToLonDeg(radiusM, lat)
        val s = AppConfig.TILE_INDEX_SCALE
        val iMin = floor((lat - dLat) * s).toInt()
        val iMax = floor((lat + dLat) * s).toInt()
        val jMin = floor((lon - dLon) * s).toInt()
        val jMax = floor((lon + dLon) * s).toInt()
        val keys = ArrayList<Int>()
        for (i in iMin..iMax) for (j in jMin..jMax) keys.add(intKey(i, j))
        return keys
    }

    private fun tileCenterDistanceM(key: String, lat: Double, lon: Double): Double {
        val parts = key.split('_')
        val cLat = (parts[0].toInt() + 0.5) / AppConfig.TILE_INDEX_SCALE
        val cLon = (parts[1].toInt() + 0.5) / AppConfig.TILE_INDEX_SCALE
        return GeoMath.distanceM(lat, lon, cLat, cLon)
    }

    /** Cache file name embeds the hash prefix, so a changed tile in the manifest forces re-download. */
    private fun cachedFile(entry: DataManifest.TileEntry) =
        File(tileDir, "${entry.key}_${entry.sha256.take(16)}.bin")

    private suspend fun download(entry: DataManifest.TileEntry): Boolean = try {
        val gz = http.getBytes("${AppConfig.DATA_BASE_URL}/${entry.path}")
        if (HttpClient.sha256Hex(gz) != entry.sha256) {
            Log.e(TAG, "Hash mismatch for tile ${entry.key}")
            false
        } else {
            val raw = GZIPInputStream(ByteArrayInputStream(gz)).use { it.readBytes() }
            val expected = AppConfig.TILE_SAMPLES * AppConfig.TILE_SAMPLES * 2
            if (raw.size != expected) {
                Log.e(TAG, "Tile ${entry.key} has ${raw.size} bytes, expected $expected")
                false
            } else {
                // Remove stale versions of the same tile before writing the new one.
                tileDir.listFiles { f -> f.name.startsWith("${entry.key}_") }?.forEach { it.delete() }
                val tmp = File(tileDir, "${entry.key}.tmp")
                tmp.writeBytes(raw)
                tmp.renameTo(cachedFile(entry))
                parseKey(entry.key)?.let { memory.remove(it) }
                true
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Tile ${entry.key} download failed", e)
        false
    }

    private fun loadGrid(key: Int, entry: DataManifest.TileEntry): ShortArray? {
        memory.get(key)?.let { return it }
        val file = cachedFile(entry)
        if (!file.exists()) return null
        return try {
            val bytes = file.readBytes()
            val grid = ShortArray(AppConfig.TILE_SAMPLES * AppConfig.TILE_SAMPLES)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(grid)
            memory.put(key, grid)
            grid
        } catch (e: Exception) {
            Log.e(TAG, "Corrupt cached tile ${entry.key}", e)
            file.delete()
            null
        }
    }

    companion object {
        private const val TAG = "DemTileRepository"

        // Integer key: offsets keep indices positive for any lat in [-90, 90], lon in [-180, 180].
        private const val LAT_OFFSET = 900
        private const val LON_OFFSET = 1800
        private const val LON_SPAN = 3601

        fun intKey(latIdx: Int, lonIdx: Int): Int = (latIdx + LAT_OFFSET) * LON_SPAN + (lonIdx + LON_OFFSET)

        fun parseKey(key: String): Int? {
            val parts = key.split('_')
            if (parts.size != 2) return null
            val i = parts[0].toIntOrNull() ?: return null
            val j = parts[1].toIntOrNull() ?: return null
            return intKey(i, j)
        }
    }
}
