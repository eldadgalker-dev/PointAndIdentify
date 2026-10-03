// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.2
package com.galker.pointandidentify.ui

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View

/** Live overlay above the camera preview; drawing is delegated to OverlayRenderer. */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val renderer = OverlayRenderer()

    // ===== Parameters =====
    /** Space kept free above the bottom edge for the capture button, px. */
    var bottomInsetPx: Float = resources.displayMetrics.density * BOTTOM_INSET_DP
        set(value) {
            field = value
            invalidate()
        }

    var content: OverlayContent = OverlayContent("", "")
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    init {
        // Software layer is required for setShadowLayer on non-text primitives.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        renderer.draw(canvas, width, height, content, bottomInsetPx)
    }

    companion object {
        private const val BOTTOM_INSET_DP = 128f // capture button 64 dp + 48 dp margin + spacing
    }
}
