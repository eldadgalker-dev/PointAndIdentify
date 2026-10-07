// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.5
package com.galker.pointandidentify.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.galker.pointandidentify.config.AppConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class ObserverFix(
    val lat: Double,
    val lon: Double,
    val mslAltitudeM: Double?,     // null when the device provides no usable altitude
    val horizontalAccuracyM: Float,
    val verticalAccuracyM: Float? = null, // GPS vertical accuracy (68 %), null when the device reports none
    val altitudeIsEstimated: Boolean = false // true when mslAltitudeM came from ellipsoidal altitude minus the fixed geoid estimate
)

/** Continuous fused location updates; converts altitude to MSL where possible. */
class LocationProvider(context: Context) {

    private val client = LocationServices.getFusedLocationProviderClient(context)
    private val filter = PositionFilter() // accuracy-weighted smoothing of the fused fixes

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
        // Same provider as the navigation and camera apps (fused: GNSS + Wi-Fi + cell + inertial sensors), best accuracy.
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, AppConfig.LOCATION_INTERVAL_MS)
            .setMinUpdateIntervalMillis(AppConfig.LOCATION_MIN_INTERVAL_MS)
            .setMinUpdateDistanceMeters(0f)
            .setGranularity(Granularity.GRANULARITY_FINE)
            .build()
        filter.reset()
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        client.lastLocation.addOnSuccessListener { loc ->
            // A cached location is only a start; an old one may be far from the present position.
            val ageMs = (SystemClock.elapsedRealtimeNanos() - (loc?.elapsedRealtimeNanos ?: 0L)) / 1_000_000L
            if (loc != null && _fix.value == null && ageMs <= AppConfig.LOCATION_LAST_FIX_MAX_AGE_MS) _fix.value = toFix(loc)
        }
    }

    fun stop() {
        client.removeLocationUpdates(callback)
    }

    private fun accuracyOf(loc: Location): Float =
        if (loc.hasAccuracy()) loc.accuracy else AppConfig.LOCATION_DEFAULT_ACCURACY_M

    private fun toFix(loc: Location): ObserverFix {
        // GPS altitude is ellipsoidal (WGS84). API 34+ may expose an MSL value directly;
        // otherwise subtract an estimated geoid undulation (used only when the DEM is unavailable).
        val platformMsl: Double? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && loc.hasMslAltitude()) loc.mslAltitudeMeters else null
        val msl: Double? = when {
            platformMsl != null -> platformMsl
            loc.hasAltitude() -> loc.altitude - AppConfig.GEOID_UNDULATION_FALLBACK_M
            else -> null
        }
        val filtered = filter.update(
            loc.elapsedRealtimeNanos / 1_000_000L, loc.latitude, loc.longitude, accuracyOf(loc).toDouble(),
            if (loc.hasSpeed()) loc.speed.toDouble() else null
        )
        return ObserverFix(
            filtered.lat, filtered.lon, msl, filtered.accuracyM.toFloat(),
            if (loc.hasVerticalAccuracy()) loc.verticalAccuracyMeters else null,
            altitudeIsEstimated = platformMsl == null && msl != null
        )
    }
}
