// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.0
package com.galker.pointandidentify.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A place defined by the user (for example "Home"); coordinates are WGS84 decimal degrees. */
data class PrivatePoint(val name: String, val lat: Double, val lon: Double)

/**
 * Private points are kept apart from the downloaded target table: that table is replaced wholesale
 * on every data update, which would delete the user's own points.
 */
class PrivatePointStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun all(): List<PrivatePoint> {
        val text = prefs.getString(KEY_POINTS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(text)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                PrivatePoint(o.getString("name"), o.getDouble("lat"), o.getDouble("lon"))
            }
        } catch (e: Exception) {
            emptyList() // corrupted value: behave as empty rather than crash
        }
    }

    @Synchronized
    fun add(point: PrivatePoint) = save(all() + point)

    @Synchronized
    fun removeAt(index: Int) {
        val current = all()
        if (index in current.indices) save(current.filterIndexed { i, _ -> i != index })
    }

    private fun save(points: List<PrivatePoint>) {
        val arr = JSONArray()
        for (p in points) {
            arr.put(JSONObject().put("name", p.name).put("lat", p.lat).put("lon", p.lon))
        }
        prefs.edit().putString(KEY_POINTS, arr.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "private_points"
        private const val KEY_POINTS = "points"
    }
}
