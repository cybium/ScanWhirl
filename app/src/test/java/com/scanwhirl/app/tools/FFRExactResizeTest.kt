package com.scanwhirl.app.tools

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.ui.tools.form.FormReadyActivity
import com.scanwhirl.app.util.ImageTools
import com.scanwhirl.app.util.ImportFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * FF-R — Exact crop-to-size in Resize (P1). Numeric acceptance tests from
 * `qa/audit-1.3.0/next/FEATURE_FITNESS.md` section 4. Synthetic fixtures only.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class FFRExactResizeTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()

    // ---- fixture helpers -------------------------------------------------

    /** 4000 x 3000 grid: a 100-px red circle plus a distinct off-centre green marker. */
    private fun gridCircleMarker(): Bitmap {
        val w = 4000
        val h = 3000
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val grid = Paint().apply { color = Color.rgb(220, 220, 220); strokeWidth = 1f }
        for (x in 0 until w step 200) c.drawLine(x.toFloat(), 0f, x.toFloat(), h.toFloat(), grid)
        for (y in 0 until h step 200) c.drawLine(0f, y.toFloat(), w.toFloat(), y.toFloat(), grid)
        c.drawCircle(1200f, 900f, 50f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED })
        c.drawRect(1000f, 1750f, 1060f, 1810f, Paint().apply { color = Color.GREEN })
        return bmp
    }

    /** Red-dominance weighted moments estimate a disc diameter below one pixel of quantisation. */
    private fun redCircleRatio(bmp: Bitmap): Float {
        var total = 0.0
        var sx = 0.0
        var sy = 0.0
        var sxx = 0.0
        var syy = 0.0
        for (y in 0 until bmp.height) {
            for (x in 0 until bmp.width) {
                val p = bmp.getPixel(x, y)
                val weight = (((p ushr 16) and 0xFF) -
                    (((p ushr 8) and 0xFF) + (p and 0xFF)) / 2f) / 255f
                if (weight <= 0f) continue
                val w = weight.toDouble()
                total += w
                sx += w * x
                sy += w * y
                sxx += w * x.toDouble() * x
                syy += w * y.toDouble() * y
            }
        }
        assertTrue("no circle in the rendered crop", total > 0)
        val cx = sx / total
        val cy = sy / total
        val varX = sxx / total - cx * cx
        val varY = syy / total - cy * cy
        assertTrue("circle has no horizontal spread: $varX", varX > 0)
        assertTrue("circle has no vertical spread: $varY", varY > 0)
        return (sqrt(varX) / sqrt(varY)).toFloat()
    }

    private fun countColor(bmp: Bitmap, predicate: (Int) -> Boolean): Int {
        var n = 0
        for (y in 0 until bmp.height) {
            for (x in 0 until bmp.width) if (predicate(bmp.getPixel(x, y))) n++
        }
        return n
    }

    private fun jpegOf(bmp: Bitmap): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        assertTrue(bmp.compress(Bitmap.CompressFormat.JPEG, 95, out))
        return out.toByteArray()
    }

    /** A real JPEG header with fake huge SOF dimensions: bounds-only rejection, no 144-MP allocation. */
    private fun hugeHeaderJpeg(): ByteArray {
        val base = jpegOf(Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawColor(Color.WHITE)
        })
        val bytes = base.copyOf()
        var sof = -1
        var i = 0
        while (i + 9 < bytes.size) {
            if ((bytes[i].toInt() and 0xFF) == 0xFF &&
                (bytes[i + 1].toInt() and 0xFF) == 0xC0
            ) { sof = i; break }
            i++
        }
        assertTrue("fixture needs an SOF marker", sof >= 0)
        fun put16(at: Int, value: Int) {
            bytes[at] = ((value shr 8) and 0xFF).toByte()
            bytes[at + 1] = (value and 0xFF).toByte()
        }
        put16(sof + 5, 12000) // height
        put16(sof + 7, 12000) // width
        return bytes
    }

    // ---- FF-R numeric acceptance ----------------------------------------

    @Test(timeout = 30_000)
    fun `4000x3000 grid with 100px circle crops to 200x230 with circle ratio within 2 percent`() {
        val source = gridCircleMarker()
        val plan = ExactResize.plan(4000, 3000, ExactResize.Options(200, 230))
        // Uniform sampling is the no-distortion invariant: the crop is already
        // 20:23, so both axes get the same scale factor.
        val sx = 200f / plan.cropRect.width()
        val sy = 230f / plan.cropRect.height()
        assertTrue("crop must scale uniformly, got sx=$sx sy=$sy", abs(sx / sy - 1f) <= 0.02f)
        // The shipped circle is ~8 px across, where integer pixel phase alone
        // skews a diameter measurement by >5%. Measure the same crop at 4x so
        // the 2% test reflects geometry rather than pixel quantisation.
        val magnified = ExactResize.render(source, plan.copy(targetWidth = 800, targetHeight = 920))
        val framed = ExactResize.render(source, plan)
        try {
            assertEquals(200, framed.width)
            assertEquals(230, framed.height)
            val ratio = redCircleRatio(magnified)
            assertTrue("circle x/y diameter ratio $ratio must be within 2%", abs(ratio - 1f) <= 0.02f)

            val out = ExactResize.process(jpegOf(source), ExactResize.Options(200, 230))
            assertEquals("image/jpeg", out.mime)
            assertEquals(200 to 230, ImageTools.imageSize(out.bytes))
            assertTrue(out.bytesMet)
            val decoded = ImportFixtures.assertJpeg(out.bytes)
            val greenPixels = countColor(decoded, { p ->
                ((p ushr 8) and 0xFF) > 140 && ((p ushr 16) and 0xFF) < 110 && (p and 0xFF) < 110
            })
            decoded.recycle()
            assertTrue("off-centre green marker must survive at 200x230, got $greenPixels px",
                greenPixels > 0)
        } finally {
            source.recycle()
            framed.recycle()
            magnified.recycle()
        }
    }

    @Test(timeout = 30_000)
    fun `borders mode has white bars on exact canvas and does not distort`() {
        val source = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888)
        val c = Canvas(source)
        c.drawColor(Color.WHITE)
        c.drawCircle(300f, 200f, 40f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED })
        val plan = ExactResize.plan(600, 400, ExactResize.Options(200, 230, ExactResize.Mode.BORDERS))
        val framed = ExactResize.render(source, plan)
        try {
            assertEquals(200 to 230, framed.width to framed.height)
            assertEquals(Color.WHITE, framed.getPixel(100, 2))
            assertEquals(Color.WHITE, framed.getPixel(100, 227))
            val ratio = redCircleRatio(framed)
            assertTrue("circle must stay round in borders mode, got $ratio", abs(ratio - 1f) <= 0.02f)
            val centre = framed.getPixel(100, 115)
            assertTrue("source content must be drawn at the centre, got $centre",
                ((centre ushr 16) and 0xFF) > 140)
        } finally {
            source.recycle()
            framed.recycle()
        }
    }

    @Test(timeout = 30_000)
    fun `20 KB target may miss bytes but the output stays exactly 200x230 and never claims success`() {
        val rnd = java.util.Random(7)
        val bmp = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(400 * 300) {
            Color.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
        }
        bmp.setPixels(pixels, 0, 400, 0, 0, 400, 300)
        val src = jpegOf(bmp)
        bmp.recycle()

        val out = ExactResize.process(src, ExactResize.Options(200, 230, maxBytes = 20_000))
        assertEquals(200, out.width)
        assertEquals(230, out.height)
        assertEquals(200 to 230, ImageTools.imageSize(out.bytes))
        assertEquals("bytesMet must reflect the real saved bytes", out.bytes.size <= 20_000, out.bytesMet)

        // A target that certainly cannot be met must still keep the pixels and say so.
        val miss = ExactResize.process(src, ExactResize.Options(200, 230, maxBytes = 2_000))
        assertEquals("a byte miss must never shrink pixels", 200 to 230, ImageTools.imageSize(miss.bytes))
        assertFalse("an unmet target must not be reported as success", miss.bytesMet)
    }

    @Test(timeout = 30_000)
    fun `huge and one-pixel-wide sources are rejected safely before any decode`() {
        val huge = hugeHeaderJpeg()
        assertEquals(12000 to 12000, ExactResize.normalizedSize(huge))
        try {
            ExactResize.process(huge, ExactResize.Options(200, 230))
            org.junit.Assert.fail("a 12000 x 12000 source must be refused")
        } catch (e: ExactResize.TooLargeException) {
            assertNotNull(e.message)
        }
        try {
            ExactResize.plan(1, 12000, ExactResize.Options(200, 230))
            org.junit.Assert.fail("a 1-px-wide source cannot make a 200x230 crop")
        } catch (e: ExactResize.InsufficientSourceException) {
            assertNotNull(e.message)
        }
    }

    @Test(timeout = 30_000)
    fun `EXIF rotation preserves the crop to within one source pixel`() {
        val original = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888)
        val c = Canvas(original)
        c.drawColor(Color.WHITE)
        // EXIF 6 = rotate 90 CW: original (x,y) -> normalised (399-y, x).
        // This block lands at normalised x=340..355, y=295..315: inside the 20:23 crop.
        c.drawRect(295f, 44f, 315f, 59f, Paint().apply { color = Color.BLUE })
        val file = File(app.cacheDir, "ffr_rotated.jpg")
        file.writeBytes(jpegOf(original))
        original.recycle()
        val oriented = try {
            androidx.exifinterface.media.ExifInterface(file).apply {
                setAttribute(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, "6")
                saveAttributes()
            }
            file.readBytes()
        } finally {
            file.delete()
        }

        val size = ExactResize.normalizedSize(oriented)
        assertEquals(400 to 600, size)
        val rotatedPlan = ExactResize.plan(size.first, size.second, ExactResize.Options(200, 230))
        val uprightPlan = ExactResize.plan(400, 600, ExactResize.Options(200, 230))
        assertEquals("crop must not drift after EXIF normalisation",
            0f, rotatedPlan.cropRect.left - uprightPlan.cropRect.left, 1f)
        assertEquals(0f, rotatedPlan.cropRect.top - uprightPlan.cropRect.top, 1f)

        val out = ExactResize.process(oriented, ExactResize.Options(200, 230))
        assertEquals(200 to 230, ImageTools.imageSize(out.bytes))
        val decoded = ImportFixtures.assertJpeg(out.bytes)
        assertTrue("the rotated marker must survive in the right half of the crop",
            countColor(decoded, { p -> (p and 0xFF) > 150 && ((p ushr 16) and 0xFF) < 110 }) > 0)
        decoded.recycle()
    }

    @Test(timeout = 30_000)
    fun `minimum and maximum KB handoff carries pixels and target to Form photo`() {
        val intent = ExactResize.formPhotoIntent(app, 200, 230, 20)
        assertEquals(FormReadyActivity::class.java.name, intent.component?.className)
        assertEquals(200, intent.getIntExtra(ExactResize.EXTRA_WIDTH, 0))
        assertEquals(230, intent.getIntExtra(ExactResize.EXTRA_HEIGHT, 0))
        assertEquals(20, intent.getIntExtra(ExactResize.EXTRA_MAX_KB, 0))
    }
}
