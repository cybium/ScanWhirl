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

/** L5 "Mask Aadhaar": number regex with OCR noise, and boxes over exactly the first 8 digits. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AadhaarMaskTest {

    private val page = OcrPage(1000, 1000, emptyList())

    private fun line(text: String) = OcrLine(text, 100, 100, 900, 140)

    @Test
    fun `OCR confusions and digit groups are normalised`() {
        assertEquals("Aadhaar 0123 4567 8912", AadhaarMask.normalize("Aadhaar O123 4567 89l2"))
        assertEquals("1010 0101", AadhaarMask.normalize("lO1O OlO1"))
    }

    @Test
    fun `Aadhaar with and without spaces and a VID are found`() {
        assertEquals(1, AadhaarMask.hits(line("Aadhaar 1234 5678 9012"), page).size)
        assertEquals(1, AadhaarMask.hits(line("123456789012"), page).size)
        assertEquals(1, AadhaarMask.hits(line("VID 1234 5678 9012 3456"), page).size)
        assertEquals(1, AadhaarMask.hits(line("Aadhaar O123 5678 9O12"), page).size)
        assertEquals(0, AadhaarMask.hits(line("Phone 9876543210"), page).size)
        assertEquals(0, AadhaarMask.hits(line("Amount 1234 5678"), page).size)
    }

    @Test
    fun `the box covers exactly the first 8 digits`() {
        val text = "Aadhaar 1234 5678 9012"
        val hit = AadhaarMask.hits(line(text), page).single()
        // Token starts at index 8; its first 8 digits end at index 17 in the line.
        val expectedLeft = (100f + (8f / text.length) * 800f) / 1000f
        val expectedRight = (100f + (17f / text.length) * 800f) / 1000f
        assertEquals(expectedLeft, hit.box.left, 0.01f)
        assertEquals(expectedRight, hit.box.right, 0.01f)
        assertTrue("box must stay on the page", hit.box.top >= 0f && hit.box.bottom <= 1f)
    }

    @Test
    fun `a VID masks only its first 8 digits`() {
        val text = "1234 5678 9012 3456"
        val hit = AadhaarMask.hits(line(text), page).single()
        assertEquals(16, hit.digits)
        val expectedRight = (100f + (9f / text.length) * 800f) / 1000f
        assertEquals(expectedRight, hit.box.right, 0.01f)
    }
}
