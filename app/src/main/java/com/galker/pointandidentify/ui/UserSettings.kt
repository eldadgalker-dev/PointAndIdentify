// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.0
package com.galker.pointandidentify.ui

import android.content.Context

/** Small persistent user preferences (SharedPreferences). The language lives in LanguageManager. */
object UserSettings {

    private const val PREFS_NAME = "settings"
    private const val KEY_ZOOM_BAR_RIGHT = "zoom_bar_right"

    /** True when the zoom bar is on the right edge (default); false for the left edge. */
    fun zoomBarOnRight(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ZOOM_BAR_RIGHT, true)

    fun setZoomBarOnRight(context: Context, onRight: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_ZOOM_BAR_RIGHT, onRight).apply()
    }
}
