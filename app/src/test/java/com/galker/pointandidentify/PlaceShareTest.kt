// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.0
package com.galker.pointandidentify

import com.galker.pointandidentify.ui.PlaceShare
import com.galker.pointandidentify.ui.SharedPlace
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaceShareTest {

    @Test
    fun onePlaceHasNameCoordinatesDetailsAndMapLink() {
        val text = PlaceShare.text(listOf(SharedPlace("Home", "Private point", 32.07295, 34.78927, "Range 2.1 km")))
        assertEquals(
            "Home (Private point)\n32.072950, 34.789270\nRange 2.1 km\nhttps://maps.google.com/?q=32.072950,34.789270",
            text
        )
    }

    @Test
    fun placesAreSeparatedByABlankLineAndOptionalPartsAreSkipped() {
        val text = PlaceShare.text(listOf(SharedPlace("A", null, 1.0, 2.0), SharedPlace("B", "", -3.5, 4.25, " ")))
        assertEquals(
            "A\n1.000000, 2.000000\nhttps://maps.google.com/?q=1.000000,2.000000\n\n" +
                "B\n-3.500000, 4.250000\nhttps://maps.google.com/?q=-3.500000,4.250000",
            text
        )
    }
}
