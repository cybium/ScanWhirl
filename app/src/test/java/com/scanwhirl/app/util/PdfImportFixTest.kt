package com.scanwhirl.app.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
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
 * I1 import-correctness regressions that survive the review: annotation rendering on API 35+,
 * agreement on supported JPEG SOF markers, and the two-copy space budget. Synthetic PDF/JPEG
 * bytes only; no device renderer, no user media.
 *
 * /Rotate is deliberately NOT re-derived here: Android's PdfRenderer already applies it. The
 * fictional fixtures in qa/phone-1.3.0-final/fixtures let the owner check that on a real phone.
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

    // ---- Annotations ----

    @Test
    fun `annotation-aware rendering is selected only on API 35 and newer`() {
        assertFalse(PdfImport.useAnnotatedRender(24))
        assertFalse(PdfImport.useAnnotatedRender(34))
        assertTrue(PdfImport.useAnnotatedRender(35))
        assertTrue(PdfImport.useAnnotatedRender(36))
    }

    // ---- JPEG SOF agreement ----

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

    // ---- Two-copy space budget ----

    @Test
    fun `the render estimate reserves a page and its embedded PDF copy`() {
        assertEquals(2 * PdfImportBudget.TYPICAL_PAGE_BYTES, PdfImport.EST_PAGE_BYTES)
        assertEquals(2, PdfImportBudget.DISK_COPIES)
        val plan = PdfImportBudget.plan(10, freeBytes = 10L * 1024 * 1024 * 1024, bytesPerPage = 600_000L)
        assertEquals(6_000_000L, plan.estimateBytes) // shown to the user: one JPEG per page
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
