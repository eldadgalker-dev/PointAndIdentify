// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.0
package com.galker.pointandidentify.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.galker.pointandidentify.config.AppConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Barometer: low-pass filtered air pressure in hPa; null when there is no barometer or before the first sample. */
class PressureProvider(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

    private var smoothed: Double? = null
    private val _pressureHpa = MutableStateFlow<Double?>(null)
    val pressureHpa: StateFlow<Double?> = _pressureHpa

    val isAvailable: Boolean get() = sensor != null

    fun start() {
        sensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        smoothed = null
        _pressureHpa.value = null // a stale value must never be paired with a new GPS fix
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_PRESSURE) return
        val p = event.values[0].toDouble()
        val prev = smoothed
        val next = if (prev == null) p else prev + AppConfig.PRESSURE_SMOOTHING_ALPHA * (p - prev)
        smoothed = next
        _pressureHpa.value = next
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
}
