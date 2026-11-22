package com.scanwhirl.app.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/** FF-G-lite: the optional page-size parameter keeps existing output untouched and makes the
 * writer honour a source page box when a raster page was re-encoded smaller. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PdfPageGeometryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun jpeg(name: String, w: Int, h: Int): File {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val file = File(tmp.root, name)
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bmp.recycle()
        return file
    }

    private fun boxes(pdf: File): List<Pair<Int, Int>> =
        Regex("/MediaBox \\[0 0 (\\d+) (\\d+)]")
            .findAll(pdf.readText(Charsets.ISO_8859_1))
            .map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            .toList()

    @Test
    fun `no-size call is byte-identical to the historical size calculation`() {
        val jpg = jpeg("a4.jpg", 1240, 1754)
        val before = File(tmp.root, "before.pdf")
        val explicit = File(tmp.root, "explicit.pdf")
        PdfGenerator.imagesToPdf(listOf(jpg), before)
        PdfGenerator.imagesToPdf(listOf(jpg), explicit, pageSizes = listOf(PdfGenerator.sizeInPoints(1240, 1754)))
        assertArrayEquals("null pageSizes must not change a single byte", before.readBytes(), explicit.readBytes())
        assertEquals(listOf(595 to 842), boxes(before))
    }

    @Test
    fun `an explicit page box is used while the JPEG pixels are smaller`() {
        val small = jpeg("small.jpg", 707, 1000)
        val pdf = File(tmp.root, "kept.pdf")
        PdfGenerator.imagesToPdf(listOf(small), pdf, pageSizes = listOf(595 to 842))
        assertEquals(listOf(595 to 842), boxes(pdf))
        val text = pdf.readText(Charsets.ISO_8859_1)
        assertTrue("image is drawn onto the preserved box", "q\n595 0 0 842 0 0 cm\n/Im0 Do" in text)
    }

    @Test
    fun `mixed page boxes are kept per page`() {
        val first = jpeg("a4.jpg", 707, 1000)
        val second = jpeg("letter.jpg", 772, 1000)
        val pdf = File(tmp.root, "mixed.pdf")
        PdfGenerator.imagesToPdf(listOf(first, second), pdf, pageSizes = listOf(595 to 842, 612 to 792))
        assertEquals(listOf(595 to 842, 612 to 792), boxes(pdf))
    }

    @Test
    fun `a null entry falls back to the pixel-derived box`() {
        val jpg = jpeg("small.jpg", 707, 1000)
        val pdf = File(tmp.root, "fallback.pdf")
        PdfGenerator.imagesToPdf(listOf(jpg), pdf, pageSizes = listOf(null))
        assertEquals(listOf(PdfGenerator.sizeInPoints(707, 1000)), boxes(pdf))
    }

    @Test
    fun `a text token keeps its relative box when the page box is preserved`() {
        val small = jpeg("small.jpg", 707, 1000)
        val layer = OcrPage(707, 1000, listOf(OcrLine("Token", 100, 200, 300, 240)))
        val pdf = File(tmp.root, "ocr.pdf")
        PdfGenerator.imagesToPdf(listOf(small), pdf, mapOf(0 to layer), pageSizes = listOf(595 to 842))
        val tm = Regex("1 0 0 1 ([0-9.]+) ([0-9.]+) Tm")
            .find(pdf.readText(Charsets.ISO_8859_1)) ?: throw AssertionError("no text matrix in PDF")
        val x = tm.groupValues[1].toDouble()
        val y = tm.groupValues[2].toDouble()
        val xRelative = x / 595.0
        val bottomRelative = (842.0 - y) / 842.0
        assertEquals("x stays in its source-relative box", 100.0 / 707.0, xRelative, 0.01)
        assertEquals("bottom stays in its source-relative box", 240.0 / 1000.0, bottomRelative, 0.01)
    }

    @Test
    fun `a mismatched page size count is rejected`() {
        val jpg = jpeg("a.jpg", 707, 1000)
        try {
            PdfGenerator.imagesToPdf(listOf(jpg), File(tmp.root, "bad.pdf"), pageSizes = emptyList())
            throw AssertionError("a page-size count that does not match the pages must be refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Page size count"))
        }
    }
}
