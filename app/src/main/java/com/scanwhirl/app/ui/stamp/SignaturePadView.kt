package com.scanwhirl.app.ui.stamp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.scanwhirl.app.util.PageStamp

/**
 * L5 Phase B — a small signature pad. It collects ink as **normalised vector points** (0..1
 * of the pad), which is exactly what [PageStamp.Mark.Signature] and
 * `SignatureStore` persist; no bitmap is created here.
 */
class SignaturePadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val strokes = ArrayList<MutableList<PointF>>()
    private var active: MutableList<PointF>? = null

    private val density = resources.displayMetrics.density
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.parseColor("#1A1A1A")
    }

    fun isEmpty(): Boolean = strokes.all { it.size < 2 }

    /** The ink as normalised vector strokes. */
    fun vectorStrokes(): List<PageStamp.Stroke> = strokes
        .filter { it.isNotEmpty() }
        .map { stroke -> PageStamp.Stroke(stroke.map { PageStamp.Point(it.x, it.y) }) }

    fun clear() {
        strokes.clear()
        active = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        strokes.forEach { stroke -> canvas.drawPath(pathOf(stroke), ink) }
    }

    private fun pathOf(stroke: List<PointF>): Path {
        val path = Path()
        stroke.forEachIndexed { index, point ->
            val x = point.x * width
            val y = point.y * height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        return path
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (width <= 0 || height <= 0) return false
        val point = PointF(
            (event.x / width).coerceIn(0f, 1f),
            (event.y / height).coerceIn(0f, 1f)
        )
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // The pad lives in a pull-down-to-close sheet: claim the gesture so a downward
                // stroke draws ink instead of dragging the sheet away (owner, 11-15).
                parent?.requestDisallowInterceptTouchEvent(true)
                active = mutableListOf(point)
                strokes += active!!
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val current = active ?: return true
                val last = current.last()
                if (kotlin.math.abs(point.x - last.x) + kotlin.math.abs(point.y - last.y) > 0.002f) {
                    current += point
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                active = null
                invalidate()
            }
        }
        return true
    }
}
