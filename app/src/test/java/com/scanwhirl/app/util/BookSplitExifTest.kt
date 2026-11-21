package com.scanwhirl.app.util

import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * G1 item 2: a book photo carrying EXIF orientation must be split as the UPRIGHT image, the
 * same way import honours TAG_ORIENTATION. Before the fix BookSplit.decode ignored the tag,
 * so a sideways phone photo was cut along the wrong axis.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class BookSplitExifTest {

    private fun fixture(width: Int, height: Int, orientation: Int): ByteArray {
        val file = File(
            ApplicationProvider.getApplicationContext<android.app.Application>().cacheDir,
            "book_exif_${System.nanoTime()}.jpg"
        )
        val bytes = ImportFixtures.oriented(file, orientation, width = width, height = height)
        file.delete()
        return bytes
    }

    @Test
    fun `decode applies EXIF rotation so a sideways photo is upright`() {
        // Raw pixels are 120x80 landscape; EXIF says rotate 90°, so the upright page is 80x120.
        val bytes = fixture(120, 80, ExifInterface.ORIENTATION_ROTATE_90)
        val decoded = BookSplit.decode(bytes, BookSplit.MAX_LONG_SIDE)
        try {
            assertEquals("EXIF rotation must be applied on decode, width", 80, decoded.width)
            assertEquals("EXIF rotation must be applied on decode, height", 120, decoded.height)
        } finally {
            decoded.recycle()
        }
    }

    @Test
    fun `splitting a rotated spread keeps the upright halves`() {
        // Raw 100x200 portrait + EXIF 90° = a 200x100 landscape two-page spread.
        val bytes = fixture(100, 200, ExifInterface.ORIENTATION_ROTATE_90)
        val preview = BookSplit.decode(bytes, BookSplit.MAX_LONG_SIDE)
        val analysis = try {
            BookSplit.analyse(preview)
        } finally {
            preview.recycle()
        }
        val (left, right) = BookSplit.splitToJpeg(bytes, analysis)
        val a = ImportFixtures.assertJpeg(left)
        val b = ImportFixtures.assertJpeg(right)
        try {
            assertEquals("each half is upright, not sideways", 100, a.height)
            assertEquals(100, b.height)
            assertEquals("no pixels lost across the cut", 200, a.width + b.width)
        } finally {
            a.recycle()
            b.recycle()
        }
    }

    @Test
    fun `a photo without EXIF keeps its decoded size`() {
        val bytes = fixture(120, 80, ExifInterface.ORIENTATION_NORMAL)
        val decoded = BookSplit.decode(bytes, BookSplit.MAX_LONG_SIDE)
        try {
            assertEquals(120, decoded.width)
            assertEquals(80, decoded.height)
        } finally {
            decoded.recycle()
        }
    }
}
