package com.scanwhirl.app.util

import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import org.junit.Assert.assertEquals
import org.junit.Test

class OcrEngineTest {

    @Test
    fun `assemble preserves all block lines in reading order`() {
        val blocks = listOf(
            OcrEngine.OcrBlock(top = 100, left = 0, lines = listOf("Footer line")),
            OcrEngine.OcrBlock(top = 10, left = 40, lines = listOf("Title second", "Title third")),
            OcrEngine.OcrBlock(top = 10, left = 10, lines = listOf("Title first"))
        )
        val result = OcrEngine.assemble(blocks)
        assertEquals(
            "Title first\n\nTitle second\nTitle third\n\nFooter line",
            result
        )
    }

    @Test
    fun `assemble drops leading blank lines within a block`() {
        val blocks = listOf(
            OcrEngine.OcrBlock(top = 10, left = 0, lines = listOf("", "", "Real content", ""))
        )
        assertEquals("Real content", OcrEngine.assemble(blocks))
    }

    @Test
    fun `assemble drops trailing blank lines within a block`() {
        val blocks = listOf(
            OcrEngine.OcrBlock(top = 10, left = 0, lines = listOf("", "Real content", "", ""))
        )
        assertEquals("Real content", OcrEngine.assemble(blocks))
    }

    @Test
    fun `assemble separates paragraphs with blank line`() {
        val blocks = listOf(
            OcrEngine.OcrBlock(top = 10, left = 0, lines = listOf("Para one line 1", "Para one line 2")),
            OcrEngine.OcrBlock(top = 100, left = 0, lines = listOf("Para two line 1"))
        )
        assertEquals(
            "Para one line 1\nPara one line 2\n\nPara two line 1",
            OcrEngine.assemble(blocks)
        )
    }

    @Test
    fun `assemble empty input returns empty string`() {
        assertEquals("", OcrEngine.assemble(emptyList()))
    }

    @Test
    fun `assemble all-blank block yields empty`() {
        val blocks = listOf(
            OcrEngine.OcrBlock(top = 10, left = 0, lines = listOf("", "", ""))
        )
        assertEquals("", OcrEngine.assemble(blocks))
    }

    @Test
    fun `OcrPage data class copies correctly`() {
        val page = OcrPage(320, 240, listOf(OcrLine("text", 1, 2, 3, 4)))
        assertEquals(320, page.width)
        assertEquals(240, page.height)
        assertEquals(1, page.lines.size)
        assertEquals("text", page.lines[0].text)
        assertEquals(1, page.lines[0].left)
        assertEquals(2, page.lines[0].top)
        assertEquals(3, page.lines[0].right)
        assertEquals(4, page.lines[0].bottom)
    }

    @Test
    fun `OcrLine equals and hashCode work`() {
        val a = OcrLine("text", 1, 2, 3, 4)
        val b = OcrLine("text", 1, 2, 3, 4)
        val c = OcrLine("other", 1, 2, 3, 4)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(false, a.equals(c))
    }

    @Test
    fun `OcrResult holds text and page`() {
        val page = OcrPage(100, 100, listOf(OcrLine("hi", 0, 0, 10, 10)))
        val result = com.scanwhirl.app.util.OcrEngine.OcrResult("hi there", page)
        assertEquals("hi there", result.text)
        assertEquals(page, result.page)
    }
}
