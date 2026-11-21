package com.scanwhirl.app.util

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * I1 import-correctness regressions: rotated PDF pages, annotations, JPEG SOF agreement and the
 * two-copy space budget. Synthetic PDF/JPEG bytes only; no device renderer, no user media.
 *
 * The native [android.graphics.pdf.PdfRenderer] cannot run on this host, so the /Rotate fix is
 * proven in two pure halves: the bounded parser reads the effective rotation from PDF bytes, and
 * the render plan applies that rotation to a marker bitmap through the same Android Matrix the
 * real renderer receives.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PdfImportFixTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun tearDown() {
        PdfImport.resetForTests()
    }

    // ---- Defect 1: /Rotate ----

    private fun pdf(vararg objects: String): ByteArray =
        ("%PDF-1.4\n" + objects.joinToString("\n") + "\n%%EOF\n").toByteArray(Charsets.ISO_8859_1)

    @Test
    fun `a rotation inherited from the Pages tree is applied in page order`() {
        val bytes = pdf(
            "1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj",
            "2 0 obj << /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 /Rotate 90 >> endobj",
            "3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 200 400] >> endobj",
            "4 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 200 400] /Rotate 270 >> endobj"
        )
        assertEquals(listOf(90, 270), PdfImport.parsePageRotations(bytes))
    }

    @Test
    fun `a page rotation overrides its parent and unrotated pages stay zero`() {
        val bytes = pdf(
            "1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj",
            "2 0 obj << /Type /Pages /Kids [3 0 R 4 0 R 5 0 R] /Count 3 >> endobj",
            "3 0 obj << /Type /Page /Parent 2 0 R /Rotate 90 >> endobj",
            "4 0 obj << /Type /Page /Parent 2 0 R >> endobj",
            "5 0 obj << /Type /Page /Parent 2 0 R /Rotate 180 >> endobj"
        )
        assertEquals(listOf(90, 0, 180), PdfImport.parsePageRotations(bytes))
    }

    @Test
    fun `an unreadable or cyclic page tree fails safe with no rotations`() {
        assertTrue(PdfImport.parsePageRotations("not a pdf at all".toByteArray()).isEmpty())
        val cyclic = pdf(
            "1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj",
            "2 0 obj << /Type /Pages /Kids [2 0 R] /Count 1 >> endobj"
        )
        assertTrue(PdfImport.parsePageRotations(cyclic).isEmpty())
    }

    @Test
    fun `a 90 degree page is rendered portrait with the marker turned upright`() {
        val plan = PdfImport.renderPlan(pageWidth = 200, pageHeight = 100, rotation = 90, dpi = 72f, maxDim = 2048)
        assertEquals(100, plan.width)   // a quarter turn swaps the box
        assertEquals(200, plan.height)

        val marker = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        val paint = Paint()
        try {
            val c = Canvas(marker)
            c.drawColor(Color.WHITE)
            paint.color = Color.RED
            c.drawRect(0f, 0f, 100f, 50f, paint) // source top-left quadrant
            val out = Bitmap.createBitmap(plan.width, plan.height, Bitmap.Config.ARGB_8888)
            try {
                Canvas(out).drawBitmap(marker, plan.matrix, paint)
                assertColor(Color.RED, out.getPixel(plan.width - 10, 10)) // now top-right
                assertColor(Color.WHITE, out.getPixel(10, 10))            // top-left is clear
            } finally {
                out.recycle()
            }
        } finally {
            marker.recycle()
        }
    }

    @Test
    fun `a 270 degree page is rendered portrait with the marker turned upright`() {
        val plan = PdfImport.renderPlan(pageWidth = 200, pageHeight = 100, rotation = 270, dpi = 72f, maxDim = 2048)
        assertEquals(100, plan.width)
        assertEquals(200, plan.height)

        val marker = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        val paint = Paint()
        try {
            val c = Canvas(marker)
            c.drawColor(Color.WHITE)
            paint.color = Color.RED
            c.drawRect(0f, 0f, 100f, 50f, paint)
            val out = Bitmap.createBitmap(plan.width, plan.height, Bitmap.Config.ARGB_8888)
            try {
                Canvas(out).drawBitmap(marker, plan.matrix, paint)
                assertColor(Color.RED, out.getPixel(10, plan.height - 10)) // now bottom-left
            } finally {
                out.recycle()
            }
        } finally {
            marker.recycle()
        }
    }

    @Test
    fun `an unrotated page keeps its dimensions and identity matrix`() {
        val plan = PdfImport.renderPlan(pageWidth = 200, pageHeight = 100, rotation = 0, dpi = 72f, maxDim = 2048)
        assertEquals(200, plan.width)
        assertEquals(100, plan.height)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), FloatArray(9).also { plan.matrix.getValues(it) }, 0f)
    }

    private fun assertColor(expected: Int, actual: Int) {
        assertTrue(
            "expected ${Integer.toHexString(expected)}, got ${Integer.toHexString(actual)}",
            kotlin.math.abs(Color.red(expected) - Color.red(actual)) <= 24 &&
                kotlin.math.abs(Color.green(expected) - Color.green(actual)) <= 24 &&
                kotlin.math.abs(Color.blue(expected) - Color.blue(actual)) <= 24
        )
    }

    // ---- Defect 2: annotations ----

    @Test
    fun `annotation-aware rendering is selected only on API 35 and newer`() {
        assertFalse(PdfImport.useAnnotatedRender(24))
        assertFalse(PdfImport.useAnnotatedRender(34))
        assertTrue(PdfImport.useAnnotatedRender(35))
        assertTrue(PdfImport.useAnnotatedRender(36))
    }

    // ---- Defect 4: JPEG SOF agreement ----

    /** A tiny structurally complete JPEG with the given SOF marker. */
    private fun jpegWithSof(marker: Int, width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        fun b(vararg v: Int) = v.forEach { out.write(it and 0xFF) }
        b(0xFF, 0xD8)                                             // SOI
        b(0xFF, marker, 0x00, 0x0B, 0x08,                         // SOF, length 11, precision 8
            height shr 8, height and 0xFF, width shr 8, width and 0xFF, 0x01)
        b(0x01, 0x11, 0x00)                                       // one component spec
        b(0xFF, 0xDA, 0x00, 0x08, 0x01, 0x01, 0x00, 0x00)          // SOS, length 8
        b(0x12, 0x34)                                             // scan data
        b(0xFF, 0xD9)                                             // EOI
        return out.toByteArray()
    }

    @Test
    fun `progressive C2 is accepted by validation and read by the dimension parser`() {
        val bytes = jpegWithSof(0xC2, width = 160, height = 120)
        PageImageFormat.checkJpeg(bytes) // no exception
        assertEquals(PdfGenerator.JpegInfo(160, 120, 1), PdfGenerator.readJpegInfo(bytes))
    }

    @Test
    fun `arithmetic C9 is rejected at validation instead of failing late in the writer`() {
        val bytes = jpegWithSof(0xC9, width = 160, height = 120)
        val failure = runCatching { PageImageFormat.checkJpeg(bytes) }.exceptionOrNull()
        assertNotNull("validation must reject arithmetic-coded JPEG up front", failure)
        assertTrue("reason must name the unsupported coding", failure!!.message!!.contains("arithmetic", true))
        assertNotNull(
            "the dimension reader agrees this SOF is unsupported",
            runCatching { PdfGenerator.readJpegInfo(bytes) }.exceptionOrNull()
        )
    }

    // ---- Defect 5: two-copy space budget ----

    @Test
    fun `the render estimate reserves a page and its embedded PDF copy`() {
        assertEquals(2 * PdfImportBudget.TYPICAL_PAGE_BYTES, PdfImport.EST_PAGE_BYTES)
        assertEquals(2, PdfImportBudget.DISK_COPIES)
        val plan = PdfImportBudget.plan(10, freeBytes = 10L * 1024 * 1024 * 1024, bytesPerPage = 600_000L)
        assertEquals(6_000_000L, plan.estimateBytes)
        assertEquals(12_000_000L, plan.diskBytes)
        assertEquals(24_000L, PdfImportBudget.requiredBytes(10, 1_200L))
    }

    @Test
    fun `the hard render gate refuses when free space covers one copy but not two`() {
        runBlocking {
            val dir = File(app.cacheDir, "i1_budget_${System.nanoTime()}").apply { mkdirs() }
            val source = object : PdfImport.PageSource {
                override fun pageCount() = 100
                override fun render(index: Int): ByteArray = ByteArray(64) { 7 }
            }
            // 100 pages * 600 KB = 60 MB for one copy, 120 MB for two, + 16 MB margin.
            val failure = runCatching {
                PdfImport.renderPages(source, dir, (0 until 100).toList(), freeBytes = 80_000_000L)
            }.exceptionOrNull()
            assertTrue("80 MB covers one copy but not two", failure is PdfImport.ImportException)
            assertEquals(PdfImport.Reason.NO_SPACE, (failure as PdfImport.ImportException).reason)
            dir.deleteRecursively()
        }
    }
}
