package com.foursightai

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * BoundingBoxOverlay — upgraded visual overlay.
 *
 * Improvements:
 *   - Full X + Y bounding box coordinates
 *   - Urgency-coded box colours: RED (stop), ORANGE (urgent), YELLOW (warning), CYAN (info)
 *   - Confidence percentage shown in label
 *   - Distance shown from ARCore or fallback
 *   - AR mode badge in top-right corner
 *   - Semi-transparent label backgrounds for readability
 */
class BoundingBoxOverlay @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ─── Paints ───────────────────────────────────────────────────────────────

    private val boxPaintRed = makePaint(Color.parseColor("#FF3D00"), 8f)
    private val boxPaintOrange = makePaint(Color.parseColor("#FF9100"), 7f)
    private val boxPaintYellow = makePaint(Color.parseColor("#FFD600"), 6f)
    private val boxPaintCyan = makePaint(Color.parseColor("#00E5FF"), 5f)

    private val labelTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 42f
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
    }
    private val labelBgPaint = Paint().apply {
        color = Color.parseColor("#CC000000")
        style = Paint.Style.FILL
    }
    private val badgeBgPaint = Paint().apply {
        color = Color.parseColor("#CC1A237E")
        style = Paint.Style.FILL
    }
    private val badgeTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 30f
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
    }

    // ─── State ────────────────────────────────────────────────────────────────

    private data class RenderItem(
        val detection: DetectionResult,
        val distanceMetres: Float
    )

    private var renderItems: List<RenderItem> = emptyList()
    var isArActive: Boolean = false
    var isDepthActive: Boolean = false

    fun updateDetections(detections: List<DetectionResult>, distances: List<Float>) {
        renderItems = detections.zip(distances).map { (det, dist) ->
            RenderItem(det, dist)
        }
        invalidate()
    }

    // ─── Draw ─────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        for (item in renderItems) {
            drawDetection(canvas, item, w, h)
        }

        drawStatusBadge(canvas, w)
    }

    private fun drawDetection(canvas: Canvas, item: RenderItem, w: Float, h: Float) {
        val det  = item.detection
        val dist = item.distanceMetres
        val box  = det.boundingBox

        // Map normalised [0,1] bounding box to screen pixels
        val left   = box.left   * w
        val top    = box.top    * h
        val right  = box.right  * w
        val bottom = box.bottom * h
        val screenRect = RectF(left, top, right, bottom)

        // Choose colour based on distance urgency
        val boxPaint = when {
            dist <= 0.6f -> boxPaintRed
            dist <= 1.5f -> boxPaintOrange
            dist <= 3.0f -> boxPaintYellow
            else         -> boxPaintCyan
        }

        // Draw rounded box
        canvas.drawRoundRect(screenRect, 18f, 18f, boxPaint)

        // Build label string
        val distStr  = String.format("%.1fm", dist)
        val confStr  = String.format("%.0f%%", det.confidence * 100)
        val labelStr = "${det.label.replaceFirstChar { it.uppercase() }}  $distStr  $confStr"

        // Label background
        val textW    = labelTextPaint.measureText(labelStr)
        val textH    = labelTextPaint.textSize
        val padding  = 12f
        val bgLeft   = left
        val bgTop    = (top - textH - padding * 2).coerceAtLeast(0f)
        val bgRight  = (left + textW + padding * 2).coerceAtMost(w)
        val bgBottom = top

        canvas.drawRoundRect(RectF(bgLeft, bgTop, bgRight, bgBottom), 8f, 8f, labelBgPaint)

        // Label text
        canvas.drawText(labelStr, bgLeft + padding, bgBottom - padding / 2, labelTextPaint)

        // Direction indicator: small triangle arrow inside box near centre
        drawDirectionArrow(canvas, screenRect, det.centerX, boxPaint.color)
    }

    private fun drawDirectionArrow(canvas: Canvas, box: RectF, centerX: Float, color: Int) {
        val arrowPaint = Paint().apply {
            this.color = color
            style = Paint.Style.FILL
            isAntiAlias = true
            alpha = 160
        }
        val cx = box.centerX()
        val cy = box.centerY()
        val size = 18f

        // Arrow pointing in direction user should look
        val direction = when {
            centerX < 0.33f -> "left"
            centerX > 0.67f -> "right"
            else            -> "center"
        }

        when (direction) {
            "left" -> canvas.drawPath(android.graphics.Path().apply {
                moveTo(cx - size, cy); lineTo(cx + size / 2, cy - size); lineTo(cx + size / 2, cy + size); close()
            }, arrowPaint)
            "right" -> canvas.drawPath(android.graphics.Path().apply {
                moveTo(cx + size, cy); lineTo(cx - size / 2, cy - size); lineTo(cx - size / 2, cy + size); close()
            }, arrowPaint)
            else -> {
                // Down-pointing: object is dead ahead
                canvas.drawPath(android.graphics.Path().apply {
                    moveTo(cx, cy + size); lineTo(cx - size, cy - size / 2); lineTo(cx + size, cy - size / 2); close()
                }, arrowPaint)
            }
        }
    }

    private fun drawStatusBadge(canvas: Canvas, screenWidth: Float) {
        val badgeText = when {
            isDepthActive -> "AR DEPTH ON"
            isArActive    -> "AR ON"
            else          -> "AI MODE"
        }
        val textW   = badgeTextPaint.measureText(badgeText)
        val padding = 16f
        val bgRect  = RectF(
            screenWidth - textW - padding * 2 - 8f, 8f,
            screenWidth - 8f, 56f
        )
        canvas.drawRoundRect(bgRect, 10f, 10f, badgeBgPaint)
        canvas.drawText(badgeText, bgRect.left + padding, bgRect.bottom - 14f, badgeTextPaint)
    }

    // ─── Helper ───────────────────────────────────────────────────────────────

    private fun makePaint(color: Int, strokeWidth: Float) = Paint().apply {
        this.color = color
        style = Paint.Style.STROKE
        this.strokeWidth = strokeWidth
        isAntiAlias = true
    }
}
