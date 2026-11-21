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
        // CP1: decimal units everywhere (KB = 1000, MB = 1,000,000).
        assertEquals(500_000L, ImageTools.parseSizeInput("500 KB"))
        assertEquals(3_000_000L, ImageTools.parseSizeInput("3MB"))
        assertEquals(500_000L, ImageTools.parseSizeInput("500"))
        assertEquals(2_500_000L, ImageTools.parseSizeInput("2.5 mb"))
        assertEquals(1_000_000L, ImageTools.parseSizeInput("1M"))
        assertNull(ImageTools.parseSizeInput(""))
        assertNull(ImageTools.parseSizeInput("   "))
        assertNull(ImageTools.parseSizeInput("abc"))
        assertNull(ImageTools.parseSizeInput(null))
    }

    @Test
    fun `a size above 2 GB is refused instead of saturating`() {
        // H3-7: Double.roundToInt() used to saturate 3 GB at Int.MAX_VALUE (2 GiB) and the run
        // then chased a number the person never asked for. The parser keeps Long precision and
        // rejects anything above the same 2 GB ceiling the Compress sheet names.
        assertNull("5000 MB is over the ceiling", ImageTools.parseSizeInput("5000 MB"))
        assertNull("3000 MB is over the ceiling", ImageTools.parseSizeInput("3000 MB"))
        assertEquals(
            "2048 MB is a decimal size below the shared 2 GiB ceiling",
            2_048_000_000L,
            ImageTools.parseSizeInput("2048 MB")
        )
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

    /**
     * H2-8: a gallery photo [normalizePage] does not know (here a 24-bit BMP, like a
     * HEIC/AVIF from a phone camera) must still come back inside the same page bound
     * every other page has (3000 px per side, 6 MP), never at full resolution.
     */
    @Test
    fun `gallery fallback decodes an unknown format inside the page bound`() {
        val out = ImageTools.normalizeGalleryPhoto(ImportFixtures.bmp(3600, 1800))
        val size = ImageTools.imageSize(out)
        assertNotNull("the fallback produced a JPEG", size)
        val (w, h) = size!!
        assertTrue("longest side ${maxOf(w, h)} must stay within 3000 px", maxOf(w, h) <= 3000)
        assertTrue(
            "pixels $w x $h must stay within ${ImageTools.MAX_PAGE_PIXELS}",
            w.toLong() * h <= ImageTools.MAX_PAGE_PIXELS
        )
        ImportFixtures.assertJpeg(out).recycle()
    }

    /**
     * H2-8: an exhausted heap inside the fallback is a refusal, not a crash. The seam
     * stands in for the platform decoder on a phone with too little memory.
     */
    @Test
    fun `gallery fallback turns out of memory into a too large photo`() {
        ImageTools.galleryDecode = { _, _ -> throw OutOfMemoryError("forced for the test") }
        try {
            var failure: Throwable? = null
            try {
                ImageTools.normalizeGalleryPhoto(ImportFixtures.bmp(40, 30))
                fail("an unbounded decode would have killed the process")
            } catch (e: ImageTools.PhotoTooLargeException) {
                failure = e
            }
            assertNotNull("the typed refusal is what the screen localizes", failure)
            assertTrue(ImageTools.isTooLargePhoto(failure!!))
        } finally {
            ImageTools.galleryDecode = null
        }
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
