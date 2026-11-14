package com.scanwhirl.app.util

import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * L5 Phase B prep: the Redact sheet's "Suggest" proposes boxes over OCR lines that contain an
 * Aadhaar number, a PAN, an Indian mobile number or a long digit run. Suggestions are only
 * proposals; nothing is redacted until the user accepts them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PageStampSuggestTest {

    private fun page(vararg lines: OcrLine, w: Int = 1000, h: Int = 1000) =
        OcrPage(w, h, lines.toList())

    private fun line(text: String, top: Int = 100, left: Int = 100, right: Int = 800) =
        OcrLine(text, left, top, right, top + 50)

    @Test
    fun `an aadhaar number line is suggested and its box covers the line`() {
        val suggestions = PageStamp.suggestRedactions(page(line("Aadhaar 1234 5678 9012")))
        assertEquals(1, suggestions.size)
        val s = suggestions.single()
        assertEquals(PageStamp.SensitiveKind.AADHAAR, s.kind)
        assertTrue("box starts at or before the line", s.box.left <= 0.1f)
        assertTrue("box ends at or after the line", s.box.right >= 0.8f)
        assertTrue("box covers the line height", s.box.top <= 0.1f && s.box.bottom >= 0.15f)
        assertTrue(s.box.left >= 0f && s.box.right <= 1f && s.box.top >= 0f && s.box.bottom <= 1f)
    }

    @Test
    fun `PAN mobile and long digit lines are all suggested`() {
        val suggestions = PageStamp.suggestRedactions(
            page(
                line("PAN ABCDE1234F", top = 100),
                line("Mobile 98765 43210", top = 200),
                line("Account 123456789012345", top = 300)
            )
        )
        assertEquals(listOf(
            PageStamp.SensitiveKind.PAN,
            PageStamp.SensitiveKind.PHONE,
            PageStamp.SensitiveKind.LONG_NUMBER
        ), suggestions.map { it.kind })
    }

    @Test
    fun `ordinary text is not suggested`() {
        val suggestions = PageStamp.suggestRedactions(
            page(
                line("Rent agreement for November"),
                line("Total 250"),
                line("Page 1 of 3")
            )
        )
        assertTrue("no sensitive number on these lines", suggestions.isEmpty())
    }

    @Test
    fun `one box per sensitive line even when two patterns match`() {
        val suggestions = PageStamp.suggestRedactions(
            page(line("Aadhaar 1234 5678 9012 / 9876543210"))
        )
        assertEquals("the whole line is redacted once", 1, suggestions.size)
        assertEquals(PageStamp.SensitiveKind.AADHAAR, suggestions.single().kind)
    }

    @Test
    fun `a line at the page edge is clamped inside the page`() {
        val edge = OcrLine("9876543210", 0, 0, 1000, 20)
        val s = PageStamp.suggestRedactions(page(edge)).single()
        assertTrue(s.box.left >= 0f && s.box.top >= 0f)
        assertEquals(1f, s.box.right, 0.0001f)
        assertTrue(s.box.bottom <= 1f)
    }
}
