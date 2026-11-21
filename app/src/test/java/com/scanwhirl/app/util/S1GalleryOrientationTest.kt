package com.scanwhirl.app.util

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * S1 defect 3: normalizeGalleryPhoto's HEIC/other-container fallback re-encoded the decoded
 * bitmap without applying EXIF orientation, so a rotated phone photo came back sideways. The
 * fix reads the same TAG_ORIENTATION normalizePage uses and applies the same matrix.
 *
 * HEIF decoding is not available in the JVM test image, so the test uses the existing decode
 * seam with a BMP (a container that only the fallback handles) and a small orientation seam.
 * Reflection keeps this file compiling against the unmodified base, where the RED failure is a
 * missing seam / unrotated output.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class S1GalleryOrientationTest {

    @After
    fun tearDown() {
        ImageTools.galleryDecode = null
        setOrientationOverride(null)
    }

    private fun setOrientationOverride(value: Int?) {
        val field = ImageTools::class.java.getDeclaredField("galleryOrientationOverride")
        field.isAccessible = true
        field.set(null, value)
    }

    @Test
    fun `the gallery fallback applies a 90 degree EXIF rotation`() {
        setOrientationOverride(6) // ORIENTATION_ROTATE_90
        ImageTools.galleryDecode = { _, _ ->
            Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        }
        val out = ImageTools.normalizeGalleryPhoto(ImportFixtures.bmp(40, 20))
        val decoded = ImportFixtures.assertJpeg(out)
        assertEquals("a 90 degree tag swaps the sides", 20, decoded.width)
        assertEquals(40, decoded.height)
        decoded.recycle()
    }

    @Test
    fun `EXIF tags 3 6 and 8 rotate the fallback page by the matching angle`() {
        // A 40x20 bitmap with a large red left half and blue right half. After rotation the
        // half that lands at a sampled point proves which transform was applied. Large blocks
        // survive JPEG's chroma subsampling, unlike a single pixel.
        val cases = listOf(
            3 to Pair(Pair(30, 10), Pair(10, 10)),  // 180: red moves to the right half (40x20)
            6 to Pair(Pair(10, 10), Pair(10, 30)),  // 90: red stays on top (20x40)
            8 to Pair(Pair(10, 30), Pair(10, 10))   // 270: red moves to the bottom (20x40)
        )
        for ((orientation, spots) in cases) {
            setOrientationOverride(orientation)
            ImageTools.galleryDecode = { _, _ ->
                Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888).apply {
                    for (x in 0 until 40) for (y in 0 until 20) {
                        setPixel(x, y, if (x < 20) Color.RED else Color.BLUE)
                    }
                }
            }
            val out = ImageTools.normalizeGalleryPhoto(ImportFixtures.bmp(40, 20))
            val decoded = ImportFixtures.assertJpeg(out)
            val expectedWidth = if (orientation == 3) 40 else 20
            val expectedHeight = if (orientation == 3) 20 else 40
            assertEquals("orientation $orientation swaps the sides", expectedWidth, decoded.width)
            assertEquals("orientation $orientation swaps the sides", expectedHeight, decoded.height)
            ImportFixtures.assertColor(Color.RED, decoded.getPixel(spots.first.first, spots.first.second), 60)
            ImportFixtures.assertColor(Color.BLUE, decoded.getPixel(spots.second.first, spots.second.second), 60)
            decoded.recycle()
        }
    }

    @Test
    fun `gallery fallback with no orientation tag keeps the decoded size`() {
        setOrientationOverride(null)
        ImageTools.galleryDecode = { _, _ ->
            Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        }
        val out = ImageTools.normalizeGalleryPhoto(ImportFixtures.bmp(40, 20))
        val decoded = ImportFixtures.assertJpeg(out)
        assertEquals(40, decoded.width)
        assertEquals(20, decoded.height)
        decoded.recycle()
    }
}
