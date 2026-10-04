// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.0
package com.galker.pointandidentify.ui

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.galker.pointandidentify.config.AppConfig

/**
 * App language (English / Hebrew). The choice is stored in SharedPreferences and applied at every
 * process start, so it behaves the same on all Android versions; English is the default.
 * Changing the language recreates the running Activity, which then reloads strings and layout direction.
 */
object LanguageManager {

    private const val PREFS_NAME = "settings"
    private const val KEY_LANGUAGE = "language"

    fun currentTag(context: Context): String {
        val saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_LANGUAGE, null)
        return if (saved == AppConfig.LANGUAGE_HEBREW_TAG || saved == AppConfig.LANGUAGE_ENGLISH_TAG) {
            saved
        } else {
            AppConfig.LANGUAGE_DEFAULT_TAG
        }
    }

    /** Applies the stored language; call once from Application.onCreate. */
    fun applyStored(context: Context) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(currentTag(context)))
    }

    /** Switches English <-> Hebrew, stores the choice and applies it (recreates the Activity). */
    fun toggle(context: Context) {
        val next = if (currentTag(context) == AppConfig.LANGUAGE_HEBREW_TAG) {
            AppConfig.LANGUAGE_ENGLISH_TAG
        } else {
            AppConfig.LANGUAGE_HEBREW_TAG
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_LANGUAGE, next).apply()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(next))
    }
}
