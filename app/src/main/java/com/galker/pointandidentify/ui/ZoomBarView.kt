// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.0
package com.galker.pointandidentify.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Vertical zoom bar on the right edge: minimum at the bottom, maximum at the top.
 * value is a normalised position 0..1 (the Activity maps it to a logarithmic zoom ratio).
 * The current zoom is printed at the thumb; the range limits are printed at the bar ends.
 */
class ZoomBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ===== Parameters (dp / sp) =====
    private val trackWidthDp = 6f
    private val thumbRadiusDp = 13f
    private val trackInsetRightDp = 16f   // distance of the track centre from the right edge
    private val labelGapDp = 10f          // gap between labels and the thumb
    private val valueTextSp = 18f
    private val limitTextSp = 13f
    private val plateAlpha = 150          // 0..255, dark plate behind labels

    /** Called while the user drags; argument is the new position 0..1. */
    var onValueChanged: ((Float) -> Unit)? = null

    var value: Float = 0f
        set(v) {
            val c = v.coerceIn(0f, 1f)
            if (c != field) {
                field = c
                invalidate()
            }
        }

    var valueLabel: String = ""
        set(v) {
            if (v != field) {
                field = v
                invalidate()
            }
        }

    var minLabel: String = ""
        set(v) {
            field = v
            invalidate()
        }

    var maxLabel: String = ""
        set(v) {
            field = v
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(200, 255, 255, 255)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.rgb(255, 193, 7)
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 193, 7) }
    private val thumbRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
    }
    private val platePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(plateAlpha, 0, 0, 0) }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        isFakeBoldText = true
        textAlign = Paint.Align.RIGHT
    }
    private val limitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(220, 220, 220)
        textAlign = Paint.Align.RIGHT
    }
    private val plateRect = RectF()

    init {
        valuePaint.textSize = valueTextSp * scaledDensity
        limitPaint.textSize = limitTextSp * scaledDensity
        trackPaint.strokeWidth = trackWidthDp * density
        fillPaint.strokeWidth = trackWidthDp * density
        thumbRingPaint.strokeWidth = 2f * density
    }

    // Track runs between these y coordinates; the thumb radius keeps the thumb inside the view at both ends.
    private fun trackTop() = thumbRadiusDp * density + paddingTop
    private fun trackBottom() = height - thumbRadiusDp * density - paddingBottom

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val x = width - trackInsetRightDp * density
        val top = trackTop()
        val bottom = trackBottom()
        if (bottom <= top) return
        val thumbY = bottom - value * (bottom - top)
        val labelRight = x - (thumbRadiusDp + labelGapDp) * density

        canvas.drawLine(x, top, x, bottom, trackPaint)
        canvas.drawLine(x, bottom, x, thumbY, fillPaint)

        // Range limits at the bar ends; skipped when the thumb label would sit on top of them.
        val clearance = valuePaint.textSize * 1.6f
        if (maxLabel.isNotEmpty() && thumbY - top > clearance) {
            drawPlated(canvas, maxLabel, limitPaint, labelRight, top)
        }
        if (minLabel.isNotEmpty() && bottom - thumbY > clearance) {
            drawPlated(canvas, minLabel, limitPaint, labelRight, bottom)
        }

        canvas.drawCircle(x, thumbY, thumbRadiusDp * density, thumbPaint)
        canvas.drawCircle(x, thumbY, thumbRadiusDp * density, thumbRingPaint)
        if (valueLabel.isNotEmpty()) drawPlated(canvas, valueLabel, valuePaint, labelRight, thumbY)
    }

    /** Text right-aligned at xRight, vertically centred on yCentre, on a dark rounded plate. */
    private fun drawPlated(canvas: Canvas, text: String, paint: Paint, xRight: Float, yCentre: Float) {
        val pad = 4f * density
        val w = paint.measureText(text)
        val fm = paint.fontMetrics
        val baseline = yCentre - (fm.ascent + fm.descent) / 2f
        plateRect.set(xRight - w - pad, baseline + fm.ascent - pad / 2, xRight + pad, baseline + fm.descent + pad / 2)
        canvas.drawRoundRect(plateRect, pad, pad, platePaint)
        canvas.drawText(text, xRight, baseline, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val top = trackTop()
                val bottom = trackBottom()
                if (bottom > top) {
                    val v = ((bottom - event.y) / (bottom - top)).coerceIn(0f, 1f)
                    value = v
                    onValueChanged?.invoke(v)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.4f
    }
}
