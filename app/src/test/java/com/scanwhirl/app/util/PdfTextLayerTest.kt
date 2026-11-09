package com.scanwhirl.app.util

import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfTextLayerTest {

    private fun line(text: String, left: Int, top: Int, right: Int, bottom: Int) =
        OcrLine(text, left, top, right, bottom)

    private fun page(w: Int, h: Int, vararg lines: OcrLine) = OcrPage(w, h, listOf(*lines))

    // ---- contentFor -------------------------------------------------------

    @Test
    fun `empty page produces empty fragment`() {
        assertTrue(PdfTextLayer.contentFor(OcrPage(100, 100, emptyList()), 100, 100).isEmpty())
    }

    @Test
    fun `blank lines are skipped`() {
        val p = page(100, 100,
            line("", 0, 0, 10, 10),
            line("   ", 0, 0, 10, 10),
            line("real", 0, 0, 10, 10)
        )
        val out = PdfTextLayer.contentFor(p, 100, 100)
        assertTrue(out.contains("real"))
        assertEquals(1, out.count { it == '\n' })
    }

    @Test
    fun `each line produces a BT…Tj ET text-showing operation`() {
        val p = page(612, 792,
            line("Hello", 10, 20, 50, 30),
            line("World", 10, 50, 50, 60)
        )
        val out = PdfTextLayer.contentFor(p, 612, 792)
        assertEquals(2, out.count { it == '\n' })
        assertTrue(out.contains("BT /F1"))
        assertTrue(out.contains("Tj ET"))
        assertTrue(out.contains("(Hello)"))
        assertTrue(out.contains("(World)"))
    }

    @Test
    fun `coordinates are mapped from OCR space to page space`() {
        // OCR page is 100×100, PDF page is 612×792 → sx=6.12, sy=7.92
        val p = page(100, 100,
            line("X", 10, 20, 30, 40)
        )
        val out = PdfTextLayer.contentFor(p, 612, 792)
        // x = 10 × 6.12 = 61.20, y = 792 − 40 × 7.92 + boxH×0.2
        assertTrue(out.contains("61.20"))
    }

    @Test
    fun `font size is clamped between 4 and 72`() {
        // Tiny box → fs clamps to 4
        val p = page(100, 100,
            line("X", 0, 0, 1, 1) // boxH = 1pt → fs = 0.8 → clamped to 4
        )
        val out = PdfTextLayer.contentFor(p, 100, 100)
        assertTrue(out.contains("4.00 Tf"))
    }

    @Test
    fun `text scale is clamped between 20 and 300`() {
        // Very wide text → tz clamps
        val p = page(100, 100,
            line("X", 0, 0, 10000, 10) // boxW huge → tz → 300
        )
        val out = PdfTextLayer.contentFor(p, 100, 100)
        assertTrue(out.contains("300.00 Tz"))
    }

    // ---- sanitize ---------------------------------------------------------

    @Test
    fun `sanitize keeps printable ASCII`() {
        val out = PdfTextLayer.contentFor(page(100, 100, line("abc 123", 0, 0, 10, 10)), 100, 100)
        assertTrue(out.contains("(abc 123)"))
    }

    @Test
    fun `sanitize maps curly quotes and dashes`() {
        val out = PdfTextLayer.contentFor(page(100, 100,
            line("\u201Chello\u201D \u2014 world\u2019s", 0, 0, 10, 10)
        ), 100, 100)
        assertTrue(out.contains("(\"hello\" - world's)"))
    }

    @Test
    fun `sanitize replaces non-Latin1 with octal`() {
        val out = PdfTextLayer.contentFor(page(100, 100,
            line("caf\u00E9", 0, 0, 10, 10) // é = U+00E9 → \351
        ), 100, 100)
        assertTrue(out.contains("\\351"))
    }

    @Test
    fun `sanitize escapes parens and backslash`() {
        val out = PdfTextLayer.contentFor(page(100, 100,
            line("a(b)c\\d", 0, 0, 10, 10)
        ), 100, 100)
        assertTrue(out.contains("\\(b\\)"))
        assertTrue(out.contains("\\\\"))
    }

    @Test
    fun `sanitize collapses multiple spaces`() {
        val out = PdfTextLayer.contentFor(page(100, 100,
            line("a   b    c", 0, 0, 10, 10)
        ), 100, 100)
        assertTrue(out.contains("(a b c)"))
    }

    @Test
    fun `sanitize truncates long lines`() {
        val long = "a".repeat(500)
        val out = PdfTextLayer.contentFor(page(100, 100, line(long, 0, 0, 10, 10)), 100, 100)
        assertTrue(out.length <= 400 + 100) // snippet overhead
    }

    @Test
    fun `max 600 lines respected`() {
        val lines = (1..700).mapTo(mutableListOf()) { line("L$it", 0, 0, 10, 10) }
        val out = PdfTextLayer.contentFor(page(100, 100, *lines.toTypedArray()), 100, 100)
        val ops = out.count { it == '\n' }
        assertTrue("should emit at most 600 lines, got $ops", ops <= 600)
    }

    // ---- FONT_DICT --------------------------------------------------------

    @Test
    fun `font dict is inline`() {
        assertTrue(PdfTextLayer.FONT_DICT.contains("/BaseFont /Helvetica"))
        assertTrue(PdfTextLayer.FONT_DICT.contains("/Encoding /WinAnsiEncoding"))
    }
}
