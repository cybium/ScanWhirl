package com.scanwhirl.app.util

import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Lane R1 — "Hide private info" defect guards.
 *
 * IFSC and UPI VPAs are bank-category detections (they reuse Kind.ACCOUNT so the existing
 * "Card & bank numbers" choice in the Find sheet picks them up without touching lane e1's
 * StampEditorActivity). Aadhaar/phone OCR spacing variants must be found, dates and amounts
 * must not. Suggest boxes the matched element, not the whole OCR line, and finds emails too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class R1RedactDefectTest {

    private val page = 1000

    private fun words(vararg texts: String, y: Int = 100): List<SensitiveScan.Word> {
        var x = 100
        return texts.map { text ->
            val width = text.length * 20
            SensitiveScan.Word(text, x, y, x + width, y + 40).also { x += width + 10 }
        }
    }

    private fun hits(vararg texts: String) =
        SensitiveScan.find(words(*texts), page, page)

    // ---- defect 1: IFSC and UPI ----------------------------------------------------

    @Test
    fun `IFSC code is a bank detection and is not an email or a random word`() {
        val hit = hits("SBIN0001234").single()
        assertEquals(SensitiveScan.Kind.ACCOUNT, hit.kind)
        assertEquals("SBIN0001234", hit.text)
        // Lower-case OCR is upper-cased before matching; the hit keeps the original glyphs.
        val lower = hits("sbin0001234").single()
        assertEquals(SensitiveScan.Kind.ACCOUNT, lower.kind)
        assertEquals("sbin0001234", lower.text)
        assertTrue("no 0 in the fifth position", hits("ABCDEFGHIJK").isEmpty())
        assertTrue("fifth character is not 0", hits("SBIN1234567").isEmpty())
    }

    @Test
    fun `UPI VPAs are bank detections while ordinary emails stay EMAIL`() {
        for (vpa in listOf("ravi@oksbi", "name.surname@ybl", "9876543210@paytm")) {
            val hit = hits(vpa).single()
            assertEquals("VPA $vpa must be in the bank category", SensitiveScan.Kind.ACCOUNT, hit.kind)
            assertEquals(vpa, hit.text)
        }
        assertEquals(SensitiveScan.Kind.EMAIL, hits("a.b@example.com").single().kind)
        // On one line the two are told apart, not collapsed into one email box.
        val both = hits("ravi@oksbi", "a.b@example.com")
        assertEquals(2, both.size)
        assertTrue(both.any { it.kind == SensitiveScan.Kind.EMAIL && it.text == "a.b@example.com" })
        assertTrue(both.any { it.kind == SensitiveScan.Kind.ACCOUNT && it.text == "ravi@oksbi" })
    }

    // ---- defect 2: grouped-number variants ------------------------------------------

    @Test
    fun `Aadhaar with double spaces dots or hyphens is found`() {
        for (text in listOf("1234  5678  9012", "1234.5678.9012", "1234-5678-9012")) {
            val found = hits(text).filter { it.kind == SensitiveScan.Kind.AADHAAR }
            assertEquals("Aadhaar $text", 1, found.size)
            assertEquals(12, found.single().text.count { it.isDigit() })
        }
    }

    @Test
    fun `Indian phone country code variants are found`() {
        for (text in listOf("+91 (98765) 43210", "091 98765 43210", "+91-98765-43210")) {
            val found = hits(text).filter { it.kind == SensitiveScan.Kind.PHONE }
            assertEquals("phone $text", 1, found.size)
        }
    }

    @Test
    fun `dates and amounts are still not flagged`() {
        assertTrue("date with dots", hits("12.03.2024").isEmpty())
        assertTrue("grouped amount", hits("1,234.56").isEmpty())
    }

    // ---- defect 3: suggest uses element boxes and finds email ------------------------

    @Test
    fun `suggest boxes the matched element not the whole line`() {
        val pg = OcrPage(1000, 1000, listOf(OcrLine("Aadhaar 1234 5678 9012 end", 100, 100, 800, 150)))
        val suggestion = PageStamp.suggestRedactions(pg).single()
        assertTrue("must start on the number, not the line: ${suggestion.box.left}", suggestion.box.left > 0.1f)
        assertTrue("must stop on the number, not the line: ${suggestion.box.right}", suggestion.box.right < 0.8f)
    }

    @Test
    fun `suggest finds an email address`() {
        val pg = OcrPage(1000, 1000, listOf(OcrLine("Mail a.b@example.com today", 100, 100, 800, 150)))
        val suggestion = PageStamp.suggestRedactions(pg).single()
        assertEquals("a.b@example.com", suggestion.text)
    }

    // ---- defect 4: honest string parity ---------------------------------------------

    @Test
    fun `both languages carry the honest save and no-suggestions messages`() {
        val en = File("src/main/res/values/strings_stamp.xml")
        val hi = File("src/main/res/values-hi/strings_stamp.xml")

        val noSuggestionsEn = readString(en, "stamp_no_suggestions")
        val noSuggestionsHi = readString(hi, "stamp_no_suggestions")
        assertTrue("EN must not promise a clean page: $noSuggestionsEn",
            noSuggestionsEn.contains("Check the page yourself", ignoreCase = true))
        assertTrue("HI must ask the user to check: $noSuggestionsHi", noSuggestionsHi.contains("खुद जाँच"))

        val savedEn = readString(en, "private_info_saved")
        val savedHi = readString(hi, "private_info_saved")
        assertEquals("EN format args", 1, formatArgs(savedEn))
        assertEquals("HI format args", 1, formatArgs(savedHi))
        assertTrue("EN must say the original stays unchanged: $savedEn",
            savedEn.contains("original", ignoreCase = true) && savedEn.contains("unchanged", ignoreCase = true))
        assertTrue("HI must say the original stays unchanged: $savedHi", savedHi.contains("मूल दस्तावेज़"))
    }

    private fun readString(file: File, name: String): String {
        val match = Regex("""(?s)<string name="$name"[^>]*>(.*?)</string>""")
            .find(file.readText())
        return requireNotNull(match) { "$name missing in ${file.path}" }.groupValues[1].trim()
    }

    private fun formatArgs(text: String) = Regex("%\\d+\\$[a-zA-Z]").findAll(text).count()
}
