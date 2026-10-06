// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.0
package com.galker.pointandidentify.location

import com.galker.pointandidentify.config.AppConfig
import kotlin.math.max
import kotlin.math.sqrt

/** Filtered position: degrees and the estimated 1-sigma accuracy in metres. */
data class FilteredPosition(val lat: Double, val lon: Double, val accuracyM: Double)

/**
 * Accuracy-weighted position filter (a scalar Kalman filter): every fix is weighted by its reported accuracy, so a
 * poor fix barely moves the estimate and a good one pulls it in. Between fixes the uncertainty grows with the speed
 * (at least FILTER_MIN_SPEED_MPS), so a walking or driving user is followed while a standing one gets steadier.
 * The reported accuracy is never below half of the raw accuracy of the latest fix: GPS errors are correlated in time,
 * so averaging them does not help as much as independent noise would.
 */
class PositionFilter {

    private var lat = 0.0
    private var lon = 0.0
    private var varianceM2 = 0.0
    private var lastMs = 0L
    private var initialized = false

    fun reset() {
        initialized = false
    }

    fun update(nowMs: Long, measuredLat: Double, measuredLon: Double, accuracyM: Double, speedMps: Double?): FilteredPosition {
        val measurementSigma = max(accuracyM, AppConfig.FILTER_MIN_ACCURACY_M)
        val r = measurementSigma * measurementSigma
        if (!initialized) {
            lat = measuredLat
            lon = measuredLon
            varianceM2 = r
            lastMs = nowMs
            initialized = true
            return FilteredPosition(lat, lon, measurementSigma)
        }
        val dtSeconds = ((nowMs - lastMs) / 1000.0).coerceIn(0.0, AppConfig.FILTER_MAX_GAP_S)
        lastMs = nowMs
        val speed = max(speedMps ?: 0.0, AppConfig.FILTER_MIN_SPEED_MPS)
        varianceM2 += speed * speed * dtSeconds * dtSeconds
        val gain = varianceM2 / (varianceM2 + r)
        lat += gain * (measuredLat - lat)
        lon += gain * (measuredLon - lon)
        varianceM2 *= (1.0 - gain)
        val reported = max(sqrt(varianceM2), 0.5 * measurementSigma)
        return FilteredPosition(lat, lon, max(reported, AppConfig.FILTER_MIN_ACCURACY_M))
    }
}
