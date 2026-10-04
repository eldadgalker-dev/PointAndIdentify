// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.0
package com.galker.pointandidentify.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Gear icon (settings), drawn from a path so it needs no image resource and scales to any size. */
class GearDrawable : Drawable() {

    // ===== Parameters (fractions of the outer radius / radians) =====
    private val teeth = 8
    private val rootRatio = 0.78f        // radius of the gear body between teeth
    private val holeRatio = 0.34f        // radius of the centre hole
    private val toothRootHalfRad = 0.26f // half angular width of a tooth at the body
    private val toothTipHalfRad = 0.17f  // half angular width of a tooth at the tip

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val path = Path()

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        buildPath(bounds)
    }

    private fun buildPath(b: Rect) {
        path.reset()
        path.fillType = Path.FillType.EVEN_ODD
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val outer = min(b.width(), b.height()) / 2f
        val root = outer * rootRatio
        val pitch = (2.0 * Math.PI / teeth).toFloat()
        for (i in 0 until teeth) {
            val a = i * pitch
            // Tooth: body edge -> tip edge -> tip edge -> body edge; the straight chord to the next tooth closes the body.
            val pts = listOf(
                root to (a - toothRootHalfRad), outer to (a - toothTipHalfRad),
                outer to (a + toothTipHalfRad), root to (a + toothRootHalfRad)
            )
            for ((j, p) in pts.withIndex()) {
                val x = cx + p.first * sin(p.second)
                val y = cy - p.first * cos(p.second)
                if (i == 0 && j == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
        }
        path.close()
        path.addCircle(cx, cy, outer * holeRatio, Path.Direction.CW) // EVEN_ODD turns this into a hole
    }

    override fun draw(canvas: Canvas) {
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
