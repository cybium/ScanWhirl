package com.scanwhirl.app.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.random.Random

/**
 * Shared window background for the Orbital design system.
 * Draws a deep space gradient (void_nebula #0B1426 to void_abyss #050A14)
 * with ~60 deterministic static star points so every activity shares the
 * exact same orbital cosmos without visual seam or memory allocation.
 */
class SpaceBackgroundDrawable : Drawable() {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFEDF9FF.toInt()
    }

    private var lastW = 0
    private var lastH = 0

    // 60 deterministic stars: normalized (x, y, radius, alpha)
    private val stars = Array(60) { index ->
        val rnd = Random(index * 1337 + 42)
        Star(
            nx = rnd.nextFloat(),
            ny = rnd.nextFloat(),
            radius = if (rnd.nextFloat() > 0.8f) 1.5f else 1.0f,
            alpha = rnd.nextInt(50, 200)
        )
    }

    private class Star(
        val nx: Float,
        val ny: Float,
        val radius: Float,
        val alpha: Int
    )

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width()
        val h = b.height()
        if (w <= 0 || h <= 0) return

        if (w != lastW || h != lastH) {
            lastW = w
            lastH = h
            bgPaint.shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                0xFF0B1426.toInt(), 0xFF050A14.toInt(),
                Shader.TileMode.CLAMP
            )
        }

        canvas.drawRect(b, bgPaint)

        val density = (w / 360f).coerceIn(1f, 4f)
        for (s in stars) {
            starPaint.alpha = s.alpha
            val sx = b.left + s.nx * w
            val sy = b.top + s.ny * h
            canvas.drawCircle(sx, sy, s.radius * density, starPaint)
        }
    }

    override fun setAlpha(alpha: Int) {
        bgPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        bgPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
