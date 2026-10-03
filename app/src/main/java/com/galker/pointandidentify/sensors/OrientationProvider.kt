// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.2
package com.galker.pointandidentify.sensors

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.galker.pointandidentify.config.AppConfig
import com.galker.pointandidentify.geo.GeoMath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

data class Orientation(
    val trueAzimuthDeg: Double,   // camera optical axis, clockwise from true north
    val cameraElevationDeg: Double, // camera axis above (+) / below (-) the horizon; -90 = phone flat, screen up
    val calibrated: Boolean
)

/**
 * Camera-axis azimuth from TYPE_ROTATION_VECTOR (fused gyro + accel + mag).
 * The rotation matrix is remapped (AXIS_X, AXIS_Z) so azimuth refers to the back-camera axis
 * while the phone is held upright in portrait, not to the device top edge as in flat use.
 */
class OrientationProvider(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val magneticSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val rotation = FloatArray(9)
    private val remapped = FloatArray(9)
    private val angles = FloatArray(3)

    // Low-pass filter on the unit vector (sin, cos) avoids the 359 -> 0 wrap jump.
    private var smoothSin = 0.0
    private var smoothCos = 1.0
    private var smoothElevation = 0.0
    private var initialized = false
    private var initializedElevation = false

    @Volatile
    private var declinationDeg = 0.0
    @Volatile
    private var magAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE

    private val _orientation = MutableStateFlow<Orientation?>(null)
    val orientation: StateFlow<Orientation?> = _orientation

    val isAvailable: Boolean get() = rotationSensor != null

    fun start() {
        rotationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        // Magnetometer is registered only to receive calibration accuracy callbacks.
        magneticSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        initialized = false
        initializedElevation = false
    }

    /** Magnetic declination (east positive) converts magnetic to true north. */
    fun updateDeclination(lat: Double, lon: Double, altM: Double) {
        declinationDeg = GeomagneticField(
            lat.toFloat(), lon.toFloat(), altM.toFloat(), System.currentTimeMillis()
        ).declination.toDouble()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        SensorManager.remapCoordinateSystem(rotation, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
        SensorManager.getOrientation(remapped, angles)

        val magneticAz = Math.toDegrees(angles[0].toDouble())
        val trueAz = GeoMath.normalizeDeg(magneticAz + declinationDeg)
        val rad = Math.toRadians(trueAz)

        if (!initialized) {
            smoothSin = sin(rad)
            smoothCos = cos(rad)
            initialized = true
        } else {
            val a = AppConfig.AZIMUTH_SMOOTHING_ALPHA.toDouble()
            smoothSin += a * (sin(rad) - smoothSin)
            smoothCos += a * (cos(rad) - smoothCos)
        }
        val filtered = GeoMath.normalizeDeg(Math.toDegrees(atan2(smoothSin, smoothCos)))

        // Camera axis = device -Z. With world = R * device (row-major R), its world-up component
        // is -R[8]; elevation = asin(-R[8]). Uses the un-remapped matrix, so no remap sign ambiguity.
        val elevation = Math.toDegrees(asin((-rotation[8]).toDouble().coerceIn(-1.0, 1.0)))
        smoothElevation = if (initializedElevation) {
            smoothElevation + AppConfig.AZIMUTH_SMOOTHING_ALPHA * (elevation - smoothElevation)
        } else {
            initializedElevation = true
            elevation
        }

        _orientation.value = Orientation(
            trueAzimuthDeg = filtered,
            cameraElevationDeg = smoothElevation,
            calibrated = magAccuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
        )
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD) magAccuracy = accuracy
    }
}
