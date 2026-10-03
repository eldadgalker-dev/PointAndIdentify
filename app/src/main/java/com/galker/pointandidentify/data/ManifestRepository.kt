// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.1
package com.galker.pointandidentify.data

import android.content.Context
import android.util.Log
import com.galker.pointandidentify.config.AppConfig
import com.galker.pointandidentify.data.net.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Loads manifest.json from GitHub, caches it on disk, and falls back to the cache when offline. */
class ManifestRepository(context: Context, private val http: HttpClient) {

    private val cacheFile = File(context.filesDir, AppConfig.MANIFEST_FILE)
    private val mutex = Mutex()

    private val _manifest = MutableStateFlow<DataManifest?>(null)
    val manifest: StateFlow<DataManifest?> = _manifest

    private val _offline = MutableStateFlow(false)
    val offline: StateFlow<Boolean> = _offline

    /** Fetches the remote manifest once per call; returns the best available manifest. */
    suspend fun refresh(): DataManifest? = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val text = http.getText("${AppConfig.DATA_BASE_URL}/${AppConfig.MANIFEST_FILE}")
                val parsed = DataManifest.parse(text) // parse before caching: never persist a corrupt file
                cacheFile.writeText(text)
                _offline.value = false
                _manifest.value = parsed
            } catch (e: Exception) {
                Log.w(TAG, "Remote manifest unavailable, using cache", e)
                _offline.value = true
                if (_manifest.value == null) _manifest.value = loadCached()
            }
            _manifest.value
        }
    }

    private fun loadCached(): DataManifest? = try {
        if (cacheFile.exists()) DataManifest.parse(cacheFile.readText()) else null
    } catch (e: Exception) {
        Log.e(TAG, "Cached manifest is corrupt", e)
        null
    }

    companion object {
        private const val TAG = "ManifestRepository"
    }
}
