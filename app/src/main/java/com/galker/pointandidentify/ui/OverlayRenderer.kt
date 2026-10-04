// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.4
package com.galker.pointandidentify.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristic
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Texts drawn on top of the camera image.
 * infoLines: groups of the observer / direction / target data block (each element may hold several
 * lines), drawn right-aligned (RTL start) near the bottom, one group per row separated by a divider.
 */
data class OverlayContent(
    val primary: String,
    val secondary: String,
    val infoLines: List<String> = emptyList(),
    val footer: String = "",
    val visible: Boolean = false,
    val zoomRatio: Double = 1.0, // camera zoom; the crosshair grows with it
    val rtl: Boolean = true      // paragraph direction of the UI language (Hebrew = true)
)

/**
 * Free space around the data block, px. Defaults reproduce the saved-photo layout.
 *  bottomPx        : space kept free at the bottom (e.g. above the capture button in the live view)
 *  bottomGapPx     : gap between the data block and that free space (null = the standard margin)
 *  sideExtraPx     : additional inset of the data block from both side edges
 *  rightReservedPx : width kept free at the right edge (zoom bar); texts are laid out left of it
 */
data class OverlayInsets(
    val bottomPx: Float = 0f,
    val bottomGapPx: Float? = null,
    val sideExtraPx: Float = 0f,
    val rightReservedPx: Float = 0f
)

/**
 * Single renderer for both the live preview and the saved photo, so both look identical.
 * All sizes scale with the shorter canvas edge; text is laid out with StaticLayout using an
 * explicit direction heuristic (RTL for Hebrew, LTR for English), which keeps mixed text in correct order.
 */
class OverlayRenderer {

    // ===== Parameters (fractions of the shorter canvas edge) =====
    private val crosshairRadiusRatio = 0.06f
    private val crosshairArmRatio = 0.03f
    private val crosshairZoomExponent = 0.5 // crosshair scale = zoom ^ exponent (zoom below 1 does not shrink it)
    private val crosshairScaleMax = 3.5f    // upper limit of the crosshair scale
    private val strokeRatio = 0.004f
    private val primaryTextRatio = 0.072f
    private val secondaryTextRatio = 0.046f
    private val infoTextRatio = 0.04f
    private val footerTextRatio = 0.03f
    private val marginRatio = 0.03f
    private val groupGapRatio = 0.012f      // vertical space on each side of a group divider
    private val dividerStrokeRatio = 0.002f
    private val infoBackgroundAlpha = 160   // 0..255, dark band behind the data block for legibility
    private val titleBackgroundAlpha = 120  // 0..255, dark plate behind the target name

    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val primaryPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val secondaryPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val infoPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(220, 220, 220) }
    private val bandPaint = Paint().apply { color = Color.BLACK }
    private val dividerPaint = Paint().apply { color = Color.argb(120, 255, 255, 255) }

    fun draw(canvas: Canvas, width: Int, height: Int, content: OverlayContent, insets: OverlayInsets = OverlayInsets()) {
        val unit = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        // Crosshair grows with the zoom so it stays easy to see against the magnified image.
        val zoomScale = max(1.0, content.zoomRatio).pow(crosshairZoomExponent).toFloat().coerceAtMost(crosshairScaleMax)
        val r = unit * crosshairRadiusRatio * zoomScale
        val arm = unit * crosshairArmRatio * zoomScale
        val direction = if (content.rtl) TextDirectionHeuristics.RTL else TextDirectionHeuristics.LTR
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
        primaryPaint.isFakeBoldText = true
        primaryPaint.textSize = unit * primaryTextRatio
        primaryPaint.setShadowLayer(shadow, shadow / 3, shadow / 3, Color.BLACK)
        secondaryPaint.textSize = unit * secondaryTextRatio
        secondaryPaint.setShadowLayer(shadow, shadow / 3, shadow / 3, Color.BLACK)
        infoPaint.textSize = unit * infoTextRatio
        infoPaint.setShadowLayer(shadow, shadow / 3, shadow / 3, Color.BLACK)
        footerPaint.textSize = unit * footerTextRatio
        footerPaint.setShadowLayer(shadow, shadow / 3, shadow / 3, Color.BLACK)

        val margin = unit * marginRatio
        // Title is centred, so the reserved right strip is mirrored on the left to keep it centred on the crosshair.
        val titleLeft = margin + insets.rightReservedPx
        val titleWidth = (width - 2 * titleLeft).toInt().coerceAtLeast(1)
        // Data block: inset from both sides, and its right end stops before the reserved strip.
        val blockLeft = margin + insets.sideExtraPx
        val blockWidth = (width - 2 * blockLeft - insets.rightReservedPx).toInt().coerceAtLeast(1)

        // Title block above the crosshair: secondary line closest, primary above it.
        val secondary = layout(content.secondary, secondaryPaint, titleWidth, Layout.Alignment.ALIGN_CENTER, direction)
        val primary = layout(content.primary, primaryPaint, titleWidth, Layout.Alignment.ALIGN_CENTER, direction)
        val secondaryTop = cy - r - arm - margin - secondary.height
        val primaryTop = secondaryTop - primary.height
        if (content.primary.isNotEmpty() || content.secondary.isNotEmpty()) {
            bandPaint.alpha = titleBackgroundAlpha
            canvas.drawRect(0f, primaryTop - margin * 0.4f, width.toFloat(), secondaryTop + secondary.height + margin * 0.4f, bandPaint)
        }
        drawLayout(canvas, primary, titleLeft, primaryTop)
        drawLayout(canvas, secondary, titleLeft, secondaryTop)

        // Data block at the bottom, stacked upwards: footer last, one row per info group above it.
        // ALIGN_NORMAL aligns to the start edge of the language: right for Hebrew, left for English.
        val gap = unit * groupGapRatio
        val blocks = ArrayList<StaticLayout>()
        for (group in content.infoLines) {
            blocks.add(layout(group, infoPaint, blockWidth, Layout.Alignment.ALIGN_NORMAL, direction))
        }
        if (content.footer.isNotEmpty()) {
            blocks.add(layout(content.footer, footerPaint, blockWidth, Layout.Alignment.ALIGN_NORMAL, direction))
        }
        if (blocks.isNotEmpty()) {
            val bottomGap = insets.bottomGapPx ?: margin
            val bottomEdge = height - insets.bottomPx - bottomGap
            val total = blocks.sumOf { it.height } + 2f * gap * (blocks.size - 1)
            bandPaint.alpha = infoBackgroundAlpha
            canvas.drawRect(
                0f, bottomEdge - total - margin * 0.5f,
                width.toFloat(), bottomEdge + min(margin * 0.5f, bottomGap * 0.5f), bandPaint
            )
            dividerPaint.strokeWidth = unit * dividerStrokeRatio
            var y = bottomEdge
            for ((i, b) in blocks.asReversed().withIndex()) {
                y -= b.height
                drawLayout(canvas, b, blockLeft, y)
                if (i < blocks.size - 1) {
                    val dividerY = y - gap
                    canvas.drawLine(blockLeft, dividerY, blockLeft + blockWidth, dividerY, dividerPaint)
                    y -= 2f * gap
                }
            }
        }
    }

    private fun layout(
        text: String, paint: TextPaint, width: Int, align: Layout.Alignment, direction: TextDirectionHeuristic
    ): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(align)
            .setTextDirection(direction)
            .setIncludePad(false)
            .build()

    private fun drawLayout(canvas: Canvas, layout: StaticLayout, x: Float, y: Float) {
        canvas.save()
        canvas.translate(x, y)
        layout.draw(canvas)
        canvas.restore()
    }
}
