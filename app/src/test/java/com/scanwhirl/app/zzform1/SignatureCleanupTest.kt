package com.scanwhirl.app.zzform1

import android.graphics.Bitmap
import com.scanwhirl.app.util.SignatureCleanup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** FORM1: signature cleanup is a predictable threshold, never an opaque "AI" filter. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SignatureCleanupTest {

    private fun twoTone(): Bitmap {
        val bmp = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        val px = IntArray(40 * 20)
        for (y in 0 until 20) {
            for (x in 0 until 40) {
                val v = if (x < 20) 60 else 240
                px[y * 40 + x] = 0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
            }
        }
        bmp.setPixels(px, 0, 40, 0, 0, 40, 20)
        return bmp
    }

    @Test
    fun `dark ink becomes black and pale paper becomes white`() {
        val src = twoTone()
        try {
            val out = SignatureCleanup.clean(src)
            try {
                assertEquals(40, out.width)
                assertEquals(20, out.height)
                assertEquals(0xFF000000.toInt(), out.getPixel(2, 2))
                assertEquals(0xFFFFFFFF.toInt(), out.getPixel(30, 2))
                // The source is never mutated: the original photo stays untouched.
                val originalInk = src.getPixel(2, 2)
                val originalPaper = src.getPixel(30, 2)
                assertNotEquals(0xFF000000.toInt(), originalInk)
                assertNotEquals(0xFFFFFFFF.toInt(), originalPaper)
            } finally {
                out.recycle()
            }
        } finally {
            src.recycle()
        }
    }

    @Test
    fun `cleanup is deterministic`() {
        val src = twoTone()
        try {
            val a = SignatureCleanup.clean(src)
            val b = SignatureCleanup.clean(src)
            try {
                assertEquals(a.width, b.width)
                for (y in 0 until a.height) {
                    for (x in 0 until a.width) {
                        assertEquals("pixel $x,$y", a.getPixel(x, y), b.getPixel(x, y))
                    }
                }
            } finally {
                a.recycle()
                b.recycle()
            }
        } finally {
            src.recycle()
        }
    }

    @Test
    fun `threshold extremes are predictable`() {
        val src = twoTone()
        try {
            val allWhite = SignatureCleanup.clean(src, 1)
            val allBlack = SignatureCleanup.clean(src, 256)
            try {
                assertEquals(0xFFFFFFFF.toInt(), allWhite.getPixel(2, 2))
                assertEquals(0xFF000000.toInt(), allBlack.getPixel(30, 2))
            } finally {
                allWhite.recycle()
                allBlack.recycle()
            }
        } finally {
            src.recycle()
        }
    }
}
