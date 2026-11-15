package com.scanwhirl.app.util

import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `ML Kit word boxes mask exactly the first two 4-digit groups`() {
        val words = listOf(
            AadhaarMask.Word("9233", 100, 100, 180, 140),
            AadhaarMask.Word("6926", 190, 100, 270, 140),
            AadhaarMask.Word("4947", 280, 100, 360, 140)
        )
        val boxes = AadhaarMask.findWords(words, 1000, 1000)
        assertEquals("two groups, not the last", 2, boxes.size)
        assertTrue("nothing may cover the last group: $boxes", boxes.all { it.right <= 0.275f })
        assertEquals(0.10f, boxes[0].left, 0.002f)
        assertEquals(0.18f, boxes[0].right, 0.002f)
        assertEquals(0.19f, boxes[1].left, 0.002f)
        assertEquals(0.27f, boxes[1].right, 0.002f)
    }

    @Test
    fun `one merged element is split after the eighth digit`() {
        val text = "9233 6926 4947"
        val box = AadhaarMask.Word(text, 100, 100, 380, 140)
        val boxes = AadhaarMask.findWords(listOf(box), 1000, 1000)
        assertEquals(1, boxes.size)

        val expectedRight = (100f + (9f / text.length) * 280f) / 1000f
        assertEquals(expectedRight, boxes[0].right, 0.005f)
        val lastGroupStart = (100f + (10f / text.length) * 280f) / 1000f
        assertTrue(
            "the mask must stop before 4947: ${boxes[0].right} / $lastGroupStart",
            boxes[0].right < lastGroupStart
        )
    }

    @Test
    fun `the QR heuristic finds a dense square and ignores a blank page`() {
        val page = android.graphics.Bitmap.createBitmap(400, 400, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(page)
        canvas.drawColor(android.graphics.Color.WHITE)
        val paint = android.graphics.Paint().apply { color = android.graphics.Color.BLACK }
        val cell = 6
        for (y in 0 until 20) for (x in 0 until 20) {
            if ((x + y) % 2 == 0) {
                canvas.drawRect(
                    (180 + x * cell).toFloat(), (180 + y * cell).toFloat(),
                    (180 + (x + 1) * cell).toFloat(), (180 + (y + 1) * cell).toFloat(),
                    paint
                )
            }
        }
        val qr = requireNotNull(AadhaarMask.findQr(page)) { "a QR-like block must be found" }
        val cx = (qr.left + qr.right) / 2f
        val cy = (qr.top + qr.bottom) / 2f
        assertTrue("centre x=$cx", cx in 0.55f..0.70f)
        assertTrue("centre y=$cy", cy in 0.55f..0.70f)
        page.recycle()

        val blank = android.graphics.Bitmap.createBitmap(300, 200, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(blank).drawColor(android.graphics.Color.WHITE)
        assertNull(AadhaarMask.findQr(blank))
        blank.recycle()
    }
}
