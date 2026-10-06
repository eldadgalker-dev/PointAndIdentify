// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.11
package com.galker.pointandidentify.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristic
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import com.galker.pointandidentify.config.AppConfig
import com.galker.pointandidentify.domain.CrosshairWindow
import com.galker.pointandidentify.domain.FindProjection
import kotlin.math.cos
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

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
    val rtl: Boolean = true,     // paragraph direction of the UI language (Hebrew = true)
    val drawCompass: Boolean = false,        // true for the saved photo (on screen the compass is a separate view)
    val compassAzimuthDeg: Float? = null,    // camera heading for the photo compass
    val compassAzimuthText: String = "",     // text shown under the photo compass
    val northLabel: String = "N",
    val findArrowRad: Float? = null,         // Find: screen direction to turn the camera (0 = right, pi/2 = up); null = no Find
    val findInside: Boolean = false,         // Find: the place is already inside the crosshair circle
    val findLabel: String = "",              // Find: name, range and azimuth, shown under the target title
    val findDeltaAzDeg: Double? = null,      // Find: horizontal offset of the place from the camera axis (right = positive)
    val findDeltaElDeg: Double? = null,      // Find: vertical offset of the place from the camera axis (up = positive)
    val hfovDeg: Double = 60.0               // camera horizontal field of view at zoom 1 (places the Find dot on the screen)
)

/**
 * Free space around the data block, px. Defaults reproduce the saved-photo layout.
 *  bottomPx        : space kept free at the bottom (e.g. above the capture button in the live view)
 *  bottomGapPx     : gap between the data block and that free space (null = the standard margin)
 *  sideExtraPx     : additional inset of the data block from both side edges
 *  reservedPx      : width kept free at one side edge (zoom bar); the data block is laid out beside it
 *  reservedOnLeft  : which edge the reserved strip is on
 *  topPx           : space kept free at the top (status block); the target title is pinned just below it
 *  titleReservedPx : width of the zoom-bar track strip that the target title must not cover (on the bar side only)
 */
data class OverlayInsets(
    val bottomPx: Float = 0f,
    val bottomGapPx: Float? = null,
    val sideExtraPx: Float = 0f,
    val reservedPx: Float = 0f,
    val reservedOnLeft: Boolean = false,
    val topPx: Float = 0f,
    val titleReservedPx: Float = 0f
)

/**
 * Single renderer for both the live preview and the saved photo, so both look identical.
 * All sizes scale with the shorter canvas edge; text is laid out with StaticLayout using an
 * explicit direction heuristic (RTL for Hebrew, LTR for English), which keeps mixed text in correct order.
 */
class OverlayRenderer {

    // ===== Parameters (fractions of the shorter canvas edge) =====
    private val crosshairRadiusRatio = AppConfig.CROSSHAIR_RADIUS_RATIO.toFloat() // shared with the target window
    private val crosshairArmRatio = 0.045f
    private val strokeRatio = 0.004f
    private val primaryTextRatio = 0.072f
    private val secondaryTextRatio = 0.046f
    private val infoTextRatio = 0.04f       // fixed: the text size never depends on the content, so nothing jumps
    private val footerTextRatio = 0.03f
    private val titleFitMin = 0.6f          // the target name shrinks to fit one line, down to this factor
    private val fullDataLines = 10          // lines of the data block, always all shown (observer 3, target 3, geometry 4)
    private val fullDataGroups = 3          // groups of the data block; the compass is placed above this height
    private val findArrowGapRatio = 0.02f   // gap between the crosshair's outer end and the Find arrow
    private val findArrowLengthRatio = 0.09f
    private val findArrowHalfWidthRatio = 0.045f
    private val findTextRatio = 0.04f
    private val findDotRadiusRatio = 0.012f // radius of the red Find dot
    private val compassRadiusRatio = 0.084f // photo compass
    private val compassGapRatio = 0.01f
    private val azimuthTextRatio = 0.05f
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
    private val findFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 229, 255); style = Paint.Style.FILL }
    private val findStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE }
    private val findRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 229, 255); style = Paint.Style.STROKE }
    private val findTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 229, 255); isFakeBoldText = true }
    private val arrowPath = Path()

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

        // The Find dot sits exactly on the place when it is on the screen; the arrow only points the way when it is not.
        val findDot = if (content.findDeltaAzDeg != null && content.findDeltaElDeg != null) {
            FindProjection.screenOffsetPx(
                content.findDeltaAzDeg, content.findDeltaElDeg, content.hfovDeg, content.zoomRatio, width, height
            )?.takeIf { abs(it.first) <= width / 2f && abs(it.second) <= height / 2f }
        } else null
        content.findArrowRad?.let { if (findDot == null || content.findInside) drawFindMarker(canvas, cx, cy, r + arm, unit, it, content.findInside) }

        primaryPaint.color = if (content.visible) Color.YELLOW else Color.rgb(255, 160, 120)
        primaryPaint.isFakeBoldText = true
        primaryPaint.textSize = unit * primaryTextRatio
        primaryPaint.setShadowLayer(shadow, shadow / 3, shadow / 3, Color.BLACK)
        secondaryPaint.textSize = unit * secondaryTextRatio
        secondaryPaint.setShadowLayer(shadow, shadow / 3, shadow / 3, Color.BLACK)
        infoPaint.setShadowLayer(shadow, shadow / 3, shadow / 3, Color.BLACK)
        footerPaint.setShadowLayer(shadow, shadow / 3, shadow / 3, Color.BLACK)

        val margin = unit * marginRatio
        // The title may use the whole width except the zoom-bar track strip, and is centred in what is left.
        val titleLeft = margin + if (insets.reservedOnLeft) insets.titleReservedPx else 0f
        val titleWidth = (width - 2 * margin - insets.titleReservedPx).toInt().coerceAtLeast(1)
        // One line if possible: shrink the target name to fit instead of wrapping it.
        val desired = Layout.getDesiredWidth(content.primary, primaryPaint)
        if (desired > titleWidth) primaryPaint.textSize *= max(titleFitMin, titleWidth / desired)
        // Data block: inset from both sides, and its right end stops before the reserved strip.
        val blockLeft = margin + insets.sideExtraPx + if (insets.reservedOnLeft) insets.reservedPx else 0f
        val blockWidth = (width - 2 * (margin + insets.sideExtraPx) - insets.reservedPx).toInt().coerceAtLeast(1)

        // Title block pinned below the status block: it does not move when the crosshair grows with the zoom.
        val secondary = layout(content.secondary, secondaryPaint, titleWidth, Layout.Alignment.ALIGN_CENTER, direction)
        val primary = layout(content.primary, primaryPaint, titleWidth, Layout.Alignment.ALIGN_CENTER, direction)
        val primaryTop = insets.topPx + margin
        val secondaryTop = primaryTop + primary.height
        // Find line (third title row): what is being searched, its range and azimuth.
        findTextPaint.textSize = unit * findTextRatio
        findTextPaint.setShadowLayer(shadow, shadow / 3, shadow / 3, Color.BLACK)
        val findLayout = if (content.findLabel.isNotEmpty()) {
            layout(content.findLabel, findTextPaint, titleWidth, Layout.Alignment.ALIGN_CENTER, direction)
        } else null
        val findTop = secondaryTop + secondary.height
        val titleBottom = findTop + (findLayout?.height ?: 0)
        if (content.primary.isNotEmpty() || content.secondary.isNotEmpty() || findLayout != null) {
            bandPaint.alpha = titleBackgroundAlpha
            canvas.drawRect(0f, primaryTop - margin * 0.4f, width.toFloat(), titleBottom + margin * 0.4f, bandPaint)
        }
        drawLayout(canvas, primary, titleLeft, primaryTop)
        drawLayout(canvas, secondary, titleLeft, secondaryTop)
        findLayout?.let { drawLayout(canvas, it, titleLeft, findTop) }

        // Data block at the bottom, stacked upwards: footer last, one row per info group above it.
        // ALIGN_NORMAL aligns to the start edge of the language: right for Hebrew, left for English.
        val gap = unit * groupGapRatio
        val bottomGap = insets.bottomGapPx ?: margin
        val bottomEdge = height - insets.bottomPx - bottomGap
        val blocks = buildBlocks(content, blockWidth, direction, unit)
        val total = blocks.sumOf { it.height } + 2f * gap * (blocks.size - 1)
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

        findDot?.let { drawFindDot(canvas, cx + it.first, cy + it.second, unit) }

        if (content.drawCompass) drawPhotoCompass(canvas, content, unit, margin, dataTop)

        // Stable top for views that sit above the block: computed from a full block, so it does not move
        // when lines appear or disappear. Never lower than the real block (long lines may wrap).
        val lineHeight = infoPaint.fontMetrics.let { it.descent - it.ascent }
        val stableTotal = fullDataLines * lineHeight + 2f * gap * (fullDataGroups - 1)
        return min(dataTop, bottomEdge - stableTotal - margin * 0.5f)
    }

    private val findDotFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 23, 23); style = Paint.Style.FILL }
    private val findDotRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE }

    /** Small red dot exactly on the searched place, so that it can be steered into the middle of the crosshair. */
    private fun drawFindDot(canvas: Canvas, x: Float, y: Float, unit: Float) {
        val radius = unit * findDotRadiusRatio
        findDotRingPaint.strokeWidth = unit * 0.004f
        canvas.drawCircle(x, y, radius, findDotFillPaint)
        canvas.drawCircle(x, y, radius, findDotRingPaint)
    }

    private val compassPainter = CompassPainter()
    private val azimuthPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val azimuthPlate = RectF()

    /** Photo only: compass dial with the heading under it, on the left, just above the data block. */
    private fun drawPhotoCompass(canvas: Canvas, content: OverlayContent, unit: Float, margin: Float, dataTop: Float) {
        val r = unit * compassRadiusRatio
        val gap = unit * compassGapRatio
        azimuthPaint.textSize = unit * azimuthTextRatio
        val fm = azimuthPaint.fontMetrics
        val plateH = (fm.descent - fm.ascent) + gap
        val plateBottom = dataTop - gap
        val plateTop = plateBottom - plateH
        val cx = margin + r
        val cy = plateTop - gap - r
        compassPainter.draw(canvas, cx, cy, r, unit * 0.004f, content.compassAzimuthDeg, content.northLabel)
        if (content.compassAzimuthText.isNotEmpty()) {
            bandPaint.alpha = titleBackgroundAlpha + 40
            azimuthPlate.set(cx - r, plateTop, cx + r, plateBottom)
            canvas.drawRect(azimuthPlate, bandPaint)
            canvas.drawText(content.compassAzimuthText, cx, plateTop + gap / 2f - fm.ascent, azimuthPaint)
        }
    }

    /**
     * Find marker attached to the outer end of the crosshair: an arrow pointing the way to turn the camera, or
     * a ring around the circle once the place is inside it. angleRad: 0 = right, pi/2 = up (screen y grows downward).
     */
    private fun drawFindMarker(canvas: Canvas, cx: Float, cy: Float, outerR: Float, unit: Float, angleRad: Float, inside: Boolean) {
        findStrokePaint.strokeWidth = unit * 0.004f
        if (inside) {
            findRingPaint.strokeWidth = unit * 0.01f
            canvas.drawCircle(cx, cy, outerR * 1.1f, findRingPaint)
            return
        }
        val dx = cos(angleRad)
        val dy = -sin(angleRad)
        val px = -dy // unit vector perpendicular to the arrow direction
        val py = dx
        val baseDist = outerR + unit * findArrowGapRatio
        val len = unit * findArrowLengthRatio
        val half = unit * findArrowHalfWidthRatio
        val shaft = half * 0.35f
        val bx = cx + dx * baseDist
        val by = cy + dy * baseDist
        val mx = bx + dx * len * 0.55f
        val my = by + dy * len * 0.55f
        arrowPath.reset()
        arrowPath.moveTo(bx + dx * len, by + dy * len)       // tip
        arrowPath.lineTo(mx + px * half, my + py * half)     // head, one wing
        arrowPath.lineTo(mx + px * shaft, my + py * shaft)
        arrowPath.lineTo(bx + px * shaft, by + py * shaft)   // shaft
        arrowPath.lineTo(bx - px * shaft, by - py * shaft)
        arrowPath.lineTo(mx - px * shaft, my - py * shaft)
        arrowPath.lineTo(mx - px * half, my - py * half)     // head, other wing
        arrowPath.close()
        canvas.drawPath(arrowPath, findFillPaint)
        canvas.drawPath(arrowPath, findStrokePaint)
    }

    /** Crosshair scale for a zoom ratio: grows with zoom, never below 1, capped. */
    private fun crosshairScale(zoomRatio: Double): Float = CrosshairWindow.scale(zoomRatio).toFloat()

    /** Outer radius of the crosshair (circle plus arms), px; used for tap hit-testing in the live view. */
    fun crosshairOuterRadius(width: Int, height: Int, zoomRatio: Double): Float =
        min(width, height) * (crosshairRadiusRatio + crosshairArmRatio) * crosshairScale(zoomRatio)

    /** One StaticLayout per info group (plus the footer). */
    private fun buildBlocks(
        content: OverlayContent, width: Int, direction: TextDirectionHeuristic, unit: Float
    ): ArrayList<StaticLayout> {
        infoPaint.textSize = unit * infoTextRatio
        footerPaint.textSize = unit * footerTextRatio
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
