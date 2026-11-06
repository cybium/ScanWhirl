package com.scanwhirl.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Manual crop surface: shows the page with a dimmed overlay outside the crop
 * rectangle and four corner handles you drag. No auto-detection — corners only.
 *
 * The crop rectangle is kept in image pixel coordinates; [getCropRect]
 * returns it for the caller to scale onto the full-resolution file.
 */
class CropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var image: Bitmap? = null

    /** Crop rectangle in image pixel coordinates. */
    private val crop = RectF()

    /** Where the bitmap is drawn (fitCenter inside the view). */
    private val imageRect = RectF()
    private val viewToImage = Matrix()

    /** Which corner is being dragged: 0 TL, 1 TR, 2 BR, 3 BL. */
    private var activeCorner = -1

    /**
     * Optional fixed aspect ratio (width / height) the crop rectangle is
     * forced to while dragging; used by the Pro ID/passport photo presets.
     * Null = free crop.
     */
    var aspectRatio: Float? = null

    private val density = resources.displayMetrics.density
    private val handleRadius = 28f * density
    private val handleLength = 20f * density
    private val minViewSize = 48f * density

    private val dimPaint = Paint().apply { color = 0xB3000000.toInt() }
    private val borderPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val handlePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }

    /** Hands the page bitmap to the view (a display-size copy, never full-res). */
    fun setImage(bitmap: Bitmap) {
        image = bitmap
        computeImageRect()
        resetCrop()
        invalidate()
    }

    /** Drops the current bitmap (before loading another page's copy). */
    fun clearImage() {
        image = null
        crop.setEmpty()
        invalidate()
    }

    /** Crop rectangle in image pixel coordinates (full image when untouched). */
    fun getCropRect(): RectF = RectF(crop)

    private fun resetCrop() {
        val bmp = image ?: return
        crop.set(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeImageRect()
        if (image != null && crop.isEmpty) resetCrop()
        invalidate()
    }

    /** fitCenter mapping of the bitmap into the view. */
    private fun computeImageRect() {
        val bmp = image ?: return
        if (width <= 0 || height <= 0) return
        val scale = min(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        val w = bmp.width * scale
        val h = bmp.height * scale
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        imageRect.set(left, top, left + w, top + h)
        viewToImage.reset()
        viewToImage.setScale(bmp.width / w, bmp.height / h)
        viewToImage.postTranslate(-left * bmp.width / w, -top * bmp.height / h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = image ?: return
        canvas.drawBitmap(bmp, null, imageRect, null)

        // Dim everything inside the image but outside the crop.
        val c = viewSpaceCrop()
        canvas.drawRect(imageRect.left, imageRect.top, imageRect.right, c.top, dimPaint)
        canvas.drawRect(imageRect.left, c.bottom, imageRect.right, imageRect.bottom, dimPaint)
        canvas.drawRect(imageRect.left, c.top, c.left, c.bottom, dimPaint)
        canvas.drawRect(c.right, c.top, imageRect.right, c.bottom, dimPaint)

        canvas.drawRect(c, borderPaint)
        drawCorner(canvas, c.left, c.top, 1f, 1f)
        drawCorner(canvas, c.right, c.top, -1f, 1f)
        drawCorner(canvas, c.right, c.bottom, -1f, -1f)
        drawCorner(canvas, c.left, c.bottom, 1f, -1f)
    }

    private fun drawCorner(canvas: Canvas, x: Float, y: Float, dx: Float, dy: Float) {
        canvas.drawLine(x, y, x + handleLength * dx, y, handlePaint)
        canvas.drawLine(x, y, x, y + handleLength * dy, handlePaint)
    }

    /** The crop rectangle in view coordinates. */
    private fun viewSpaceCrop(): RectF {
        val bmp = image ?: return RectF()
        val sx = imageRect.width() / bmp.width
        val sy = imageRect.height() / bmp.height
        return RectF(
            imageRect.left + crop.left * sx,
            imageRect.top + crop.top * sy,
            imageRect.left + crop.right * sx,
            imageRect.top + crop.bottom * sy
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bmp = image ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x
                val y = event.y
                val c = viewSpaceCrop()
                val corners = floatArrayOf(
                    c.left, c.top, c.right, c.top,
                    c.right, c.bottom, c.left, c.bottom
                )
                var best = -1
                var bestDist = Float.MAX_VALUE
                for (i in 0 until 3) {
                    val dist = hypot(x - corners[i * 2], y - corners[i * 2 + 1])
                    if (dist <= handleRadius && dist < bestDist) {
                        best = i
                        bestDist = dist
                    }
                }
                if (best < 0) return false
                activeCorner = best
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeCorner < 0) return false
                // Map the touch into image coordinates (viewToImage already
                // maps view -> image: scale = image / drawn size).
                val pts = floatArrayOf(event.x, event.y)
                viewToImage.mapPoints(pts)
                val ix = pts[0].coerceIn(0f, bmp.width.toFloat())
                val iy = pts[1].coerceIn(0f, bmp.height.toFloat())
                dragCornerTo(bmp, ix, iy)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasDragging = activeCorner >= 0
                activeCorner = -1
                return wasDragging
            }
        }
        return false
    }

    /** Moves the active corner to image coordinate (ix, iy), keeping a sane size. */
    private fun dragCornerTo(bmp: Bitmap, ix: Float, iy: Float) {
        // Minimum size in image pixels, derived from the on-screen minimum.
        val sx = bmp.width / imageRect.width().coerceAtLeast(1f)
        val sy = bmp.height / imageRect.height().coerceAtLeast(1f)
        val minW = minViewSize * sx
        val minH = minViewSize * sy

        when (activeCorner) {
            0 -> {
                crop.left = ix.coerceIn(0f, crop.right - minW)
                crop.top = iy.coerceIn(0f, crop.bottom - minH)
            }
            1 -> {
                crop.right = ix.coerceIn(crop.left + minW, bmp.width.toFloat())
                crop.top = iy.coerceIn(0f, crop.bottom - minH)
            }
            2 -> {
                crop.right = ix.coerceIn(crop.left + minW, bmp.width.toFloat())
                crop.bottom = iy.coerceIn(crop.top + minH, bmp.height.toFloat())
            }
            3 -> {
                crop.left = ix.coerceIn(0f, crop.right - minW)
                crop.bottom = iy.coerceIn(crop.top + minH, bmp.height.toFloat())
            }
        }
        if (aspectRatio != null) applyAspectRatio()
        crop.left = max(0f, crop.left)
        crop.top = max(0f, crop.top)
        crop.right = min(bmp.width.toFloat(), crop.right)
        crop.bottom = min(bmp.height.toFloat(), crop.bottom)
    }

    /**
     * Keeps width/height at [aspectRatio], anchored to the corner opposite
     * the one being dragged. Clamped to the image bounds; the opposite corner
     * stays put, so the rectangle grows/shrinks from the dragged one only.
     */
    private fun applyAspectRatio() {
        val ratio = aspectRatio ?: return
        val bmp = image ?: return
        when (activeCorner) {
            0 -> { // anchored BR
                var h = (crop.width() / ratio)
                if (crop.bottom - h < 0f) h = crop.bottom
                crop.top = crop.bottom - h
            }
            1 -> { // anchored BL
                var h = (crop.width() / ratio)
                if (crop.bottom - h < 0f) h = crop.bottom
                crop.top = crop.bottom - h
            }
            2 -> { // anchored TL
                var h = (crop.width() / ratio)
                if (crop.top + h > bmp.height) h = bmp.height - crop.top
                crop.bottom = crop.top + h
            }
            3 -> { // anchored TR
                var h = (crop.width() / ratio)
                if (crop.top + h > bmp.height) h = bmp.height - crop.top
                crop.bottom = crop.top + h
            }
        }
    }
}
