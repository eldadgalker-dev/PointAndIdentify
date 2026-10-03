// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.1
package com.galker.pointandidentify.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.galker.pointandidentify.BuildConfig
import com.galker.pointandidentify.config.AppConfig
import com.galker.pointandidentify.data.net.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException

// =============================================================
// Release contract (produced by .github/workflows/release.yml)
//   Assets of the latest GitHub Release:
//     PointAndIdentify.apk     signed release APK
//     version.json  { "versionCode": <int>, "versionName": "<str>", "sha256": "<apk sha256 hex>" }
//   The "latest/download/<asset>" path redirects to the newest non-prerelease asset,
//   so no GitHub API call (and no API rate limit) is involved.
// =============================================================

data class RemoteVersion(val versionCode: Int, val versionName: String, val sha256: String)

class UpdateManager(context: Context, private val http: HttpClient) {

    private val appContext = context.applicationContext
    private val updateDir = File(appContext.cacheDir, "updates")
    private val apkFile = File(updateDir, AppConfig.RELEASE_APK_ASSET)

    val installedVersionName: String = BuildConfig.VERSION_NAME

    /** Returns the remote version when it is newer than the installed one, null otherwise. */
    suspend fun checkForUpdate(): RemoteVersion? = withContext(Dispatchers.IO) {
        val json = JSONObject(http.getText("${AppConfig.RELEASE_LATEST_URL}/${AppConfig.RELEASE_VERSION_ASSET}"))
        val remote = RemoteVersion(
            versionCode = json.getInt("versionCode"),
            versionName = json.getString("versionName"),
            sha256 = json.getString("sha256").lowercase()
        )
        if (remote.versionCode > BuildConfig.VERSION_CODE) remote else null
    }

    /** Downloads the APK and verifies its SHA-256 against version.json before it can be installed. */
    suspend fun download(remote: RemoteVersion, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        updateDir.mkdirs()
        updateDir.listFiles()?.forEach { it.delete() }
        http.download("${AppConfig.RELEASE_LATEST_URL}/${AppConfig.RELEASE_APK_ASSET}", apkFile, onProgress)
        if (HttpClient.sha256Hex(apkFile) != remote.sha256) {
            apkFile.delete()
            throw SecurityException("APK hash mismatch")
        }
        apkFile
    }

    /** Android 8+: the user must allow this app to install packages (per-app setting). */
    fun canInstall(): Boolean = appContext.packageManager.canRequestPackageInstalls()

    fun unknownSourcesSettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${appContext.packageName}"))

    /**
     * Hands the verified APK to the system installer. The installer itself rejects an APK whose
     * signing certificate differs from the installed app, which protects against substitution.
     */
    fun installIntent(apk: File): Intent {
        if (!apk.exists()) throw IOException("APK missing")
        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", apk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
