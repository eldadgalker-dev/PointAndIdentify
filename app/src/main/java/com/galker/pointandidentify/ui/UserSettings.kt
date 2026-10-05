// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.2
package com.galker.pointandidentify.ui

import android.content.Context
import com.galker.pointandidentify.config.AppConfig

/** Small persistent user preferences (SharedPreferences). The language lives in LanguageManager. */
object UserSettings {

    private const val PREFS_NAME = "settings"
    private const val KEY_ZOOM_BAR_RIGHT = "zoom_bar_right"
    private const val KEY_OBSERVER_HEIGHT = "observer_height_m"
    private const val KEY_SENSOR_HEIGHT = "sensor_height_enabled"

    /** True when the zoom bar is on the right edge (default); false for the left edge. */
    fun zoomBarOnRight(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ZOOM_BAR_RIGHT, true)

    /** Observer height above the ground, m: eye level on the ground by default; larger on a high floor, roof or lookout. */
    fun observerHeightM(context: Context): Double {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_OBSERVER_HEIGHT, AppConfig.EYE_HEIGHT_M.toFloat()).toDouble()
        return stored.coerceIn(0.0, AppConfig.OBSERVER_HEIGHT_MAX_M)
    }

    /** True (default) when the barometer + GPS altitude may raise the observer height above the manual value. */
    fun sensorHeightEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_SENSOR_HEIGHT, true)

    fun setSensorHeightEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_SENSOR_HEIGHT, enabled).apply()
    }

    fun setObserverHeightM(context: Context, heightM: Double) {
        val value = heightM.coerceIn(0.0, AppConfig.OBSERVER_HEIGHT_MAX_M).toFloat()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putFloat(KEY_OBSERVER_HEIGHT, value).apply()
    }

    fun setZoomBarOnRight(context: Context, onRight: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_ZOOM_BAR_RIGHT, onRight).apply()
    }
}
