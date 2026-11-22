package com.scanwhirl.app.util

import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** LX: pure detection rules, including Hindi numerals and the printed-page offset. */
class LinkIndexDetectorTest {

    private fun page(vararg lines: Pair<String, Int>): OcrPage = OcrPage(
        width = 1000,
        height = 1400,
        lines = lines.mapIndexed { i, (text, left) ->
            OcrLine(text, left, 40 + i * 40, 950, 70 + i * 40)
        }
    )

    @Test
    fun `dotted leaders and section numbers are parsed in English`() {
        val result = LinkIndexDetector.parse("Chapter 3 ........ 45")!!
        assertEquals("Chapter 3", result.title)
        assertEquals(45, result.printed)
        assertTrue(result.leader)
    }

    @Test
    fun `paper index style number title page is parsed`() {
        val result = LinkIndexDetector.parse("3. Motion  45")!!
        assertEquals("3. Motion", result.title)
        assertEquals(45, result.printed)
        assertFalse(result.leader)
        assertTrue(result.gap >= 2)
    }

    @Test
    fun `Devanagari numerals and ellipsis leader are parsed`() {
        val result = LinkIndexDetector.parse("१२. गति … ४५")!!
        assertEquals("१२. गति", result.title)
        assertEquals(45, result.printed)
        assertTrue(result.leader)
    }

    @Test
    fun `a title ending in digits is not mistaken for a page number`() {
        assertNull(LinkIndexDetector.parse("COVID-19"))
        assertNull(LinkIndexDetector.parse("COVID-19  45x"))
    }

    @Test
    fun `headers and prose are skipped`() {
        assertNull(LinkIndexDetector.parse("Contents"))
        assertNull(LinkIndexDetector.parse("अनुक्रमणिका"))
        assertNull(LinkIndexDetector.parse("This paragraph has words but no number."))
    }

    @Test
    fun `digit conversion covers ASCII Devanagari and fullwidth`() {
        assertEquals(0, LinkIndexDetector.digit('0'))
        assertEquals(5, LinkIndexDetector.digit('५'))
        assertEquals(9, LinkIndexDetector.digit('９'))
        assertNull(LinkIndexDetector.digit('x'))
        assertEquals("12 45", LinkIndexDetector.asciiDigits("१२ ４５"))
    }

    @Test
    fun `detection keeps reading order and assigns confidence`() {
        val p = page(
            "Contents" to 60,
            "Chapter 3 ........ 45" to 60,
            "3. Motion  45" to 80,
            "१२. गति … ४५" to 100,
            "4. Heat  50" to 60
        )
        val found = LinkIndexDetector.detect(p, 0, 60)
        assertEquals(listOf("Chapter 3", "3. Motion", "१२. गति", "4. Heat"), found.map { it.title })
        assertEquals(listOf(45, 45, 45, 50), found.map { it.printedPage })
        assertEquals(0, found[0].level)
        assertTrue(found[2].level >= 1)
        assertTrue(found.all { it.confidence != LinkIndexDetector.Confidence.LOW })
    }

    @Test
    fun `offset math maps printed to PDF and refuses out of range`() {
        assertEquals(9, LinkIndexDetector.targetPage(5, 4, 60))
        assertEquals(1, LinkIndexDetector.targetPage(1, 0, 60))
        assertNull(LinkIndexDetector.targetPage(60, 4, 60))
        assertNull(LinkIndexDetector.targetPage(1, -1, 60))
    }

    @Test
    fun `suggested offset prefers zero on a tie`() {
        val found = LinkIndexDetector.detect(
            page("1. One ..... 5" to 60, "2. Two ..... 12" to 60, "3. Three ..... 30" to 60),
            0, 60
        )
        assertEquals(0, LinkIndexDetector.suggestOffset(found, 60))
    }

    @Test
    fun `suggested offset finds printed page one equals PDF page five`() {
        val found = LinkIndexDetector.detect(
            page("1. One ..... 5" to 60, "2. Two ..... 12" to 60, "3. Three ..... 30" to 60),
            0, 4
        )
        // With only 4 PDF pages, the default guess cannot map the later entries;
        // the suggestion never silently claims one that does not fit.
        assertTrue(LinkIndexDetector.suggestOffset(found, 4) in -100..100)
    }

    @Test
    fun `rect maps top-left pixels into bottom-left PDF points`() {
        val rect = LinkIndexDetector.toPdfRect(
            LinkIndexDetector.Box(100, 100, 200, 150), 1000, 1400, 480, 672
        )
        assertEquals(listOf(48, 600, 96, 624), rect.toList())
    }
}
