// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.0
package com.galker.pointandidentify.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws the small compass dial. Shared by the live CompassView and the photo overlay (OverlayRenderer),
 * so the saved photo shows the same compass as the screen.
 * The top of the dial is where the camera points; the red needle and the north label turn
 * so that they always point to TRUE north. azimuthDeg = camera heading, clockwise from true north.
 */
class CompassPainter {

    // ===== Parameters (fractions of the dial radius unless noted) =====
    private val needleLengthRatio = 0.58f
    private val needleHalfWidthRatio = 0.18f
    private val labelRadiusRatio = 0.76f
    private val labelTextRatio = 0.42f
    private val headingMarkRatio = 0.14f   // fixed "camera direction" tick at the top rim
    private val dialAlpha = 150            // 0..255, dark dial background

    private val dialPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(dialAlpha, 0, 0, 0) }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val northPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(229, 57, 53) }
    private val southPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(235, 235, 235) }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 193, 7) }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 138, 128)
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val path = Path()

    /** outerRadius includes the ring; azimuthDeg null draws only the empty dial. */
    fun draw(canvas: Canvas, cx: Float, cy: Float, outerRadius: Float, ringWidth: Float, azimuthDeg: Float?, northLabel: String) {
        val r = outerRadius - ringWidth
        if (r <= 0f) return
        ringPaint.strokeWidth = ringWidth

        canvas.drawCircle(cx, cy, r, dialPaint)
        canvas.drawCircle(cx, cy, r, ringPaint)

        // Fixed mark at the top rim: the direction the camera is pointing.
        path.reset()
        path.moveTo(cx, cy - r + r * headingMarkRatio)
        path.lineTo(cx - r * headingMarkRatio * 0.7f, cy - r)
        path.lineTo(cx + r * headingMarkRatio * 0.7f, cy - r)
        path.close()
        canvas.drawPath(path, markPaint)

        val az = azimuthDeg ?: return
        // True north lies at -az clockwise from the top of the dial.
        val angle = -az
        canvas.save()
        canvas.rotate(angle, cx, cy)
        val len = r * needleLengthRatio
        val half = r * needleHalfWidthRatio
        path.reset()
        path.moveTo(cx, cy - len)
        path.lineTo(cx - half, cy)
        path.lineTo(cx + half, cy)
        path.close()
        canvas.drawPath(path, northPaint)
        path.reset()
        path.moveTo(cx, cy + len)
        path.lineTo(cx - half, cy)
        path.lineTo(cx + half, cy)
        path.close()
        canvas.drawPath(path, southPaint)
        canvas.restore()

        // North label stays upright: it is placed along the north direction instead of being rotated with the needle.
        labelPaint.textSize = r * labelTextRatio
        val rad = Math.toRadians(angle.toDouble())
        val lx = cx + (r * labelRadiusRatio * sin(rad)).toFloat()
        val ly = cy - (r * labelRadiusRatio * cos(rad)).toFloat()
        val fm = labelPaint.fontMetrics
        canvas.drawText(northLabel, lx, ly - (fm.ascent + fm.descent) / 2f, labelPaint)
    }
}
