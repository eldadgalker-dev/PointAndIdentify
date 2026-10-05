// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.11
package com.galker.pointandidentify.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.galker.pointandidentify.PointApp
import com.galker.pointandidentify.config.AppConfig
import com.galker.pointandidentify.data.db.TargetEntity
import com.galker.pointandidentify.data.dem.DemTileRepository
import com.galker.pointandidentify.domain.Candidate
import com.galker.pointandidentify.domain.CompassCheck
import com.galker.pointandidentify.domain.CompassReport
import com.galker.pointandidentify.domain.CompassVerdict
import com.galker.pointandidentify.domain.CrosshairWindow
import com.galker.pointandidentify.domain.FindGuidance
import com.galker.pointandidentify.domain.FindGuide
import com.galker.pointandidentify.domain.LineOfSightCalculator
import com.galker.pointandidentify.domain.PhonePose
import com.galker.pointandidentify.domain.TargetEvaluation
import com.galker.pointandidentify.domain.TargetSelector
import com.galker.pointandidentify.domain.TerrainSource
import com.galker.pointandidentify.domain.Visibility
import com.galker.pointandidentify.geo.GeoMath
import com.galker.pointandidentify.location.LocationProvider
import com.galker.pointandidentify.location.ObserverFix
import com.galker.pointandidentify.sensors.Orientation
import com.galker.pointandidentify.sensors.OrientationProvider
import com.galker.pointandidentify.update.RemoteVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Phase { INITIALIZING, WAITING_LOCATION, READY }

/** The place chosen with Find and where to turn the camera to reach it. */
data class FindState(val name: String, val distanceM: Double, val bearingDeg: Double, val guide: FindGuide)

data class UiState(
    val phase: Phase = Phase.INITIALIZING,
    val azimuthDeg: Double? = null,
    val compassCalibrated: Boolean = true,
    val cameraElevationDeg: Double? = null,
    val target: TargetEvaluation? = null,
    val candidates: List<Candidate> = emptyList(), // targets inside the crosshair window, best first
    val zoomRatio: Double = 1.0,
    val compass: CompassReport = CompassReport(CompassVerdict.CHECKING),
    val fix: ObserverFix? = null,
    val observerEyeAltM: Double? = null,
    val raised: Boolean = true, // phone raised (aiming) or flat; positions of targets are shown only when raised
    val find: FindState? = null,
    val tiles: DemTileRepository.FetchStatus = DemTileRepository.FetchStatus(0, 0),
    val targetsCount: Int = 0,
    val offline: Boolean = false
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val versionName: String) : UpdateState
    data class Available(val remote: RemoteVersion) : UpdateState
    data class Downloading(val percent: Int) : UpdateState
    data class ReadyToInstall(val apk: File) : UpdateState
    data object VerifyFailed : UpdateState
    data object Failed : UpdateState
}

/**
 * Orchestration: location -> (tile fetch, LOS for all nearby targets) once per significant move;
 * orientation -> cheap target selection on cached LOS results, throttled for the UI.
 */
@OptIn(FlowPreview::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val services = app as PointApp
    private val dem = services.demRepository
    private val targets = services.targetRepository
    private val manifest = services.manifestRepository
    private val updater = services.updateManager

    val orientationProvider = OrientationProvider(app)
    private val locationProvider = LocationProvider(app)
    private val losCalculator = LineOfSightCalculator(TerrainSource { lat, lon -> dem.elevationM(lat, lon) })

    private val evaluations = MutableStateFlow<List<TargetEvaluation>>(emptyList())

    // Find: the chosen place and its latest evaluation from the observer (bearing, range, apparent vertical angle).
    private val findTarget = MutableStateFlow<TargetEntity?>(null)
    private val findEval = MutableStateFlow<TargetEvaluation?>(null)
    private var raised = true

    /** One sensor/data snapshot for the throttled UI update. */
    private class Tick(val o: Orientation, val evals: List<TargetEvaluation>, val zoom: Double, val find: TargetEvaluation?)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    private val _update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val update: StateFlow<UpdateState> = _update

    @Volatile
    var hfovDeg: Double = AppConfig.DEFAULT_HFOV_DEG // un-zoomed horizontal FOV of the part of the image the screen shows

    private val zoom = MutableStateFlow(AppConfig.ZOOM_MIN_FALLBACK)

    /** True while an automatic (start-up) update check runs: "up to date" and network errors stay silent. */
    @Volatile
    var silentUpdateCheck = false
        private set

    /** Last compass report already shown to the user; kept here so recreating the Activity (language change) does not repeat it. */
    var lastReportedCompass: CompassReport? = null

    private var startupChecksDone = false
    private var updateCheckDone = false
    private var compassJob: Job? = null

    private var lastLosFix: ObserverFix? = null
    private var lastFetchFix: ObserverFix? = null
    private var losJob: Job? = null
    private var fetchJob: Job? = null
    private var dataReady = false

    init {
        viewModelScope.launch {
            targets.ensureSeeded() // seeding completes before the first query: no empty-list race
            _ui.value = _ui.value.copy(phase = Phase.WAITING_LOCATION, targetsCount = targets.totalCount)
            manifest.refresh()
            if (targets.updateFromRemoteIfNewer()) forceRecompute()
            _ui.value = _ui.value.copy(targetsCount = targets.totalCount, offline = manifest.offline.value)
            dataReady = true
            locationProvider.fix.value?.let { onFix(it) }
        }

        viewModelScope.launch {
            locationProvider.fix.filterNotNull().collect { onFix(it) }
        }

        viewModelScope.launch {
            dem.status.collect { s ->
                _ui.value = _ui.value.copy(tiles = s)
            }
        }

        viewModelScope.launch {
            combine(orientationProvider.orientation.filterNotNull(), evaluations, zoom, findEval) { o, evals, z, fe ->
                Tick(o, evals, z, fe)
            }
                .sample(AppConfig.UI_UPDATE_INTERVAL_MS)
                .collect { tick ->
                    val o = tick.o
                    val evals = tick.evals
                    val z = tick.zoom
                    // Zoom narrows the field of view, and with it the crosshair window.
                    val effectiveHfov = TargetSelector.effectiveHfovDeg(hfovDeg, z)
                    val selection = TargetSelector.select(evals, o.trueAzimuthDeg, effectiveHfov, z, o.cameraElevationDeg)
                    raised = PhonePose.isRaised(raised, o.cameraElevationDeg)
                    val circleDeg = CrosshairWindow.halfAngleDeg(effectiveHfov, z)
                    val find = tick.find?.let { e ->
                        FindState(
                            e.target.name, e.distanceM, e.bearingDeg,
                            FindGuidance.guide(e.bearingDeg, e.elevationAngleDeg, o.trueAzimuthDeg, o.cameraElevationDeg, circleDeg)
                        )
                    }
                    _ui.value = _ui.value.copy(
                        raised = raised,
                        find = find,
                        azimuthDeg = o.trueAzimuthDeg,
                        compassCalibrated = o.calibrated,
                        cameraElevationDeg = o.cameraElevationDeg,
                        target = selection.best,
                        candidates = selection.candidates,
                        zoomRatio = z
                    )
                }
        }
    }

    fun startLocation() = locationProvider.start()
    fun stopLocation() = locationProvider.stop()

    /** Free-text place search (coordinates, private points, target names, geocoder). */
    suspend fun searchPlaces(query: String): List<TargetEntity> = targets.search(query)

    /** Starts (or, with null, stops) guiding the camera to a place. */
    fun setFind(place: TargetEntity?) {
        findTarget.value = place
        if (place == null) findEval.value = null else forceRecompute()
    }

    fun onZoomChanged(ratio: Double) {
        if (ratio > 0.0) zoom.value = ratio
    }

    /** Full stop used by the exit button: no sensor or location listener may outlive the Activity. */
    fun shutdown() {
        compassJob?.cancel()
        locationProvider.stop()
        orientationProvider.stop()
    }

    // ===== Start-up checks =====

    /** Update check on every app launch (once per ViewModel); needs no permissions. */
    fun startUpdateCheckOnce() {
        if (updateCheckDone) return
        updateCheckDone = true
        checkForUpdate(silent = true)
        viewModelScope.launch { services.usagePing.sendIfDue() } // anonymous per-version counter, see UsagePing
    }

    /** Runs once per process after permissions are granted: compass health. */
    fun startStartupChecks() {
        if (startupChecksDone) return
        startupChecksDone = true
        runCompassCheck()
    }

    /**
     * Samples the orientation sensors for COMPASS_CHECK_DURATION_MS and rates them
     * (field strength, calibration accuracy, azimuth stability). The phone should be held still.
     */
    fun runCompassCheck() {
        compassJob?.cancel()
        _ui.value = _ui.value.copy(compass = CompassReport(CompassVerdict.CHECKING))
        compassJob = viewModelScope.launch {
            if (!orientationProvider.isAvailable || !orientationProvider.hasMagnetometer) {
                _ui.value = _ui.value.copy(compass = CompassReport(CompassVerdict.NO_SENSOR))
                return@launch
            }
            val azimuths = ArrayList<Double>()
            var lastField: Double? = null
            var lastCalibrated = false
            // collect() never completes on a StateFlow: the timeout is what ends the sampling window.
            withTimeoutOrNull(AppConfig.COMPASS_CHECK_DURATION_MS) {
                orientationProvider.orientation.filterNotNull().collect { o ->
                    azimuths.add(o.trueAzimuthDeg)
                    lastField = o.fieldStrengthUt ?: lastField
                    lastCalibrated = o.calibrated
                }
            }
            _ui.value = _ui.value.copy(compass = CompassCheck.evaluate(azimuths, lastField, lastCalibrated))
        }
    }

    private fun onFix(fix: ObserverFix) {
        _ui.value = _ui.value.copy(fix = fix, phase = if (dataReady) Phase.READY else _ui.value.phase)
        orientationProvider.updateDeclination(fix.lat, fix.lon, fix.mslAltitudeM ?: 0.0)
        if (!dataReady) return

        val needFetch = lastFetchFix?.let { moved(it, fix) > AppConfig.REFETCH_DISTANCE_M } ?: true
        if (needFetch && fetchJob?.isActive != true) {
            lastFetchFix = fix
            fetchJob = viewModelScope.launch {
                dem.ensureTilesAround(fix.lat, fix.lon, AppConfig.FETCH_RADIUS_M)
                _ui.value = _ui.value.copy(offline = manifest.offline.value)
                forceRecompute() // terrain changed: previous LOS results may be UNKNOWN
            }
        }

        val needLos = lastLosFix?.let { moved(it, fix) > AppConfig.LOS_RECALC_DISTANCE_M } ?: true
        if (needLos) recompute(fix)
    }

    /** Re-evaluates all targets now (called after the user's private points changed). */
    fun refreshTargets() = forceRecompute()

    private fun forceRecompute() {
        lastLosFix = null
        locationProvider.fix.value?.let { recompute(it) }
    }

    private fun recompute(fix: ObserverFix) {
        lastLosFix = fix
        losJob?.cancel()
        losJob = viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                val eyeAlt = observerEyeAltitude(fix)
                val nearby = targets.within(fix.lat, fix.lon, AppConfig.MIN_TARGET_RANGE_M, AppConfig.MAX_TARGET_RANGE_M)
                // At 50 km a full pass can take noticeable time; cancellation is checked per target
                // so a newer fix replaces a stale computation immediately.
                val evals = if (eyeAlt == null) {
                    emptyList()
                } else {
                    nearby.map {
                        ensureActive()
                        losCalculator.evaluate(fix.lat, fix.lon, eyeAlt, it)
                    }
                }
                // Find works at any range: without an eye altitude only bearing and range are known.
                val found = findTarget.value?.let { t ->
                    if (eyeAlt != null) {
                        losCalculator.evaluate(fix.lat, fix.lon, eyeAlt, t)
                    } else {
                        TargetEvaluation(
                            t, GeoMath.bearingDeg(fix.lat, fix.lon, t.latitude, t.longitude),
                            GeoMath.distanceM(fix.lat, fix.lon, t.latitude, t.longitude), Visibility.UNKNOWN
                        )
                    }
                }
                Triple(eyeAlt, evals, found)
            }
            _ui.value = _ui.value.copy(observerEyeAltM = result.first)
            evaluations.value = result.second
            findEval.value = result.third
        }
    }

    /** DEM ground + eye height is preferred: GPS vertical error is typically several times larger. */
    private fun observerEyeAltitude(fix: ObserverFix): Double? =
        dem.elevationM(fix.lat, fix.lon)?.let { it + AppConfig.EYE_HEIGHT_M } ?: fix.mslAltitudeM

    private fun moved(a: ObserverFix, b: ObserverFix) = GeoMath.distanceM(a.lat, a.lon, b.lat, b.lon)

    // ===== Self-update =====

    fun checkForUpdate(silent: Boolean = false) {
        val current = _update.value
        // ReadyToInstall is handled by the Activity directly (install retry), never re-checked here.
        if (current is UpdateState.Checking || current is UpdateState.Downloading) return
        if (current is UpdateState.ReadyToInstall && current.apk.exists()) return
        silentUpdateCheck = silent
        _update.value = UpdateState.Checking
        viewModelScope.launch {
            _update.value = try {
                updater.checkForUpdate()?.let { UpdateState.Available(it) }
                    ?: UpdateState.UpToDate(updater.installedVersionName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateState.Failed
            }
        }
    }

    fun downloadUpdate(remote: RemoteVersion) {
        _update.value = UpdateState.Downloading(0)
        viewModelScope.launch {
            _update.value = try {
                val apk = updater.download(remote) { pct -> _update.value = UpdateState.Downloading(pct.coerceAtLeast(0)) }
                UpdateState.ReadyToInstall(apk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                UpdateState.VerifyFailed
            } catch (e: Exception) {
                UpdateState.Failed
            }
        }
    }

    fun resetUpdateState() {
        if (_update.value !is UpdateState.ReadyToInstall) _update.value = UpdateState.Idle
    }

    override fun onCleared() {
        shutdown()
        super.onCleared()
    }
}
