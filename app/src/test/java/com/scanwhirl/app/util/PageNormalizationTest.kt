package com.scanwhirl.app.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PageNormalizationTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `transparent PNG becomes JPEG with white transparent areas`() {
        val source = ImportFixtures.image(Bitmap.CompressFormat.PNG, transparent = true)
        val before = source.copyOf()
        val bitmap = ImportFixtures.assertJpeg(ImageTools.normalizePage(source))
        try {
            ImportFixtures.assertColor(Color.WHITE, bitmap.getPixel(119, 79))
            ImportFixtures.assertColor(Color.RED, bitmap.getPixel(15, 15))
        } finally { bitmap.recycle() }
        assertArrayEquals(before, source)
    }

    @Suppress("DEPRECATION")
    @Test fun `WebP becomes JPEG regardless of name`() {
        val source = ImportFixtures.image(Bitmap.CompressFormat.WEBP)
        assertEquals("RIFF", String(source, 0, 4))
        assertEquals("WEBP", String(source, 8, 4))
        val bitmap = ImportFixtures.assertJpeg(ImageTools.normalizePage(source))
        try { ImportFixtures.assertColor(Color.BLUE, bitmap.getPixel(15, 55)) }
        finally { bitmap.recycle() }
    }

    @Test fun `normal and untagged JPEG stay byte identical`() {
        val plain = ImportFixtures.image()
        assertArrayEquals(plain, ImageTools.normalizePage(plain))
        val tagged = ImportFixtures.oriented(File(tmp.root, "normal.jpg"), 1)
        assertArrayEquals(tagged, ImageTools.normalizePage(tagged))
    }

    private fun checkOrientation(orientation: Int, corners: List<Int>, format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG) {
        val source = ImportFixtures.oriented(File(tmp.root, "orientation.jpg"), orientation, format)
        val bitmap = ImportFixtures.assertJpeg(ImageTools.normalizePage(source))
        try {
            assertEquals(if (orientation >= 5) 80 else 120, bitmap.width)
            assertEquals(if (orientation >= 5) 120 else 80, bitmap.height)
            val points = listOf(15 to 15, bitmap.width - 16 to 15,
                15 to bitmap.height - 16, bitmap.width - 16 to bitmap.height - 16)
            points.forEachIndexed { i, (x, y) -> ImportFixtures.assertColor(corners[i], bitmap.getPixel(x, y)) }
        } finally { bitmap.recycle() }
        val normalized = ImageTools.normalizePage(source)
        assertArrayEquals("normalization must be idempotent", normalized, ImageTools.normalizePage(normalized))
        assertEquals(ExifInterface.ORIENTATION_UNDEFINED,
            ExifInterface(normalized.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
    }

    @Test fun `HEIC container is recognised and a truncated HEIC is refused`() {
        val heic = byteArrayOf(
            0, 0, 0, 20,
            'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            'h'.code.toByte(), 'e'.code.toByte(), 'i'.code.toByte(), 'c'.code.toByte(),
            0, 0, 0, 0,
            'm'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), '1'.code.toByte()
        )
        assertEquals(PageImageFormat.Format.HEIF, PageImageFormat.check(heic))
        val truncated = heic.copyOf(12)
        assertTrue(
            "a truncated HEIC must not be offered as a supported page",
            runCatching { PageImageFormat.check(truncated) }.isFailure
        )
    }

    @Test fun `EXIF horizontal mirror`() = checkOrientation(2, listOf(Color.GREEN, Color.RED, Color.YELLOW, Color.BLUE))    @Test fun `EXIF rotation 180`() = checkOrientation(3, listOf(Color.YELLOW, Color.BLUE, Color.GREEN, Color.RED))
    @Test fun `EXIF vertical mirror`() = checkOrientation(4, listOf(Color.BLUE, Color.YELLOW, Color.RED, Color.GREEN))
    @Test fun `EXIF transpose`() = checkOrientation(5, listOf(Color.RED, Color.BLUE, Color.GREEN, Color.YELLOW))
    @Test fun `EXIF rotation 90`() = checkOrientation(6, listOf(Color.BLUE, Color.RED, Color.YELLOW, Color.GREEN))
    @Test fun `EXIF transverse`() = checkOrientation(7, listOf(Color.YELLOW, Color.GREEN, Color.BLUE, Color.RED))
    @Test fun `EXIF rotation 270`() = checkOrientation(8, listOf(Color.GREEN, Color.YELLOW, Color.RED, Color.BLUE))

    @Test fun `12 MP phone JPEG with EXIF rotation 90 is sampled upright and portrait`() {
        val source = ImportFixtures.oriented(File(tmp.root, "phone.jpg"), 6, width = 4000, height = 3000)
        val before = source.copyOf()
        val bitmap = ImportFixtures.assertJpeg(ImageTools.normalizePage(source))
        try {
            // 4000x3000 exceeds 3000 px, so it is sampled to 2000x1500 and then rotated.
            assertEquals(1500, bitmap.width)
            assertEquals(2000, bitmap.height)
            val corners = listOf(Color.BLUE, Color.RED, Color.YELLOW, Color.GREEN)
            listOf(15 to 15, bitmap.width - 16 to 15, 15 to bitmap.height - 16,
                bitmap.width - 16 to bitmap.height - 16).forEachIndexed { i, (x, y) ->
                ImportFixtures.assertColor(corners[i], bitmap.getPixel(x, y))
            }
        } finally { bitmap.recycle() }
        assertArrayEquals(before, source)
    }

    @Suppress("DEPRECATION")
    @Test fun `invalid EXIF orientation is identity not a rejection`() {
        for (value in listOf(9, 255, 65535)) {
            val jpeg = ImportFixtures.oriented(File(tmp.root, "bad-$value.jpg"), value)
            assertArrayEquals("orientation $value", jpeg, ImageTools.normalizePage(jpeg))
            for (format in listOf(Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.WEBP)) {
                val source = ImportFixtures.oriented(File(tmp.root, "bad-$value-$format"), value, format)
                val bitmap = ImportFixtures.assertJpeg(ImageTools.normalizePage(source))
                try {
                    assertEquals("$format $value", 120, bitmap.width)
                    assertEquals("$format $value", 80, bitmap.height)
                    ImportFixtures.assertColor(Color.RED, bitmap.getPixel(15, 15))
                    ImportFixtures.assertColor(Color.YELLOW, bitmap.getPixel(104, 64))
                } finally { bitmap.recycle() }
            }
        }
    }

    @Test fun `empty garbage and unsupported image are rejected`() {
        listOf(byteArrayOf(), "not an image".toByteArray(), "GIF89a".toByteArray()).forEach { bytes ->
            assertThrows(IllegalArgumentException::class.java) { ImageTools.normalizePage(bytes) }
        }
    }

    @Suppress("DEPRECATION")
    @Test fun `truncated JPEG PNG and WebP are rejected even with readable bounds`() {
        listOf(Bitmap.CompressFormat.JPEG, Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.WEBP).forEach { format ->
            val bytes = ImportFixtures.image(format)
            listOf(bytes.copyOf(bytes.size - 2), bytes.copyOf(bytes.size / 2)).forEach { truncated ->
                assertThrows("$format", IllegalArgumentException::class.java) { ImageTools.normalizePage(truncated) }
            }
        }
    }

    @Test fun `PNG bad CRC is rejected instead of trusting forgiving decoder`() {
        val bytes = ImportFixtures.image(Bitmap.CompressFormat.PNG)
        bytes[29] = (bytes[29].toInt() xor 1).toByte() // IHDR CRC
        assertThrows(IllegalArgumentException::class.java) { ImageTools.normalizePage(bytes) }
    }

    @Test fun `large image is downsampled before orientation and conversion`() {
        val source = ImportFixtures.image(Bitmap.CompressFormat.PNG, 7000, 1000)
        val bitmap = ImportFixtures.assertJpeg(ImageTools.normalizePage(source))
        try {
            assertTrue(bitmap.width <= 3000)
            assertTrue(bitmap.width.toLong() * bitmap.height <= 6_000_000)
            assertEquals(7f, bitmap.width.toFloat() / bitmap.height, 0.02f)
        } finally { bitmap.recycle() }
    }

    @Test fun `fine black strokes and half transparent pixels remain legible on white`() {
        val bitmap = Bitmap.createBitmap(320, 160, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { color = Color.BLACK }
        for (x in 20..200 step 10) canvas.drawRect(x.toFloat(), 20f, x + 2f, 140f, paint)
        paint.color = Color.argb(128, 0, 0, 0)
        canvas.drawRect(240f, 20f, 300f, 140f, paint)
        val bytes = ImageTools.encodePng(bitmap)
        bitmap.recycle()
        val result = ImportFixtures.assertJpeg(ImageTools.normalizePage(bytes))
        try {
            assertTrue(Color.red(result.getPixel(20, 80)) < 40)
            assertTrue(Color.red(result.getPixel(25, 80)) > 220)
            assertTrue(Color.red(result.getPixel(270, 80)) in 110..145)
        } finally { result.recycle() }
    }

    @Test fun `all filters operate on opaque normalized pixels`() {
        for (filter in ScanFilter.entries) {
            val bytes = ImportFixtures.image(Bitmap.CompressFormat.PNG, transparent = true)
            val bitmap = ImportFixtures.assertJpeg(ImageTools.normalizePage(bytes, filter))
            try {
                ImportFixtures.assertColor(Color.WHITE, bitmap.getPixel(119, 79))
                assertEquals(120, bitmap.width)
                assertEquals(80, bitmap.height)
            } finally { bitmap.recycle() }
        }
    }

    @Suppress("DEPRECATION")
    @Test fun `PNG and WebP all EXIF mirrors and rotations use explicit transforms`() {
        for (format in listOf(Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.WEBP)) {
            checkOrientation(2, listOf(Color.GREEN, Color.RED, Color.YELLOW, Color.BLUE), format)
            checkOrientation(3, listOf(Color.YELLOW, Color.BLUE, Color.GREEN, Color.RED), format)
            checkOrientation(4, listOf(Color.BLUE, Color.YELLOW, Color.RED, Color.GREEN), format)
            checkOrientation(5, listOf(Color.RED, Color.BLUE, Color.GREEN, Color.YELLOW), format)
            checkOrientation(6, listOf(Color.BLUE, Color.RED, Color.YELLOW, Color.GREEN), format)
            checkOrientation(7, listOf(Color.YELLOW, Color.GREEN, Color.BLUE, Color.RED), format)
            checkOrientation(8, listOf(Color.GREEN, Color.YELLOW, Color.RED, Color.BLUE), format)
        }
    }

    @Test fun `sampling arithmetic bounds huge and extreme aspect ratios without allocation`() {
        for ((w, h) in listOf(1 to Int.MAX_VALUE, Int.MAX_VALUE to Int.MAX_VALUE,
            3001 to 3001, 100000 to 100, 3000 to 2000, 2999 to 2999)) {
            val sample = ImageTools.pageSampleSize(w, h)
            val sw = (w.toLong() + sample - 1) / sample
            val sh = (h.toLong() + sample - 1) / sample
            assertTrue(sw <= 3000 && sh <= 3000 && sw * sh <= 6_000_000)
        }
    }

    @Test fun `oversized provider stream stops reading at the encoded limit`() {
        var read = 0L
        val input = object : java.io.InputStream() {
            override fun read(): Int { read++; return 1 }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                read += len
                return len
            }
        }
        val error = assertThrows(IllegalArgumentException::class.java) { ImageTools.readPageBytes(input) }
        assertTrue(error.message!!.contains("32 MB"))
        assertTrue(read <= ImageTools.MAX_PAGE_BYTES.toLong() + 8192)
    }

    @Test fun `malformed JPEG segment and header-only JPEG are rejected`() {
        val jpeg = ImportFixtures.image(width = 800, height = 600)
        val brokenLength = jpeg.copyOf().apply { this[4] = 0x7f; this[5] = 0xff.toByte() }
        assertThrows(IllegalArgumentException::class.java) { ImageTools.normalizePage(brokenLength) }
        val headerOnly = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())
        assertThrows(IllegalArgumentException::class.java) { ImageTools.normalizePage(headerOnly) }
    }

    @Test fun `PDF generator rejects truncated JPEG before replacing existing PDF`() {
        val source = File(tmp.root, "page.jpg").apply { writeBytes(ImportFixtures.image().dropLast(2).toByteArray()) }
        val pdf = File(tmp.root, "existing.pdf").apply { writeText("existing document sentinel") }
        val original = pdf.readBytes()
        assertThrows(IllegalArgumentException::class.java) { PdfGenerator.imagesToPdf(listOf(source), pdf) }
        assertArrayEquals(original, pdf.readBytes())
        assertFalse(File(tmp.root, "existing.pdf.tmp").exists())
    }

    @Test fun `lossless transparent WebP is flattened on white`() {
        val bytes = ImportFixtures.image(Bitmap.CompressFormat.WEBP_LOSSLESS, transparent = true)
        val bitmap = ImportFixtures.assertJpeg(ImageTools.normalizePage(bytes))
        try { ImportFixtures.assertColor(Color.WHITE, bitmap.getPixel(119, 79)) }
        finally { bitmap.recycle() }
    }

    @Test fun `progressive JPEG is validated and preserved without reencoding`() {
        // Pillow-generated solid blue 120x80 RGB, JPEG quality=95, progressive=True.
        // Embedded so the regression has no Python/ImageIO runtime dependency.
        val bytes = android.util.Base64.decode(
            "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAIBAQEBAQIBAQECAgICAgQDAgICAgUEBAMEBgUGBgYFBgYGBwkIBgcJBwYGCAsI" +
                "CQoKCgoKBggLDAsKDAkKCgr/2wBDAQICAgICAgUDAwUKBwYHCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoK" +
                "CgoKCgoKCgoKCgoKCgr/wgARCABQAHgDASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAj/xAAWAQEBAQAAAAAAAAAA" +
                "AAAAAAAABgj/2gAMAwEAAhADEAAAAY5G/pUAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAD//xAAUEAEAAAAAAAAAAAAA" +
                "AAAAAABw/9oACAEBAAEFAhb/xAAUEQEAAAAAAAAAAAAAAAAAAABQ/9oACAEDAQE/ASP/xAAUEQEAAAAAAAAAAAAAAAAAAABQ" +
                "/9oACAECAQE/ASP/xAAUEAEAAAAAAAAAAAAAAAAAAABw/9oACAEBAAY/Ahb/xAAUEAEAAAAAAAAAAAAAAAAAAABw/9oACAEB" +
                "AAE/IRb/2gAMAwEAAgADAAAAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAP/EABQRAQAAAAAAAAAAAAAAAAAAAFD/" +
                "2gAIAQMBAT8QI//EABQRAQAAAAAAAAAAAAAAAAAAAFD/2gAIAQIBAT8QI//EABQQAQAAAAAAAAAAAAAAAAAAAHD/2gAIAQEA" +
                "AT8QFv/Z",
            android.util.Base64.DEFAULT
        )
        assertArrayEquals(bytes, ImageTools.normalizePage(bytes))
        val bitmap = ImportFixtures.assertJpeg(bytes)
        try { ImportFixtures.assertColor(Color.BLUE, bitmap.getPixel(60, 40)) }
        finally { bitmap.recycle() }
    }
}
