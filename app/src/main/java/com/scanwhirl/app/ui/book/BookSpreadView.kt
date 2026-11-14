package com.scanwhirl.app.ui.book

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.scanwhirl.app.ui.kit.SwStyle
import kotlin.math.max
import kotlin.math.min

/**
 * v2 UI: "Book pages" preview — the picked spread with a draggable gutter line.
 *
 * The line starts where [BookSplit.scanGutter] found the fold (or the exact middle) and can be
 * dragged anywhere in the middle 80% so the owner can correct a bad detection before creating
 * the pages. Touches are only used for the line; the bitmap itself is never modified.
 */
class BookSpreadView(context: Context) : View(context) {

    var bitmap: Bitmap? = null
        private set

    /** Gutter position as a fraction of the bitmap width. */
    var ratio: Float = 0.5f
        set(value) {
            field = value.coerceIn(MIN_RATIO, MAX_RATIO)
            invalidate()
        }

    /** True while the line is where detection put it. */
    var autoGutter: Boolean = true
        private set

    /** Called while the owner drags the line. */
    var onGutterMoved: (() -> Unit)? = null

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SwStyle.color(context, com.scanwhirl.app.R.color.cyan_primary)
        style = Paint.Style.STROKE
        strokeWidth = SwStyle.dp(context, 2f).toFloat()
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SwStyle.color(context, com.scanwhirl.app.R.color.cyan_primary)
        style = Paint.Style.FILL
    }
    private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = SwStyle.dp(context, 2f).toFloat()
    }

    private val dst = RectF()

    fun setSpread(source: Bitmap, gutterRatio: Float, auto: Boolean) {
        bitmap = source
        autoGutter = auto
        ratio = gutterRatio
        requestLayout()
        invalidate()
    }

    /** The line as a [com.scanwhirl.app.util.BookSplit.Gutter] for the engine. */
    fun gutter(): com.scanwhirl.app.util.BookSplit.Gutter =
        com.scanwhirl.app.util.BookSplit.Gutter(ratio, autoGutter)

    /** Frees the preview bitmap when the screen goes away. */
    fun release() {
        bitmap?.recycle()
        bitmap = null
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val source = bitmap
        val aspect = if (source != null && source.height > 0) {
            source.height.toFloat() / source.width
        } else {
            0.7f
        }
        val height = max(SwStyle.dp(context, 140f), (width * aspect).toInt())
        setMeasuredDimension(width, min(height, SwStyle.dp(context, 420f)))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val source = bitmap ?: return
        // Fit (never stretch) inside the measured box; the line follows the drawn bitmap.
        val scale = min(width.toFloat() / source.width, height.toFloat() / source.height)
        val drawnWidth = source.width * scale
        val drawnHeight = source.height * scale
        val left = (width - drawnWidth) / 2f
        val top = (height - drawnHeight) / 2f
        dst.set(left, top, left + drawnWidth, top + drawnHeight)
        canvas.drawBitmap(source, null, dst, bitmapPaint)

        // The crop lines: a crisp vertical line plus a knob at its middle.
        val x = left + ratio * drawnWidth
        canvas.drawLine(x, top, x, top + drawnHeight, linePaint)
        val knobY = top + drawnHeight / 2f
        val radius = SwStyle.dp(context, 11f).toFloat()
        canvas.drawCircle(x, knobY, radius, handleFill)
        canvas.drawCircle(x, knobY, radius, handleRing)
        val grip = SwStyle.dp(context, 3f).toFloat()
        canvas.drawLine(x - grip, knobY - radius / 2, x - grip, knobY + radius / 2, handleRing)
        canvas.drawLine(x + grip, knobY - radius / 2, x + grip, knobY + radius / 2, handleRing)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null || dst.width() <= 0f) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val lineX = dst.left + ratio * dst.width()
                if (kotlin.math.abs(event.x - lineX) > SwStyle.dp(context, 32f)) {
                    // Not on the handle: still allow a deliberate tap elsewhere to move it.
                    if (event.x / width < MIN_RATIO || event.x / width > MAX_RATIO) return false
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                moveLine(event.x)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                moveLine(event.x)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    private fun moveLine(x: Float) {
        if (dst.width() <= 0f) return
        ratio = (x - dst.left) / dst.width()
        autoGutter = false
        onGutterMoved?.invoke()
    }

    companion object {
        /** The line may not sit closer to an edge than this: each page needs real content. */
        const val MIN_RATIO = 0.12f
        const val MAX_RATIO = 0.88f
    }
}
