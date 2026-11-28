package com.scanwhirl.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.scanwhirl.app.ui.qr.QrContent
import com.scanwhirl.app.ui.qr.QrKind
import com.scanwhirl.app.util.BookSplit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/**
 * G4 small fixes: the book-split decoded bitmap must be recycled on the 0° identity path, and a
 * Wi-Fi code with no SSID must never put its raw `P:<password>` payload in the big QR title.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class G4SmallFixTest {

    /** Same harness as BookSplitTest: a JPEG on disk-like bytes, decoded inside the engine. */
    private fun spreadBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val out = ByteArrayOutputStream()
        assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out))
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun assertTwoJpegs(left: ByteArray, right: ByteArray) {
        val leftBitmap = BitmapFactory.decodeByteArray(left, 0, left.size)
        val rightBitmap = BitmapFactory.decodeByteArray(right, 0, right.size)
        assertTrue("left page decodes", leftBitmap.width > 1 && leftBitmap.height > 1)
        assertTrue("right page decodes", rightBitmap.width > 1 && rightBitmap.height > 1)
        leftBitmap.recycle()
        rightBitmap.recycle()
    }

    @Test
    fun `a zero-degree analysis still recycles the decoded spread`() {
        val bytes = spreadBytes()
        var decoded: Bitmap? = null
        BookSplit.decodeProbeForTest = { decoded = it }
        try {
            val analysis = BookSplit.Analysis(0, BookSplit.Gutter(0.5f, found = false), false)
            val (left, right) = BookSplit.splitToJpeg(bytes, analysis)
            val source = requireNotNull(decoded) { "the engine must decode the spread" }
            assertTrue(
                "rotate(0) returns the same object, so the source itself must be recycled",
                source.isRecycled
            )
            assertTwoJpegs(left, right)
        } finally {
            BookSplit.decodeProbeForTest = null
            decoded?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    @Test
    fun `a rotated analysis still produces pages and recycles the decoded source`() {
        val bytes = spreadBytes()
        var decoded: Bitmap? = null
        BookSplit.decodeProbeForTest = { decoded = it }
        try {
            val analysis = BookSplit.Analysis(90, BookSplit.Gutter(0.5f, found = false), false)
            val (left, right) = BookSplit.splitToJpeg(bytes, analysis)
            assertTrue(
                "the rotated copy is a second bitmap; the decoded source is still recycled",
                requireNotNull(decoded).isRecycled
            )
            assertTwoJpegs(left, right)
        } finally {
            BookSplit.decodeProbeForTest = null
            decoded?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    private fun wifi(raw: String): QrContent =
        QrContent.parse(raw, QrKind.WIFI, "Contact", "Wi-Fi network")

    @Test
    fun `a wifi code without an SSID uses a neutral title instead of the raw payload`() {
        val content = wifi("WIFI:T:WPA;P:hunter2;;")
        assertFalse("the password is not a title: ${content.title}", content.title.contains("hunter2"))
        assertFalse("the raw code is not a title: ${content.title}", content.title.contains("WIFI:"))
        assertEquals("Wi-Fi network", content.title)
        assertTrue(
            "the password remains only in the masked secret field",
            content.fields.any { it.secret && it.value == "hunter2" })
        assertEquals("hunter2", content.copyText)
    }

    @Test
    fun `an explicitly empty SSID uses the neutral title as well`() {
        val content = wifi("WIFI:T:WPA;S:;P:hunter2;;")
        assertFalse(content.title.contains("hunter2"))
        assertEquals("Wi-Fi network", content.title)
        assertTrue(content.fields.any { it.secret && it.value == "hunter2" })
    }

    @Test
    fun `a wifi code with an SSID keeps that network name as its title`() {
        val content = wifi("WIFI:T:WPA;S:Home 5G;P:hunter2;;")
        assertEquals("Home 5G", content.title)
        assertTrue(content.fields.any { it.secret && it.value == "hunter2" })
    }
}
