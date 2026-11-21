package com.scanwhirl.app.util

import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Lane R1 independent-review fixes: dot/comma shapes without decimal or IPv4 false positives,
 * full-card coverage in Suggest, and one covering box for three OCR words.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class R1ReviewFixTest {

    private val page = 1000

    private fun words(vararg texts: String, y: Int = 100): List<SensitiveScan.Word> {
        var x = 100
        return texts.map { text ->
            val width = text.length * 20
            SensitiveScan.Word(text, x, y, x + width, y + 40).also { x += width + 10 }
        }
    }

    private fun hits(vararg texts: String) = SensitiveScan.find(words(*texts), page, page)

    @Test
    fun `decimal amounts and IPv4 addresses are not flagged`() {
        assertTrue("decimal amount", hits("Rs 1234567.89").isEmpty())
        assertTrue("IPv4-like address", hits("192.168.1.100").isEmpty())
        assertTrue("grouped decimal", hits("1,234.56").isEmpty())
        assertTrue("Indian grouped amount", hits("Rs 12,345").isEmpty())
        assertTrue("dot date", hits("12.03.2024").isEmpty())
    }

    @Test
    fun `comma grouped Aadhaar is found without flagging amounts`() {
        for (text in listOf("12,34,56,78,90,12", "1234,5678,9012")) {
            val found = hits(text).filter { it.kind == SensitiveScan.Kind.AADHAAR }
            assertEquals("comma Aadhaar $text", 1, found.size)
            assertEquals(12, found.single().text.count { it.isDigit() })
        }
    }

    @Test
    fun `IFSC with a space is found and a dotted phone stays a phone`() {
        val ifsc = hits("SBIN 0001234").single()
        assertEquals(SensitiveScan.Kind.ACCOUNT, ifsc.kind)
        assertEquals("SBIN 0001234", ifsc.text)

        val phone = hits("+91.98765.43210").single()
        assertEquals(SensitiveScan.Kind.PHONE, phone.kind)
    }

    @Test
    fun `three separate OCR words make one covering Aadhaar box`() {
        val three = listOf(
            SensitiveScan.Word("1234", 100, 100, 180, 140),
            SensitiveScan.Word("5678", 190, 100, 270, 140),
            SensitiveScan.Word("9012", 280, 100, 360, 140)
        )
        val found = SensitiveScan.find(three, page, page)
        val hit = found.single()
        assertEquals(SensitiveScan.Kind.AADHAAR, hit.kind)
        assertTrue("starts on the first word: ${hit.box.left}", hit.box.left <= 0.10f)
        assertTrue("covers the last word: ${hit.box.right}", hit.box.right >= 0.36f)
    }

    @Test
    fun `Suggest covers the full card number while Find keeps its last four UX`() {
        val line = OcrLine("Card 4111 1111 1111 1111", 100, 100, 800, 150)
        val pg = OcrPage(1000, 1000, listOf(line))
        val suggestion = PageStamp.suggestRedactions(pg).single { it.kind == PageStamp.SensitiveKind.CARD }
        val findHit = SensitiveScan.find(pg).single { it.kind == SensitiveScan.Kind.CARD }
        assertTrue(
            "Suggest must cover the last 4 digits: suggest=${suggestion.box.right} find=${findHit.box.right}",
            suggestion.box.right > findHit.box.right + 0.05f
        )
        assertTrue("Suggest reaches the end of the card: ${suggestion.box.right}", suggestion.box.right > 0.78f)
    }
}
