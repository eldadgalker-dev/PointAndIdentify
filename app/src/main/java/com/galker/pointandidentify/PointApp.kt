// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.3
package com.galker.pointandidentify

import android.app.Application
import com.galker.pointandidentify.data.ManifestRepository
import com.galker.pointandidentify.data.TargetRepository
import com.galker.pointandidentify.data.db.AppDatabase
import com.galker.pointandidentify.data.dem.DemTileRepository
import com.galker.pointandidentify.data.net.HttpClient
import com.galker.pointandidentify.update.UpdateManager

/**
 * Application-scoped service locator. Singletons live here, never in an Activity scope,
 * so background work (seeding, tile download) is not cancelled by Activity recreation.
 */
class PointApp : Application() {

    lateinit var httpClient: HttpClient
        private set
    lateinit var manifestRepository: ManifestRepository
        private set
    lateinit var demRepository: DemTileRepository
        private set
    lateinit var targetRepository: TargetRepository
        private set
    lateinit var updateManager: UpdateManager
        private set

    override fun onCreate() {
        super.onCreate()
        httpClient = HttpClient()
        manifestRepository = ManifestRepository(this, httpClient)
        demRepository = DemTileRepository(this, httpClient, manifestRepository)
        targetRepository = TargetRepository(
            context = this,
            dao = AppDatabase.get(this).targetDao(),
            http = httpClient,
            manifestRepository = manifestRepository
        )
        updateManager = UpdateManager(this, httpClient)
    }
}
