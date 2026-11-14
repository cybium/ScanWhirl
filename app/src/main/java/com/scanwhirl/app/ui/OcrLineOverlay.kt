package com.scanwhirl.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.ui.kit.SwStyle

/**
 * v2 UI: 08_text_ocr — the recognised line boxes over the page image.
 *
 * Lines are in page-pixel coordinates; the box maths mirrors the ImageView's
 * fitCenter inside the same padding. A tap on a line reports it so the editor
 * can jump to that text. No geometry means no boxes, never a crash.
 */
class OcrLineOverlay(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var lines: List<OcrLine> = emptyList()
    private var pageWidth = 0
    private var pageHeight = 0
    private var highlighted = -1

    /** Called with the tapped line (page coordinates already matched). */
    var onLineTap: ((OcrLine) -> Unit)? = null

    private val padPx = SwStyle.dp(context, 6f).toFloat()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x3800F0FF // cyan at 22%
        style = Paint.Style.FILL
    }
    private val fillHi = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x7300F0FF // cyan at 45%
        style = Paint.Style.FILL
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC00D5E6.toInt()
        style = Paint.Style.STROKE
        strokeWidth = SwStyle.dp(context, 1.5f).toFloat().coerceAtLeast(1f)
    }

    /** Sets the page geometry; an empty list clears the boxes. */
    fun setPage(next: List<OcrLine>, width: Int, height: Int) {
        lines = next
        pageWidth = width
        pageHeight = height
        highlighted = -1
        invalidate()
    }

    fun highlightLine(index: Int) {
        highlighted = index
        invalidate()
    }

    /** The displayed image rect (fitCenter) inside this view's padding. */
    private fun imageRect(): RectF? {
        if (pageWidth <= 0 || pageHeight <= 0) return null
        val availW = width - paddingLeft - paddingRight - 2 * padPx
        val availH = height - paddingTop - paddingBottom - 2 * padPx
        if (availW <= 0 || availH <= 0) return null
        val scale = minOf(availW / pageWidth, availH / pageHeight)
        val w = pageWidth * scale
        val h = pageHeight * scale
        val left = paddingLeft + padPx + (availW - w) / 2f
        val top = paddingTop + padPx + (availH - h) / 2f
        return RectF(left, top, left + w, top + h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image = imageRect() ?: return
        if (lines.isEmpty()) return
        val scaleX = image.width() / pageWidth
        val scaleY = image.height() / pageHeight
        for ((index, line) in lines.withIndex()) {
            val rect = RectF(
                image.left + line.left * scaleX,
                image.top + line.top * scaleY,
                image.left + line.right * scaleX,
                image.top + line.bottom * scaleY
            )
            canvas.drawRect(rect, if (index == highlighted) fillHi else fill)
            canvas.drawRect(rect, stroke)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val image = imageRect() ?: return false
        val scaleX = image.width() / pageWidth
        val scaleY = image.height() / pageHeight
        val x = (event.x - image.left) / scaleX
        val y = (event.y - image.top) / scaleY
        // Nearest line whose box contains the tap (a little slop for fingers).
        val slopX = SwStyle.dp(context, 8f) / scaleX
        val slopY = SwStyle.dp(context, 8f) / scaleY
        val hit = lines.indexOfFirst {
            x >= it.left - slopX && x <= it.right + slopX &&
                y >= it.top - slopY && y <= it.bottom + slopY
        }
        if (hit < 0) return true
        highlighted = hit
        invalidate()
        onLineTap?.invoke(lines[hit])
        return true
    }
}
