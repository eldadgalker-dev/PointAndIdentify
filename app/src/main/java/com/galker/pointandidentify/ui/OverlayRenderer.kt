// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.2
package com.galker.pointandidentify.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import kotlin.math.min

/**
 * Texts drawn on top of the camera image.
 * infoLines: observer / direction / target data block, drawn right-aligned (RTL start) near the bottom.
 */
data class OverlayContent(
    val primary: String,
    val secondary: String,
    val infoLines: List<String> = emptyList(),
    val footer: String = "",
    val visible: Boolean = false
)

/**
 * Single renderer for both the live preview and the saved photo, so both look identical.
 * All sizes scale with the shorter canvas edge; text is laid out with StaticLayout using an
 * explicit RTL direction heuristic, which keeps mixed Hebrew / numeric text in correct order.
 */
class OverlayRenderer {

    // ===== Parameters (fractions of the shorter canvas edge) =====
    private val crosshairRadiusRatio = 0.06f
    private val crosshairArmRatio = 0.03f
    private val strokeRatio = 0.004f
    private val primaryTextRatio = 0.05f
    private val secondaryTextRatio = 0.033f
    private val infoTextRatio = 0.028f
    private val footerTextRatio = 0.024f
    private val marginRatio = 0.03f
    private val infoBackgroundAlpha = 110   // 0..255, dark band behind the data block for legibility

    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val primaryPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val secondaryPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val infoPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(220, 220, 220) }
    private val bandPaint = Paint().apply { color = Color.BLACK }

    /** bottomInsetPx: space kept free at the bottom (e.g. above the capture button in the live view). */
    fun draw(canvas: Canvas, width: Int, height: Int, content: OverlayContent, bottomInsetPx: Float = 0f) {
        val unit = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val r = unit * crosshairRadiusRatio
        val arm = unit * crosshairArmRatio
        val shadow = unit * 0.006f

        crosshairPaint.color = if (content.visible) Color.GREEN else Color.RED
        crosshairPaint.strokeWidth = unit * strokeRatio
        crosshairPaint.setShadowLayer(shadow, 0f, 0f, Color.BLACK)
        canvas.drawCircle(cx, cy, r, crosshairPaint)
        canvas.drawLine(cx - r - arm, cy, cx - r * 0.4f, cy, crosshairPaint)
        canvas.drawLine(cx + r * 0.4f, cy, cx + r + arm, cy, crosshairPaint)
        canvas.drawLine(cx, cy - r - arm, cx, cy - r * 0.4f, crosshairPaint)
        canvas.drawLine(cx, cy + r * 0.4f, cx, cy + r + arm, crosshairPaint)

        primaryPaint.color = if (content.visible) Color.YELLOW else Color.rgb(255, 160, 120)
        primaryPaint.textSize = unit * primaryTextRatio
        primaryPaint.setShadowLayer(shadow, shadow / 3, shadow / 3, Color.BLACK)
        secondaryPaint.textSize = unit * secondaryTextRatio
        secondaryPaint.setShadowLayer(shadow, shadow / 3, shadow / 3, Color.BLACK)
        infoPaint.textSize = unit * infoTextRatio
        infoPaint.setShadowLayer(shadow, shadow / 3, shadow / 3, Color.BLACK)
        footerPaint.textSize = unit * footerTextRatio
        footerPaint.setShadowLayer(shadow, shadow / 3, shadow / 3, Color.BLACK)

        val margin = unit * marginRatio
        val textWidth = (width - 2 * margin).toInt().coerceAtLeast(1)

        // Title block above the crosshair: secondary line closest, primary above it.
        val secondary = layout(content.secondary, secondaryPaint, textWidth, Layout.Alignment.ALIGN_CENTER)
        val primary = layout(content.primary, primaryPaint, textWidth, Layout.Alignment.ALIGN_CENTER)
        val secondaryTop = cy - r - arm - margin - secondary.height
        val primaryTop = secondaryTop - primary.height
        drawLayout(canvas, primary, margin, primaryTop)
        drawLayout(canvas, secondary, margin, secondaryTop)

        // Data block at the bottom, stacked upwards: footer last, info lines above it.
        // ALIGN_NORMAL with an RTL heuristic aligns to the right edge.
        var bottom = height - bottomInsetPx - margin
        val blocks = ArrayList<StaticLayout>()
        if (content.footer.isNotEmpty()) blocks.add(layout(content.footer, footerPaint, textWidth, Layout.Alignment.ALIGN_NORMAL))
        if (content.infoLines.isNotEmpty()) {
            blocks.add(0, layout(content.infoLines.joinToString("\n"), infoPaint, textWidth, Layout.Alignment.ALIGN_NORMAL))
        }
        if (blocks.isNotEmpty()) {
            val total = blocks.sumOf { it.height } + margin * 0.3f * (blocks.size - 1)
            bandPaint.alpha = infoBackgroundAlpha
            canvas.drawRect(0f, bottom - total - margin * 0.5f, width.toFloat(), bottom + margin * 0.5f, bandPaint)
            for (b in blocks.asReversed()) {
                bottom -= b.height
                drawLayout(canvas, b, margin, bottom)
                bottom -= margin * 0.3f
            }
        }
    }

    private fun layout(text: String, paint: TextPaint, width: Int, align: Layout.Alignment): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(align)
            .setTextDirection(TextDirectionHeuristics.RTL)
            .setIncludePad(false)
            .build()

    private fun drawLayout(canvas: Canvas, layout: StaticLayout, x: Float, y: Float) {
        canvas.save()
        canvas.translate(x, y)
        layout.draw(canvas)
        canvas.restore()
    }
}
