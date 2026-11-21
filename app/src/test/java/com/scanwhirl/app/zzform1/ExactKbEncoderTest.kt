package com.scanwhirl.app.zzform1

import android.graphics.Bitmap
import com.scanwhirl.app.util.ExactKbEncoder
import com.scanwhirl.app.util.FormPresets
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FORM1: the exact-KB engine. Every synthetic image must stay at or under the maximum,
 * a fit must land inside the band, a flat image must report an honest minimum miss, and
 * the JFIF density must carry the chosen DPI.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ExactKbEncoderTest {

    private enum class Kind { NOISE, GRADIENT, STROKES, FLAT }

    private data class Target(val w: Int, val h: Int, val min: Long, val max: Long)

    private val targets = listOf(
        Target(200, 230, 20_000L, 50_000L),   // photo
        Target(140, 60, 10_000L, 20_000L),    // signature
        Target(240, 240, 20_000L, 50_000L),   // thumb
        Target(800, 400, 50_000L, 100_000L)   // declaration
    )

    private fun synthetic(w: Int, h: Int, kind: Kind): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                when (kind) {
                    Kind.NOISE -> {
                        // Coloured high-entropy pixels: realistic for a scan of textured paper and
                        // large enough to make the 10-20 KB signature band reachable honestly.
                        val r = (x * 31 + y * 67) and 0xFF
                        val g = (x * 97 + y * 13 + ((x * y) and 0xFF)) and 0xFF
                        val b = (x * 53 + y * 181) and 0xFF
                        px[y * w + x] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
                    }
                    else -> {
                        val v = when (kind) {
                            Kind.GRADIENT -> x * 255 / (w - 1).coerceAtLeast(1)
                            Kind.STROKES -> if (x % 11 < 2) 18 else 244
                            else -> 250 // FLAT
                        }
                        px[y * w + x] = 0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
                    }
                }
            }
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp
    }

    private fun withSource(w: Int, h: Int, kind: Kind, block: (Bitmap) -> Unit) {
        val bmp = synthetic(w, h, kind)
        try {
            block(bmp)
        } finally {
            bmp.recycle()
        }
    }

    @Test
    fun `never exceeds the maximum for many synthetic images and bands`() {
        for (kind in Kind.values()) {
            for (t in targets) {
                withSource(620, 480, kind) { src ->
                    val r = ExactKbEncoder.encode(src, t.w, t.h, 200, t.min, t.max)
                    assertTrue(
                        "$kind ${t.w}x${t.h}: ${r.bytes.size} > ${t.max}",
                        r.bytes.size <= t.max
                    )
                    assertTrue("$kind ${t.w}x${t.h}: empty output", r.bytes.isNotEmpty())
                    // SOI marker: this is a real JPEG, not padding.
                    assertEquals(0xFF.toByte(), r.bytes[0])
                    assertEquals(0xD8.toByte(), r.bytes[1])
                    assertEquals(t.w, r.width)
                    assertEquals(t.h, r.height)
                    if (r.outcome == ExactKbEncoder.Outcome.IN_BAND) {
                        assertTrue(r.bytes.size >= t.min)
                        assertTrue(r.bytes.size <= t.max)
                    }
                }
            }
        }
    }

    @Test
    fun `high entropy images land inside the band`() {
        for (t in targets) {
            withSource(620, 480, Kind.NOISE) { src ->
                val r = ExactKbEncoder.encode(src, t.w, t.h, 200, t.min, t.max)
                assertEquals(
                    "noise ${t.w}x${t.h} size=${r.bytes.size} quality=${r.quality}",
                    ExactKbEncoder.Outcome.IN_BAND,
                    r.outcome
                )
            }
        }
    }

    @Test
    fun `a flat image reports an honest minimum miss at maximum quality`() {
        val t = targets[1] // signature 10 to 20 KB
        withSource(620, 480, Kind.FLAT) { src ->
            val r = ExactKbEncoder.encode(src, t.w, t.h, 200, t.min, t.max)
            assertEquals(ExactKbEncoder.Outcome.BELOW_MIN, r.outcome)
            assertTrue(r.bytes.size < t.min)
            assertTrue(r.bytes.size <= t.max)
            assertEquals(ExactKbEncoder.MAX_QUALITY, r.quality)
        }
    }

    @Test
    fun `decimal KB threshold is 20000 bytes not 20480`() {
        withSource(620, 480, Kind.NOISE) { src ->
            val r = ExactKbEncoder.encode(src, 200, 230, 200, 20_000L, 20_000L)
            assertTrue("${r.bytes.size} exceeds 20000 decimal bytes", r.bytes.size <= 20_000L)
        }
    }

    @Test
    fun `JFIF density carries the chosen DPI`() {
        for (dpi in FormPresets.SUPPORTED_DPI) {
            withSource(400, 300, Kind.STROKES) { src ->
                val r = ExactKbEncoder.encode(src, 200, 230, dpi, 20_000L, 50_000L)
                assertEquals(dpi, ExactKbEncoder.readJfifDpi(r.bytes))
            }
        }
    }

    @Test
    fun `the same image and spec give byte-identical output`() {
        withSource(620, 480, Kind.NOISE) { src ->
            val a = ExactKbEncoder.encode(src, 200, 230, 200, 20_000L, 50_000L)
            val b = ExactKbEncoder.encode(src, 200, 230, 200, 20_000L, 50_000L)
            assertTrue(a.bytes.contentEquals(b.bytes))
            assertEquals(a, b)
        }
    }

    @Test
    fun `cancellation is cooperative`() {
        withSource(620, 480, Kind.NOISE) { src ->
            try {
                ExactKbEncoder.encode(src, 200, 230, 200, 20_000L, 50_000L, { true })
                fail("cancelled encode must throw")
            } catch (e: CancellationException) {
                assertNotNull(e.message)
            }
        }
    }
}
