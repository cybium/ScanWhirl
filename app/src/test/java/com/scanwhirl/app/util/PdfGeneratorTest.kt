package com.scanwhirl.app.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PdfGeneratorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun makeJpeg(name: String, w: Int, h: Int, color: Int): File {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(color)
        val f = File(tmp.root, name)
        FileOutputStream(f).use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        bmp.recycle()
        return f
    }

    @Test
    fun `single page pdf is structurally valid`() {
        val jpg = makeJpeg("a.jpg", 620, 877, Color.BLUE)
        val pdf = File(tmp.root, "out.pdf")

        PdfGenerator.imagesToPdf(listOf(jpg), pdf)

        assertTrue(pdf.exists())
        assertTrue(pdf.length() > 0)
        val text = pdf.readText(Charsets.ISO_8859_1)
        assertTrue(text.startsWith("%PDF-1.4"))
        assertTrue(text.endsWith("%%EOF\n"))
        assertTrue("/Count 1" in text)
        assertTrue("/DCTDecode" in text)
        assertTrue("/MediaBox [0 0" in text)
        assertTrue("xref" in text)
    }

    @Test
    fun `multi page pdf has correct count and kids`() {
        val pages = (1..5).map { i ->
            makeJpeg("p$i.jpg", 600 + i, 800 + i, Color.rgb(i * 40, 100, 255 - i * 30))
        }
        val pdf = File(tmp.root, "multi.pdf")

        PdfGenerator.imagesToPdf(pages, pdf)

        val text = pdf.readText(Charsets.ISO_8859_1)
        assertTrue("/Count 5" in text)
        val kids = Regex("/Kids \\[([^]]+)]").find(text)!!.groupValues[1].trim()
        assertEquals("3 0 R 6 0 R 9 0 R 12 0 R 15 0 R", kids)
        assertEquals(5, Regex("/Subtype /Image").findAll(text).count())
        assertEquals(5, Regex("/Type /Page[^s]").findAll(text).count())
    }

    @Test
    fun `jpeg dimensions parsed correctly`() {
        val jpg = makeJpeg("dim.jpg", 333, 444, Color.GREEN)
        val info = PdfGenerator.readJpegInfo(jpg.readBytes())
        assertEquals(333, info.width)
        assertEquals(444, info.height)
        assertEquals(3, info.components)
    }

    @Test
    fun `embedded jpeg bytes are preserved losslessly`() {
        val jpg = makeJpeg("raw.jpg", 500, 700, Color.RED)
        val original = jpg.readBytes()
        val pdf = File(tmp.root, "lossless.pdf")

        PdfGenerator.imagesToPdf(listOf(jpg), pdf)

        val pdfBytes = pdf.readBytes()
        var found = false
        outer@ for (start in 0..pdfBytes.size - original.size) {
            for (k in original.indices) {
                if (pdfBytes[start + k] != original[k]) continue@outer
            }
            found = true
            break
        }
        assertTrue("JPEG payload should be embedded byte-identical", found)
    }

    @Test
    fun `xref offsets point at correct objects`() {
        val pages = (1..3).map { makeJpeg("x$it.jpg", 620, 877, Color.rgb(it * 60, 0, 0)) }
        val pdf = File(tmp.root, "xref.pdf")
        PdfGenerator.imagesToPdf(pages, pdf)

        val bytes = pdf.readBytes()
        val text = String(bytes, Charsets.ISO_8859_1)
        val xrefSection = text.substringAfter("xref\n")
        // lines: "0 11", free entry, then 10 object entries
        val entries = xrefSection.lineSequence().drop(2).take(10).toList()
        for ((index, entry) in entries.withIndex()) {
            val objNum = index + 1
            val offset = entry.trim().substringBefore(' ').toLong()
            val at = String(bytes, offset.toInt(), 8, Charsets.ISO_8859_1)
            assertTrue(
                "obj $objNum should start at $offset but found '$at'",
                at.startsWith("$objNum 0 obj")
            )
        }
    }

    @Test
    fun `rejects non-jpeg input`() {
        val bogus = File(tmp.root, "bogus.jpg").apply { writeText("not a jpeg at all") }
        try {
            PdfGenerator.imagesToPdf(listOf(bogus), File(tmp.root, "bad.pdf"))
            fail("expected failure for non-jpeg input")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Not a JPEG"))
        }
    }

    @Test
    fun `rejects missing file`() {
        try {
            PdfGenerator.imagesToPdf(listOf(File(tmp.root, "nope.jpg")), File(tmp.root, "bad2.pdf"))
            fail("expected failure for missing file")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Missing page image"))
        }
    }

    @Test
    fun `rejects empty list`() {
        try {
            PdfGenerator.imagesToPdf(emptyList(), File(tmp.root, "bad3.pdf"))
            fail("expected failure for empty list")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("No images"))
        }
    }

    @Test
    fun `each page keeps its own cropped size`() {
        // Two deliberately different aspect ratios: a cropped page must not be
        // padded to a fixed page size (which would show bands around it).
        val wide = makeJpeg("wide.jpg", 800, 300, Color.GREEN)
        val tall = makeJpeg("tall.jpg", 300, 400, Color.RED)
        val pdf = File(tmp.root, "sizes.pdf")

        PdfGenerator.imagesToPdf(listOf(wide, tall), pdf)

        val boxes = Regex("/MediaBox \\[0 0 (\\d+) (\\d+)]")
            .findAll(pdf.readText(Charsets.ISO_8859_1))
            .map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            .toList()
        assertEquals(2, boxes.size)
        // 72 pt / 150 dpi = 0.48
        assertEquals(
            (800 * 72f / 150f).roundToInt() to (300 * 72f / 150f).roundToInt(),
            boxes[0]
        )
        assertEquals(
            (300 * 72f / 150f).roundToInt() to (400 * 72f / 150f).roundToInt(),
            boxes[1]
        )
        assertNotEquals("pages must not share a forced page size", boxes[0], boxes[1])
    }

    @Test
    fun `pipeline works with real scan-like pages`() {
        // simulate what ScanRepository.mergeDocuments will do
        val pages = (1..4).map { i ->
            makeJpeg("scan_$i.jpg", 1240, 1754, Color.rgb(240, 240 - i * 10, 230))
        }
        val pdf = File(tmp.root, "scan.pdf")
        PdfGenerator.imagesToPdf(pages, pdf)
        val text = pdf.readText(Charsets.ISO_8859_1)
        assertTrue("/Count 4" in text)
        // A4 @150dpi -> ~595x842 pt
        assertTrue(Regex("/MediaBox \\[0 0 59[0-9] 84[0-9]]").containsMatchIn(text))
    }




}
