// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.0
package com.galker.pointandidentify.update

import android.content.Context
import com.galker.pointandidentify.BuildConfig
import com.galker.pointandidentify.config.AppConfig
import com.galker.pointandidentify.data.net.HttpClient
import kotlinx.coroutines.CancellationException
import kotlin.math.abs

// =============================================================
// Anonymous usage counting, with no identifier and no server of our own.
//   The release page counts downloads per asset. The app downloads one tiny public file:
//     install.json : once, on the first run of a fresh installation (not after an update)
//     launch.json  : once per installed version, on its first run
//   The request carries no device id, account, location or app data; like any download, GitHub sees the
//   network address. The statistics page (docs/stats.html) reads the counters from the Releases API.
//   Local builds (version name with a suffix) have no release and are never counted.
// =============================================================
class UsagePing(context: Context, private val http: HttpClient) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Sends the counters that are due for this installed version; failures are retried on later launches, a few times. */
    suspend fun sendIfDue() {
        val versionName = BuildConfig.VERSION_NAME
        val versionCode = BuildConfig.VERSION_CODE
        if (versionName.contains('-')) return
        if (prefs.getInt(KEY_DONE_CODE, 0) >= versionCode) return
        val base = "${AppConfig.RELEASE_TAG_URL}/v$versionName"
        try {
            if (!prefs.getBoolean(KEY_INSTALL_HANDLED, false)) {
                if (isFreshInstall()) http.getText("$base/${AppConfig.RELEASE_INSTALL_ASSET}")
                prefs.edit().putBoolean(KEY_INSTALL_HANDLED, true).apply()
            }
            http.getText("$base/${AppConfig.RELEASE_LAUNCH_ASSET}")
            prefs.edit().putInt(KEY_DONE_CODE, versionCode).apply()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Offline, or a release without these files: count the failure and stop after a few launches.
            val key = KEY_FAILURES_PREFIX + versionCode
            val failures = prefs.getInt(key, 0) + 1
            val edit = prefs.edit().putInt(key, failures)
            if (failures >= AppConfig.USAGE_PING_MAX_FAILURES) edit.putInt(KEY_DONE_CODE, versionCode)
            edit.apply()
        }
    }

    /** A fresh installation has never been updated: first-install and last-update times are equal. */
    @Suppress("DEPRECATION")
    private fun isFreshInstall(): Boolean {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        return abs(info.lastUpdateTime - info.firstInstallTime) < FRESH_INSTALL_TOLERANCE_MS
    }

    companion object {
        private const val PREFS_NAME = "usage_ping"
        private const val KEY_DONE_CODE = "done_version_code"
        private const val KEY_INSTALL_HANDLED = "install_handled"
        private const val KEY_FAILURES_PREFIX = "failures_"
        private const val FRESH_INSTALL_TOLERANCE_MS = 2_000L
    }
}
