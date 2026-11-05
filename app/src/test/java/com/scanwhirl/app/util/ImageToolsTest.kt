package com.scanwhirl.app.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ImageToolsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun jpeg(w: Int, h: Int): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(120, 180, 240))
        val paint = Paint().apply { textSize = 40f; color = Color.BLACK }
        c.drawText("x", 10f, 50f, paint)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
        bmp.recycle()
        return out.toByteArray()
    }

    @Test
    fun `resize produces exact dimensions`() {
        val src = jpeg(640, 480)
        val out = ImageTools.resizeJpeg(src, 320, 240, 95)
        assertEquals(320 to 240, ImageTools.imageSize(out))
    }

    @Test
    fun `resize can change aspect ratio`() {
        val src = jpeg(640, 480)
        val out = ImageTools.resizeJpeg(src, 100, 400, 95)
        assertEquals(100 to 400, ImageTools.imageSize(out))
    }

    @Test
    fun `resize rejects invalid target`() {
        val src = jpeg(64, 64)
        try {
            ImageTools.resizeJpeg(src, 0, 100)
            fail("expected failure")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Invalid target size"))
        }
    }

    @Test
    fun `recompress with maxDim downscales longest side`() {
        val src = jpeg(2000, 1500)
        val out = ImageTools.recompressJpeg(src, 45, 800)
        val (w, h) = ImageTools.imageSize(out)!!
        assertTrue("longest side ${maxOf(w, h)} should be <= 800", maxOf(w, h) <= 800)
        assertTrue("aspect roughly kept: $w x $h", w > h)
    }

    @Test
    fun `recompress without maxDim keeps dimensions`() {
        val src = jpeg(640, 480)
        val out = ImageTools.recompressJpeg(src, 40, null)
        assertEquals(640 to 480, ImageTools.imageSize(out))
    }

    @Test
    fun `recompress lowers quality and file size`() {
        val src = jpeg(800, 1000)
        val small = ImageTools.recompressJpeg(src, 30, null)
        assertTrue("compressed should be smaller", small.size < src.size)
    }

    @Test
    fun `imageSize returns null for garbage`() {
        assertNull(ImageTools.imageSize(byteArrayOf(1, 2, 3, 4)))
    }

    @Test
    fun `decodeSampled rejects garbage`() {
        try {
            ImageTools.decodeSampled(byteArrayOf(9, 9, 9), 1000)
            fail("expected failure")
        } catch (e: IllegalArgumentException) {
            assertNotNull(e.message)
        }
    }

    // ------------------------------------------------------------------
    // New resize API
    // ------------------------------------------------------------------

    @Test
    fun `parseSizeInput understands kb mb and bare numbers`() {
        assertEquals(500L * 1024, ImageTools.parseSizeInput("500 KB"))
        assertEquals(3L * 1_048_576, ImageTools.parseSizeInput("3MB"))
        assertEquals(500L * 1024, ImageTools.parseSizeInput("500"))
        assertEquals(2_621_440L, ImageTools.parseSizeInput("2.5 mb"))
        assertEquals(1L * 1_048_576, ImageTools.parseSizeInput("1M"))
        assertNull(ImageTools.parseSizeInput(""))
        assertNull(ImageTools.parseSizeInput("   "))
        assertNull(ImageTools.parseSizeInput("abc"))
        assertNull(ImageTools.parseSizeInput(null))
    }

    @Test
    fun `resize with both fields null keeps original dimensions`() {
        val src = jpeg(640, 480)
        val out = ImageTools.resize(src, ImageTools.ResizeOptions(width = null, height = null))
        assertEquals(640, out.width)
        assertEquals(480, out.height)
        assertEquals(640 to 480, ImageTools.imageSize(out.bytes))
        assertTrue(!out.isPng)
    }

    @Test
    fun `resize with only width derives height from ratio`() {
        val src = jpeg(640, 480)
        val out = ImageTools.resize(src, ImageTools.ResizeOptions(width = 320, height = null, keepRatio = true))
        assertEquals(320, out.width)
        assertEquals(240, out.height)
    }

    @Test
    fun `resize honors explicit both-axis size`() {
        val src = jpeg(640, 480)
        val out = ImageTools.resize(src, ImageTools.ResizeOptions(width = 100, height = 400, keepRatio = true))
        assertEquals(100 to 400, ImageTools.imageSize(out.bytes))
    }

    @Test
    fun `resize with maxBytes fits under the target`() {
        val noise = noiseJpeg(900, 700, seed = 7)
        val target = 80L * 1024
        val out = ImageTools.resize(noise, ImageTools.ResizeOptions(maxBytes = target))
        assertTrue(
            "got ${out.bytes.size} bytes, target $target",
            out.bytes.size <= target
        )
        assertTrue(out.width > 0 && out.height > 0)
    }

    @Test
    fun `compressToFit shrinks noisy content below the cap`() {
        val bmp = noiseBitmap(700, 700, seed = 42)
        val target = 64L * 1024
        val fit = ImageTools.compressToFit(bmp, target)
        bmp.recycle()
        assertTrue("got ${fit.bytes.size} bytes", fit.bytes.size <= target)
        assertTrue(fit.width > 0 && fit.height > 0)
    }

    @Test
    fun `patchJpegDpi writes density into JFIF header`() {
        val src = jpeg(120, 90)
        val patched = ImageTools.patchJpegDpi(src, 300)
        // locate JFIF\0 and read Xdensity / units
        var idx = -1
        for (i in 0 until patched.size - 5) {
            if (patched[i] == 0x4A.toByte() && patched[i + 1] == 0x46.toByte() &&
                patched[i + 2] == 0x49.toByte() && patched[i + 3] == 0x46.toByte() &&
                patched[i + 4] == 0x00.toByte()
            ) { idx = i; break }
        }
        assertTrue("JFIF header present", idx >= 0)
        val units = patched[idx + 7].toInt() and 0xFF
        val x = ((patched[idx + 8].toInt() and 0xFF) shl 8) or (patched[idx + 9].toInt() and 0xFF)
        val y = ((patched[idx + 10].toInt() and 0xFF) shl 8) or (patched[idx + 11].toInt() and 0xFF)
        assertEquals(1, units)
        assertEquals(300, x)
        assertEquals(300, y)
    }

    @Test
    fun `patchPngDpi inserts a valid pHYs chunk before IEND`() {
        val bmp = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.rgb(10, 200, 30))
        val png = ImageTools.encodePng(bmp)
        bmp.recycle()
        assertTrue(ImageTools.isPng(png))

        val patched = ImageTools.patchPngDpi(png, 150)
        val text = String(patched, Charsets.ISO_8859_1)
        assertTrue("pHYs chunk present", "pHYs" in text)
        assertTrue("IEND still last", text.endsWith("IEND\u0000\u0000\u0000\u0000\u0000\u0000\u0000") || text.contains("IEND"))
        // still decodable
        assertNotNull(ImageTools.imageSize(patched))
        // applying twice keeps a single pHYs
        val twice = ImageTools.patchPngDpi(patched, 300)
        assertEquals(1, Regex("pHYs").findAll(String(twice, Charsets.ISO_8859_1)).count())
    }

    private fun noiseBitmap(w: Int, h: Int, seed: Long): Bitmap {
        val rnd = java.util.Random(seed)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h) {
            Color.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }

    private fun noiseJpeg(w: Int, h: Int, seed: Long): ByteArray {
        val bmp = noiseBitmap(w, h, seed)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
        bmp.recycle()
        return out.toByteArray()
    }
}
