// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 2.0
package com.galker.pointandidentify.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.galker.pointandidentify.config.AppConfig
import com.galker.pointandidentify.domain.FixSelection
import com.galker.pointandidentify.domain.FixSource
import com.galker.pointandidentify.domain.SourceFix
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class ObserverFix(
    val lat: Double,
    val lon: Double,
    val mslAltitudeM: Double?,     // null when the device provides no usable altitude
    val horizontalAccuracyM: Float,
    val verticalAccuracyM: Float? = null, // GPS vertical accuracy (68 %), null when the device reports none
    val altitudeIsEstimated: Boolean = false, // true when mslAltitudeM came from ellipsoidal altitude minus the fixed geoid estimate
    val satellitesUsed: Int? = null,      // GNSS satellites used in the fix (native GNSS status), null when unknown
    val source: FixSource = FixSource.SYSTEM // which source delivered the fix
)

/**
 * Observer position. Sources, all asked at once:
 *   1. the phone's location service (fused: GNSS + Wi-Fi + cell + inertial sensors), the same position that the map
 *      and camera apps show - the primary source; started with a fresh single request (getCurrentLocation) for a fast first fix;
 *   2. the native GNSS provider, which replaces the system fix only when it is clearly more accurate (FixSelection);
 *   3. the GNSS status, for the number of satellites in the fix (shown as a quality indicator).
 * A fix whose reported accuracy is good (FIX_TRUST_RAW_ACCURACY_M) is used exactly as the phone reports it; only a poor fix
 * goes through the accuracy-weighted filter, which steadies a standing observer.
 */
class LocationProvider(context: Context) {

    private val appContext = context.applicationContext
    private val client = LocationServices.getFusedLocationProviderClient(appContext)
    private val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val filter = PositionFilter() // accuracy-weighted smoothing, applied only to poor fixes

    private var latestSystem: Location? = null
    private var latestGps: Location? = null
    @Volatile
    private var satellitesUsed: Int? = null
    private var currentRequest: CancellationTokenSource? = null

    private val _fix = MutableStateFlow<ObserverFix?>(null)
    val fix: StateFlow<ObserverFix?> = _fix

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { onSystemLocation(it) }
        }
    }

    // All four methods are implemented: before Android 11 the platform calls them and has no default implementation.
    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onGpsLocation(location)
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
            satellitesUsed = used
        }
    }

    /** Caller must hold ACCESS_FINE_LOCATION. */
    @SuppressLint("MissingPermission")
    fun start() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, AppConfig.LOCATION_INTERVAL_MS)
            .setMinUpdateIntervalMillis(AppConfig.LOCATION_MIN_INTERVAL_MS)
            .setMinUpdateDistanceMeters(0f)
            .setGranularity(Granularity.GRANULARITY_FINE)
            .build()
        filter.reset()
        latestSystem = null
        latestGps = null
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        client.lastLocation.addOnSuccessListener { loc ->
            // A cached location is only a start; an old one may be far from the present position.
            val ageMs = (SystemClock.elapsedRealtimeNanos() - (loc?.elapsedRealtimeNanos ?: 0L)) / 1_000_000L
            if (loc != null && _fix.value == null && ageMs <= AppConfig.LOCATION_LAST_FIX_MAX_AGE_MS) {
                _fix.value = toFix(loc, FixSource.SYSTEM)
            }
        }
        // One fresh high-accuracy request at once: the first fix does not wait for the regular interval.
        val source = CancellationTokenSource()
        currentRequest = source
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, source.token).addOnSuccessListener { loc ->
            if (loc != null) onSystemLocation(loc)
        }
        startNativeGnss()
    }

    @SuppressLint("MissingPermission")
    private fun startNativeGnss() {
        try {
            if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                manager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, AppConfig.LOCATION_INTERVAL_MS, 0f, gpsListener, Looper.getMainLooper()
                )
            }
            @Suppress("DEPRECATION")
            manager.registerGnssStatusCallback(gnssCallback, Handler(Looper.getMainLooper()))
        } catch (e: SecurityException) {
            Log.w(TAG, "Native GNSS not available (permission)", e)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Native GNSS provider missing", e)
        }
    }

    fun stop() {
        client.removeLocationUpdates(callback)
        currentRequest?.cancel()
        currentRequest = null
        try {
            manager.removeUpdates(gpsListener)
            manager.unregisterGnssStatusCallback(gnssCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Native GNSS stop failed", e)
        }
        satellitesUsed = null
    }

    private fun onSystemLocation(loc: Location) {
        latestSystem = loc
        publish()
    }

    private fun onGpsLocation(loc: Location) {
        latestGps = loc
        publish()
    }

    /** Offers both sources to FixSelection and publishes the winner. */
    private fun publish() {
        val now = SystemClock.elapsedRealtimeNanos()
        fun asSource(l: Location?) = l?.let { SourceFix(accuracyOf(it), (now - it.elapsedRealtimeNanos) / 1_000_000L) }
        val system = latestSystem
        val gps = latestGps
        val choice = FixSelection.choose(asSource(system), asSource(gps)) ?: return
        val chosen = (if (choice == FixSource.GPS) gps else system) ?: return
        _fix.value = toFix(chosen, choice)
    }

    private fun accuracyOf(loc: Location): Float =
        if (loc.hasAccuracy()) loc.accuracy else AppConfig.LOCATION_DEFAULT_ACCURACY_M

    private fun toFix(loc: Location, source: FixSource): ObserverFix {
        // GPS altitude is ellipsoidal (WGS84). API 34+ may expose an MSL value directly;
        // otherwise subtract an estimated geoid undulation (used only when the DEM is unavailable).
        val platformMsl: Double? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && loc.hasMslAltitude()) loc.mslAltitudeMeters else null
        val msl: Double? = when {
            platformMsl != null -> platformMsl
            loc.hasAltitude() -> loc.altitude - AppConfig.GEOID_UNDULATION_FALLBACK_M
            else -> null
        }
        val reported = accuracyOf(loc)
        // The filter always sees the fix, so its state is current when a poor fix needs it.
        val filtered = filter.update(
            loc.elapsedRealtimeNanos / 1_000_000L, loc.latitude, loc.longitude, reported.toDouble(),
            if (loc.hasSpeed()) loc.speed.toDouble() else null
        )
        val trusted = reported <= AppConfig.FIX_TRUST_RAW_ACCURACY_M
        return ObserverFix(
            if (trusted) loc.latitude else filtered.lat,
            if (trusted) loc.longitude else filtered.lon,
            msl,
            if (trusted) reported else filtered.accuracyM.toFloat(),
            if (loc.hasVerticalAccuracy()) loc.verticalAccuracyMeters else null,
            altitudeIsEstimated = platformMsl == null && msl != null,
            satellitesUsed = satellitesUsed,
            source = source
        )
    }

    private companion object {
        private const val TAG = "LocationProvider"
    }
}
