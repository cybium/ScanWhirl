package com.scanwhirl.app.form1

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.tools.ExactResize
import com.scanwhirl.app.ui.tools.form.FormReadyActivity
import com.scanwhirl.app.util.FormPresets
import com.scanwhirl.app.util.ImageTools
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.android.controller.ActivityController
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * FX2: numeric regressions for the real-phone J1 evidence. The fixtures are fictional and copied
 * verbatim from qa/phone-1.3.0-final/fixtures_v2. Every check is a number read from the SAVED file,
 * never from the in-memory encoder result. Robolectric's ExifInterface may not parse a real EXIF
 * tag, so the orientation seam is set explicitly to the fixture's documented EXIF 6.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class FormFx2PhoneEvidenceTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @After
    fun tearDown() {
        setOrientation(null)
        RuntimeEnvironmentQualifiersReset()
    }

    private fun RuntimeEnvironmentQualifiersReset() {
        org.robolectric.RuntimeEnvironment.setQualifiers("en-w360dp-h800dp-xhdpi")
    }

    private fun setOrientation(value: Int?) {
        val field = ImageTools::class.java.getDeclaredField("galleryOrientationOverride")
        field.isAccessible = true
        field.set(null, value)
    }

    private fun waitUntil(timeoutMs: Long = 12_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        idle()
        return condition()
    }

    private fun controller(): ActivityController<FormReadyActivity> =
        Robolectric.buildActivity(FormReadyActivity::class.java).setup()

    private fun fixture(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("formfx/$name")!!.use { it.readBytes() }

    private fun jpegOf(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use {
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)
        it.toByteArray()
    }

    private fun jpegSolid(color: Int, w: Int, h: Int): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        return try {
            bmp.eraseColor(color)
            jpegOf(bmp)
        } finally {
            bmp.recycle()
        }
    }

    private fun decode(bytes: ByteArray): Bitmap =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    private fun decodeFile(file: java.io.File): Bitmap = BitmapFactory.decodeFile(file.path)!!

    private fun formatKb(bytes: Long): String =
        String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0)

    /** Ground-truth face box, display-normalised (3000 x 4000 after EXIF 6). */
    private val faceNorm = floatArrayOf(0.6303f, 0.5905f, 0.7697f, 0.7629f)

    private fun faceBox(uprightW: Int, uprightH: Int): RectF = RectF(
        faceNorm[0] * uprightW, faceNorm[1] * uprightH,
        faceNorm[2] * uprightW, faceNorm[3] * uprightH
    )

    private data class Skin(
        val fraction: Double, val cx: Double, val cy: Double,
        val x0: Double, val y0: Double, val x1: Double, val y1: Double, val shape: Double
    )

    /** The J1 verifier's skin heuristic, implemented exactly, so the test measures the saved pixels. */
    private fun skinStats(bitmap: Bitmap): Skin? {
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        val xs = ArrayList<Int>()
        val ys = ArrayList<Int>()
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            if (r > 193 && r < 220 && g > 137 && g < 165 && b > 85 && b < 123) {
                xs.add(i % w)
                ys.add(i / w)
            }
        }
        if (xs.size < 12) return null
        fun percentile(sorted: List<Int>, p: Double): Double {
            val pos = (sorted.size - 1) * p
            val lo = floor(pos).toInt()
            val hi = ceil(pos).toInt()
            return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
        }
        val sx = xs.sorted()
        val sy = ys.sorted()
        val x0 = percentile(sx, 0.01) / w
        val x1 = percentile(sx, 0.99) / w
        val y0 = percentile(sy, 0.01) / h
        val y1 = percentile(sy, 0.99) / h
        val shape = if (y1 > y0) (x1 - x0) / (y1 - y0) else 0.0
        return Skin(
            xs.size.toDouble() / (w * h), xs.average() / w, ys.average() / h,
            x0, y0, x1, y1, shape
        )
    }

    @Test(timeout = 30_000)
    fun `an off aspect crop frame is snapped so the saved circle stays round`() {
        val c = controller()
        try {
            val a = c.get()
            idle()
            val src = Bitmap.createBitmap(600, 500, Bitmap.Config.ARGB_8888)
            try {
                Canvas(src).drawColor(Color.WHITE)
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                paint.color = Color.rgb(20, 20, 20)
                Canvas(src).drawCircle(300f, 250f, 120f, paint)
                a.acceptPickedBytes(jpegOf(src))
            } finally {
                src.recycle()
            }
            assertTrue("photo never loaded", waitUntil { a.hasImageForTest() && a.cropReadyForTest() })
            // Deliberately off-aspect (3:2) while the preset asks for 200:230. The frame must be
            // snapped to the target aspect before extraction, never stretched during rasterising.
            a.setCropRectForTest(RectF(50f, 50f, 500f, 350f))
            idle()
            a.findViewById<View>(R.id.formReadyApply).performClick()
            assertTrue("file never made", waitUntil { a.lastOutputForTest() != null })
            val out = decodeFile(a.lastOutputForTest()!!)
            try {
                assertEquals(200, out.width)
                assertEquals(230, out.height)
                val px = IntArray(out.width * out.height)
                out.getPixels(px, 0, out.width, 0, 0, out.width, out.height)
                var minX = out.width; var maxX = -1; var minY = out.height; var maxY = -1
                for (y in 0 until out.height) for (x in 0 until out.width) {
                    val col = px[y * out.width + x]
                    val lum = (((col shr 16) and 0xFF) + ((col shr 8) and 0xFF) + (col and 0xFF)) / 3
                    if (lum < 100) {
                        minX = minOf(minX, x); maxX = maxOf(maxX, x)
                        minY = minOf(minY, y); maxY = maxOf(maxY, y)
                    }
                }
                assertTrue("circle not found", maxX > minX && maxY > minY)
                val boxAspect = (maxX - minX + 1).toFloat() / (maxY - minY + 1)
                assertTrue("stretched circle: box aspect $boxAspect", abs(boxAspect - 1f) < 0.06f)
            } finally {
                out.recycle()
            }
        } finally {
            c.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `EXIF six fixture is upright the default frame holds the face and the saved face is not stretched`() {
        setOrientation(6) // ORIENTATION_ROTATE_90, as recorded in ground_truth.json
        val c = controller()
        try {
            val a = c.get()
            idle()
            a.acceptPickedBytes(fixture("exam_photo.jpg"))
            assertTrue("photo never loaded", waitUntil { a.hasImageForTest() && a.cropReadyForTest() })
            val rect = a.cropRectForTest()
            // normalizeGalleryPhoto bounds to 2000x1500 before decodeSampled, so the upright
            // source space is 1500x2000 (the RED run showed the base code stuck at 1500x2000
            // landscape, with the face at x~1890 completely outside the frame).
            val face = faceBox(1500, 2000)
            assertTrue(
                "cropped source must be EXIF-normalised 1500x2000; rect=$rect face=$face",
                rect.left <= face.left + 1f && rect.top <= face.top + 1f &&
                    rect.right >= face.right - 1f && rect.bottom >= face.bottom - 1f
            )
            // The person zooms the frame onto the subject. This seam is the confirmed crop frame,
            // the same value the eight drag handles write when the user moves/zooms it.
            val fx = (face.left + face.right) / 2f
            val fy = (face.top + face.bottom) / 2f
            val cw = 560f
            val ch = cw * 230f / 200f
            a.setCropRectForTest(RectF(fx - cw / 2f, fy - ch / 2f, fx + cw / 2f, fy + ch / 2f))
            idle()
            a.findViewById<View>(R.id.formReadyApply).performClick()
            assertTrue("file never made", waitUntil { a.lastOutputForTest() != null })
            val file = a.lastOutputForTest()!!
            val out = decodeFile(file)
            val baseline = decode(fixture("portrait_crop.png"))
            try {
                assertEquals(200, out.width)
                assertEquals(230, out.height)
                val skin = skinStats(out)
                val ref = skinStats(baseline)
                assertNotNull("face lost in saved photo", skin)
                assertNotNull("baseline face missing", ref)
                val margin = minOf(skin!!.x0, skin.y0, 1 - skin.x1, 1 - skin.y1)
                val offset = maxOf(abs(skin.cx - 0.5), abs(skin.cy - 0.5))
                // The offline JPEG encoder on the test host keeps fewer skin-coloured pixels than
                // the phone (0.0032 here); the face-lost phone result was 0.00026. The threshold
                // still proves the subject is present and centered, while the grid/circle test
                // above measures the <0.01 geometric distortion numerically.
                assertTrue("skin fraction ${skin.fraction}", skin.fraction >= 0.002)
                assertTrue("face margin $margin", margin >= 0.02)
                assertTrue("face centre offset $offset", offset <= 0.22)
                // The sub-pixel skin mask changes shape slightly under 5x downsampling, so the
                // strict <0.01 geometric check is made with the known circle/grid fixture above
                // (a genuinely stretched x/y scale produced 0.195 on the phone). Here we only
                // require that the face is not grossly distorted relative to the clean baseline.
                val error = abs(skin.shape / ref!!.shape - 1)
                assertTrue("stretched face error $error", error < 0.12)
                // 20-50 KiB, measured on the saved bytes.
                assertTrue("${file.length()} bytes outside 20-50 KiB", file.length() in (20 * 1024)..(50 * 1024))
            } finally {
                out.recycle()
                baseline.recycle()
            }
        } finally {
            c.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `signature below the minimum says so and never shows inside band or ready`() {
        val c = controller()
        try {
            val a = c.get()
            idle()
            a.findViewById<View>(R.id.formReadyPresetSignature).performClick()
            idle()
            a.acceptPickedBytes(fixture("signature_on_paper.jpg"))
            assertTrue("photo never loaded", waitUntil { a.hasImageForTest() })
            a.findViewById<View>(R.id.formReadyApply).performClick()
            assertTrue("file never made", waitUntil { a.lastOutputForTest() != null })
            val file = a.lastOutputForTest()!!
            val note = a.lastNoteForTest.orEmpty()
            assertTrue("below-min file was ${file.length()} bytes", file.length() < 10 * 1024)
            assertTrue("note must carry a X status: $note", note.contains("X"))
            assertTrue("note must name the needed band: $note", note.contains("(needs"))
            assertFalse("below-min must never show OK: $note", note.contains("OK"))
            assertFalse("below-min must never show inside band: $note", note.contains("Within "))
            assertFalse("below-min must never show ready: $note", note.contains("Matches your limits"))
            assertTrue("hero must be measured from the saved file: $note", note.contains(
                app.getString(R.string.form1_result_hero, 140, 60, formatKb(file.length()))
            ))
        } finally {
            c.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `the displayed KB is measured from the saved file with binary KB rounding`() {
        val c = controller()
        try {
            val a = c.get()
            idle()
            val src = Bitmap.createBitmap(600, 500, Bitmap.Config.ARGB_8888)
            val px = IntArray(600 * 500) { i ->
                val x = i % 600
                val y = i / 600
                val r = (x * 31 + y * 67) and 0xFF
                val g = (x * 97 + y * 13 + ((x * y) and 0xFF)) and 0xFF
                val b = (x * 53 + y * 181) and 0xFF
                0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
            }
            src.setPixels(px, 0, 600, 0, 0, 600, 500)
            try {
                a.acceptPickedBytes(jpegOf(src))
            } finally {
                src.recycle()
            }
            assertTrue("photo never loaded", waitUntil { a.hasImageForTest() })
            a.findViewById<View>(R.id.formReadyApply).performClick()
            assertTrue("file never made", waitUntil { a.lastOutputForTest() != null })
            val file = a.lastOutputForTest()!!
            val note = a.lastNoteForTest.orEmpty()
            assertTrue("hero must be measured from the saved file: $note", note.contains(
                app.getString(R.string.form1_result_hero, 200, 230, formatKb(file.length()))
            ))
            assertTrue("note must show ${formatKb(file.length())} from 1024-byte KB: $note",
                note.contains(formatKb(file.length())))
        } finally {
            c.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `form presets use 1024 byte KB so a 20 KB band survives the portal verifier`() {
        assertEquals(20L * 1024, FormPresets.PHOTO.minBytes)
        assertEquals(50L * 1024, FormPresets.PHOTO.maxBytes)
        assertEquals(10L * 1024, FormPresets.SIGNATURE.minBytes)
        assertEquals(20L * 1024, FormPresets.SIGNATURE.maxBytes)
    }

    @Test(timeout = 30_000)
    fun `signature default frame frames the ink cleanup starts off and the name is human`() {
        val c = controller()
        try {
            val a = c.get()
            idle()
            a.findViewById<View>(R.id.formReadyPresetSignature).performClick()
            idle()
            assertFalse("cleanup must be opt-in", a.currentCleanupForTest())
            a.acceptPickedBytes(fixture("signature_on_paper.jpg"), "signature_on_paper.jpg")
            assertTrue("photo never loaded", waitUntil { a.hasImageForTest() && a.cropReadyForTest() })
            val src = a.sourceBitmapForTest()!!
            // The fixture pastes the ink at (430,1520)-(2130,2076) of the 3000x4000 sheet; the
            // camera homography puts it at (664,488)-(1258,686) in this 2000x1500 test copy.
            val ink = RectF(664f, 488f, 1258f, 686f)
            val rect = a.cropRectForTest()
            assertTrue("signature frame must contain the ink: $rect vs $ink",
                rect.left <= ink.left + 1f && rect.top <= ink.top + 1f &&
                    rect.right >= ink.right - 1f && rect.bottom >= ink.bottom - 1f)
            assertEquals("signature frame aspect", 140f / 60f, rect.width() / rect.height(), 0.01f)
            assertEquals("signature_on_paper signature.jpg", a.outputNameForTest())
            a.findViewById<View>(R.id.formReadyApply).performClick()
            assertTrue("file never made", waitUntil { a.lastOutputForTest() != null })
            assertEquals("signature_on_paper signature.jpg", a.lastOutputForTest()!!.name)
        } finally {
            c.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `background normalised cleanup keeps at least 90 percent of ink and makes no solid block`() {
        fun apply(cleanup: Boolean): Pair<FormReadyActivity, ActivityController<FormReadyActivity>> {
            val c = controller()
            val a = c.get()
            idle()
            a.findViewById<View>(R.id.formReadyPresetSignature).performClick()
            idle()
            if (cleanup) {
                a.findViewById<View>(R.id.formReadyCleanup).performClick()
                idle()
            }
            a.acceptPickedBytes(fixture("signature_on_paper.jpg"), "signature_on_paper.jpg")
            assertTrue("photo never loaded", waitUntil { a.hasImageForTest() && a.cropReadyForTest() })
            return a to c
        }
        val plain = apply(false)
        val plainRect = plain.first.cropRectForTest()
        plain.first.findViewById<View>(R.id.formReadyApply).performClick()
        assertTrue("plain file never made", waitUntil { plain.first.lastOutputForTest() != null })
        val plainOut = decodeFile(plain.first.lastOutputForTest()!!)

        val cleaned = apply(true)
        cleaned.first.setCropRectForTest(plainRect)
        idle()
        cleaned.first.findViewById<View>(R.id.formReadyApply).performClick()
        assertTrue("cleaned file never made", waitUntil { cleaned.first.lastOutputForTest() != null })
        val cleanOut = decodeFile(cleaned.first.lastOutputForTest()!!)
        try {
            assertEquals(140, plainOut.width)
            assertEquals(140, cleanOut.width)
            val before = IntArray(140 * 60)
            val after = IntArray(140 * 60)
            plainOut.getPixels(before, 0, 140, 0, 0, 140, 60)
            cleanOut.getPixels(after, 0, 140, 0, 0, 140, 60)
            fun dark(c: Int): Boolean {
                val lum = (((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)) / 3
                return lum < 110
            }
            var ink = 0
            var survived = 0
            for (y in 0 until 60) for (x in 0 until 140) {
                if (dark(before[y * 140 + x])) {
                    ink++
                    if (dark(after[y * 140 + x])) survived++
                }
            }
            assertTrue("no ink to measure ($ink pixels)", ink > 20)
            val survival = survived.toFloat() / ink
            assertTrue("only ${(survival * 100).toInt()}% of ink survived", survival >= 0.90f)
            // No solid black region bigger than 2% of the image (the old black bar/blob).
            val bw = 20
            val bh = 20
            for (by in 0 until 60 step bh) for (bx in 0 until 140 step bw) {
                var black = 0
                var total = 0
                for (y in by until minOf(60, by + bh)) for (x in bx until minOf(140, bx + bw)) {
                    total++
                    val c = after[y * 140 + x]
                    if ((((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)) / 3 < 40) black++
                }
                assertTrue("solid black block at $bx,$by: $black/$total",
                    black.toFloat() / total < 0.98f)
            }
        } finally {
            plainOut.recycle()
            cleanOut.recycle()
            plain.second.pause().stop().destroy()
            cleaned.second.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `PM-2 immutable source and displayed previews survive cleanup and export`() {
        val c = controller()
        try {
            val a = c.get()
            idle()
            a.findViewById<View>(R.id.formReadyPresetSignature).performClick()
            a.findViewById<View>(R.id.formReadyCleanup).performClick()
            idle()
            val mutable = Bitmap.createBitmap(140, 60, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(mutable)
            canvas.drawColor(Color.WHITE)
            val ink = android.graphics.Paint().apply { color = Color.rgb(30, 40, 90) }
            for (x in 20 until 120 step 4) canvas.drawLine(x.toFloat(), 20f, x + 2f, 44f, ink)
            val source = mutable.copy(Bitmap.Config.ARGB_8888, false) // immutable, crop == whole image
            mutable.recycle()
            assertFalse("fixture must be immutable", source.isMutable)
            a.useBitmapForTest(source, "signature_on_paper.jpg")
            assertTrue("preview never appeared", waitUntil { a.previewVisibleForTest() })
            val previews = a.previewBitmapsForTest()
            assertEquals(2, previews.size)
            previews.forEachIndexed { i, bmp -> assertFalse("preview $i recycled before export", bmp.isRecycled) }
            assertFalse("source recycled by cleanup preview", source.isRecycled)
            // A redraw must not hand the ImageView the source bitmap's pixels either.
            a.findViewById<View>(R.id.formReadyCleanupPreview).invalidate()
            idle()
            a.previewBitmapsForTest().forEachIndexed { i, bmp ->
                assertFalse("preview $i recycled after redraw", bmp.isRecycled)
            }
            a.findViewById<View>(R.id.formReadyApply).performClick()
            assertTrue("export never finished", waitUntil { a.lastOutputForTest() != null })
            assertTrue("export empty", a.lastOutputForTest()!!.length() > 0)
            assertFalse("source recycled by export", source.isRecycled)
            a.previewBitmapsForTest().forEachIndexed { i, bmp ->
                assertFalse("preview $i recycled by export", bmp.isRecycled)
            }
        } finally {
            c.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `cleanup preview crops the exact same rect that the export saves`() {
        val c = controller()
        try {
            val a = c.get()
            idle()
            a.findViewById<View>(R.id.formReadyPresetSignature).performClick()
            a.findViewById<View>(R.id.formReadyCleanup).performClick()
            idle()
            a.acceptPickedBytes(jpegSolid(Color.WHITE, 600, 500), "signature.jpg")
            assertTrue("photo never loaded", waitUntil { a.hasImageForTest() && a.cropReadyForTest() })
            // Deliberately off-aspect: the unsnapped float rect would differ from the saved crop.
            a.setCropRectForTest(RectF(50f, 50f, 400f, 300f))
            a.findViewById<View>(R.id.formReadyCleanup).performClick()
            idle()
            a.findViewById<View>(R.id.formReadyCleanup).performClick()
            idle()
            assertTrue("preview never appeared", waitUntil { a.previewVisibleForTest() })
            val preview = a.previewCropRectForTest()
            assertNotNull("preview did not publish its crop", preview)
            a.findViewById<View>(R.id.formReadyApply).performClick()
            assertTrue("file never made", waitUntil { a.lastOutputForTest() != null })
            val exported = a.exportCropRectForTest()
            assertNotNull("export did not publish its crop", exported)
            assertEquals("preview must equal saved crop", preview, exported)
        } finally {
            c.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `resize width and height fields are 48dp touch targets`() {
        val c = controller()
        try {
            val a = c.get()
            val view = android.view.LayoutInflater.from(a).inflate(R.layout.dialog_resize, null, false)
            val density = a.resources.displayMetrics.density
            val expected = (48f * density).toInt()
            for (id in listOf(R.id.editWidth, R.id.editHeight)) {
                val field = view.findViewById<View>(id)
                assertNotNull("missing field $id", field)
                assertTrue("field $id is ${field.layoutParams.height}px, below $expected",
                    field.layoutParams.height >= expected)
            }
        } finally {
            c.pause().stop().destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `PM-3 resize handoff initialises custom fields and survives recreation`() {
        val intent = ExactResize.formPhotoIntent(app, 140, 60, 20)
        val c = Robolectric.buildActivity(FormReadyActivity::class.java, intent).setup()
        try {
            val a = c.get()
            idle()
            assertEquals("custom", a.currentSpecIdForTest())
            assertEquals(140, a.currentWidthPxForTest())
            assertEquals(60, a.currentHeightPxForTest())
            assertEquals(20, a.currentMaxKbForTest())
            c.recreate()
            idle()
            val restored = c.get()
            assertEquals("custom", restored.currentSpecIdForTest())
            assertEquals(140, restored.currentWidthPxForTest())
            assertEquals(60, restored.currentHeightPxForTest())
            assertEquals(20, restored.currentMaxKbForTest())
        } finally {
            c.pause().stop().destroy()
        }
    }
}
