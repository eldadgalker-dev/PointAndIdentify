// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.1
package com.galker.pointandidentify.data

import android.content.Context
import android.util.Log
import com.galker.pointandidentify.config.AppConfig
import com.galker.pointandidentify.data.db.TargetDao
import com.galker.pointandidentify.data.db.TargetEntity
import com.galker.pointandidentify.data.net.HttpClient
import com.galker.pointandidentify.geo.GeoMath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

// =============================================================
// targets.json format (assets seed and data/targets.json in the repository)
//   { "version": <int>, "items": [ { "name": "...", "kind": "<TargetKind.code>",
//       "lat": <deg>, "lon": <deg>, "alt": <m MSL | null>, "h": <structure height m | null> } ] }
//   Missing "kind" (legacy settlements.json) = "settlement".
// =============================================================

/**
 * Seeds the table from the bundled asset before the first read (no race), and replaces it
 * atomically when the manifest announces a newer targets version.
 */
class TargetRepository(
    context: Context,
    private val dao: TargetDao,
    private val http: HttpClient,
    private val manifestRepository: ManifestRepository
) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("targets", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    @Volatile
    var totalCount: Int = 0
        private set

    /** Must complete before any query; idempotent. Seeding also resets the stored version. */
    suspend fun ensureSeeded() = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (dao.count() == 0) {
                val text = appContext.assets.open(SEED_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
                val (version, items) = parse(text)
                dao.replaceAll(items)
                prefs.edit().putInt(KEY_VERSION, version).apply()
            }
            totalCount = dao.count()
        }
    }

    /** Downloads the targets file when its manifest version is newer than the stored one. */
    suspend fun updateFromRemoteIfNewer(): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val entry = manifestRepository.manifest.value?.targets ?: return@withContext false
            if (entry.version <= prefs.getInt(KEY_VERSION, 0)) return@withContext false
            try {
                val bytes = http.getBytes("${AppConfig.DATA_BASE_URL}/${entry.path}")
                if (HttpClient.sha256Hex(bytes) != entry.sha256) {
                    Log.e(TAG, "Targets hash mismatch")
                    return@withContext false
                }
                val (version, items) = parse(String(bytes, Charsets.UTF_8))
                if (items.isEmpty()) return@withContext false
                dao.replaceAll(items)
                prefs.edit().putInt(KEY_VERSION, version).apply()
                totalCount = items.size
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Targets update failed", e)
                false
            }
        }
    }

    /** Targets within [minM, maxM] of the observer: SQL box prefilter, then exact distance. */
    suspend fun within(lat: Double, lon: Double, minM: Double, maxM: Double): List<TargetEntity> =
        withContext(Dispatchers.IO) {
            val dLat = GeoMath.metersToLatDeg(maxM)
            val dLon = GeoMath.metersToLonDeg(maxM, lat)
            dao.inBox(lat - dLat, lat + dLat, lon - dLon, lon + dLon)
                .filter { GeoMath.distanceM(lat, lon, it.latitude, it.longitude) in minM..maxM }
        }

    private fun parse(text: String): Pair<Int, List<TargetEntity>> {
        val root = JSONObject(text)
        val arr = root.getJSONArray("items")
        val list = ArrayList<TargetEntity>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                TargetEntity(
                    name = o.getString("name"),
                    kind = o.optString("kind", "settlement").ifBlank { "settlement" },
                    latitude = o.getDouble("lat"),
                    longitude = o.getDouble("lon"),
                    altitudeM = optDouble(o, "alt"),
                    heightM = optDouble(o, "h")
                )
            )
        }
        return root.getInt("version") to list
    }

    private fun optDouble(o: JSONObject, key: String): Double? =
        if (o.has(key) && !o.isNull(key)) o.getDouble(key) else null

    companion object {
        private const val TAG = "TargetRepository"
        private const val SEED_ASSET = "targets.json"
        private const val KEY_VERSION = "version"
    }
}
