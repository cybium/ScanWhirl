package com.scanwhirl.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2 UI: 08_text_ocr — script merge and text hygiene. No ML Kit: the two recognizers are
 * represented by synthetic raw pages, exactly what the seam returns on a phone.
 */
class OcrMergeTest {

    private fun box(l: Int, t: Int, r: Int, b: Int) = OcrBox(l, t, r, b)

    private fun latin(text: String, t: Int) = OcrRawLine(text, box(20, t, 560, t + 30), OcrScript.LATIN, 1f)

    private fun deva(text: String, t: Int) = OcrRawLine(text, box(20, t, 560, t + 30), OcrScript.DEVANAGARI, 1f)

    @Test
    fun `english and hindi lines keep their own script`() {
        // The Latin recognizer turns the Hindi line into Latin gibberish at the same place.
        val latinPage = OcrRawPage(
            600, 800,
            listOf(
                latin("Invoice number 4711", 40),
                latin("iqDrh ifjl %", 120),
                latin("Total: 1250", 200)
            )
        )
        val devaPage = OcrRawPage(
            600, 800,
            listOf(
                // A Devanagari model cannot read Latin print: it returns Latin-ish noise.
                deva("Invo1ce nurnber 4711", 40),
                deva("कुल राशि: 1250", 120),
                deva("कुल: 1250", 200)
            )
        )

        val merged = OcrMerge.merge(listOf(latinPage, devaPage))
        val text = OcrMerge.assemble(merged)
        assertTrue("the English line survives: $text", text.contains("Invoice number 4711"))
        assertTrue("the Hindi line is Devanagari: $text", text.contains("कुल राशि: 1250"))
        assertFalse("no replacement characters", text.contains('\uFFFD'))
        // Line order is preserved top to bottom.
        assertTrue(text.indexOf("Invoice") < text.indexOf("कुल"))
    }

    @Test
    fun `a line only one recognizer saw is kept`() {
        val latinPage = OcrRawPage(600, 800, listOf(latin("English only", 40)))
        val devaPage = OcrRawPage(600, 800, listOf(deva("केवल हिंदी", 140)))
        val text = OcrMerge.assemble(OcrMerge.merge(listOf(latinPage, devaPage)))
        assertTrue(text.contains("English only"))
        assertTrue(text.contains("केवल हिंदी"))
    }

    @Test
    fun `hindi only keeps Devanagari and drops replacement characters`() {
        val devaPage = OcrRawPage(
            600, 800,
            listOf(
                deva("पहला \uFFFD\uFFFD वाक्य", 40),
                deva("दूसरा वाक्य", 100)
            )
        )
        val text = OcrMerge.assemble(OcrMerge.merge(listOf(devaPage)))
        assertEquals("पहला वाक्य\nदूसरा वाक्य", text)
        assertFalse(text.contains('\uFFFD'))
    }

    @Test
    fun `a paragraph gap becomes a blank line`() {
        val page = OcrRawPage(
            600, 800,
            listOf(latin("Para one line one", 40), latin("Para one line two", 80), latin("Para two", 300))
        )
        assertEquals("Para one line one\nPara one line two\n\nPara two", OcrMerge.assemble(page))
    }

    @Test
    fun `merging two columns keeps the left column first`() {
        val page = OcrRawPage(
            600, 800,
            listOf(
                latin("right one", 40).copy(box = box(320, 40, 580, 70)),
                latin("left one", 40).copy(box = box(20, 40, 270, 70)),
                latin("right two", 80).copy(box = box(320, 80, 580, 110)),
                latin("left two", 80).copy(box = box(20, 80, 270, 110))
            )
        )
        assertEquals("left one\nleft two\nright one\nright two", OcrMerge.assemble(OcrMerge.ordered(page)))
    }

    @Test
    fun `sanitize removes replacement characters and unpaired surrogates`() {
        assertEquals("fox umps", OcrText.sanitize("fox \uFFFD\uFFFDumps"))
        assertEquals("ab", OcrText.sanitize("a\uD83Db"))
        assertEquals("ab c", OcrText.sanitize("a\uDC00b c"))
        assertEquals("पाठ ठीक", OcrText.sanitize("पाठ\u00A0ठीक"))
        assertEquals("one two", OcrText.sanitize("one\t\ntwo"))
        assertEquals("😀 ok", OcrText.sanitize("😀 ok"))
    }

    @Test
    fun `devanagari detection`() {
        assertTrue(OcrText.hasDevanagari("कुल 1250"))
        assertFalse(OcrText.hasDevanagari("Total 1250"))
        assertTrue(OcrText.looksLatin("Total 1250"))
        assertFalse(OcrText.looksLatin("कुल 1250"))
    }

    @Test
    fun `pick prefers the line that actually has Devanagari`() {
        val gibberish = latin("iqDrh", 40)
        val hindi = deva("कुल", 40)
        assertEquals(hindi, OcrMerge.pick(gibberish, hindi))
        assertEquals(hindi, OcrMerge.pick(hindi, gibberish))
    }

    @Test
    fun `pick takes the more confident line when the scripts agree`() {
        val weak = latin("Total 100", 40).copy(confidence = 0.4f)
        val strong = latin("Total 100", 40).copy(confidence = 0.9f)
        assertEquals(0.9f, OcrMerge.pick(weak, strong).confidence)
    }
}
