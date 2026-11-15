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

    @Test
    fun `a noisy Latin reading on a Hindi page loses to the Devanagari one`() {
        // Exactly the owner's photo of an Aadhaar: glare makes the Latin model return "fafI" and
        // "gov/" where the card says सरकार and आधार.
        val latinPage = OcrRawPage(
            600, 800,
            listOf(latin("fafI", 40), latin("gov/", 120), latin("Aadhaar 1234 5678", 200))
        )
        val devaPage = OcrRawPage(
            600, 800,
            listOf(deva("सरकार", 40), deva("आधार", 120), deva("आधार 1234 5678", 200))
        )
        val text = OcrMerge.assemble(OcrMerge.merge(listOf(latinPage, devaPage)))
        assertTrue("सरकार survives: $text", text.contains("सरकार"))
        assertTrue("आधार survives: $text", text.contains("आधार"))
        assertFalse("the Latin noise is dropped: $text", text.contains("fafI") || text.contains("gov/"))
    }

    @Test
    fun `a short Latin token on a Hindi page loses even when the other model saw letters`() {
        // Neither reading has Devanagari for this box: the Devanagari model returned "srkr" and
        // the Latin model "faf". The page is Devanagari, so the Devanagari model's reading wins.
        val latinPage = OcrRawPage(600, 800, listOf(latin("faf", 40), latin("Total 1250", 120)))
        val devaPage = OcrRawPage(600, 800, listOf(deva("srkr", 40), deva("कुल 1250", 120)))
        val text = OcrMerge.assemble(OcrMerge.merge(listOf(latinPage, devaPage)))
        assertFalse("the Latin noise is dropped: $text", text.contains("faf"))
        assertTrue("the Devanagari reading of the box is kept: $text", text.contains("srkr"))
    }

    @Test
    fun `a clean English line on a Hindi page is not replaced`() {
        val latinPage = OcrRawPage(600, 800, listOf(latin("Invoice number 4711", 40), latin("Total 1250", 120)))
        val devaPage = OcrRawPage(600, 800, listOf(deva("Invo1ce nurnber 4711", 40), deva("कुल 1250", 120)))
        val text = OcrMerge.assemble(OcrMerge.merge(listOf(latinPage, devaPage)))
        assertTrue("the readable English line wins: $text", text.contains("Invoice number 4711"))
    }

    @Test
    fun `genuinely different lines are both kept`() {
        val latinPage = OcrRawPage(600, 800, listOf(latin("Invoice number 4711", 40)))
        val devaPage = OcrRawPage(600, 800, listOf(deva("कुल 1250", 200)))
        val text = OcrMerge.assemble(OcrMerge.merge(listOf(latinPage, devaPage)))
        assertTrue(text.contains("Invoice number 4711"))
        assertTrue(text.contains("कुल 1250"))
    }

    @Test
    fun `noise detection names the Latin-model artifacts`() {
        assertTrue(OcrMerge.looksLikeNoise(latin("fafI", 40)))
        assertTrue(OcrMerge.looksLikeNoise(latin("gov/", 40)))
        assertTrue(OcrMerge.looksLikeNoise(latin("Invo1ce", 40)))
        assertTrue(OcrMerge.looksLikeNoise(latin("x", 40)))
        assertTrue(OcrMerge.looksLikeNoise(latin("Total 100", 40).copy(confidence = 0.2f)))
        assertTrue(OcrMerge.looksLikeNoise(latin("faf", 40), hindiPage = true))
        assertFalse(OcrMerge.looksLikeNoise(latin("Invoice number 4711", 40)))
        assertFalse(OcrMerge.looksLikeNoise(latin("Total: 1250", 40)))
        assertFalse(OcrMerge.looksLikeNoise(latin("GST 5%", 40)))
    }
}
