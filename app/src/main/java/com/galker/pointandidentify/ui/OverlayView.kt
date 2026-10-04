// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.6
package com.galker.pointandidentify.ui

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import kotlin.math.hypot

/** Live overlay above the camera preview; drawing is delegated to OverlayRenderer. */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val renderer = OverlayRenderer()

    // ===== Parameters =====
    /** Space kept free above the bottom edge for the capture buttons, px (the Activity sets the measured value). */
    var bottomInsetPx: Float = resources.displayMetrics.density * BOTTOM_INSET_DP
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** True when the zoom bar is on the left edge, so the data block keeps clear of that side. */
    var zoomBarOnLeft: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val insets: OverlayInsets
        get() = OverlayInsets(
            bottomPx = bottomInsetPx,
            bottomGapPx = mm(BOTTOM_GAP_MM),
            sideExtraPx = mm(SIDE_EXTRA_MM),
            reservedPx = resources.displayMetrics.density * ZOOM_BAR_RESERVED_DP,
            reservedOnLeft = zoomBarOnLeft
        )

    private fun mm(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_MM, value, resources.displayMetrics)

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

    /** True when a screen point (raw coordinates) lies inside the crosshair, with a little slack for the finger. */
    fun isInsideCrosshair(rawX: Float, rawY: Float): Boolean {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val dx = rawX - loc[0] - width / 2f
        val dy = rawY - loc[1] - height / 2f
        return hypot(dx, dy) <= renderer.crosshairOuterRadius(width, height, content.zoomRatio) * TAP_SLACK
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        renderer.draw(canvas, width, height, content, insets)
    }

    companion object {
        private const val TAP_SLACK = 1.15f         // tap radius = crosshair outer radius times this
        private const val BOTTOM_INSET_DP = 80f      // fallback: capture button 60 dp + 20 dp margin
        private const val BOTTOM_GAP_MM = 1f         // data block sits this far above the bottom buttons
        private const val SIDE_EXTRA_MM = 1f         // data block is inset this much further from the side edges
        private const val ZOOM_BAR_RESERVED_DP = 84f // zoom bar width 80 dp + margin; texts stay clear of it
    }
}
