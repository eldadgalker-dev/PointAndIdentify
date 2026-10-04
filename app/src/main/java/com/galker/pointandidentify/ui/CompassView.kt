// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.1
package com.galker.pointandidentify.ui

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/** Small compass for a quick read of where north is relative to the camera direction; drawing is in CompassPainter. */
class CompassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val painter = CompassPainter()

    /** Camera heading, degrees clockwise from true north; null while no heading is available. */
    var azimuthDeg: Float? = null
        set(v) {
            if (v != field) {
                field = v
                invalidate()
            }
        }

    var northLabel: String = "N"
        set(v) {
            if (v != field) {
                field = v
                invalidate()
            }
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val ring = 2f * resources.displayMetrics.density
        painter.draw(canvas, width / 2f, height / 2f, min(width, height) / 2f, ring, azimuthDeg, northLabel)
    }
}
