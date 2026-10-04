// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.7
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
 *  reservedPx      : width kept free at one side edge (zoom bar); the data block is laid out beside it
 *  reservedOnLeft  : which edge the reserved strip is on
 *  topPx           : space kept free at the top (status block); the target title is pinned just below it
 */
data class OverlayInsets(
    val bottomPx: Float = 0f,
    val bottomGapPx: Float? = null,
    val sideExtraPx: Float = 0f,
    val reservedPx: Float = 0f,
    val reservedOnLeft: Boolean = false,
    val topPx: Float = 0f
)

/**
 * Single renderer for both the live preview and the saved photo, so both look identical.
 * All sizes scale with the shorter canvas edge; text is laid out with StaticLayout using an
 * explicit direction heuristic (RTL for Hebrew, LTR for English), which keeps mixed text in correct order.
 */
class OverlayRenderer {

    // ===== Parameters (fractions of the shorter canvas edge) =====
    private val crosshairRadiusRatio = 0.09f
    private val crosshairArmRatio = 0.045f
    private val crosshairZoomExponent = 0.5 // crosshair scale = zoom ^ exponent (zoom below 1 does not shrink it)
    private val crosshairScaleMax = 2.5f    // upper limit of the crosshair scale
    private val strokeRatio = 0.004f
    private val primaryTextRatio = 0.072f
    private val secondaryTextRatio = 0.046f
    private val infoTextRatio = 0.07f
    private val footerTextRatio = 0.052f
    private val infoFitMin = 0.45f       // smallest shrink factor when the data block must fit under the crosshair
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

    /** Draws everything; returns the y of the top of the data block (px), so other views can sit above it. */
    fun draw(canvas: Canvas, width: Int, height: Int, content: OverlayContent, insets: OverlayInsets = OverlayInsets()): Float {
        val unit = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        // Crosshair grows with the zoom so it stays easy to see against the magnified image.
        val zoomScale = crosshairScale(content.zoomRatio)
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
        infoPaint.setShadowLayer(shadow, shadow / 3, shadow / 3, Color.BLACK)
        footerPaint.setShadowLayer(shadow, shadow / 3, shadow / 3, Color.BLACK)

        val margin = unit * marginRatio
        // Title is centred, so the reserved right strip is mirrored on the left to keep it centred on the crosshair.
        val titleLeft = margin + insets.reservedPx
        val titleWidth = (width - 2 * titleLeft).toInt().coerceAtLeast(1)
        // Data block: inset from both sides, and its right end stops before the reserved strip.
        val blockLeft = margin + insets.sideExtraPx + if (insets.reservedOnLeft) insets.reservedPx else 0f
        val blockWidth = (width - 2 * (margin + insets.sideExtraPx) - insets.reservedPx).toInt().coerceAtLeast(1)

        // Title block pinned below the status block: it does not move when the crosshair grows with the zoom.
        val secondary = layout(content.secondary, secondaryPaint, titleWidth, Layout.Alignment.ALIGN_CENTER, direction)
        val primary = layout(content.primary, primaryPaint, titleWidth, Layout.Alignment.ALIGN_CENTER, direction)
        val primaryTop = insets.topPx + margin
        val secondaryTop = primaryTop + primary.height
        if (content.primary.isNotEmpty() || content.secondary.isNotEmpty()) {
            bandPaint.alpha = titleBackgroundAlpha
            canvas.drawRect(0f, primaryTop - margin * 0.4f, width.toFloat(), secondaryTop + secondary.height + margin * 0.4f, bandPaint)
        }
        drawLayout(canvas, primary, titleLeft, primaryTop)
        drawLayout(canvas, secondary, titleLeft, secondaryTop)

        // Data block at the bottom, stacked upwards: footer last, one row per info group above it.
        // ALIGN_NORMAL aligns to the start edge of the language: right for Hebrew, left for English.
        val gap = unit * groupGapRatio
        val bottomGap = insets.bottomGapPx ?: margin
        val bottomEdge = height - insets.bottomPx - bottomGap
        // The block must not cover the crosshair: text shrinks (down to infoFitMin) until it fits below the crosshair
        // at zoom 1. The reference is zoom-independent, so the text size does not change while zooming.
        val refCrosshair = unit * (crosshairRadiusRatio + crosshairArmRatio)
        val maxTotal = bottomEdge - (cy + refCrosshair + margin) - margin * 0.5f
        var fit = 1f
        var blocks = buildBlocks(content, blockWidth, direction, unit, fit)
        var total = blocks.sumOf { it.height } + 2f * gap * (blocks.size - 1)
        var attempts = 0
        while (blocks.isNotEmpty() && total > maxTotal && fit > infoFitMin && attempts < 4) {
            fit = max(infoFitMin, fit * (maxTotal / total).coerceIn(0f, 1f))
            blocks = buildBlocks(content, blockWidth, direction, unit, fit)
            total = blocks.sumOf { it.height } + 2f * gap * (blocks.size - 1)
            attempts++
        }
        var dataTop = bottomEdge
        if (blocks.isNotEmpty()) {
            dataTop = bottomEdge - total - margin * 0.5f
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
        return dataTop
    }

    /** Crosshair scale for a zoom ratio: grows with zoom, never below 1, capped. */
    private fun crosshairScale(zoomRatio: Double): Float =
        max(1.0, zoomRatio).pow(crosshairZoomExponent).toFloat().coerceAtMost(crosshairScaleMax)

    /** Outer radius of the crosshair (circle plus arms), px; used for tap hit-testing in the live view. */
    fun crosshairOuterRadius(width: Int, height: Int, zoomRatio: Double): Float =
        min(width, height) * (crosshairRadiusRatio + crosshairArmRatio) * crosshairScale(zoomRatio)

    /** One StaticLayout per info group (plus the footer), with text sizes scaled by fit. */
    private fun buildBlocks(
        content: OverlayContent, width: Int, direction: TextDirectionHeuristic, unit: Float, fit: Float
    ): ArrayList<StaticLayout> {
        infoPaint.textSize = unit * infoTextRatio * fit
        footerPaint.textSize = unit * footerTextRatio * fit
        val blocks = ArrayList<StaticLayout>()
        for (group in content.infoLines) {
            blocks.add(layout(group, infoPaint, width, Layout.Alignment.ALIGN_NORMAL, direction))
        }
        if (content.footer.isNotEmpty()) {
            blocks.add(layout(content.footer, footerPaint, width, Layout.Alignment.ALIGN_NORMAL, direction))
        }
        return blocks
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
