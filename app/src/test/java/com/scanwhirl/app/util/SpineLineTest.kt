package com.scanwhirl.app.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Lead 11-15 (owner photo 005924): a book photographed at an angle has a TILTED spine with a dark,
 * smooth fold shadow. The cut must follow the tilt through the gap between the text blocks and
 * must not touch letters; a vertical cut through the middle would slice words.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SpineLineTest {

    private val w = 900
    private val h = 1200

    /** True gutter x for a row: 360 at the top, 432 at the bottom (about 3.4 degrees). */
    private fun gutterX(y: Float) = 360f + 0.06f * y

    private fun tiltedSpread(): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(236, 232, 226))
        // Smooth fold shadow, 26 px wide, darkest at the gutter (NOT ink: a gradient).
        for (y in 0 until h) {
            val gx = gutterX(y.toFloat())
            for (dx in -13..13) {
                val x = (gx + dx).roundToInt()
                if (x !in 0 until w) continue
                val dark = (90 * (1f - abs(dx) / 13f)).roundToInt()
                bmp.setPixel(x, y, Color.rgb(236 - dark, 232 - dark, 226 - dark))
            }
        }
        // Text lines: left block ends 16 px before the gutter, right block starts 16 px after it.
        val ink = Paint().apply { color = Color.rgb(30, 30, 35) }
        var y = 60f
        while (y < h - 60f) {
            val gx = gutterX(y)
            var x = 40f
            while (x < gx - 16f - 30f) {
                val word = minOf(30f + (x * 7 % 50), gx - 16f - x)
                c.drawRect(x, y, x + word, y + 14f, ink)
                x += word + 9f
            }
            x = gx + 16f
            while (x < w - 40f) {
                val word = minOf(30f + (x * 5 % 45), w - 40f - x)
                c.drawRect(x, y, x + word, y + 14f, ink)
                x += word + 9f
            }
            y += 30f
        }
        return bmp
    }

    @Test
    fun `a tilted spine is followed through the gap without touching letters`() {
        val spread = tiltedSpread()
        val grid = SpineLine.grid(spread)
        // Sanity: a vertical cut through the middle of the tilt would slice words.
        val mid = gutterX(h / 2f) / w
        assertTrue(
            "a vertical cut must cross text in this spread",
            SpineLine.inkRowsFraction(grid, mid, mid) > 0.05f
        )

        val line = SpineLine.find(grid, 0.45f)
        assertNotNull("the tilted spine is found", line)
        line!!
        val topX = line.topRatio * w
        val bottomX = line.bottomRatio * w
        assertTrue("top follows the gutter (was $topX, want ~${gutterX(0f)})", abs(topX - gutterX(0f)) <= 14f)
        assertTrue("bottom follows the gutter (was $bottomX, want ~${gutterX(h.toFloat())})", abs(bottomX - gutterX(h.toFloat())) <= 14f)
        assertTrue("the cut touches no letters (${line.inkRowsFraction})", line.inkRowsFraction <= 0.01f)
    }

    @Test
    fun `a broad page-curl shadow is not ink`() {
        // Pages curve into the spine: a wide, smooth darkening (here 80 px, 70 levels). A global
        // darkness threshold calls this "ink" and then no gap is ever found; the local test must not.
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.rgb(236, 232, 226))
        for (y in 0 until h) for (dx in -40..40) {
            val x = (gutterX(y.toFloat()) + dx).roundToInt()
            val dark = (70 * (1f - abs(dx) / 40f)).roundToInt()
            bmp.setPixel(x, y, Color.rgb(236 - dark, 232 - dark, 226 - dark))
        }
        val grid = SpineLine.grid(bmp)
        val inkPixels = grid.ink.count { it }
        assertTrue("a smooth shadow must not be marked as ink ($inkPixels px)", inkPixels < grid.w * grid.h / 500)
    }

    @Test
    fun `a clean gap without a shadow is cut through its middle`() {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(242, 238, 228))
        val ink = Paint().apply { color = Color.rgb(40, 38, 36); strokeWidth = 2f }
        var y = 80f
        while (y < h - 80f) {
            c.drawLine(w * 0.08f, y, w * 0.44f, y, ink)
            c.drawLine(w * 0.56f, y, w * 0.92f, y, ink)
            y += 40f
        }
        val line = SpineLine.find(SpineLine.grid(bmp), 0.5f)
        assertNotNull(line)
        assertTrue("cut in the middle of the gap (${line!!.middleRatio})", abs(line.middleRatio - 0.5f) <= 0.02f)
    }

    @Test
    fun `split along a tilted spine keeps each page on its own side`() {
        val spread = tiltedSpread()
        val gutter = BookSplit.Gutter(
            ratio = gutterX(h / 2f) / w, found = true, confidence = 0.8f,
            topRatio = gutterX(0f) / w, bottomRatio = gutterX(h.toFloat()) / w
        )
        val split = BookSplit.split(spread, gutter)
        // The left page's right edge at the TOP lies beyond the tilted line: it must be paper, not text.
        val left = split.left
        val probeX = left.width - 3
        var darkTop = 0
        for (y in 60 until 200) if (Color.red(left.getPixel(probeX, y)) < 120) darkTop++
        assertTrue("no right-page text survives in the left page's top corner ($darkTop px)", darkTop == 0)
        // The right page's left edge at the BOTTOM lies before the line: it must be paper too.
        val right = split.right
        var darkBottom = 0
        for (y in h - 200 until h - 60) if (Color.red(right.getPixel(2, y)) < 120) darkBottom++
        assertTrue("no left-page text survives in the right page's bottom corner ($darkBottom px)", darkBottom == 0)
    }
}
