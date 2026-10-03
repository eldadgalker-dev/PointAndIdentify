// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.1
package com.galker.pointandidentify.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Build
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.galker.pointandidentify.config.AppConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class ObserverFix(
    val lat: Double,
    val lon: Double,
    val mslAltitudeM: Double?,     // null when the device provides no usable altitude
    val horizontalAccuracyM: Float
)

/** Continuous fused location updates; converts altitude to MSL where possible. */
class LocationProvider(context: Context) {

    private val client = LocationServices.getFusedLocationProviderClient(context)

    private val _fix = MutableStateFlow<ObserverFix?>(null)
    val fix: StateFlow<ObserverFix?> = _fix

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { _fix.value = toFix(it) }
        }
    }

    /** Caller must hold ACCESS_FINE_LOCATION. */
    @SuppressLint("MissingPermission")
    fun start() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, AppConfig.LOCATION_INTERVAL_MS)
            .setMinUpdateIntervalMillis(AppConfig.LOCATION_MIN_INTERVAL_MS)
            .build()
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        client.lastLocation.addOnSuccessListener { loc -> if (loc != null && _fix.value == null) _fix.value = toFix(loc) }
    }

    fun stop() {
        client.removeLocationUpdates(callback)
    }

    private fun toFix(loc: Location): ObserverFix {
        // GPS altitude is ellipsoidal (WGS84). API 34+ may expose an MSL value directly;
        // otherwise subtract an estimated geoid undulation (used only when the DEM is unavailable).
        val msl: Double? = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && loc.hasMslAltitude() ->
                loc.mslAltitudeMeters
            loc.hasAltitude() -> loc.altitude - AppConfig.GEOID_UNDULATION_FALLBACK_M
            else -> null
        }
        return ObserverFix(loc.latitude, loc.longitude, msl, loc.accuracy)
    }
}
