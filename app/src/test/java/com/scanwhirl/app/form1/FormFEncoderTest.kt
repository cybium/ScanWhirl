package com.scanwhirl.app.form1

import android.graphics.Bitmap
import com.scanwhirl.app.util.ExactKbEncoder
import com.scanwhirl.app.util.SignatureCleanup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Lane F, F1: the encoder must never quietly shrink the pixels a person asked for, and the
 * signature cleanup must stay a predictable black-ink-on-white-paper transform.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class FormFEncoderTest {

    private fun flat(color: Int, w: Int = 120, h: Int = 90): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        return bmp
    }

    @Test(timeout = 30_000)
    fun `an impossible KB band keeps the requested pixels and reports the miss honestly`() {
        val src = flat(-0x1)
        try {
            // One byte can never hold a JPEG. The old engine silently rewrote 200x230 down to 8x8
            // while still calling that IN_BAND; the new engine keeps the requested dimensions.
            val r = ExactKbEncoder.encode(src, 200, 230, 200, 1L, 1L)
            assertEquals(200, r.width)
            assertEquals(230, r.height)
            assertEquals(ExactKbEncoder.Outcome.ABOVE_MAX, r.outcome)
            assertTrue(r.bytes.isNotEmpty())
            assertEquals(0xFF.toByte(), r.bytes[0])
            assertEquals(0xD8.toByte(), r.bytes[1])
        } finally {
            src.recycle()
        }
    }

    @Test(timeout = 30_000)
    fun `a reachable band still uses the requested pixels`() {
        val src = flat(-0x1, 320, 240)
        try {
            val r = ExactKbEncoder.encode(src, 140, 60, 200, 10_000L, 20_000L)
            assertEquals(140, r.width)
            assertEquals(60, r.height)
        } finally {
            src.recycle()
        }
    }

    @Test(timeout = 30_000)
    fun `signature cleanup maps paper to white and ink to black without changing size`() {
        val src = flat(0xFFF5F3EE.toInt(), 40, 20)
        val ink = flat(0xFF202020.toInt(), 40, 20)
        val combined = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(40 * 20) { if (it < 400) ink.getPixel(it % 40, it / 40) else src.getPixel(it % 40, it / 40) }
        combined.setPixels(pixels, 0, 40, 0, 0, 40, 20)
        val cleaned = SignatureCleanup.clean(combined)
        try {
            assertEquals(40, cleaned.width)
            assertEquals(20, cleaned.height)
            assertEquals(0xFF000000.toInt(), cleaned.getPixel(0, 0))
            assertEquals(0xFFFFFFFF.toInt(), cleaned.getPixel(20, 10))
        } finally {
            cleaned.recycle()
            combined.recycle()
            src.recycle()
            ink.recycle()
        }
    }
}
