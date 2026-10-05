// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.10
package com.galker.pointandidentify.config

import com.galker.pointandidentify.BuildConfig

// =============================================================
// Conventions
//   Distances  : metres
//   Heights    : metres above mean sea level (MSL)
//   Angles     : degrees, azimuth clockwise from TRUE north, range [0, 360)
//   Coordinates: WGS84 decimal degrees
// =============================================================
object AppConfig {

    // ===== Parameters: data source (composed in Gradle from gradle.properties) =====
    val DATA_BASE_URL: String = BuildConfig.DATA_BASE_URL          // .../data
    val RELEASE_LATEST_URL: String = BuildConfig.RELEASE_LATEST_URL  // .../releases/latest/download
    const val MANIFEST_FILE = "manifest.json"
    const val RELEASE_APK_ASSET = "PointAndIdentify.apk"
    const val RELEASE_VERSION_ASSET = "version.json"
    val RELEASE_TAG_URL: String = BuildConfig.RELEASE_TAG_URL      // .../releases/download (followed by /v<version>/<asset>)
    const val RELEASE_INSTALL_ASSET = "install.json"   // downloaded once per fresh installation: counts new installs
    const val RELEASE_LAUNCH_ASSET = "launch.json"     // downloaded once per installed version: counts devices per version
    const val USAGE_PING_MAX_FAILURES = 3              // attempts per version before the counter gives up

    // ===== Parameters: terrain tiles =====
    const val TILE_DEG = 0.1                  // deg, tile edge length (lat and lon)
    const val TILE_INDEX_SCALE = 10           // tiles per degree = 1 / TILE_DEG
    const val TILE_SAMPLES = 361              // samples per tile edge, shared edges included
    const val SAMPLES_PER_DEG = 3600          // 1 arc-second grid (SRTM1)
    const val TILE_MEMORY_CACHE = 160         // tiles kept in RAM (~260 kB each, ~42 MB total)
    const val FETCH_RADIUS_M = 50_000.0       // m, terrain fetched around the observer (~110 tiles)
    const val REFETCH_DISTANCE_M = 5_000.0    // m, observer movement that triggers re-fetch
    const val TILE_DOWNLOAD_PARALLELISM = 4   // concurrent tile downloads, nearest tiles first

    // ===== Parameters: targets and line of sight =====
    const val MAX_TARGET_RANGE_M = 50_000.0   // m, must not exceed FETCH_RADIUS_M; curvature drop here ~171 m
    const val MIN_TARGET_RANGE_M = 300.0      // m, observer standing at/inside a target does not select it
    const val LOS_RECALC_DISTANCE_M = 150.0   // m, observer movement that triggers LOS recompute
    const val DEM_SAMPLE_SPACING_M = 30.0     // m, ~ SRTM1 cell size
    const val EYE_HEIGHT_M = 1.7              // m, observer eye above ground
    // Target height, footprint exclusion and angular radius are per kind: see domain/TargetKind.kt
    const val EARTH_RADIUS_M = 6_371_008.8    // m, mean Earth radius
    const val REFRACTION_K = 0.13             // standard atmospheric refraction coefficient
    const val GEOID_UNDULATION_FALLBACK_M = 20.0 // m, ESTIMATE for Israel; used only without DEM and MSL

    // ===== Parameters: target selection =====
    const val DEFAULT_HFOV_DEG = 60.0         // deg, used when camera FOV is unavailable
    const val PREVIEW_ASPECT = 3.0 / 4.0      // width / height of the (portrait) camera image; the view crops it to the screen shape

    // ===== Parameters: crosshair (shared by the drawing and by the target window) =====
    const val CROSSHAIR_RADIUS_RATIO = 0.09   // circle radius as a fraction of the shorter screen edge, at zoom 1
    const val CROSSHAIR_ZOOM_EXPONENT = 0.5   // crosshair scale = zoom ^ exponent (zoom below 1 does not shrink it)
    const val CROSSHAIR_SCALE_MAX = 2.5       // upper limit of the crosshair scale
    const val CANDIDATES_MAX = 5              // targets listed by the identify dialog
    const val VERTICAL_TIEBREAK_WEIGHT = 0.2  // score weight of the vertical angle mismatch (tie-breaker only)
    const val VERTICAL_TIEBREAK_CAP_DEG = 10.0 // deg, vertical mismatch beyond this adds no further penalty

    // ===== Parameters: phone pose (raised = aiming, flat = reading the screen; hysteresis between the two) =====
    const val FLAT_BELOW_DEG = -55.0          // deg, camera elevation below this = phone flat
    const val RAISED_ABOVE_DEG = -35.0        // deg, camera elevation above this = phone raised

    // ===== Parameters: find (free-text place search) =====
    const val FIND_MAX_RESULTS = 10           // places listed for the user to choose from
    const val GEOCODER_MAX_RESULTS = 5        // address matches requested from the platform geocoder

    // ===== Parameters: zoom =====
    const val ZOOM_MIN_FALLBACK = 1.0         // used until the camera reports its zoom range
    const val EXTRA_ZOOM_MAX = 4.0            // extra calculated (digital) zoom factor applied beyond the camera's own maximum

    // ===== Parameters: language =====
    const val LANGUAGE_ENGLISH_TAG = "en"
    const val LANGUAGE_HEBREW_TAG = "he"
    const val LANGUAGE_DEFAULT_TAG = LANGUAGE_HEBREW_TAG // used until the user picks a language in Settings

    // ===== Parameters: sensors =====
    const val AZIMUTH_SMOOTHING_ALPHA = 0.15f // low-pass factor, 0..1 (lower = smoother)
    const val UI_UPDATE_INTERVAL_MS = 100L    // ms, overlay refresh throttle

    // ===== Parameters: compass health check (runs once at start) =====
    const val COMPASS_FIELD_MIN_UT = 25.0     // uT, below this the magnetometer reads too weak a field
    const val COMPASS_FIELD_MAX_UT = 65.0     // uT, above this a magnet or steel is distorting the field (Earth: ~25-65)
    const val COMPASS_CHECK_DURATION_MS = 4_000L // ms, sampling window with the phone held still
    const val COMPASS_SPREAD_MAX_DEG = 8.0    // deg, circular std-dev of azimuth above which the reading is unstable
    const val COMPASS_FIELD_SMOOTHING_ALPHA = 0.2f // low-pass factor of the field strength

    // ===== Parameters: location =====
    const val LOCATION_INTERVAL_MS = 2_000L
    const val LOCATION_MIN_INTERVAL_MS = 1_000L

    // ===== Parameters: network =====
    const val HTTP_CONNECT_TIMEOUT_MS = 10_000
    const val HTTP_READ_TIMEOUT_MS = 30_000
    const val HTTP_MAX_BYTES = 120L * 1024 * 1024 // hard cap on any single download

    // ===== Parameters: photo =====
    const val JPEG_QUALITY = 95
    const val PHOTO_RELATIVE_PATH = "DCIM/PointAndIdentify"

    init {
        // Validation: inconsistent parameters produce silently wrong visibility results.
        require(MAX_TARGET_RANGE_M <= FETCH_RADIUS_M) { "MAX_TARGET_RANGE_M must not exceed FETCH_RADIUS_M" }
        require(MIN_TARGET_RANGE_M in 0.0..MAX_TARGET_RANGE_M) { "MIN_TARGET_RANGE_M out of range" }
        require(TILE_SAMPLES == (SAMPLES_PER_DEG * TILE_DEG).toInt() + 1) { "TILE_SAMPLES inconsistent with TILE_DEG" }
        require(DEM_SAMPLE_SPACING_M > 0 && EYE_HEIGHT_M >= 0) { "Invalid LOS parameters" }
        require(REFRACTION_K in 0.0..0.5) { "REFRACTION_K out of physical range" }
        require(AZIMUTH_SMOOTHING_ALPHA in 0.01f..1f) { "AZIMUTH_SMOOTHING_ALPHA out of range" }
        require(TILE_DOWNLOAD_PARALLELISM in 1..8) { "TILE_DOWNLOAD_PARALLELISM out of range" }
        require(CROSSHAIR_RADIUS_RATIO in 0.01..0.5 && CROSSHAIR_SCALE_MAX >= 1.0 && CROSSHAIR_ZOOM_EXPONENT >= 0.0) {
            "Invalid crosshair parameters"
        }
        require(PREVIEW_ASPECT in 0.1..1.0) { "PREVIEW_ASPECT must be a portrait ratio" }
        require(FLAT_BELOW_DEG < RAISED_ABOVE_DEG) { "FLAT_BELOW_DEG must be below RAISED_ABOVE_DEG (hysteresis)" }
        require(FIND_MAX_RESULTS >= 1 && GEOCODER_MAX_RESULTS >= 1) { "Invalid find result limits" }
        require(USAGE_PING_MAX_FAILURES >= 1) { "USAGE_PING_MAX_FAILURES must be positive" }
        require(CANDIDATES_MAX >= 1) { "CANDIDATES_MAX must be positive" }
        require(VERTICAL_TIEBREAK_WEIGHT in 0.0..1.0 && VERTICAL_TIEBREAK_CAP_DEG > 0) { "Invalid vertical tie-break" }
        require(LANGUAGE_DEFAULT_TAG == LANGUAGE_ENGLISH_TAG || LANGUAGE_DEFAULT_TAG == LANGUAGE_HEBREW_TAG) {
            "LANGUAGE_DEFAULT_TAG must be a supported language"
        }
        require(EXTRA_ZOOM_MAX >= 1.0) { "EXTRA_ZOOM_MAX must be at least 1" }
        require(ZOOM_MIN_FALLBACK > 0) { "ZOOM_MIN_FALLBACK must be positive" }
        require(COMPASS_FIELD_MIN_UT in 0.0..COMPASS_FIELD_MAX_UT) { "Invalid compass field range" }
        require(COMPASS_CHECK_DURATION_MS > 0 && COMPASS_SPREAD_MAX_DEG > 0) { "Invalid compass check parameters" }
        require(COMPASS_FIELD_SMOOTHING_ALPHA in 0.01f..1f) { "COMPASS_FIELD_SMOOTHING_ALPHA out of range" }
    }
}
