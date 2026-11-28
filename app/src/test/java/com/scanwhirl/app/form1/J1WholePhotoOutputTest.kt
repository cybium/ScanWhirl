package com.scanwhirl.app.form1

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import com.scanwhirl.app.util.ExactKbEncoder
import com.scanwhirl.app.util.FormPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * J1 / PROMPT 10 real output check. The harm prevented is a "fit" that quietly crops the portrait:
 * if all four source corners survive the actual JPEG encode, the whole image was kept.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class J1WholePhotoOutputTest {

    private fun cornerSource(): Bitmap = Bitmap.createBitmap(600, 500, Bitmap.Config.ARGB_8888).apply {
        val c = Canvas(this)
        c.drawColor(Color.WHITE)
        val patch = 70
        c.drawRect(0f, 0f, patch.toFloat(), patch.toFloat(), android.graphics.Paint().apply { color = Color.RED })
        c.drawRect((width - patch).toFloat(), 0f, width.toFloat(), patch.toFloat(),
            android.graphics.Paint().apply { color = Color.GREEN })
        c.drawRect(0f, (height - patch).toFloat(), patch.toFloat(), height.toFloat(),
            android.graphics.Paint().apply { color = Color.BLUE })
        c.drawRect((width - patch).toFloat(), (height - patch).toFloat(), width.toFloat(), height.toFloat(),
            android.graphics.Paint().apply { color = Color.YELLOW })
    }

    private fun close(actual: Int, expected: Int, tolerance: Int = 55): Boolean =
        kotlin.math.abs(android.graphics.Color.red(actual) - android.graphics.Color.red(expected)) <= tolerance &&
            kotlin.math.abs(android.graphics.Color.green(actual) - android.graphics.Color.green(expected)) <= tolerance &&
            kotlin.math.abs(android.graphics.Color.blue(actual) - android.graphics.Color.blue(expected)) <= tolerance

    @Test(timeout = 30_000)
    fun `whole photo corners survive the real JPEG encode`() {
        val source = cornerSource()
        try {
            val encoded = ExactKbEncoder.encode(
                source, 600, 500, 200,
                1L * FormPresets.BYTES_PER_KB, 10_000L * FormPresets.BYTES_PER_KB
            )
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(encoded.bytes, 0, encoded.bytes.size, bounds)
            assertEquals("output pixels are the requested pixels", 600, bounds.outWidth)
            assertEquals(500, bounds.outHeight)
            assertTrue("a real JPEG was written", encoded.bytes.size > 1000)

            val out = BitmapFactory.decodeByteArray(encoded.bytes, 0, encoded.bytes.size)
            try {
                assertTrue("top-left source corner was cut: ${out.getPixel(12, 12)}",
                    close(out.getPixel(12, 12), Color.RED))
                assertTrue("top-right source corner was cut: ${out.getPixel(out.width - 12, 12)}",
                    close(out.getPixel(out.width - 12, 12), Color.GREEN))
                assertTrue("bottom-left source corner was cut: ${out.getPixel(12, out.height - 12)}",
                    close(out.getPixel(12, out.height - 12), Color.BLUE))
                assertTrue("bottom-right source corner was cut: ${out.getPixel(out.width - 12, out.height - 12)}",
                    close(out.getPixel(out.width - 12, out.height - 12), Color.YELLOW))
            } finally {
                out.recycle()
            }
        } finally {
            source.recycle()
        }
    }
}
