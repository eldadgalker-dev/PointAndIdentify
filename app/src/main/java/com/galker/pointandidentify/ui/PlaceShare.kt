// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.0
package com.galker.pointandidentify.ui

import java.util.Locale

/** A place to copy or send: private points and the targets listed by the identify window. */
data class SharedPlace(
    val name: String,
    val kind: String?,            // localized kind label, null when not known
    val lat: Double,
    val lon: Double,
    val details: String? = null   // free line, for example "range 2.1 km | visible"
)

/**
 * Plain-text form of places, for the clipboard and for sending by e-mail or any other app.
 * One block per place: name (kind), coordinates, optional details, and a map link that opens in any maps app.
 * Numbers use a fixed locale (Western digits, dot decimals) so the text is the same in both UI languages.
 */
object PlaceShare {

    fun mapsUrl(lat: Double, lon: Double): String = String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", lat, lon)

    fun text(places: List<SharedPlace>): String = places.joinToString("\n\n") { p ->
        buildList {
            add(if (p.kind.isNullOrBlank()) p.name else "${p.name} (${p.kind})")
            add(String.format(Locale.US, "%.6f, %.6f", p.lat, p.lon))
            p.details?.takeIf { it.isNotBlank() }?.let { add(it) }
            add(mapsUrl(p.lat, p.lon))
        }.joinToString("\n")
    }
}
