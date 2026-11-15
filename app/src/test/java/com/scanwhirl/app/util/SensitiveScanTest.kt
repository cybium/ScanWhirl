package com.scanwhirl.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Find detectors, one test per group, with OCR noise and the false positives the owner
 * called out (dates, prices, short order numbers). Boxes follow the precision rules: element
 * geometry, 15% horizontal padding, Aadhaar stops before the 9th digit, cards keep the last 4.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SensitiveScanTest {

    private val page = 1000

    /** Words laid left to right on one 40px-high line at y=100. */
    private fun words(vararg texts: String, y: Int = 100): List<SensitiveScan.Word> {
        var x = 100
        return texts.map { text ->
            val width = text.length * 20
            SensitiveScan.Word(text, x, y, x + width, y + 40).also { x += width + 10 }
        }
    }

    private fun hits(vararg texts: String, wholeAadhaar: Boolean = false) =
        SensitiveScan.find(words(*texts), page, page, wholeAadhaar)

    @Test
    fun `aadhaar masks the first eight digits and stops in the gap`() {
        val hit = hits("9233", "6926", "4947").single()
        assertEquals(SensitiveScan.Kind.AADHAAR, hit.kind)
        // First glyph starts at x=100, pad 15% of 40px = 6px.
        assertEquals(0.094f, hit.box.left, 0.002f)
        // The 9th digit starts at 280; the box stops halfway through the gap (275).
        assertEquals(0.275f, hit.box.right, 0.002f)
        assertTrue("never covers the last group: ${hit.box.right}", hit.box.right < 0.28f)
    }

    @Test
    fun `whole-number option covers all twelve digits`() {
        val hit = hits("9233", "6926", "4947", wholeAadhaar = true).single()
        assertEquals(SensitiveScan.Kind.AADHAAR, hit.kind)
        assertTrue("covers the last group: ${hit.box.right}", hit.box.right > 0.36f)
    }

    @Test
    fun `ocr confusion characters still find the aadhaar`() {
        val hit = hits("9233", "6926", "49O7").single()
        assertEquals(SensitiveScan.Kind.AADHAAR, hit.kind)
    }

    @Test
    fun `a card keeps its last four digits`() {
        val hit = hits("4111 1111 1111 1111").single()
        assertEquals(SensitiveScan.Kind.CARD, hit.kind)
        val lastGroupStart = 0.100f + (15f / 19f) * (380f / 1000f)
        assertTrue("mask stops before the last group: ${hit.box.right}", hit.box.right < lastGroupStart)
        assertTrue("and covers the twelfth digit", hit.box.right > 0.100f)
    }

    @Test
    fun `a sixteen digit non luhn number is a VID and keeps four digits`() {
        val hit = hits("1234 5678 9012 3456").single()
        assertEquals(SensitiveScan.Kind.VID, hit.kind)
        assertTrue(hit.box.right < 0.100f + (15f / 19f) * 0.380f + 0.01f)
    }

    @Test
    fun `PAN email IBAN SSN phone and account are each detected`() {
        assertEquals(SensitiveScan.Kind.PAN, hits("ABCPE1234F").single().kind)
        assertEquals(SensitiveScan.Kind.EMAIL, hits("a.b@example.com").single().kind)
        assertEquals(
            SensitiveScan.Kind.IBAN,
            hits("GB82 WEST 1234 5698 7654 32").single().kind
        )
        assertEquals(SensitiveScan.Kind.SSN, hits("123-45-6789").single().kind)
        assertEquals(SensitiveScan.Kind.PHONE, hits("+91 98765 43210").single().kind)
        assertEquals(SensitiveScan.Kind.PHONE, hits("98765 43210").single().kind)
        assertEquals(SensitiveScan.Kind.PHONE, hits("+1 (555) 123-4567").single().kind)
        assertEquals(SensitiveScan.Kind.ACCOUNT, hits("1234567890").single().kind)
    }

    @Test
    fun `dates prices and short numbers are not sensitive`() {
        assertTrue("order date", hits("2024-11-15").isEmpty())
        assertTrue("slash date", hits("15/11/2024").isEmpty())
        assertTrue("price", hits("1,299.00").isEmpty())
        assertTrue("short order", hits("12345").isEmpty())
        assertTrue("invalid SSN is not an account", hits("000-12-3456").isEmpty())
    }

    @Test
    fun `luhn and IBAN checks`() {
        assertTrue(SensitiveScan.luhn("4111 1111 1111 1111"))
        assertTrue(SensitiveScan.luhn("5555-5555-5555-4444"))
        assertFalse(SensitiveScan.luhn("4111 1111 1111 1112"))
        assertTrue(SensitiveScan.ibanValid("GB82WEST12345698765432"))
        assertFalse(SensitiveScan.ibanValid("GB82WEST12345698765431"))
        assertFalse(SensitiveScan.ibanValid("NOTANIBAN"))
    }

    @Test
    fun `a box is clipped so it cannot bleed into the line below`() {
        val top = words("9233", "6926", "4947", y = 100)
        val below = words("1234", "5678", "9012", "3456", y = 150)
        val hit = SensitiveScan.find(top + below, page, page, wholeAadhaar = true)
            .first { it.kind == SensitiveScan.Kind.AADHAAR }
        assertTrue(
            "must stop above the next line: ${hit.box.bottom}",
            hit.box.bottom <= 150f / page
        )
    }
}
