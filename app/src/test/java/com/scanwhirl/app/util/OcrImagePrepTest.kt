package com.scanwhirl.app.util

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.CRC32

/** Gallery-image OCR uses its own lenient, bounded decoder, not the page-import contract. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class OcrImagePrepTest {
    @get:Rule val tmp = TemporaryFolder()

    /** 24-bit bottom-up BMP with the same four quadrants as [ImportFixtures.image]. */
    private fun bmp(width: Int = 120, height: Int = 80): ByteArray {
        val rowSize = (width * 3 + 3) / 4 * 4
        val out = ByteArrayOutputStream()
        fun le(v: Int, n: Int) { for (i in 0 until n) out.write((v shr (8 * i)) and 0xFF) }
        out.write('B'.code); out.write('M'.code)
        le(54 + rowSize * height, 4); le(0, 4); le(54, 4)
        le(40, 4); le(width, 4); le(height, 4); le(1, 2); le(24, 2); le(0, 4)
        le(rowSize * height, 4); le(2835, 4); le(2835, 4); le(0, 4); le(0, 4)
        val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        for (y in height - 1 downTo 0) {
            var written = 0
            for (x in 0 until width) {
                val c = colors[(y / (height / 2)).coerceAtMost(1) * 2 + (x / (width / 2)).coerceAtMost(1)]
                out.write(Color.blue(c)); out.write(Color.green(c)); out.write(Color.red(c)); written += 3
            }
            while (written < rowSize) { out.write(0); written++ }
        }
        return out.toByteArray()
    }

    private val gif1x1 = intArrayOf(
        0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00, 0x80, 0x00, 0x00, 0xFF, 0xFF, 0xFF,
        0x00, 0x00, 0x00, 0x21, 0xF9, 0x04, 0x01, 0x00, 0x00, 0x00, 0x00, 0x2C, 0x00, 0x00, 0x00, 0x00,
        0x01, 0x00, 0x01, 0x00, 0x00, 0x02, 0x02, 0x44, 0x01, 0x00, 0x3B
    ).map { it.toByte() }.toByteArray()

    /** PNG with a valid IHDR declaring [width]x[height] and no pixel data: bounds only. */
    private fun declaredPng(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        fun be(v: Int) { for (s in listOf(24, 16, 8, 0)) out.write((v shr s) and 0xFF) }
        fun chunk(type: String, data: ByteArray) {
            be(data.size)
            val body = type.toByteArray(Charsets.US_ASCII) + data
            out.write(body)
            be(CRC32().apply { update(body) }.value.toInt())
        }
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val ihdr = ByteArrayOutputStream().also { o ->
            for (v in listOf(width, height)) for (s in listOf(24, 16, 8, 0)) o.write((v shr s) and 0xFF)
            o.write(byteArrayOf(8, 2, 0, 0, 0))
        }.toByteArray()
        chunk("IHDR", ihdr)
        chunk("IDAT", byteArrayOf(0x78, 0x9C.toByte(), 0, 0, 0, 0, 1))
        return out.toByteArray()
    }

    private fun corners(bitmap: Bitmap, expected: List<Int>) {
        listOf(15 to 15, bitmap.width - 16 to 15, 15 to bitmap.height - 16,
            bitmap.width - 16 to bitmap.height - 16).forEachIndexed { i, (x, y) ->
            ImportFixtures.assertColor(expected[i], bitmap.getPixel(x, y))
        }
    }

    @Test fun `BMP and GIF are readable for OCR though page import rejects them`() {
        val bmp = bmp()
        val gif = gif1x1
        assertThrows(IllegalArgumentException::class.java) { ImageTools.normalizePage(bmp) }
        assertThrows(IllegalArgumentException::class.java) { ImageTools.normalizePage(gif) }
        val bmpBitmap = OcrImagePrep.decode(bmp)
        try {
            assertEquals(120, bmpBitmap.width)
            assertEquals(80, bmpBitmap.height)
            corners(bmpBitmap, listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW))
        } finally { bmpBitmap.recycle() }
        val gifBitmap = OcrImagePrep.decode(gif)
        try {
            assertEquals(1, gifBitmap.width)
            assertEquals(1, gifBitmap.height)
        } finally { gifBitmap.recycle() }
    }

    @Test fun `PNG with trailing bytes is readable for OCR though page import rejects it`() {
        val png = ImportFixtures.image(Bitmap.CompressFormat.PNG) + byteArrayOf(0, 0)
        assertThrows(IllegalArgumentException::class.java) { ImageTools.normalizePage(png) }
        val bitmap = OcrImagePrep.decode(png)
        try {
            assertEquals(120, bitmap.width)
            corners(bitmap, listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW))
        } finally { bitmap.recycle() }
    }

    @Test fun `large rotated phone JPEG keeps full OCR resolution and is upright`() {
        val source = ImportFixtures.oriented(File(tmp.root, "phone.jpg"), 6, width = 4000, height = 3000)
        val before = source.copyOf()
        val bitmap = OcrImagePrep.decode(source)
        try {
            // Page import would halve this to 1500x2000 (3 MP); OCR keeps all 12 MP.
            assertEquals(3000, bitmap.width)
            assertEquals(4000, bitmap.height)
            corners(bitmap, listOf(Color.BLUE, Color.RED, Color.YELLOW, Color.GREEN))
        } finally { bitmap.recycle() }
        assertArrayEquals(before, source)
    }

    @Test fun `all EXIF orientations are applied and invalid ones are identity`() {
        val expected = mapOf(
            2 to listOf(Color.GREEN, Color.RED, Color.YELLOW, Color.BLUE),
            3 to listOf(Color.YELLOW, Color.BLUE, Color.GREEN, Color.RED),
            4 to listOf(Color.BLUE, Color.YELLOW, Color.RED, Color.GREEN),
            5 to listOf(Color.RED, Color.BLUE, Color.GREEN, Color.YELLOW),
            6 to listOf(Color.BLUE, Color.RED, Color.YELLOW, Color.GREEN),
            7 to listOf(Color.YELLOW, Color.GREEN, Color.BLUE, Color.RED),
            8 to listOf(Color.GREEN, Color.YELLOW, Color.RED, Color.BLUE)
        )
        for ((orientation, colors) in expected) {
            val bitmap = OcrImagePrep.decode(ImportFixtures.oriented(File(tmp.root, "o$orientation.jpg"), orientation))
            try {
                assertEquals("$orientation", if (orientation >= 5) 80 else 120, bitmap.width)
                corners(bitmap, colors)
            } finally { bitmap.recycle() }
        }
        for (invalid in listOf(0, 9, 255)) {
            val bitmap = OcrImagePrep.decode(ImportFixtures.oriented(File(tmp.root, "i$invalid.jpg"), invalid))
            try {
                assertEquals(120, bitmap.width)
                assertEquals(80, bitmap.height)
                corners(bitmap, listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW))
            } finally { bitmap.recycle() }
        }
    }

    @Test fun `unreadable input gets an actionable error`() {
        listOf(byteArrayOf(), "not an image".toByteArray()).forEach { bytes ->
            val failure = assertThrows(IllegalArgumentException::class.java) { OcrImagePrep.decode(bytes) }
            assertTrue(failure.message!!, failure.message!!.contains("Choose a different image"))
        }
    }

    @Test fun `decoded size is bounded by sampling and absurd declared sizes are refused early`() {
        assertEquals(1, OcrImagePrep.sampleSize(4000, 3000))      // 12 MP
        assertEquals(1, OcrImagePrep.sampleSize(4624, 3472))      // 16 MP sensor
        assertEquals(2, OcrImagePrep.sampleSize(8160, 6120))      // 50 MP -> 12.5 MP
        assertEquals(4, OcrImagePrep.sampleSize(20000, 10000))    // 200 MP -> 12.5 MP
        for ((w, h) in listOf(4000 to 3000, 9000 to 7000, 12000 to 12000, Int.MAX_VALUE to Int.MAX_VALUE)) {
            val sample = OcrImagePrep.sampleSize(w, h)
            val side = { n: Int -> (n.toLong() + sample - 1) / sample }
            assertTrue("$w x $h", side(w) * side(h) <= OcrImagePrep.MAX_OCR_PIXELS)
            assertTrue("$w x $h", sample == 1 || side(w) * side(h) * 4 > OcrImagePrep.MAX_OCR_PIXELS)
        }
        val failure = assertThrows(IllegalArgumentException::class.java) { OcrImagePrep.decode(declaredPng(20000, 10000)) }
        assertTrue(failure.message!!, failure.message!!.contains("too large"))
    }

    @Test fun `encoded file read is bounded at 32 MB with an actionable error`() {
        class Zeros(private var left: Long) : InputStream() {
            var served = 0L
            override fun read() = if (left-- > 0) 0 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                val n = minOf(len.toLong(), left).toInt()
                left -= n; served += n
                return n
            }
        }
        val ok = OcrImagePrep.readBytes(Zeros(1024))
        assertEquals(1024, ok.size)
        val stream = Zeros(100L * 1024 * 1024)
        val failure = assertThrows(IllegalArgumentException::class.java) { OcrImagePrep.readBytes(stream) }
        assertTrue(failure.message!!, failure.message!!.contains("32 MB"))
        assertTrue("must stop reading shortly after the limit", stream.served < 34L * 1024 * 1024)
    }
}
