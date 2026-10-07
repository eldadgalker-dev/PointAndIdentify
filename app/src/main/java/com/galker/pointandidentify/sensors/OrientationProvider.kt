// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.5
package com.galker.pointandidentify.sensors

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.galker.pointandidentify.config.AppConfig
import com.galker.pointandidentify.domain.AdaptiveAngleSmoother
import com.galker.pointandidentify.domain.HeadingFusion
import com.galker.pointandidentify.geo.GeoMath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class Orientation(
    val trueAzimuthDeg: Double,   // camera optical axis, clockwise from true north
    val cameraElevationDeg: Double, // camera axis above (+) / below (-) the horizon; -90 = phone flat, screen up
    val calibrated: Boolean,
    val fieldStrengthUt: Double? = null // smoothed magnetometer field magnitude, uT; null until first sample
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
    // Gyroscope-only rotation vector: smooth and fast, no magnetic disturbance; null on phones without a gyroscope.
    private val gameSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    private val rotation = FloatArray(9)
    private val remapped = FloatArray(9)
    private val angles = FloatArray(3)

    private val gameRotation = FloatArray(9)
    private val fusion = HeadingFusion()
    private val azimuthSmoother = AdaptiveAngleSmoother(circular = true)
    private val elevationSmoother = AdaptiveAngleSmoother(circular = false)
    @Volatile
    private var lastMagTrueAz: Double? = null // latest azimuth of the magnetic rotation vector, true north
    private var lastGameTimestampNs = 0L

    @Volatile
    private var declinationDeg = 0.0
    @Volatile
    private var fieldUt: Double? = null
    @Volatile
    private var magAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE

    private val _orientation = MutableStateFlow<Orientation?>(null)
    val orientation: StateFlow<Orientation?> = _orientation

    val isAvailable: Boolean get() = rotationSensor != null
    val hasMagnetometer: Boolean get() = magneticSensor != null

    fun start() {
        rotationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gameSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        // Magnetometer is registered only to receive calibration accuracy callbacks.
        magneticSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        azimuthSmoother.reset()
        elevationSmoother.reset()
        fusion.reset()
        lastGameTimestampNs = 0L
        lastMagTrueAz = null
        fieldUt = null // stale after a pause: restart the smoothing from a fresh sample
    }

    /** Magnetic declination (east positive) converts magnetic to true north. */
    fun updateDeclination(lat: Double, lon: Double, altM: Double) {
        declinationDeg = GeomagneticField(
            lat.toFloat(), lon.toFloat(), altM.toFloat(), System.currentTimeMillis()
        ).declination.toDouble()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            // Field magnitude is rotation-independent; a value far from Earth's ~25-65 uT means interference.
            val v = event.values
            val magnitude = sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble())
            val prev = fieldUt
            fieldUt = if (prev == null) magnitude else prev + AppConfig.COMPASS_FIELD_SMOOTHING_ALPHA * (magnitude - prev)
            return
        }
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                val trueMagAz = GeoMath.normalizeDeg(cameraAzimuthDeg(rotation) + declinationDeg)
                lastMagTrueAz = trueMagAz
                // Without a gyroscope-only sensor the magnetic azimuth is used directly (with the adaptive smoothing).
                if (gameSensor == null) publish(trueMagAz, cameraElevationDeg(rotation))
            }
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(gameRotation, event.values)
                val dtSeconds = if (lastGameTimestampNs == 0L) 0.0 else (event.timestamp - lastGameTimestampNs) / 1e9
                lastGameTimestampNs = event.timestamp
                // The gyroscope azimuth has an arbitrary zero: publish nothing until the magnetic reference exists.
                if (lastMagTrueAz == null) return
                val fused = fusion.fuse(cameraAzimuthDeg(gameRotation), lastMagTrueAz, magneticReadingTrusted(), dtSeconds)
                publish(fused, cameraElevationDeg(gameRotation))
            }
        }
    }

    /** Azimuth of the back-camera axis for an upright phone (remapped X, Z), degrees clockwise from the sensor's north. */
    private fun cameraAzimuthDeg(matrix: FloatArray): Double {
        SensorManager.remapCoordinateSystem(matrix, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
        SensorManager.getOrientation(remapped, angles)
        return Math.toDegrees(angles[0].toDouble())
    }

    /**
     * Camera axis = device -Z. With world = R * device (row-major R), its world-up component is -R[8];
     * elevation = asin(-R[8]). Uses the un-remapped matrix, so no remap sign ambiguity.
     */
    private fun cameraElevationDeg(matrix: FloatArray): Double =
        Math.toDegrees(asin((-matrix[8]).toDouble().coerceIn(-1.0, 1.0)))

    /** The magnetic heading may correct the gyroscope heading only while the field is Earth-like and the sensor calibrated. */
    private fun magneticReadingTrusted(): Boolean {
        val field = fieldUt ?: return false
        return field in AppConfig.COMPASS_FIELD_MIN_UT..AppConfig.COMPASS_FIELD_MAX_UT &&
            magAccuracy >= SensorManager.SENSOR_STATUS_ACCURACY_LOW
    }

    private fun publish(azimuthDeg: Double, elevationDeg: Double) {
        _orientation.value = Orientation(
            trueAzimuthDeg = azimuthSmoother.update(GeoMath.normalizeDeg(azimuthDeg)),
            cameraElevationDeg = elevationSmoother.update(elevationDeg),
            calibrated = magAccuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM,
            fieldStrengthUt = fieldUt
        )
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD) magAccuracy = accuracy
    }
}
