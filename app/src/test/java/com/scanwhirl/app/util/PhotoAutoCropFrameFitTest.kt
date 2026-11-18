package com.scanwhirl.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/**
 * fixquad: a quad whose four corners are the frame border is not a page.
 *
 * The gradient detector picks the strongest edge pixels in each corner direction. On a busy
 * patterned surface those extremes sit at the 3% inset, so the "detected quad" is the frame
 * itself and the old app cropped a pointless 3% border off the photo (and never showed the
 * honest "no page edge found" hint). This test is the benchmark case (clip 03: plain white page
 * on patterned cloth, quad = frame border) reduced to a synthetic photo.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PhotoAutoCropFrameFitTest {

    private fun jpeg(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()

    /** A plain bright page on a high-contrast patterned surface that reaches every frame border. */
    private fun pageOnPatternedSurface(
        width: Int = 1200,
        height: Int = 900,
        page: RectF = RectF(320f, 200f, 880f, 700f)
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            // The pattern is the whole background: it touches all four borders, so the strongest
            // gradient pixels in every corner direction are the pattern pixels near the edges.
            val dark = Paint().apply { color = Color.rgb(35, 30, 40) }
            val light = Paint().apply { color = Color.rgb(205, 195, 180) }
            val cell = 16
            var row = 0
            var y = 0
            while (y < height) {
                var col = 0
                var x = 0
                while (x < width) {
                    canvas.drawRect(
                        x.toFloat(), y.toFloat(),
                        (x + cell).toFloat(), (y + cell).toFloat(),
                        if ((row + col) % 2 == 0) dark else light
                    )
                    x += cell
                    col++
                }
                y += cell
                row++
            }
            canvas.drawRect(page, Paint().apply { color = Color.rgb(246, 245, 242) })
            return jpeg(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun isFrameFit(pts: FloatArray): Boolean = (0 until 4).all { c ->
        val x = pts[c * 2]
        val y = pts[c * 2 + 1]
        minOf(x, 1f - x, y, 1f - y) < 0.05f
    }

    @Test
    fun `a patterned surface makes the old detector return the frame border`() {
        val source = pageOnPatternedSurface()

        val quad = requireNotNull(Deskew.detectQuad(source)) { "the pattern gives the old detector a quad" }

        // This is the bug being fixed: the "page" is the 3% inset frame, not the paper.
        assertTrue(
            "the gradient detector picks the frame border on a patterned surface",
            isFrameFit(quad.pts)
        )
    }

    @Test
    fun `a frame-fit quad is not used as a crop`() {
        val source = pageOnPatternedSurface()

        val result = PhotoAutoCrop.crop(source)

        assertFalse("the frame border is not a page: nothing to crop", result.cropped)
        assertArrayEquals("an unchanged photo keeps its bytes", source, result.bytes)
        // The photo is still decodable and whole (the app shows it as it is).
        val decoded = requireNotNull(BitmapFactory.decodeByteArray(result.bytes, 0, result.bytes.size))
        assertTrue("the photo keeps its size", decoded.width == 1200 && decoded.height == 900)
        decoded.recycle()
    }
}
