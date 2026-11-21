package com.scanwhirl.app.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/**
 * T1: saved ID card pages must carry the same 150 dpi JFIF density as the PDF that embeds them,
 * otherwise a page prints at the wrong physical size.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class T1IdCardJpegDpiTest {

    private fun jpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).drawColor(Color.WHITE)
            return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertJfifDpi(bytes: ByteArray, dpi: Int) {
        var idx = -1
        for (i in 0 until bytes.size - 11) {
            if (bytes[i] == 0x4A.toByte() && bytes[i + 1] == 0x46.toByte() &&
                bytes[i + 2] == 0x49.toByte() && bytes[i + 3] == 0x46.toByte() &&
                bytes[i + 4] == 0x00.toByte()
            ) { idx = i; break }
        }
        assertTrue("JFIF header present", idx >= 0)
        val units = bytes[idx + 7].toInt() and 0xFF
        val x = ((bytes[idx + 8].toInt() and 0xFF) shl 8) or (bytes[idx + 9].toInt() and 0xFF)
        val y = ((bytes[idx + 10].toInt() and 0xFF) shl 8) or (bytes[idx + 11].toInt() and 0xFF)
        assertEquals("density unit is dots per inch", 1, units)
        assertEquals(dpi, x)
        assertEquals(dpi, y)
    }

    @Test
    fun `both sides on one page carry 150 dpi`() {
        assertJfifDpi(IdCard.compose(jpeg(), jpeg()), 150)
    }

    @Test
    fun `a single side page carries 150 dpi`() {
        assertJfifDpi(IdCard.composeSingle(jpeg()), 150)
    }
}
