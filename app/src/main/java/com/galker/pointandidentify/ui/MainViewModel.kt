// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.15
package com.galker.pointandidentify.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.galker.pointandidentify.PointApp
import com.galker.pointandidentify.config.AppConfig
import com.galker.pointandidentify.data.db.TargetEntity
import com.galker.pointandidentify.data.dem.DemTileRepository
import com.galker.pointandidentify.domain.AltitudeEstimate
import com.galker.pointandidentify.domain.BaroGpsFusion
import com.galker.pointandidentify.domain.Candidate
import com.galker.pointandidentify.domain.CompassCheck
import com.galker.pointandidentify.domain.CompassReport
import com.galker.pointandidentify.domain.CompassVerdict
import com.galker.pointandidentify.domain.CrosshairWindow
import com.galker.pointandidentify.domain.FindGuidance
import com.galker.pointandidentify.domain.FindGuide
import com.galker.pointandidentify.domain.LookingDown
import com.galker.pointandidentify.domain.TargetKind
import com.galker.pointandidentify.domain.LineOfSightCalculator
import com.galker.pointandidentify.domain.PhonePose
import com.galker.pointandidentify.domain.Selection
import com.galker.pointandidentify.domain.TargetEvaluation
import com.galker.pointandidentify.domain.TargetSelector
import com.galker.pointandidentify.domain.TerrainSource
import com.galker.pointandidentify.domain.Visibility
import com.galker.pointandidentify.geo.GeoMath
import com.galker.pointandidentify.location.LocationProvider
import com.galker.pointandidentify.location.ObserverFix
import com.galker.pointandidentify.sensors.Orientation
import com.galker.pointandidentify.sensors.OrientationProvider
import com.galker.pointandidentify.sensors.PressureProvider
import com.galker.pointandidentify.update.RemoteVersion
import com.galker.pointandidentify.update.UpdateSchedule
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
import kotlin.math.abs
import kotlin.math.max

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
    val sensorHeightM: Double? = null,  // height above ground from barometer + GPS, null when unavailable
    val sensorSigmaM: Double? = null,   // its standard deviation
    val cityMode: Boolean = false, // camera pointing down: the target is the current city, whatever the azimuth
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
    val pressureProvider = PressureProvider(app)
    private val altitudeFusion = BaroGpsFusion()
    private var lastEyeAlt: Double? = null
    private val locationProvider = LocationProvider(app)
    private val losCalculator = LineOfSightCalculator(TerrainSource { lat, lon -> dem.elevationM(lat, lon) })

    private val evaluations = MutableStateFlow<List<TargetEvaluation>>(emptyList())

    // Find: the chosen place and its latest evaluation from the observer (bearing, range, apparent vertical angle).
    private val findTarget = MutableStateFlow<TargetEntity?>(null)
    private val findEval = MutableStateFlow<TargetEvaluation?>(null)
    private var raised = true

    // Current city: the nearest settlement (with hysteresis, so GPS jitter does not make it jump); shown when the camera points down.
    private val cityEval = MutableStateFlow<TargetEvaluation?>(null)
    @Volatile
    private var currentCity: TargetEntity? = null
    private var lookingDown = false

    /** One sensor/data snapshot for the throttled UI update. */
    private class Tick(
        val o: Orientation, val evals: List<TargetEvaluation>, val zoom: Double,
        val find: TargetEvaluation?, val city: TargetEvaluation?
    )

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
    private var lastUpdateAttemptMs = 0L // SystemClock.elapsedRealtime of the last automatic or manual check; 0 = none yet
    private var lastUpdateSucceeded = false
    private var usagePingStarted = false
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
            combine(orientationProvider.orientation.filterNotNull(), evaluations, zoom, findEval, cityEval) { o, evals, z, fe, ce ->
                Tick(o, evals, z, fe, ce)
            }
                .sample(AppConfig.UI_UPDATE_INTERVAL_MS)
                .collect { tick ->
                    val o = tick.o
                    val evals = tick.evals
                    val z = tick.zoom
                    // Zoom narrows the field of view, and with it the crosshair window.
                    val effectiveHfov = TargetSelector.effectiveHfovDeg(hfovDeg, z)
                    var selection = TargetSelector.select(evals, o.trueAzimuthDeg, effectiveHfov, z, o.cameraElevationDeg)
                    raised = PhonePose.isRaised(raised, o.cameraElevationDeg)
                    // Pointing down (phone on a table): the compass direction means nothing, so only the current city is shown.
                    lookingDown = LookingDown.isLookingDown(lookingDown, o.cameraElevationDeg)
                    val city = tick.city
                    val cityMode = lookingDown && city != null
                    if (cityMode && city != null) selection = Selection(city, 0.0, listOf(Candidate(city, 0.0, 0.0)))
                    val circleDeg = CrosshairWindow.halfAngleDeg(effectiveHfov, z)
                    val find = tick.find?.let { e ->
                        FindState(
                            e.target.name, e.distanceM, e.bearingDeg,
                            FindGuidance.guide(e.bearingDeg, e.elevationAngleDeg, o.trueAzimuthDeg, o.cameraElevationDeg, circleDeg)
                        )
                    }
                    _ui.value = _ui.value.copy(
                        raised = raised,
                        cityMode = cityMode,
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
        pressureProvider.stop()
    }

    // ===== Start-up checks =====

    /**
     * Automatic update check: on every launch, again when the user returns after UPDATE_CHECK_INTERVAL_MS,
     * and after UPDATE_RETRY_INTERVAL_MS when the last check failed. Needs no permissions; silent unless an update exists.
     */
    fun startUpdateCheckIfDue() {
        if (UpdateSchedule.isDue(lastUpdateAttemptMs, lastUpdateSucceeded, SystemClock.elapsedRealtime())) {
            checkForUpdate(silent = true)
        }
        if (!usagePingStarted) { // once per launch; UsagePing itself sends once per installed version
            usagePingStarted = true
            viewModelScope.launch { services.usagePing.sendIfDue() } // anonymous per-version counter, see UsagePing
        }
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

    /** Pairs this GPS fix with the current pressure so the filter can anchor the barometer to the GPS altitude. */
    private fun updateAltitudeFusion(fix: ObserverFix) {
        val pressure = pressureProvider.pressureHpa.value ?: return
        val altitude = fix.mslAltitudeM ?: return
        val sigma = fix.verticalAccuracyM?.toDouble() ?: AppConfig.ALT_GPS_SIGMA_DEFAULT_M
        altitudeFusion.update(SystemClock.elapsedRealtime(), altitude, sigma, pressure)
    }

    private fun sensorAltitude(): AltitudeEstimate? {
        val pressure = pressureProvider.pressureHpa.value ?: return null
        return altitudeFusion.estimate(pressure)
    }

    private fun onFix(fix: ObserverFix) {
        updateAltitudeFusion(fix)
        val sensor = sensorAltitude()
        val ground = dem.elevationM(fix.lat, fix.lon)
        _ui.value = _ui.value.copy(
            fix = fix, phase = if (dataReady) Phase.READY else _ui.value.phase,
            sensorHeightM = if (sensor != null && ground != null) sensor.altM - ground else null,
            sensorSigmaM = sensor?.sigmaM
        )
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

        // A change of the observer altitude (stairs, lift) also needs a new visibility pass, even when standing still.
        val eyeNow = observerEyeAltitude(fix)
        val previousEye = lastEyeAlt
        val eyeChanged = eyeNow != null && previousEye != null && abs(eyeNow - previousEye) > AppConfig.ALT_RECALC_M
        val needLos = lastLosFix?.let { moved(it, fix) > AppConfig.LOS_RECALC_DISTANCE_M } ?: true
        if (needLos || eyeChanged) recompute(fix)
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
                Pass(eyeAlt, evals, found, evaluateCity(fix, eyeAlt))
            }
            lastEyeAlt = result.eyeAlt
            _ui.value = _ui.value.copy(observerEyeAltM = result.eyeAlt)
            evaluations.value = result.evals
            findEval.value = result.found
            cityEval.value = result.city
        }
    }

    /** Result of one visibility pass. */
    private class Pass(
        val eyeAlt: Double?, val evals: List<TargetEvaluation>, val found: TargetEvaluation?, val city: TargetEvaluation?
    )

    /**
     * The current city = the nearest SETTLEMENT within CITY_SEARCH_RADIUS_M. Another settlement replaces the previous
     * one only when it is CITY_SWITCH_MARGIN_M nearer, so GPS jitter (tens to hundreds of metres indoors) cannot flip it.
     */
    private suspend fun evaluateCity(fix: ObserverFix, eyeAlt: Double?): TargetEvaluation? {
        val near = targets.within(fix.lat, fix.lon, 0.0, AppConfig.CITY_SEARCH_RADIUS_M)
            .filter { it.targetKind == TargetKind.SETTLEMENT }
        fun distance(t: TargetEntity) = GeoMath.distanceM(fix.lat, fix.lon, t.latitude, t.longitude)
        val nearest = near.minByOrNull { distance(it) }
        val previous = currentCity
        val chosen = when {
            nearest == null -> null
            previous == null -> nearest
            near.any { it.name == previous.name && it.latitude == previous.latitude && it.longitude == previous.longitude } -> {
                val keep = near.first { it.name == previous.name && it.latitude == previous.latitude && it.longitude == previous.longitude }
                if (distance(nearest) + AppConfig.CITY_SWITCH_MARGIN_M < distance(keep)) nearest else keep
            }
            else -> nearest
        }
        currentCity = chosen
        if (chosen == null) return null
        return if (eyeAlt != null) {
            losCalculator.evaluate(fix.lat, fix.lon, eyeAlt, chosen)
        } else {
            TargetEvaluation(
                chosen, GeoMath.bearingDeg(fix.lat, fix.lon, chosen.latitude, chosen.longitude),
                distance(chosen), Visibility.UNKNOWN
            )
        }
    }

    /**
     * Observer eye altitude, m MSL.
     *   base   = DEM ground + the height above ground chosen in Settings (GPS vertical error is several times larger).
     *   sensor = barometer + GPS altitude, used (when enabled and certain enough) only to RAISE the base, and only by
     *            its lower bound (altitude - ALT_LOWER_BOUND_SIGMAS * sigma): on a high floor or a roof the terrain model
     *            would otherwise place the observer at street level and nearby buildings would hide every target.
     */
    private fun observerEyeAltitude(fix: ObserverFix): Double? {
        val ground = dem.elevationM(fix.lat, fix.lon) ?: return fix.mslAltitudeM
        val base = ground + UserSettings.observerHeightM(getApplication<Application>())
        if (!UserSettings.sensorHeightEnabled(getApplication<Application>())) return base
        val sensor = sensorAltitude() ?: return base
        if (sensor.sigmaM > AppConfig.ALT_AUTO_MAX_SIGMA_M) return base
        val bound = sensor.altM - AppConfig.ALT_LOWER_BOUND_SIGMAS * sensor.sigmaM
        return max(base, bound).coerceAtMost(ground + AppConfig.OBSERVER_HEIGHT_MAX_M)
    }

    private fun moved(a: ObserverFix, b: ObserverFix) = GeoMath.distanceM(a.lat, a.lon, b.lat, b.lon)

    // ===== Self-update =====

    fun checkForUpdate(silent: Boolean = false) {
        val current = _update.value
        // ReadyToInstall is handled by the Activity directly (install retry), never re-checked here.
        if (current is UpdateState.Checking || current is UpdateState.Downloading) return
        if (current is UpdateState.ReadyToInstall && current.apk.exists()) return
        silentUpdateCheck = silent
        lastUpdateAttemptMs = SystemClock.elapsedRealtime()
        _update.value = UpdateState.Checking
        viewModelScope.launch {
            val result = try {
                updater.checkForUpdate()?.let { UpdateState.Available(it) }
                    ?: UpdateState.UpToDate(updater.installedVersionName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateState.Failed
            }
            lastUpdateSucceeded = result !is UpdateState.Failed
            _update.value = result
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
