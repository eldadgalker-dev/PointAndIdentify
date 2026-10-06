// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.21
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
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.os.Process
import android.text.InputType
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.inputmethod.EditorInfo
import android.util.Log
import android.view.View
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
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
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
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
import kotlin.math.tan

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
    private var locationSettingsAsked = false
    private val locationSettingsLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { }
    private var sensorHfovDeg = AppConfig.DEFAULT_HFOV_DEG // horizontal FOV of the full sensor image (portrait short side)

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
        binding.findButton.setOnClickListener { showFind() }
        binding.compassView.northLabel = getString(R.string.compass_north)

        // The data text sits just above the bottom buttons: follow their measured position.
        binding.captureButton.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateOverlayBottomInset() }
        binding.overlayView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateOverlayBottomInset() }

        // The target title is pinned below the status block; the compass sits just above the data block.
        binding.dataStatusText.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateOverlayTopInset() }
        binding.overlayView.onDataTopChanged = { top -> placeCompassAbove(top) }

        // Zoom: vertical bar (log scale) and pinch gesture both end in setZoom().
        binding.zoomBar.onValueChanged = { setZoom(sliderToZoom(it.toDouble())) }
        binding.viewFinder.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyVisibleHfov() }
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

    /** Asks (once per launch) to turn on location with the best accuracy; the dialog appears only when it is needed. */
    private fun checkLocationSettings() {
        if (locationSettingsAsked) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, AppConfig.LOCATION_INTERVAL_MS).build()
        val settings = LocationSettingsRequest.Builder().addLocationRequest(request).build()
        LocationServices.getSettingsClient(this).checkLocationSettings(settings).addOnFailureListener { e ->
            if (e is ResolvableApiException && !locationSettingsAsked) {
                locationSettingsAsked = true
                try {
                    locationSettingsLauncher.launch(IntentSenderRequest.Builder(e.resolution).build())
                } catch (ex: Exception) {
                    Log.w(TAG, "Location settings dialog failed", ex)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.startUpdateCheckIfDue() // every launch (and a return after a while) checks for a newer version, even before permissions are granted
        vm.orientationProvider.start()
        vm.pressureProvider.start()
        if (hasCorePermissions()) {
            checkLocationSettings()
            vm.startLocation()
            vm.startStartupChecks() // compass health; runs once per process
        }
    }

    override fun onPause() {
        super.onPause()
        vm.orientationProvider.stop()
        vm.pressureProvider.stop()
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
        val observerHeight = MaterialButton(this).apply {
            text = getString(R.string.settings_observer_height, fmt("%.1f", UserSettings.observerHeightM(this@MainActivity)))
            setOnClickListener {
                dialog?.dismiss()
                showObserverHeight()
            }
        }
        val sensorHeightOn = UserSettings.sensorHeightEnabled(this)
        val sensorHeight = MaterialButton(this).apply {
            text = getString(if (sensorHeightOn) R.string.settings_sensor_height_on else R.string.settings_sensor_height_off)
            setOnClickListener {
                dialog?.dismiss()
                UserSettings.setSensorHeightEnabled(this@MainActivity, !sensorHeightOn)
                vm.refreshTargets() // visibility depends on the height
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
            addView(observerHeight)
            addView(sensorHeight)
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

    /** Height above the ground at the observer's spot; visibility is computed from this height. */
    private fun showObserverHeight() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            setText(fmt("%.1f", UserSettings.observerHeightM(this@MainActivity)))
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine()
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(TextView(this@MainActivity).apply { setText(R.string.observer_height_hint) })
            addView(TextView(this@MainActivity).apply {
                val s = vm.ui.value
                val h = s.sensorHeightM
                val sigma = s.sensorSigmaM
                setPadding(0, pad / 2, 0, pad / 2)
                text = if (h != null && sigma != null) {
                    getString(R.string.observer_height_sensor, fmt("%.0f", h), fmt("%.0f", sigma))
                } else {
                    getString(R.string.observer_height_sensor_none)
                }
            })
            addView(input)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.observer_height_title)
            .setView(box)
            .setPositiveButton(R.string.points_save, null) // click handler set below so invalid input keeps the dialog open
            .setNegativeButton(R.string.dialog_close, null)
            .create()
        dialog.setOnShowListener {
            input.requestFocus()
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString().trim().replace(',', '.').toDoubleOrNull()
                if (value == null || value < 0.0 || value > AppConfig.OBSERVER_HEIGHT_MAX_M) {
                    toast(getString(R.string.observer_height_invalid, AppConfig.OBSERVER_HEIGHT_MAX_M.toInt()))
                } else {
                    UserSettings.setObserverHeightM(this, value)
                    vm.refreshTargets() // visibility depends on the height
                    toast(getString(R.string.observer_height_saved))
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
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
                .setItems(items) { _, index -> showPointActions(index, points[index]) }
        }
        builder.show()
    }

    /** Tap on a saved point: rename it or delete it. */
    private fun showPointActions(index: Int, point: PrivatePoint) {
        AlertDialog.Builder(this)
            .setTitle(point.name)
            .setItems(arrayOf(getString(R.string.points_action_rename), getString(R.string.points_action_delete))) { _, which ->
                if (which == 0) showRenamePoint(index, point) else confirmDeletePoint(index, point)
            }
            .setNegativeButton(R.string.dialog_close) { _, _ -> showPrivatePoints() }
            .show()
    }

    /** Only the name changes: the coordinates stay as saved. */
    private fun showRenamePoint(index: Int, point: PrivatePoint) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            setText(point.name)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(input)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.points_rename_title)
            .setView(box)
            .setPositiveButton(R.string.points_save, null) // click handler set below so invalid input keeps the dialog open
            .setNegativeButton(R.string.dialog_close) { _, _ -> showPrivatePoints() }
            .create()
        dialog.setOnShowListener {
            input.requestFocus()
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isEmpty() || name.length > MAX_POINT_NAME_LENGTH) {
                    toast(getString(R.string.points_invalid_name))
                } else {
                    privateStore().rename(index, name)
                    vm.refreshTargets()
                    toast(getString(R.string.points_renamed))
                    dialog.dismiss()
                    showPrivatePoints()
                }
            }
        }
        dialog.show()
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
                } else if (!privateStore().add(PrivatePoint(name, lat, lon))) {
                    toast(getString(R.string.points_limit, AppConfig.PRIVATE_POINTS_MAX))
                } else {
                    vm.refreshTargets()
                    toast(getString(R.string.points_saved))
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    /** Help: overview, glossary of the on-screen terms, license, and the APK download link (selectable and copyable). */
    private fun showHelp() {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val apkUrl = AppConfig.RELEASE_LATEST_URL + "/" + AppConfig.RELEASE_APK_ASSET
        val textColor = com.google.android.material.color.MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorOnSurface
        )
        // ltr = true: English text in either UI language (license, URL): left-to-right and aligned to the left.
        fun body(text: CharSequence, selectable: Boolean = true, ltr: Boolean = false) = TextView(this).apply {
            this.text = text
            setTextColor(textColor)
            textSize = 15f
            if (ltr) {
                textDirection = View.TEXT_DIRECTION_LTR
                textAlignment = View.TEXT_ALIGNMENT_TEXT_START
            } else {
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            }
            setTextIsSelectable(selectable)
            setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
        }
        // The download address is a blue, tappable link (opens the browser, which downloads the APK).
        val link = body(apkUrl, selectable = false, ltr = true).apply {
            Linkify.addLinks(this, Linkify.WEB_URLS)
            setLinkTextColor(Color.rgb(30, 136, 229))
            movementMethod = LinkMovementMethod.getInstance()
        }
        val copy = MaterialButton(this).apply {
            text = getString(R.string.btn_copy_link)
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("PointAndIdentify APK", apkUrl))
                toast(getString(R.string.link_copied))
            }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
            addView(body(getString(R.string.help_text)))
            addView(body(getString(R.string.help_glossary)))
            addView(body(getString(R.string.help_accuracy)))
            addView(body(getString(R.string.help_license), ltr = true))
            addView(body(getString(R.string.help_install)))
            addView(link)
            addView(copy)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.help_title)
            .setView(ScrollView(this).apply { addView(box) })
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
                horizontalFovDeg(bound)?.let { sensorHfovDeg = it }
                applyVisibleHfov()
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

    /**
     * The preview fills the screen (centre crop). A 3:4 image on a taller screen loses its left and right parts,
     * so the visible horizontal FOV is smaller than the sensor FOV: tan(visible / 2) = tan(sensor / 2) * (view w/h) / (image w/h).
     * The target window must use the visible FOV, because the crosshair is drawn on the visible image.
     */
    private fun applyVisibleHfov() {
        val w = binding.viewFinder.width
        val h = binding.viewFinder.height
        val fraction = if (w > 0 && h > 0) minOf(1.0, (w.toDouble() / h) / AppConfig.PREVIEW_ASPECT) else 1.0
        vm.hfovDeg = Math.toDegrees(2.0 * atan(tan(Math.toRadians(sensorHfovDeg / 2.0)) * fraction))
    }

    private fun capture() {
        val ic = imageCapture ?: return
        val extra = extraZoom // zoom at the moment of the click, applied to this photo
        val viewAspect = binding.viewFinder.width.toDouble() / binding.viewFinder.height.coerceAtLeast(1) // photo = screen shape
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
                        photoExporter.export(image, content, meta, extra, viewAspect)
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

    // ===== Find =====

    /** Free-text search of a place ("Home", a settlement, an address or "lat, lon"); the crosshair then shows an arrow toward it. */
    private fun showFind() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            setHint(R.string.find_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(input)
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.find_title)
            .setView(box)
            .setPositiveButton(R.string.find_ok, null) // handler below: the dialog closes only for a non-empty query
            .setNegativeButton(R.string.dialog_close, null)
        if (vm.ui.value.find != null) {
            builder.setNeutralButton(R.string.find_stop) { _, _ -> vm.setFind(null) }
        }
        val dialog = builder.create()
        fun submit() {
            val query = input.text.toString().trim()
            if (query.isEmpty()) return
            dialog.dismiss()
            runFind(query)
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { submit(); true } else false
        }
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener { submit() }
            input.requestFocus()
        }
        dialog.show()
    }

    private fun runFind(query: String) {
        lifecycleScope.launch {
            val places = try {
                vm.searchPlaces(query)
            } catch (e: Exception) {
                Log.w(TAG, "Find failed", e)
                emptyList()
            }
            when (places.size) {
                0 -> toast(getString(R.string.find_none))
                1 -> startFind(places[0])
                else -> AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.find_pick_title)
                    .setItems(places.map { getString(R.string.points_item, it.name, it.latitude, it.longitude) }.toTypedArray()) { _, i ->
                        startFind(places[i])
                    }
                    .setNegativeButton(R.string.dialog_close, null)
                    .show()
            }
        }
    }

    private fun startFind(place: com.galker.pointandidentify.data.db.TargetEntity) {
        vm.setFind(place)
        toast(getString(R.string.find_started, place.name))
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
        val find = state.find
        return base.copy(
            infoLines = infoLines(state), zoomRatio = state.zoomRatio, rtl = rtl,
            findArrowRad = find?.guide?.screenAngleRad?.toFloat(),
            findInside = find?.guide?.inside ?: false,
            findDeltaAzDeg = find?.guide?.deltaAzimuthDeg,
            findDeltaElDeg = find?.guide?.deltaElevationDeg,
            hfovDeg = vm.hfovDeg,
            findLabel = find?.let {
                getString(R.string.find_label, it.name, it.distanceM / 1000.0, it.bearingDeg.roundToInt().mod(360)) +
                    if (it.guide.inside) " " + getString(R.string.find_in_crosshair) else ""
            } ?: ""
        )
    }

    /** The live view shows visible targets only (the selector guarantees it); hidden ones are listed by a tap on the crosshair. */
    private fun targetContent(state: UiState, az: Int?): OverlayContent {
        val azText = az?.let { getString(R.string.status_azimuth, it) } ?: ""
        // No visible target: say so, but point out hidden ones inside the crosshair (listed by a tap on it).
        // Camera pointing down: only the current city, whatever the azimuth.
        if (state.cityMode && state.target != null) {
            // The circle diameter is twice the distance at which the camera axis meets the ground: the higher above the
            // aimed ground, the larger the circle; lowering the camera shrinks it.
            val aim = state.aimDistanceM
            val secondary = if (aim != null) {
                val diameter = 2.0 * aim
                val text = if (diameter >= 1000.0) {
                    fmt("%.1f", diameter / 1000.0) + " " + getString(R.string.unit_km)
                } else {
                    getString(R.string.info_meters, diameter.roundToInt())
                }
                getString(R.string.city_current_circle, text)
            } else {
                getString(R.string.city_current)
            }
            return OverlayContent(state.target.target.name, secondary, visible = true)
        }
        val t = state.target ?: return OverlayContent(
            if (state.candidates.isEmpty()) getString(R.string.status_no_target)
            else getString(R.string.status_hidden_in_crosshair, state.candidates.size),
            azText
        )
        val km = t.distanceM / 1000.0
        val bearing = t.bearingDeg.roundToInt().mod(360)
        return OverlayContent(
            t.target.name, getString(R.string.target_visible_details, km, bearing), visible = true
        )
    }

    /**
     * Data block shown on screen and burned into the photo, one group per topic, one fact per line.
     * ALL lines are always present (a "-" stands for an unknown value), so the block never changes size:
     *   observer position / accuracy / height with the camera vertical angle,
     *   target kind / height / position, and target geometry (range, azimuth, angle, clearance).
     * The target position is shown only while the phone is raised; held flat, only the current position is shown.
     * The camera azimuth is shown under the compass and the zoom next to the zoom bar, not here.
     */
    private fun infoLines(state: UiState): List<String> {
        val none = getString(R.string.info_none)
        val fix = state.fix
        val t = state.target
        val observer = listOf(
            getString(R.string.info_observer_pos, fix?.let { fmt("%.5f, %.5f", it.lat, it.lon) } ?: none),
            getString(
                R.string.info_observer_acc,
                fix?.let { "±" + getString(R.string.info_meters, it.horizontalAccuracyM.roundToInt()) } ?: none
            ),
            getString(
                R.string.info_height_angle, altText(state.observerEyeAltM),
                state.cameraElevationDeg?.let { fmt("%+.1f°", it) } ?: none
            )
        )
        val position = if (state.raised && !state.cityMode) t?.let { fmt("%.5f, %.5f", it.target.latitude, it.target.longitude) } else null
        val target = listOf(
            getString(R.string.info_target_kind, t?.let { getString(kindLabel(it.target.targetKind)) } ?: none),
            getString(R.string.info_target_height, t?.let { altText(it.topAltM) } ?: none),
            getString(R.string.info_target_pos, position ?: none)
        )
        val geometry = listOf(
            getString(
                R.string.info_geometry_range,
                t?.let { fmt("%.2f", it.distanceM / 1000.0) + " " + getString(R.string.unit_km) } ?: none
            ),
            getString(R.string.info_geometry_bearing, t?.let { fmt("%.1f°", it.bearingDeg) } ?: none),
            getString(R.string.info_geometry_angle, t?.elevationAngleDeg?.let { fmt("%+.2f°", it) } ?: none),
            getString(
                R.string.info_clearance,
                t?.minClearanceM?.let { getString(R.string.info_meters, it.roundToInt()) } ?: none
            )
        )
        return listOf(observer, target, geometry).map { it.joinToString("\n") }
    }

    /** Fixed-locale number formatting (Western digits in both UI languages). */
    private fun fmt(pattern: String, vararg args: Any): String = String.format(java.util.Locale.US, pattern, *args)

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
        val primary = if (state.target != null && !state.cityMode) getString(R.string.photo_target_prefix, live.primary) else live.primary
        val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val footer = getString(R.string.photo_footer, time, updater().installedVersionName)
        // The saved photo shows the compass too (on screen it is a separate view).
        return live.copy(
            primary = primary, footer = footer,
            findArrowRad = null, findInside = false, findLabel = "", findDeltaAzDeg = null, findDeltaElDeg = null, // the Find guide is a live aid, not part of the photo
            drawCompass = true,
            compassAzimuthDeg = state.azimuthDeg?.toFloat(),
            compassAzimuthText = state.azimuthDeg?.let { getString(R.string.azimuth_value, it.roundToInt().mod(360)) } ?: "",
            northLabel = getString(R.string.compass_north)
        )
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
