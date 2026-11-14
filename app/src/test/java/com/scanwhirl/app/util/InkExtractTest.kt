package com.scanwhirl.app.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** L5: ink extraction must remove paper, shadows and gradients but keep black and blue ink. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class InkExtractTest {

    private fun gradientPaper(w: Int, h: Int, ink: Int = Color.BLACK): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paper = Paint().apply {
            shader = LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(),
                Color.rgb(235, 235, 235), Color.rgb(150, 150, 150),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paper)
        val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            style = Paint.Style.STROKE
            strokeWidth = 10f
        }
        canvas.drawLine(w * 0.25f, h * 0.55f, w * 0.75f, h * 0.45f, pen)
        return bmp
    }

    @Test
    fun `paper becomes transparent and ink opaque on a gradient`() {
        val source = gradientPaper(300, 200)
        val extracted = requireNotNull(InkExtract.extract(source)) { "ink must be found" }
        val bmp = extracted.bitmap
        try {
            // A corner far from the stroke is paper: fully transparent.
            assertEquals(0, Color.alpha(bmp.getPixel(2, 2)))
            // The middle of the stroke is opaque.
            var maxAlpha = 0
            for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
                maxAlpha = maxOf(maxAlpha, Color.alpha(bmp.getPixel(x, y)))
            }
            assertTrue("ink must be opaque, max alpha $maxAlpha", maxAlpha >= 230)
            assertTrue("output must be trimmed", bmp.width < 300 && bmp.height < 200)
        } finally {
            bmp.recycle()
            source.recycle()
        }
    }

    @Test
    fun `blue ink stays blue and can be recoloured`() {
        val source = gradientPaper(300, 200, ink = Color.rgb(24, 60, 150))
        val original = requireNotNull(InkExtract.extract(source, InkExtract.Recolor.ORIGINAL))
        try {
            assertEquals(InkExtract.InkHue.BLUE, original.hue)
            val strongest = strongestPixel(original.bitmap)
            assertTrue("blue must dominate: $strongest", Color.blue(strongest) > Color.red(strongest) + 20)
        } finally {
            original.bitmap.recycle()
        }

        val black = requireNotNull(InkExtract.extract(source, InkExtract.Recolor.BLACK))
        try {
            val p = strongestPixel(black.bitmap)
            assertTrue("recoloured black ${Integer.toHexString(p)}", Color.red(p) < 30 && Color.blue(p) < 30)
        } finally {
            black.bitmap.recycle()
            source.recycle()
        }
    }

    @Test
    fun `a blank page has no ink`() {
        val blank = Bitmap.createBitmap(200, 120, Bitmap.Config.ARGB_8888)
        Canvas(blank).drawColor(Color.rgb(220, 220, 220))
        assertNull(InkExtract.extract(blank))
        blank.recycle()
    }

    private fun strongestPixel(bmp: Bitmap): Int {
        var best = 0
        var bestAlpha = -1
        for (y in 0 until bmp.height step 2) for (x in 0 until bmp.width step 2) {
            val p = bmp.getPixel(x, y)
            if (Color.alpha(p) > bestAlpha) {
                bestAlpha = Color.alpha(p)
                best = p
            }
        }
        return best
    }
}
