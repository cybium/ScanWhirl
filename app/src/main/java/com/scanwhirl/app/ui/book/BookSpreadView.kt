package com.scanwhirl.app.ui.book

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.util.BookSplit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * v2 UI: "Book pages" preview — the picked spread with a draggable gutter line.
 *
 * The line is vertical for a normal spread and horizontal when the book was photographed
 * sideways (the owner's phone held upright). It starts where [BookSplit.scanGutter] placed it and
 * can be dragged. When the detection is unsure the line pulses once and the note says "Check the
 * line."; the photo is never modified.
 */
class BookSpreadView(context: Context) : View(context) {

    var bitmap: Bitmap? = null
        private set

    var ratio: Float = 0.5f
        set(value) {
            field = value.coerceIn(MIN_RATIO, MAX_RATIO)
            invalidate()
        }

    /** The spine at the top and bottom of the image (fractions of the drawn width). */
    var topRatio: Float = 0.5f
        private set
    var bottomRatio: Float = 0.5f
        private set

    /** True when the spine runs across the image (pages above/below). */
    var horizontal: Boolean = false
        private set

    var confidence: Float = 1f
        private set

    var singlePage: Boolean = false
        private set

    /** True while the line is where detection put it. */
    var autoGutter: Boolean = true
        private set

    /** False when the owner chose "Keep as one page": the line and handle disappear. */
    var lineVisible: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    /** Set by [pulseLine]; tests read it. */
    var pulsed: Boolean = false
        private set

    var onGutterMoved: (() -> Unit)? = null

    private var lineAlpha = 255
    private var animator: ValueAnimator? = null

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

    fun setSpread(source: Bitmap, gutter: BookSplit.Gutter) {
        bitmap = source
        horizontal = gutter.horizontal
        confidence = gutter.confidence
        singlePage = gutter.singlePage
        autoGutter = gutter.found
        ratio = gutter.ratio
        topRatio = gutter.topRatio
        bottomRatio = gutter.bottomRatio
        requestLayout()
        invalidate()
    }

    /** The line as a [BookSplit.Gutter] with the current owner-adjusted position. */
    fun gutter(): BookSplit.Gutter = BookSplit.Gutter(
        ratio = ratio,
        found = autoGutter,
        confidence = confidence,
        singlePage = singlePage,
        horizontal = horizontal,
        topRatio = topRatio,
        bottomRatio = bottomRatio
    )

    fun release() {
        animator?.cancel()
        animator = null
        bitmap?.recycle()
        bitmap = null
        invalidate()
    }

    /** One gentle pulse so the eye finds a line the detector is unsure about. */
    fun pulseLine() {
        pulsed = true
        animator?.cancel()
        animator = ValueAnimator.ofInt(90, 255).apply {
            duration = 700L
            addUpdateListener {
                lineAlpha = it.animatedValue as Int
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val source = bitmap
        val aspect = if (source != null && source.width > 0) {
            source.height.toFloat() / source.width
        } else {
            0.7f
        }
        val height = max(SwStyle.dp(context, 140f), (width * aspect).toInt())
        setMeasuredDimension(width, min(height, SwStyle.dp(context, 460f)))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val source = bitmap ?: return
        val scale = min(width.toFloat() / source.width, height.toFloat() / source.height)
        val drawnWidth = source.width * scale
        val drawnHeight = source.height * scale
        val left = (width - drawnWidth) / 2f
        val top = (height - drawnHeight) / 2f
        dst.set(left, top, left + drawnWidth, top + drawnHeight)
        canvas.drawBitmap(source, null, dst, bitmapPaint)
        if (!lineVisible) return

        linePaint.alpha = lineAlpha
        handleFill.alpha = lineAlpha
        handleRing.alpha = lineAlpha
        if (horizontal) {
            val y = top + ratio * drawnHeight
            canvas.drawLine(left, y, left + drawnWidth, y, linePaint)
            val knobX = left + drawnWidth / 2f
            drawKnob(canvas, knobX, y, horizontal = true)
        } else {
            val xTop = left + topRatio * drawnWidth
            val xBottom = left + bottomRatio * drawnWidth
            canvas.drawLine(xTop, top, xBottom, top + drawnHeight, linePaint)
            val knobX = left + ratio * drawnWidth
            val knobY = top + drawnHeight / 2f
            drawKnob(canvas, knobX, knobY, horizontal = false)
        }
    }

    private fun drawKnob(canvas: Canvas, x: Float, y: Float, horizontal: Boolean) {
        val radius = SwStyle.dp(context, 11f).toFloat()
        canvas.drawCircle(x, y, radius, handleFill)
        canvas.drawCircle(x, y, radius, handleRing)
        val grip = SwStyle.dp(context, 3f).toFloat()
        if (horizontal) {
            canvas.drawLine(x - radius / 2, y - grip, x + radius / 2, y - grip, handleRing)
            canvas.drawLine(x - radius / 2, y + grip, x + radius / 2, y + grip, handleRing)
        } else {
            canvas.drawLine(x - grip, y - radius / 2, x - grip, y + radius / 2, handleRing)
            canvas.drawLine(x + grip, y - radius / 2, x + grip, y + radius / 2, handleRing)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!lineVisible || bitmap == null || dst.width() <= 0f) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val linePos = if (horizontal) dst.top + ratio * dst.height() else dst.left + ratio * dst.width()
                val touch = if (horizontal) event.y else event.x
                val span = if (horizontal) dst.height() else dst.width()
                if (abs(touch - linePos) > SwStyle.dp(context, 32f)) {
                    val frac = (touch - if (horizontal) dst.top else dst.left) / span
                    if (frac < MIN_RATIO || frac > MAX_RATIO) return false
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                moveLine(if (horizontal) event.y else event.x)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                moveLine(if (horizontal) event.y else event.x)
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

    private fun moveLine(coordinate: Float) {
        val start = if (horizontal) dst.top else dst.left
        val span = if (horizontal) dst.height() else dst.width()
        if (span <= 0f) return
        val next = ((coordinate - start) / span).coerceIn(MIN_RATIO, MAX_RATIO)
        if (horizontal) {
            ratio = next
        } else {
            // Drag the whole spine, keeping its tilt.
            val delta = next - ratio
            ratio = next
            topRatio = (topRatio + delta).coerceIn(MIN_RATIO, MAX_RATIO)
            bottomRatio = (bottomRatio + delta).coerceIn(MIN_RATIO, MAX_RATIO)
        }
        autoGutter = false
        onGutterMoved?.invoke()
    }

    companion object {
        const val MIN_RATIO = 0.12f
        const val MAX_RATIO = 0.88f
    }
}
