package com.scanwhirl.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * Draws the paper quad the scanner detected on the live preview, so the user
 * sees exactly what a capture will crop before pressing the shutter.
 */
class EdgeOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x3D, 0xB6, 0x7B) // scanner green
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
    }
    private val dimPaint = Paint().apply {
        color = 0x66000000
        style = Paint.Style.FILL
    }
    private val path = Path()

    /** Quad in 0..1 coordinates (8 floats, TL/TR/BR/BL) or null = no paper. */
    private var quad: FloatArray? = null

    fun setQuad(points: FloatArray?) {
        quad = points
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val q = quad ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val x0 = q[0] * w
        val y0 = q[1] * h
        val x1 = q[2] * w
        val y1 = q[3] * h
        val x2 = q[4] * w
        val y2 = q[5] * h
        val x3 = q[6] * w
        val y3 = q[7] * h

        // Dim outside the quad so the page reads like a scan crop.
        path.reset()
        path.moveTo(x0, y0)
        path.lineTo(x1, y1)
        path.lineTo(x2, y2)
        path.lineTo(x3, y3)
        path.close()
        canvas.save()
        canvas.clipPath(path, android.graphics.Region.Op.DIFFERENCE)
        canvas.drawRect(0f, 0f, w, h, dimPaint)
        canvas.restore()

        canvas.drawPath(path, paint)
    }
}
