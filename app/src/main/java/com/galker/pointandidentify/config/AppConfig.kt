// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.22
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
    val DATA_FALLBACK_URL: String = BuildConfig.DATA_FALLBACK_URL  // .../data on a second host, used when the first fails
    val RELEASE_LATEST_URL: String = BuildConfig.RELEASE_LATEST_URL  // .../releases/latest/download
    const val MANIFEST_FILE = "manifest.json"
    const val RELEASE_APK_ASSET = "PointAndIdentify.apk"
    const val RELEASE_VERSION_ASSET = "version.json"
    val RELEASE_TAG_URL: String = BuildConfig.RELEASE_TAG_URL      // .../releases/download (followed by /v<version>/<asset>)
    const val RELEASE_INSTALL_ASSET = "install.json"   // downloaded once per fresh installation: counts new installs
    const val RELEASE_LAUNCH_ASSET = "launch.json"     // downloaded once per installed version: counts devices per version
    const val USAGE_PING_MAX_FAILURES = 3              // attempts per version before the counter gives up

    // ===== Parameters: automatic update check =====
    const val UPDATE_CHECK_INTERVAL_MS = 10 * 60 * 1000L // ms, repeat the check when the user returns to the app after this long
    const val TILE_RETRY_INTERVAL_MS = 30_000L  // ms, retry the terrain download after an incomplete fetch (offline, failed tiles)
    const val LOCATION_DEFAULT_ACCURACY_M = 50f // m, assumed horizontal accuracy of a fix that reports none
    const val UPDATE_RETRY_INTERVAL_MS = 60 * 1000L      // ms, retry after a failed check (no network)

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
    const val DEM_SAMPLE_SPACING_M = 20.0     // m, LOS / aim-ray step; below the smallest SRTM1 cell width in the region (~25 m east-west at 34 N)
    const val EYE_HEIGHT_M = 1.7              // m, default observer eye above ground (the user can change it in Settings)
    const val OBSERVER_HEIGHT_MAX_M = 500.0   // m, upper limit of the user-entered height above ground
    // Target height, footprint exclusion and angular radius are per kind: see domain/TargetKind.kt
    const val LOS_CLEARANCE_TOLERANCE_M = 5.0 // m, terrain-model noise (SRTM ~ +-6..16 m, includes buildings): a blocker below the line by less than this is ignored
    const val EARTH_RADIUS_M = 6_371_008.8    // m, mean Earth radius
    const val REFRACTION_K = 0.13             // standard atmospheric refraction coefficient
    const val GEOID_UNDULATION_FALLBACK_M = 20.0 // m, ESTIMATE for Israel; replaces the platform MSL altitude on Android < 14
    const val GEOID_FALLBACK_SIGMA_M = 5.0    // m, uncertainty of that estimate; added in quadrature to the fusion sigma when it is used

    // ===== Parameters: target selection =====
    const val DEFAULT_HFOV_DEG = 60.0         // deg, used when camera FOV is unavailable
    const val PREVIEW_ASPECT = 3.0 / 4.0      // width / height of the (portrait) camera image; the view crops it to the screen shape

    // ===== Parameters: crosshair (shared by the drawing and by the target window) =====
    const val CROSSHAIR_RADIUS_RATIO = 0.09   // circle radius as a fraction of the shorter screen edge, at zoom 1
    const val CROSSHAIR_ZOOM_EXPONENT = 0.5   // crosshair scale = zoom ^ exponent (zoom below 1 does not shrink it)
    const val CROSSHAIR_SCALE_MAX = 2.5       // upper limit of the crosshair scale
    const val CANDIDATES_MAX = 5              // targets listed by the identify dialog
    const val VERTICAL_MARGIN_DEG = 0.5       // deg, tolerance added to a target's vertical extent (structure height and DEM noise)

    // ===== Parameters: observer altitude from barometer + GPS (see domain/BaroGpsFusion.kt) =====
    const val SEA_LEVEL_PRESSURE_HPA = 1013.25 // hPa, reference of the pressure altitude (only changes matter)
    const val PRESSURE_SMOOTHING_ALPHA = 0.2f  // low-pass factor of the pressure, 0..1
    const val ALT_BARO_DRIFT_M_PER_SQRT_S = 0.1 // m / sqrt(s), weather drift of the barometer (about 6 m per hour)
    const val ALT_BARO_NOISE_M = 1.0           // m, barometer noise after smoothing
    const val ALT_INITIAL_SIGMA_MIN_M = 15.0   // m, the first GPS altitude is never trusted more than this (a bad first fix must not lock the filter)
    const val ALT_MIN_SIGMA_M = 4.0            // m, the fused altitude is never reported as more certain than this
    const val ALT_GPS_SIGMA_DEFAULT_M = 20.0   // m, GPS vertical accuracy when the device reports none
    const val ALT_GPS_SIGMA_MIN_M = 3.0        // m, lower clamp of the reported GPS vertical accuracy
    const val ALT_GPS_SIGMA_MAX_M = 50.0       // m, upper clamp of the reported GPS vertical accuracy
    const val ALT_GATE_SIGMAS = 4.0            // GPS altitudes farther than this many sigmas from the prediction are ignored
    const val ALT_AUTO_MAX_SIGMA_M = 12.0      // m, the sensor altitude is used only when it is at least this certain
    const val ALT_LOWER_BOUND_SIGMAS = 1.5     // the sensor altitude is used minus this many sigmas (never optimistic)
    const val ALT_RECALC_M = 3.0               // m, change of the observer altitude that triggers a new visibility pass

    // ===== Parameters: phone pose (raised = aiming, flat = reading the screen; hysteresis between the two) =====
    const val FLAT_BELOW_DEG = -55.0          // deg, camera elevation below this = phone flat
    const val RAISED_ABOVE_DEG = -35.0        // deg, camera elevation above this = phone raised

    // ===== Parameters: current-city mode (camera pointing well below the horizon, e.g. the street below or a phone on a table) =====
    const val CITY_MODE_BELOW_DEG = -30.0     // deg, camera elevation below this (down to -90) = show only the current city
    const val CITY_MODE_EXIT_DEG = -26.0      // deg, camera elevation above this = leave city mode (hysteresis)
    const val CITY_YIELD_MARGIN_M = 200.0     // m, a visible target in the crosshair up to this much beyond the aimed ground point beats the current-city display
    const val AIM_MIN_EYE_ABOVE_GROUND_M = 0.5 // m, the aim ray starts at least this high above the terrain under the observer
    const val AIM_RAY_MAX_M = 5_000.0         // m, longest distance at which the camera axis is intersected with the terrain
    const val CITY_AIM_RADIUS_M = 1_500.0     // m, the ray meets the ground within this radius: current-location circle (any angle)
    const val CITY_AIM_EXIT_M = 1_800.0       // m, ... and the mode ends only beyond this radius (hysteresis)
    const val CITY_SEARCH_RADIUS_M = 15_000.0 // m, the current city is the nearest settlement within this range
    const val CITY_AZIMUTH_TOL_DEG = 30.0     // deg, the current place is the settlement nearest in azimuth, if within this of the camera direction
    const val CITY_AZIMUTH_SWITCH_MARGIN_DEG = 10.0 // deg, another settlement replaces the current one only when this much closer in azimuth

    // ===== Parameters: find (free-text place search) =====
    const val FIND_MAX_RESULTS = 10           // places listed for the user to choose from
    const val GEOCODER_MAX_RESULTS = 5        // address matches requested from the platform geocoder

    // ===== Parameters: zoom =====
    const val PRIVATE_POINTS_MAX = 100        // user-defined points (kept in SharedPreferences, loaded as one JSON text)
    const val ZOOM_MIN_FALLBACK = 1.0         // used until the camera reports its zoom range
    const val EXTRA_ZOOM_MAX = 4.0            // extra calculated (digital) zoom factor applied beyond the camera's own maximum

    // ===== Parameters: language =====
    const val LANGUAGE_ENGLISH_TAG = "en"
    const val LANGUAGE_HEBREW_TAG = "he"
    const val LANGUAGE_DEFAULT_TAG = LANGUAGE_HEBREW_TAG // used until the user picks a language in Settings

    // ===== Parameters: sensors =====
    const val SMOOTH_ALPHA_MIN = 0.04         // heading / elevation smoothing for tiny changes (hand tremor): heavy
    const val SMOOTH_ALPHA_MAX = 0.60         // ... and for real turns: almost none
    const val SMOOTH_FAST_DEG = 5.0           // deg, a change of this size or more uses SMOOTH_ALPHA_MAX
    const val HEADING_MAG_TAU_S = 3.0         // s, time constant with which the gyroscope heading follows the magnetic heading
    const val UI_UPDATE_INTERVAL_MS = 200L    // ms, overlay refresh throttle (was 100: slower, so small shakes change less)
    const val SELECTION_HOLD_MS = 600L        // ms, a challenger must stay clearly better this long to replace the shown target
    const val SELECTION_LOST_MS = 400L        // ms, a target that just left the crosshair stays on the screen this long
    const val SELECTION_SWITCH_RATIO = 0.6    // a challenger replaces the shown target only with a score below this fraction of its score

    // ===== Parameters: compass health check (runs once at start) =====
    const val COMPASS_FIELD_MIN_UT = 25.0     // uT, below this the magnetometer reads too weak a field
    const val COMPASS_FIELD_MAX_UT = 65.0     // uT, above this a magnet or steel is distorting the field (Earth: ~25-65)
    const val COMPASS_CHECK_DURATION_MS = 4_000L // ms, sampling window with the phone held still
    const val COMPASS_SPREAD_MAX_DEG = 8.0    // deg, circular std-dev of azimuth above which the reading is unstable
    const val COMPASS_FIELD_SMOOTHING_ALPHA = 0.2f // low-pass factor of the field strength

    // ===== Parameters: location =====
    const val LOCATION_INTERVAL_MS = 1_000L   // fused provider interval (was 2 s; the position filter weights every fix)
    const val LOCATION_LAST_FIX_MAX_AGE_MS = 120_000L // a cached last location older than this is not used as the first fix
    const val FILTER_MIN_ACCURACY_M = 3.0     // m, no fix is trusted (and no accuracy reported) better than this
    const val FILTER_MIN_SPEED_MPS = 0.5      // m/s, assumed minimum movement of the observer between fixes
    const val FILTER_MAX_GAP_S = 60.0         // s, longer gaps between fixes count as this long
    const val PRIVATE_HERE_MIN_M = 100.0      // m, a private point this close counts as "the current place" (grows with poor accuracy)
    const val PRIVATE_HERE_MAX_M = 400.0      // m, upper limit of that radius
    const val LOCATION_MIN_INTERVAL_MS = 500L

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
        require(EYE_HEIGHT_M in 0.0..OBSERVER_HEIGHT_MAX_M) { "EYE_HEIGHT_M out of range" }
        require(DEM_SAMPLE_SPACING_M > 0 && EYE_HEIGHT_M >= 0) { "Invalid LOS parameters" }
        require(REFRACTION_K in 0.0..0.5) { "REFRACTION_K out of physical range" }
        require(SMOOTH_ALPHA_MIN in 0.0..1.0 && SMOOTH_ALPHA_MAX in SMOOTH_ALPHA_MIN..1.0 && SMOOTH_FAST_DEG > 0) { "Invalid smoothing parameters" }
        require(HEADING_MAG_TAU_S > 0) { "HEADING_MAG_TAU_S must be positive" }
        require(FILTER_MIN_ACCURACY_M > 0 && FILTER_MIN_SPEED_MPS >= 0 && FILTER_MAX_GAP_S > 0) { "Invalid position filter parameters" }
        require(PRIVATE_HERE_MIN_M in 0.0..PRIVATE_HERE_MAX_M) { "Invalid private-point radius" }
        require(TILE_DOWNLOAD_PARALLELISM in 1..8) { "TILE_DOWNLOAD_PARALLELISM out of range" }
        require(CROSSHAIR_RADIUS_RATIO in 0.01..0.5 && CROSSHAIR_SCALE_MAX >= 1.0 && CROSSHAIR_ZOOM_EXPONENT >= 0.0) {
            "Invalid crosshair parameters"
        }
        require(PREVIEW_ASPECT in 0.1..1.0) { "PREVIEW_ASPECT must be a portrait ratio" }
        require(LOS_CLEARANCE_TOLERANCE_M >= 0.0) { "LOS_CLEARANCE_TOLERANCE_M must not be negative" }
        require(PRESSURE_SMOOTHING_ALPHA in 0.01f..1f) { "PRESSURE_SMOOTHING_ALPHA out of range" }
        require(ALT_GPS_SIGMA_MIN_M in 0.5..ALT_GPS_SIGMA_MAX_M && ALT_MIN_SIGMA_M > 0 && ALT_BARO_NOISE_M >= 0) {
            "Invalid altitude fusion parameters"
        }
        require(ALT_BARO_DRIFT_M_PER_SQRT_S > 0 && ALT_GATE_SIGMAS > 0 && ALT_AUTO_MAX_SIGMA_M > ALT_MIN_SIGMA_M) {
            "Invalid altitude fusion limits"
        }
        require(ALT_LOWER_BOUND_SIGMAS >= 0.0 && ALT_RECALC_M > 0.0) { "Invalid altitude bound parameters" }
        require(CITY_MODE_BELOW_DEG < CITY_MODE_EXIT_DEG) { "CITY_MODE_BELOW_DEG must be below CITY_MODE_EXIT_DEG (hysteresis)" }
        require(0.0 < CITY_AIM_RADIUS_M && CITY_AIM_RADIUS_M < CITY_AIM_EXIT_M && CITY_AIM_EXIT_M <= AIM_RAY_MAX_M) {
            "Invalid current-location circle radii"
        }
        require(CITY_YIELD_MARGIN_M >= 0 && CITY_MODE_EXIT_DEG < 0) { "Invalid city-mode parameters" }
        require(GEOID_FALLBACK_SIGMA_M >= 0 && PRIVATE_POINTS_MAX >= 1) { "Invalid altitude / private point parameters" }
        require(SELECTION_HOLD_MS >= 0 && SELECTION_LOST_MS >= 0 && SELECTION_SWITCH_RATIO in 0.0..1.0) { "Invalid selection stability parameters" }
        require(CITY_AZIMUTH_TOL_DEG > 0 && CITY_AZIMUTH_SWITCH_MARGIN_DEG >= 0) { "Invalid current-city azimuth parameters" }
        require(CITY_SEARCH_RADIUS_M > 0) { "Invalid current-city parameters" }
        require(FLAT_BELOW_DEG < RAISED_ABOVE_DEG) { "FLAT_BELOW_DEG must be below RAISED_ABOVE_DEG (hysteresis)" }
        require(FIND_MAX_RESULTS >= 1 && GEOCODER_MAX_RESULTS >= 1) { "Invalid find result limits" }
        require(UPDATE_RETRY_INTERVAL_MS in 1..UPDATE_CHECK_INTERVAL_MS) { "Invalid update check intervals" }
        require(ALT_INITIAL_SIGMA_MIN_M >= ALT_MIN_SIGMA_M && AIM_MIN_EYE_ABOVE_GROUND_M > 0.0) { "Invalid altitude / aim parameters" }
        require(TILE_RETRY_INTERVAL_MS > 0 && LOCATION_DEFAULT_ACCURACY_M > 0f) { "Invalid retry / accuracy parameters" }
        require(USAGE_PING_MAX_FAILURES >= 1) { "USAGE_PING_MAX_FAILURES must be positive" }
        require(CANDIDATES_MAX >= 1) { "CANDIDATES_MAX must be positive" }
        require(VERTICAL_MARGIN_DEG in 0.0..5.0) { "VERTICAL_MARGIN_DEG out of range" }
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
