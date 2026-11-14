package com.scanwhirl.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v2 UI: 08_text_ocr — reading order (design: OCR quality). Synthetic boxes only; ML Kit never
 * runs here. Covers the QA bugs: single column, two columns, a two-page spread and a rotated
 * page, plus reversed lines inside one block.
 */
class OcrLayoutTest {

    private fun box(l: Int, t: Int, r: Int, b: Int) = OcrBox(l, t, r, b)

    private fun unit(text: String, l: Int, t: Int, r: Int, b: Int) = OcrUnit(box(l, t, r, b), text)

    private fun order(vararg units: OcrUnit<String>, width: Int = 600, height: Int = 800, rotation: Int = 0) =
        OcrLayout.valuesInReadingOrder(units.toList(), width, height, rotation)

    @Test
    fun `single column reads top to bottom`() {
        val result = order(
            unit("line 3", 40, 200, 560, 230),
            unit("line 1", 40, 40, 560, 70),
            unit("line 2", 40, 120, 560, 150)
        )
        assertEquals(listOf("line 1", "line 2", "line 3"), result)
    }

    @Test
    fun `two columns finish the left column before the right one`() {
        // A page with a gutter at x ~ 290: rows are interleaved in the input on purpose.
        val result = order(
            unit("R1", 320, 40, 580, 70),
            unit("L1", 20, 40, 270, 70),
            unit("R2", 320, 120, 580, 150),
            unit("L2", 20, 120, 270, 150),
            unit("R3", 320, 200, 580, 230),
            unit("L3", 20, 200, 270, 230)
        )
        assertEquals(listOf("L1", "L2", "L3", "R1", "R2", "R3"), result)
    }

    @Test
    fun `a two page spread reads the left page before the right one`() {
        // 1200 x 800 spread: left page 20..560, right page 640..1180, page numbers at the bottom.
        val result = order(
            unit("right body 1", 640, 60, 1180, 90),
            unit("left body 1", 20, 60, 560, 90),
            unit("right 42", 1100, 700, 1180, 730),
            unit("left 41", 30, 700, 110, 730),
            unit("right body 2", 640, 140, 1180, 170),
            unit("left body 2", 20, 140, 560, 170),
            width = 1200,
            height = 800
        )
        assertEquals(
            listOf("left body 1", "left body 2", "left 41", "right body 1", "right body 2", "right 42"),
            result
        )
    }

    @Test
    fun `a heading over one column stays above the text`() {
        val result = order(
            unit("body line 1", 60, 150, 540, 180),
            unit("Title of the page", 180, 40, 420, 80),
            unit("body line 2", 60, 200, 540, 230)
        )
        assertEquals(listOf("Title of the page", "body line 1", "body line 2"), result)
    }

    @Test
    fun `lines reversed by the recognizer are put back in order`() {
        // ML Kit returned the block's lines bottom-up (the QA "lines 12..6 before 1..5").
        val lines = (1..6).map { i -> unit("line $i", 40, i * 40, 560, i * 40 + 20) }.reversed()
        assertEquals(
            (1..6).map { "line $it" },
            OcrLayout.valuesInReadingOrder(lines, 600, 800)
        )
    }

    @Test
    fun `a rotated page is read in its own upward direction`() {
        // The page was stored sideways (content rotated 90 degrees clockwise), so the first line
        // of text sits at the largest x. Page image: 200 wide, 100 tall.
        val rotated = listOf(
            unit("third line", 90, 10, 100, 90),
            unit("first line", 150, 10, 160, 90),
            unit("second line", 120, 10, 130, 90)
        )
        val result = OcrLayout.valuesInReadingOrder(rotated, pageWidth = 200, pageHeight = 100, rotationDegrees = 90)
        assertEquals(listOf("first line", "second line", "third line"), result)
    }

    @Test
    fun `sameLine matches boxes that describe one line from two recognizers`() {
        assertTrue(OcrLayout.sameLine(box(10, 10, 200, 30), box(12, 11, 205, 31)))
    }

    @Test
    fun `sameLine rejects the next line and the neighbouring column`() {
        assertFalse(OcrLayout.sameLine(box(10, 10, 200, 30), box(10, 40, 200, 60)))
        assertFalse(OcrLayout.sameLine(box(10, 10, 200, 30), box(320, 10, 580, 30)))
    }

    private fun assertTrue(value: Boolean) = org.junit.Assert.assertTrue(value)

    private fun assertFalse(value: Boolean) = org.junit.Assert.assertFalse(value)
}
