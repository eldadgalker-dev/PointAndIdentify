// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.3
package com.galker.pointandidentify.ui

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.ScaleGestureDetector
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.ZoomState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.galker.pointandidentify.R
import com.galker.pointandidentify.capture.PhotoExporter
import com.galker.pointandidentify.capture.PhotoMeta
import com.galker.pointandidentify.config.AppConfig
import com.galker.pointandidentify.databinding.ActivityMainBinding
import com.galker.pointandidentify.domain.CompassReport
import com.galker.pointandidentify.domain.CompassVerdict
import com.galker.pointandidentify.domain.TargetKind
import com.galker.pointandidentify.domain.Visibility
import com.galker.pointandidentify.update.RemoteVersion
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** UI shell only: camera binding, permissions, rendering of ViewModel state. No domain logic here. */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val vm: MainViewModel by viewModels()

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var photoExporter: PhotoExporter
    private var imageCapture: ImageCapture? = null
    private var lastUpdateDialogFor: RemoteVersion? = null
    private var autoInstallPromptedFor: File? = null // installer is auto-launched once per APK; later via button

    private var camera: Camera? = null
    private var zoomLive: LiveData<ZoomState>? = null
    private var minZoom = AppConfig.ZOOM_MIN_FALLBACK
    private var maxZoom = AppConfig.ZOOM_MIN_FALLBACK
    private lateinit var scaleDetector: ScaleGestureDetector
    private var lastCompassReport: CompassReport? = null // identity-compared: each finished check is a new object
    private var exiting = false

    /** Camera zoom state -> label, slider position and the ViewModel (which narrows the selection window). */
    private val zoomObserver = Observer<ZoomState> { st ->
        minZoom = st.minZoomRatio.toDouble()
        maxZoom = st.maxZoomRatio.toDouble()
        val z = st.zoomRatio.toDouble()
        vm.onZoomChanged(z)
        binding.zoomText.text = getString(R.string.zoom_label, z)
        binding.zoomSlider.isEnabled = maxZoom > minZoom + 1e-6
        val t = zoomToSlider(z).toFloat()
        if (abs(binding.zoomSlider.value - t) > 0.001f) binding.zoomSlider.value = t
    }

    private val requiredPermissions: Array<String> by lazy {
        buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.toTypedArray()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val ok = result[Manifest.permission.CAMERA] == true && result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (ok) startSystems() else Toast.makeText(this, R.string.perm_missing, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Single background executor for capture callbacks: decoding never runs on the UI thread.
        cameraExecutor = Executors.newSingleThreadExecutor()
        photoExporter = PhotoExporter(applicationContext)

        binding.captureButton.setOnClickListener { capture() }
        binding.updateButton.setOnClickListener { onUpdateClicked() }
        binding.exitButton.setOnClickListener { confirmExit() }
        binding.identifyButton.setOnClickListener { showIdentify() }

        // Zoom: slider (log scale) and pinch gesture both end in setZoom().
        binding.zoomSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) setZoom(sliderToZoom(value.toDouble()))
        }
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val current = camera?.cameraInfo?.zoomState?.value?.zoomRatio?.toDouble() ?: return false
                setZoom(current * detector.scaleFactor)
                return true
            }
        })
        binding.viewFinder.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            true
        }

        if (hasCorePermissions()) startSystems() else permissionLauncher.launch(requiredPermissions)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.ui.collect { render(it) } }
                launch { vm.update.collect { renderUpdate(it) } }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.orientationProvider.start()
        if (hasCorePermissions()) {
            vm.startLocation()
            vm.startStartupChecks() // compass health + update check; runs once per process
        }
    }

    override fun onPause() {
        super.onPause()
        vm.orientationProvider.stop()
        vm.stopLocation()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        // Full shutdown requested by the user: end the process so nothing keeps running in the background.
        if (exiting) Process.killProcess(Process.myPid())
    }

    // ===== Exit =====

    private fun confirmExit() {
        AlertDialog.Builder(this)
            .setTitle(R.string.exit_title)
            .setMessage(R.string.exit_msg)
            .setPositiveButton(R.string.dialog_yes) { _, _ -> exitApp() }
            .setNegativeButton(R.string.dialog_no, null)
            .show()
    }

    /** Stops sensors, location and camera, removes the task, then kills the process (see onDestroy). */
    private fun exitApp() {
        exiting = true
        vm.shutdown()
        try {
            ProcessCameraProvider.getInstance(this).get().unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "Camera unbind on exit failed", e)
        }
        finishAndRemoveTask()
        // Fallback if onDestroy is delayed by the system.
        Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, EXIT_KILL_DELAY_MS)
    }

    // ===== Zoom =====

    /** Slider position 0..1 <-> zoom ratio, logarithmic so each slider step feels like the same magnification step. */
    private fun sliderToZoom(t: Double): Double =
        if (maxZoom > minZoom) minZoom * (maxZoom / minZoom).pow(t.coerceIn(0.0, 1.0)) else minZoom

    private fun zoomToSlider(z: Double): Double =
        if (maxZoom > minZoom) (ln(z / minZoom) / ln(maxZoom / minZoom)).coerceIn(0.0, 1.0) else 0.0

    private fun setZoom(ratio: Double) {
        val cam = camera ?: return
        cam.cameraControl.setZoomRatio(ratio.coerceIn(minZoom, maxZoom).toFloat())
    }

    private fun hasCorePermissions() = listOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
        .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun startSystems() {
        startCamera()
        vm.startLocation()
    }

    // ===== Camera =====

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(binding.viewFinder.surfaceProvider) }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
            try {
                provider.unbindAll()
                val bound = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                camera = bound
                horizontalFovDeg(bound)?.let { vm.hfovDeg = it }
                zoomLive?.removeObserver(zoomObserver)
                zoomLive = bound.cameraInfo.zoomState.also { it.observe(this, zoomObserver) }
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
                Toast.makeText(this, R.string.camera_error, Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Horizontal FOV in portrait = FOV across the sensor's short side:
     * hfov = 2 * atan(sensorHeight / (2 * focalLength)). Used to clamp target selection tolerance.
     */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun horizontalFovDeg(camera: Camera): Double? = try {
        val info = Camera2CameraInfo.from(camera.cameraInfo)
        val size = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val focal = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        if (size != null && focal != null && focal > 0f) {
            val shortSide = minOf(size.width, size.height).toDouble()
            Math.toDegrees(2.0 * atan(shortSide / (2.0 * focal)))
        } else null
    } catch (e: Exception) {
        null
    }

    private fun capture() {
        val ic = imageCapture ?: return
        val state = vm.ui.value
        val content = buildPhotoContent(state)
        val t = state.target
        val meta = PhotoMeta(
            lat = state.fix?.lat,
            lon = state.fix?.lon,
            altitudeM = state.observerEyeAltM,
            trueAzimuthDeg = state.azimuthDeg,
            destLat = t?.target?.latitude,
            destLon = t?.target?.longitude,
            destBearingDeg = t?.bearingDeg,
            destDistanceM = t?.distanceM
        )

        ic.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                lifecycleScope.launch {
                    val ok = try {
                        photoExporter.export(image, content, meta)
                        true
                    } catch (e: Exception) {
                        Log.e(TAG, "Photo export failed", e)
                        false
                    }
                    Toast.makeText(
                        this@MainActivity,
                        if (ok) R.string.photo_saved else R.string.photo_save_error,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e(TAG, "Capture failed", exception)
                runOnUiThread { Toast.makeText(this@MainActivity, R.string.capture_error, Toast.LENGTH_SHORT).show() }
            }
        })
    }

    // ===== Rendering =====

    private fun render(state: UiState) {
        binding.overlayView.content = buildLiveContent(state)

        val status = StringBuilder(
            getString(R.string.data_status, state.tiles.available, state.tiles.required, state.targetsCount)
        )
        status.append('\n').append(compassStatusText(state.compass))
        if (state.offline) status.append('\n').append(getString(R.string.data_offline))
        if (!state.compassCalibrated) status.append('\n').append(getString(R.string.compass_uncalibrated))
        binding.dataStatusText.text = status
        handleCompassReport(state.compass)
    }

    // ===== Compass health =====

    private fun compassStatusText(r: CompassReport): String = when (r.verdict) {
        CompassVerdict.CHECKING -> getString(R.string.compass_status_checking)
        CompassVerdict.OK -> r.fieldUt?.let { getString(R.string.compass_status_ok, it) }
            ?: getString(R.string.compass_status_ok_plain)
        else -> getString(R.string.compass_status_bad)
    }

    private fun compassMessage(r: CompassReport): String = when (r.verdict) {
        CompassVerdict.NO_SENSOR -> getString(R.string.compass_msg_no_sensor)
        CompassVerdict.UNCALIBRATED -> getString(R.string.compass_msg_uncalibrated)
        CompassVerdict.INTERFERENCE -> getString(
            R.string.compass_msg_interference, r.fieldUt ?: 0.0,
            AppConfig.COMPASS_FIELD_MIN_UT, AppConfig.COMPASS_FIELD_MAX_UT
        )
        CompassVerdict.UNSTABLE -> getString(R.string.compass_msg_unstable, r.spreadDeg ?: 0.0)
        CompassVerdict.OK, CompassVerdict.CHECKING -> ""
    }

    /** Each finished check is reported once: a toast when healthy, a dialog with advice and a re-check otherwise. */
    private fun handleCompassReport(r: CompassReport) {
        if (r.verdict == CompassVerdict.CHECKING || r === lastCompassReport) return
        lastCompassReport = r
        if (r.verdict == CompassVerdict.OK) {
            toast(compassStatusText(r))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.compass_title)
            .setMessage(compassMessage(r))
            .setPositiveButton(R.string.compass_recheck) { _, _ -> vm.runCompassCheck() }
            .setNegativeButton(R.string.compass_continue, null)
            .show()
    }

    // ===== Identify =====

    /** Lists the targets inside the crosshair window (ranked), so the pointed target can be confirmed. */
    private fun showIdentify() {
        val s = vm.ui.value
        val message = if (s.candidates.isEmpty()) {
            getString(R.string.identify_none)
        } else {
            s.candidates.mapIndexed { i, c ->
                val e = c.evaluation
                val vis = when (e.visibility) {
                    Visibility.VISIBLE -> R.string.identify_visible
                    Visibility.OBSTRUCTED -> R.string.identify_hidden
                    Visibility.UNKNOWN -> R.string.identify_unknown
                }
                getString(
                    R.string.identify_line, i + 1, e.target.name, getString(kindLabel(e.target.targetKind)),
                    e.distanceM / 1000.0, c.angleDiffDeg, getString(vis)
                )
            }.joinToString("\n\n")
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.identify_title, s.zoomRatio))
            .setMessage(message)
            .setPositiveButton(R.string.dialog_close, null)
            .show()
    }

    private fun buildLiveContent(state: UiState): OverlayContent {
        val az = state.azimuthDeg?.roundToInt()?.mod(360)
        val base = when {
            state.phase == Phase.INITIALIZING -> OverlayContent(getString(R.string.status_initializing), "")
            state.fix == null -> OverlayContent(getString(R.string.status_waiting_location), "")
            else -> targetContent(state, az)
        }
        return base.copy(infoLines = infoLines(state))
    }

    private fun targetContent(state: UiState, az: Int?): OverlayContent {
        val azText = az?.let { getString(R.string.status_azimuth, it) } ?: ""
        val t = state.target ?: return OverlayContent(getString(R.string.status_no_target), azText)
        val km = t.distanceM / 1000.0
        val bearing = t.bearingDeg.roundToInt().mod(360)
        val name = t.target.name
        return when (t.visibility) {
            Visibility.VISIBLE -> OverlayContent(
                name, getString(R.string.target_visible_details, km, bearing), visible = true
            )
            Visibility.OBSTRUCTED -> OverlayContent(
                getString(R.string.target_hidden_name, name),
                getString(R.string.target_hidden_details, (t.obstructionDistanceM ?: 0.0) / 1000.0, bearing)
            )
            Visibility.UNKNOWN -> OverlayContent(
                getString(R.string.target_unknown_name, name),
                getString(R.string.target_visible_details, km, bearing)
            )
        }
    }

    /**
     * Data block shown on screen and burned into the photo, one group (row) per topic:
     *   observer position / accuracy / altitude, camera direction (azimuth + vertical angle),
     *   target kind / position / altitude, and target geometry (range, bearing, vertical angle, clearance).
     */
    private fun infoLines(state: UiState): List<String> {
        val groups = ArrayList<String>(4)
        state.fix?.let { fix ->
            groups.add(
                listOf(
                    getString(R.string.info_observer_pos, fix.lat, fix.lon),
                    getString(R.string.info_observer_acc, fix.horizontalAccuracyM.roundToInt(), altText(state.observerEyeAltM))
                ).joinToString("\n")
            )
        }
        state.azimuthDeg?.let { az ->
            groups.add(
                listOf(
                    getString(R.string.info_direction, az.roundToInt().mod(360), state.cameraElevationDeg ?: 0.0),
                    getString(R.string.info_zoom, state.zoomRatio)
                ).joinToString("\n")
            )
        }
        state.target?.let { t ->
            groups.add(
                listOf(
                    getString(R.string.info_target_name, t.target.name),
                    getString(R.string.info_target_kind, getString(kindLabel(t.target.targetKind)), altText(t.topAltM)),
                    getString(R.string.info_target_pos, t.target.latitude, t.target.longitude)
                ).joinToString("\n")
            )
            val clearance = t.minClearanceM?.let { getString(R.string.info_meters, it.roundToInt()) }
                ?: getString(R.string.info_none)
            groups.add(
                listOf(
                    getString(R.string.info_geometry_range, t.distanceM / 1000.0, t.bearingDeg),
                    getString(R.string.info_geometry_angle, t.elevationAngleDeg ?: 0.0, clearance)
                ).joinToString("\n")
            )
        }
        return groups
    }

    private fun altText(altM: Double?): String =
        altM?.let { getString(R.string.info_meters, it.roundToInt()) } ?: getString(R.string.info_none)

    private fun kindLabel(kind: TargetKind): Int = when (kind) {
        TargetKind.SETTLEMENT -> R.string.kind_settlement
        TargetKind.PEAK -> R.string.kind_peak
        TargetKind.TOWER -> R.string.kind_tower
        TargetKind.LIGHTHOUSE -> R.string.kind_lighthouse
        TargetKind.CHIMNEY -> R.string.kind_chimney
        TargetKind.WATER_TOWER -> R.string.kind_water_tower
        TargetKind.CASTLE -> R.string.kind_castle
        TargetKind.RUINS -> R.string.kind_ruins
        TargetKind.MONUMENT -> R.string.kind_monument
        TargetKind.OTHER -> R.string.kind_other
    }

    /** Photo overlay: same data block, plus a footer with timestamp and app version. */
    private fun buildPhotoContent(state: UiState): OverlayContent {
        val live = buildLiveContent(state)
        val primary = if (state.target != null) getString(R.string.photo_target_prefix, live.primary) else live.primary
        val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val footer = getString(R.string.photo_footer, time, updater().installedVersionName)
        return live.copy(primary = primary, footer = footer)
    }

    // ===== Self-update =====

    private fun onUpdateClicked() {
        val s = vm.update.value
        if (s is UpdateState.ReadyToInstall && s.apk.exists()) install(s.apk) else vm.checkForUpdate(silent = false)
    }

    private fun renderUpdate(state: UpdateState) {
        when (state) {
            UpdateState.Idle -> binding.updateButton.text = getString(R.string.btn_update)
            UpdateState.Checking -> binding.updateButton.text = getString(R.string.update_checking)
            is UpdateState.Downloading -> binding.updateButton.text = getString(R.string.update_downloading, state.percent)
            is UpdateState.UpToDate -> {
                // The automatic start-up check only speaks up when an update exists.
                if (!vm.silentUpdateCheck) toast(getString(R.string.update_none, state.versionName))
                vm.resetUpdateState()
            }
            is UpdateState.Available -> if (lastUpdateDialogFor != state.remote) {
                lastUpdateDialogFor = state.remote
                AlertDialog.Builder(this)
                    .setTitle(R.string.update_available_title)
                    .setMessage(
                        getString(R.string.update_available_msg, state.remote.versionName, updater().installedVersionName)
                    )
                    .setPositiveButton(R.string.dialog_yes) { _, _ ->
                        lastUpdateDialogFor = null
                        vm.downloadUpdate(state.remote)
                    }
                    .setNegativeButton(R.string.dialog_no) { _, _ ->
                        lastUpdateDialogFor = null
                        vm.resetUpdateState()
                    }
                    .setOnCancelListener {
                        lastUpdateDialogFor = null
                        vm.resetUpdateState()
                    }
                    .show()
            }
            is UpdateState.ReadyToInstall -> {
                binding.updateButton.text = getString(R.string.btn_update)
                if (autoInstallPromptedFor != state.apk) {
                    autoInstallPromptedFor = state.apk
                    install(state.apk)
                }
            }
            UpdateState.VerifyFailed -> {
                toast(getString(R.string.update_verify_failed))
                vm.resetUpdateState()
            }
            UpdateState.Failed -> {
                if (!vm.silentUpdateCheck) toast(getString(R.string.update_error))
                vm.resetUpdateState()
            }
        }
    }

    private fun install(apk: File) {
        val updater = updater()
        if (!updater.canInstall()) {
            toast(getString(R.string.update_allow_install))
            startActivity(updater.unknownSourcesSettingsIntent())
            return
        }
        startActivity(updater.installIntent(apk))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun updater() = (application as com.galker.pointandidentify.PointApp).updateManager

    companion object {
        private const val TAG = "MainActivity"
        private const val EXIT_KILL_DELAY_MS = 1_500L
    }
}
