package com.scanwhirl.app.util

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ScanFilterTest {

    private fun regionMean(bmp: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int): Double {
        var sum = 0.0
        var count = 0
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val c = bmp.getPixel(x, y)
                sum += (0.299 * ((c shr 16) and 0xFF)) +
                    (0.587 * ((c shr 8) and 0xFF)) +
                    (0.114 * (c and 0xFF))
                count++
            }
        }
        return sum / count
    }

    @Test
    fun `original filter does not touch the bitmap`() {
        val src = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        assertSame(src, ScanFilter.ORIGINAL.applyTo(src))
    }

    @Test
    fun `enhance widens a washed out range`() {
        val w = 64
        val h = 64
        val src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = 100 + x * 60 / (w - 1)
                pixels[y * w + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        src.setPixels(pixels, 0, w, 0, 0, w, h)

        var beforeMin = 255
        var beforeMax = 0
        for (v in 100..160) {
            if (v < beforeMin) beforeMin = v
            if (v > beforeMax) beforeMax = v
        }

        val out = ScanFilter.ENHANCE.applyTo(src)
        assertEquals(w, out.width)
        assertEquals(h, out.height)

        var afterMin = 255
        var afterMax = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = out.getPixel(x, y)
                val v = (c shr 16) and 0xFF
                if (v < afterMin) afterMin = v
                if (v > afterMax) afterMax = v
            }
        }
        assertTrue("afterMin=$afterMin", afterMin < beforeMin - 20)
        assertTrue("afterMax=$afterMax", afterMax > beforeMax + 20)
    }

    @Test
    fun `clean whitens stains but keeps text dark`() {
        val w = 96
        val h = 96
        val src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = when {
                    x < 20 -> 190              // stain band down the left side
                    x in 40..79 && y in 40..59 -> 40   // block of "text"
                    else -> 235                // paper
                }
                pixels[y * w + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        src.setPixels(pixels, 0, w, 0, 0, w, h)
        val pristine = src.copy(Bitmap.Config.ARGB_8888, false)!!

        val out = ScanFilter.CLEAN.applyTo(src)
        assertEquals(w, out.width)

        val stainedBefore = regionMean(pristine, 2, 60, 18, 90)
        val stainedAfter = regionMean(out, 2, 60, 18, 90)
        assertTrue("stain $stainedBefore -> $stainedAfter", stainedAfter > stainedBefore + 20)
        assertTrue("stain should be near white: $stainedAfter", stainedAfter > 220)

        val textAfter = regionMean(out, 45, 43, 75, 57)
        assertTrue("text must stay dark: $textAfter", textAfter < 120)
    }

    /** Synthetic notebook page: paper, a thin gray ruling line, a block of "text". */
    private fun ruledPage(): Bitmap {
        val w = 96
        val h = 96
        val src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = when {
                    y in 30..31 -> 160                       // thin ruling line
                    x in 10..40 && y in 60..70 -> 40         // block of "text"
                    else -> 235                              // paper
                }
                pixels[y * w + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        src.setPixels(pixels, 0, w, 0, 0, w, h)
        return src
    }

    @Test
    fun `clean lightens notebook ruling lines but keeps text dark`() {
        val out = ScanFilter.CLEAN.applyTo(ruledPage())

        val lineAfter = regionMean(out, 60, 30, 90, 32)
        assertTrue("ruling line should be gone: $lineAfter", lineAfter > 210)

        val textAfter = regionMean(out, 15, 63, 35, 68)
        assertTrue("text must stay dark: $textAfter", textAfter < 120)
    }

    @Test
    fun `bw binarizes to pure black and white and drops ruled lines`() {
        val out = ScanFilter.BLACKWHITE.applyTo(ruledPage())

        // Every sampled pixel is exactly black or exactly white.
        for (y in 0 until 96 step 7) {
            for (x in 0 until 96 step 7) {
                val c = out.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                assertTrue("not binary at $x,$y: $r/$g/$b", r == g && g == b && (r == 0 || r == 255))
            }
        }

        assertEquals(0xFF000000.toInt(), out.getPixel(20, 65))   // text is black
        assertEquals(0xFFFFFFFF.toInt(), out.getPixel(80, 10))   // paper is white
        assertEquals(0xFFFFFFFF.toInt(), out.getPixel(80, 30))   // ruling line removed
    }
}
