// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.7
package com.galker.pointandidentify.ui

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.os.Bundle
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.content.DialogInterface
import android.os.Process
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
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
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.galker.pointandidentify.R
import com.google.android.material.button.MaterialButton
import com.galker.pointandidentify.capture.PhotoExporter
import com.galker.pointandidentify.capture.PhotoMeta
import com.galker.pointandidentify.config.AppConfig
import com.galker.pointandidentify.data.PrivatePoint
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
import kotlin.math.min
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
    private var camZoom = AppConfig.ZOOM_MIN_FALLBACK // zoom ratio currently set on the camera
    private var extraZoom = 1.0                        // calculated (digital) zoom on top of the camera zoom, >= 1
    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var tapDetector: GestureDetector
    private var updateStatusLine: String? = null       // update progress line shown in the status block
    private var exiting = false

    /** Camera zoom state -> zoom bar and ViewModel. */
    private val zoomObserver = Observer<ZoomState> { st ->
        minZoom = st.minZoomRatio.toDouble()
        maxZoom = st.maxZoomRatio.toDouble()
        camZoom = st.zoomRatio.toDouble()
        refreshZoomUi()
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
        applyZoomBarSide()

        // Single background executor for capture callbacks: decoding never runs on the UI thread.
        cameraExecutor = Executors.newSingleThreadExecutor()
        photoExporter = PhotoExporter(applicationContext)

        binding.captureButton.setOnClickListener { capture() }
        binding.exitButton.setOnClickListener { confirmExit() }
        binding.settingsButton.icon = GearDrawable()
        binding.settingsButton.setOnClickListener { showSettings() }
        binding.helpButton.setOnClickListener { showHelp() }
        binding.compassView.northLabel = getString(R.string.compass_north)

        // The data text sits just above the bottom buttons: follow their measured position.
        binding.captureButton.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateOverlayBottomInset() }
        binding.overlayView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateOverlayBottomInset() }

        // The target title is pinned below the status block; the compass sits just above the data block.
        binding.dataStatusText.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateOverlayTopInset() }
        binding.overlayView.onDataTopChanged = { top -> placeCompassAbove(top) }

        // Zoom: vertical bar (log scale) and pinch gesture both end in setZoom().
        binding.zoomBar.onValueChanged = { setZoom(sliderToZoom(it.toDouble())) }
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (camera == null) return false
                setZoom(camZoom * extraZoom * detector.scaleFactor)
                return true
            }
        })
        // A tap inside the crosshair shows the identify list (replaces the former identify button).
        tapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (binding.overlayView.isInsideCrosshair(e.rawX, e.rawY)) showIdentify()
                return true
            }
        })
        binding.viewFinder.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            tapDetector.onTouchEvent(event)
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
        vm.startUpdateCheckOnce() // every launch checks for a newer version, even before permissions are granted
        vm.orientationProvider.start()
        if (hasCorePermissions()) {
            vm.startLocation()
            vm.startStartupChecks() // compass health; runs once per process
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

    // ===== Settings =====

    /** Space from the overlay's top edge to the bottom of the status block (plus its margin), so the title starts below it. */
    private fun updateOverlayTopInset() {
        val inset = (binding.dataStatusText.bottom - binding.overlayView.top).toFloat() +
            resources.displayMetrics.density * STATUS_MARGIN_DP
        if (inset > 0f) binding.overlayView.topInsetPx = inset
    }

    /** Puts the compass (with the azimuth under it) directly above the data block, whose top is at dataTopPx. */
    private fun placeCompassAbove(dataTopPx: Float) {
        val lp = binding.azimuthText.layoutParams as ConstraintLayout.LayoutParams
        val margin = (binding.overlayView.height - dataTopPx + resources.displayMetrics.density * COMPASS_GAP_DP).toInt()
        if (lp.bottomMargin != margin) {
            lp.bottomMargin = margin.coerceAtLeast(0)
            binding.azimuthText.layoutParams = lp
        }
    }

    /** Distance from the overlay's bottom edge to the top of the bottom buttons, so the data block rests on them. */
    private fun updateOverlayBottomInset() {
        val inset = (binding.overlayView.bottom - binding.captureButton.top).toFloat()
        if (inset > 0f) binding.overlayView.bottomInsetPx = inset
    }

    /** Settings dialog: language switch (Hebrew <-> English) and update check. */
    private fun showSettings() {
        var dialog: AlertDialog? = null
        val pad = (16 * resources.displayMetrics.density).toInt()
        val switchLanguage = MaterialButton(this).apply {
            text = getString(R.string.settings_switch_language)
            setOnClickListener {
                dialog?.dismiss()
                LanguageManager.toggle(this@MainActivity) // recreates this Activity with the new language
            }
        }
        val checkUpdate = MaterialButton(this).apply {
            text = getString(R.string.settings_check_update)
            setOnClickListener {
                dialog?.dismiss()
                onUpdateClicked()
            }
        }
        val barOnRight = UserSettings.zoomBarOnRight(this)
        val switchBarSide = MaterialButton(this).apply {
            text = getString(if (barOnRight) R.string.settings_zoom_bar_right else R.string.settings_zoom_bar_left)
            setOnClickListener {
                dialog?.dismiss()
                UserSettings.setZoomBarOnRight(this@MainActivity, !barOnRight)
                applyZoomBarSide()
            }
        }
        val privatePoints = MaterialButton(this).apply {
            text = getString(R.string.settings_private_points)
            setOnClickListener {
                dialog?.dismiss()
                showPrivatePoints()
            }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(switchLanguage)
            addView(switchBarSide)
            addView(privatePoints)
            addView(checkUpdate)
        }
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setView(box)
            .setNegativeButton(R.string.dialog_close, null)
            .show()
    }

    /** Puts the zoom bar on the chosen edge and the compass on the opposite one. */
    private fun applyZoomBarSide() {
        val onRight = UserSettings.zoomBarOnRight(this)
        val root = binding.root as ConstraintLayout
        val set = ConstraintSet()
        set.clone(root)
        val density = resources.displayMetrics.density
        fun pin(id: Int, toRight: Boolean, marginDp: Float) {
            set.clear(id, ConstraintSet.LEFT)
            set.clear(id, ConstraintSet.RIGHT)
            val side = if (toRight) ConstraintSet.RIGHT else ConstraintSet.LEFT
            set.connect(id, side, ConstraintSet.PARENT_ID, side, (marginDp * density).toInt())
        }
        pin(R.id.zoomBar, onRight, ZOOM_BAR_MARGIN_DP)
        pin(R.id.compassView, !onRight, COMPASS_MARGIN_DP)
        set.applyTo(root)
        binding.zoomBar.onLeft = !onRight
        binding.overlayView.zoomBarOnLeft = !onRight
    }

    // ===== Private points =====

    private fun privateStore() = (application as com.galker.pointandidentify.PointApp).targetRepository.privatePoints

    /** List of the user's own points (tap one to delete it) with an Add button. */
    private fun showPrivatePoints() {
        val points = privateStore().all()
        val builder = AlertDialog.Builder(this)
            .setPositiveButton(R.string.points_add) { _, _ -> showAddPoint() }
            .setNegativeButton(R.string.dialog_close, null)
        if (points.isEmpty()) {
            builder.setTitle(R.string.points_title).setMessage(R.string.points_empty)
        } else {
            val items = points.map { getString(R.string.points_item, it.name, it.lat, it.lon) }.toTypedArray()
            builder.setTitle(getString(R.string.points_title) + "\n" + getString(R.string.points_hint_delete))
                .setItems(items) { _, index -> confirmDeletePoint(index, points[index]) }
        }
        builder.show()
    }

    private fun confirmDeletePoint(index: Int, point: PrivatePoint) {
        AlertDialog.Builder(this)
            .setTitle(R.string.points_delete_title)
            .setMessage(getString(R.string.points_delete_msg, point.name))
            .setPositiveButton(R.string.dialog_yes) { _, _ ->
                privateStore().removeAt(index)
                vm.refreshTargets()
                showPrivatePoints()
            }
            .setNegativeButton(R.string.dialog_no) { _, _ -> showPrivatePoints() }
            .show()
    }

    /** Form for a new point; coordinates start as the current location and can be edited. */
    private fun showAddPoint() {
        val fix = vm.ui.value.fix
        val pad = (16 * resources.displayMetrics.density).toInt()
        val numeric = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        fun field(hintRes: Int, initial: String, type: Int) = EditText(this).apply {
            setHint(hintRes)
            setText(initial)
            inputType = type
        }
        val nameField = field(R.string.points_name_hint, getString(R.string.points_default_name), InputType.TYPE_CLASS_TEXT)
        val latField = field(R.string.points_lat_hint, fix?.let { String.format(java.util.Locale.US, "%.5f", it.lat) } ?: "", numeric)
        val lonField = field(R.string.points_lon_hint, fix?.let { String.format(java.util.Locale.US, "%.5f", it.lon) } ?: "", numeric)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(nameField)
            addView(latField)
            addView(lonField)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.points_add_title)
            .setView(box)
            .setPositiveButton(R.string.points_save, null) // click handler set below so invalid input keeps the dialog open
            .setNegativeButton(R.string.dialog_close, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val name = nameField.text.toString().trim()
                val lat = latField.text.toString().trim().replace(',', '.').toDoubleOrNull()
                val lon = lonField.text.toString().trim().replace(',', '.').toDoubleOrNull()
                if (name.isEmpty() || name.length > MAX_POINT_NAME_LENGTH ||
                    lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0
                ) {
                    toast(getString(R.string.points_invalid))
                } else {
                    privateStore().add(PrivatePoint(name, lat, lon))
                    vm.refreshTargets()
                    toast(getString(R.string.points_saved))
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun showHelp() {
        AlertDialog.Builder(this)
            .setTitle(R.string.help_title)
            .setMessage(R.string.help_text)
            .setPositiveButton(R.string.dialog_close, null)
            .show()
    }

    // ===== Exit =====

    /** Exit confirmation: shown high on the screen, black text on a white plate for maximum contrast over the camera image. */
    private fun confirmExit() {
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.exit_title)
            .setMessage(R.string.exit_msg)
            .setPositiveButton(R.string.dialog_yes) { _, _ -> exitApp() }
            .setNegativeButton(R.string.dialog_no, null)
            .create()
        dialog.window?.let { w ->
            w.setGravity(Gravity.TOP)
            val lp = w.attributes
            lp.y = (resources.displayMetrics.heightPixels * EXIT_DIALOG_TOP_FRACTION).toInt()
            w.attributes = lp
            val density = resources.displayMetrics.density
            w.setBackgroundDrawable(GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 16f * density
                setStroke((3 * density).toInt(), Color.BLACK)
            })
        }
        dialog.setOnShowListener {
            dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.setTextColor(Color.BLACK)
            dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(Color.BLACK)
            dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setTextColor(Color.rgb(183, 28, 28))
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE)?.setTextColor(Color.BLACK)
        }
        dialog.show()
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

    /** Largest total zoom: the camera maximum times the extra calculated zoom. */
    private fun totalMaxZoom(): Double = maxZoom * AppConfig.EXTRA_ZOOM_MAX

    /** Slider position 0..1 <-> total zoom ratio, logarithmic so each slider step feels like the same magnification step. */
    private fun sliderToZoom(t: Double): Double {
        val top = totalMaxZoom()
        return if (top > minZoom) minZoom * (top / minZoom).pow(t.coerceIn(0.0, 1.0)) else minZoom
    }

    private fun zoomToSlider(z: Double): Double {
        val top = totalMaxZoom()
        return if (top > minZoom) (ln(z / minZoom) / ln(top / minZoom)).coerceIn(0.0, 1.0) else 0.0
    }

    /**
     * Sets the total zoom. The camera takes as much as it can; the rest is the extra calculated zoom,
     * applied as a scale of the preview (and as a centre crop of the saved photo).
     */
    private fun setZoom(ratio: Double) {
        val cam = camera ?: return
        val total = ratio.coerceIn(minZoom, totalMaxZoom())
        val camPart = min(total, maxZoom)
        camZoom = camPart
        extraZoom = if (camPart > 0.0) total / camPart else 1.0
        binding.viewFinder.scaleX = extraZoom.toFloat()
        binding.viewFinder.scaleY = extraZoom.toFloat()
        cam.cameraControl.setZoomRatio(camPart.toFloat())
        refreshZoomUi()
    }

    /** Total zoom (camera x extra) -> zoom bar, labels and the ViewModel (which narrows the selection window). */
    private fun refreshZoomUi() {
        val z = camZoom * extraZoom
        vm.onZoomChanged(z)
        binding.zoomBar.valueLabel = getString(R.string.zoom_label, z)
        binding.zoomBar.minLabel = getString(R.string.zoom_label, minZoom)
        binding.zoomBar.maxLabel = getString(R.string.zoom_label, totalMaxZoom())
        binding.zoomBar.isEnabled = totalMaxZoom() > minZoom + 1e-6
        val t = zoomToSlider(z).toFloat()
        if (abs(binding.zoomBar.value - t) > 0.001f) binding.zoomBar.value = t
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
        val extra = extraZoom // zoom at the moment of the click, applied to this photo
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
                        photoExporter.export(image, content, meta, extra)
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
        updateStatusLine?.let { status.append('\n').append(it) }
        binding.dataStatusText.text = status
        binding.compassView.azimuthDeg = state.azimuthDeg?.toFloat()
        binding.azimuthText.text = state.azimuthDeg?.let { getString(R.string.azimuth_value, it.roundToInt().mod(360)) } ?: ""
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
        if (r.verdict == CompassVerdict.CHECKING || r === vm.lastReportedCompass) return
        vm.lastReportedCompass = r
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
        val rtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        return base.copy(infoLines = infoLines(state), zoomRatio = state.zoomRatio, rtl = rtl)
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
     * Data block shown on screen and burned into the photo, one group per topic, one fact per line:
     *   observer position / accuracy / eye height, camera vertical angle,
     *   target kind / height / position, and target geometry (range, azimuth, angle, clearance).
     * The camera azimuth is shown under the compass and the zoom next to the zoom bar, not here.
     */
    private fun infoLines(state: UiState): List<String> {
        val groups = ArrayList<String>(4)
        state.fix?.let { fix ->
            groups.add(
                listOf(
                    getString(R.string.info_observer_pos, fix.lat, fix.lon),
                    getString(R.string.info_observer_acc, fix.horizontalAccuracyM.roundToInt()),
                    getString(R.string.info_eye_height, altText(state.observerEyeAltM))
                ).joinToString("\n")
            )
        }
        state.cameraElevationDeg?.let { elevation ->
            groups.add(getString(R.string.info_vertical_angle, elevation))
        }
        state.target?.let { t ->
            groups.add(
                listOf(
                    getString(R.string.info_target_kind, getString(kindLabel(t.target.targetKind))),
                    getString(R.string.info_target_height, altText(t.topAltM)),
                    getString(R.string.info_target_pos, t.target.latitude, t.target.longitude)
                ).joinToString("\n")
            )
            val clearance = t.minClearanceM?.let { getString(R.string.info_meters, it.roundToInt()) }
                ?: getString(R.string.info_none)
            groups.add(
                listOf(
                    getString(R.string.info_geometry_range, t.distanceM / 1000.0),
                    getString(R.string.info_geometry_bearing, t.bearingDeg),
                    getString(R.string.info_geometry_angle, t.elevationAngleDeg ?: 0.0),
                    getString(R.string.info_clearance, clearance)
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
        TargetKind.PRIVATE -> R.string.kind_private
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

    /** Update progress is a line in the status block (the update button now lives in Settings). */
    private fun setUpdateStatus(line: String?) {
        if (line == updateStatusLine) return
        updateStatusLine = line
        render(vm.ui.value)
    }

    private fun renderUpdate(state: UpdateState) {
        when (state) {
            UpdateState.Idle -> setUpdateStatus(null)
            UpdateState.Checking -> setUpdateStatus(getString(R.string.update_checking))
            is UpdateState.Downloading -> setUpdateStatus(getString(R.string.update_downloading, state.percent))
            is UpdateState.UpToDate -> {
                setUpdateStatus(null)
                // The automatic start-up check only speaks up when an update exists.
                if (!vm.silentUpdateCheck) toast(getString(R.string.update_none, state.versionName))
                vm.resetUpdateState()
            }
            is UpdateState.Available -> if (lastUpdateDialogFor != state.remote) {
                setUpdateStatus(null)
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
                setUpdateStatus(null)
                if (autoInstallPromptedFor != state.apk) {
                    autoInstallPromptedFor = state.apk
                    install(state.apk)
                }
            }
            UpdateState.VerifyFailed -> {
                setUpdateStatus(null)
                toast(getString(R.string.update_verify_failed))
                vm.resetUpdateState()
            }
            UpdateState.Failed -> {
                setUpdateStatus(null)
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
        private const val EXIT_DIALOG_TOP_FRACTION = 0.12f // exit dialog top edge, as a fraction of the screen height
        private const val STATUS_MARGIN_DP = 8f            // margin of the status block (activity_main.xml)
        private const val COMPASS_GAP_DP = 4f              // gap between the data block and the azimuth label above it
        private const val ZOOM_BAR_MARGIN_DP = 4f
        private const val COMPASS_MARGIN_DP = 8f
        private const val MAX_POINT_NAME_LENGTH = 60
    }
}
