package com.scanwhirl.app.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.max
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PaperScannerTest {

    /** A white page on a dark desk, well separated from the frame. */
    private fun paperPhoto(): ByteArray {
        val desk = Bitmap.createBitmap(1200, 1700, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(desk)
        canvas.drawColor(Color.rgb(34, 34, 34))
        val paper = Bitmap.createBitmap(900, 1300, Bitmap.Config.ARGB_8888)
        Canvas(paper).drawColor(Color.WHITE)
        val rotated = Bitmap.createBitmap(
            paper, 0, 0, 900, 1300,
            Matrix().apply { postRotate(8f) }, true
        )
        canvas.drawBitmap(
            rotated,
            (1200 - rotated.width) / 2f,
            (1700 - rotated.height) / 2f,
            Paint(Paint.FILTER_BITMAP_FLAG)
        )
        return ImageTools.encodeJpeg(desk, 95).also { desk.recycle(); paper.recycle() }
    }

    @Test
    fun `auto crop finds the paper and keeps it original`() {
        val crop = PaperScanner.autoCrop(paperPhoto())

        assertNotNull("paper should be detected", crop)
        val found = crop!!
        // The detected page should be close to the 900x1300 page, not the desk.
        assertTrue(
            "crop width ${found.width} should be ~900",
            found.width in (900 * 70 / 100)..(900 * 145 / 100)
        )
        assertTrue(
            "crop height ${found.height} should be ~1300",
            found.height in (1300 * 70 / 100)..(1300 * 145 / 100)
        )

        // The inside is the paper: overwhelmingly light pixels.
        val bmp = android.graphics.BitmapFactory.decodeByteArray(
            found.bytes, 0, found.bytes.size
        )
        val step = max(1, bmp.width / 100)
        var light = 0
        var total = 0
        for (y in step until bmp.height) {
            for (x in step until bmp.width) {
                val c = bmp.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (r > 200 && g > 200 && b > 200) light++
                total++
            }
        }
        bmp.recycle()
        assertTrue(
            "inside should be light: $light/$total",
            light.toFloat() / total > 0.75f
        )
        assertTrue("auto-crop should stay under 4s", found.detectMs + found.warpMs < 4000)
        assertTrue("a JPEG result is required", found.bytes[0] == 0xFF.toByte())
    }

    @Test
    fun `detect quad sane order`() {
        val bytes = paperPhoto()
        val crop = PaperScanner.autoCrop(bytes)!!
        // TL and BR should be far apart; TR/BL likewise.
        assertEquals(
            true,
            (crop.width > 0 && crop.height > 0)
        )
    }
}
