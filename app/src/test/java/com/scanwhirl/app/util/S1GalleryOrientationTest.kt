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
        // A 40x20 bitmap: red top-left, blue bottom-right. After rotation the red pixel's
        // position proves which transform was applied, not just the output size.
        val cases = listOf(
            3 to Pair(39, 19),  // 180 degrees: 40x20
            6 to Pair(19, 0),   // 90 degrees: 20x40
            8 to Pair(0, 39)    // 270 degrees: 20x40
        )
        for ((orientation, spot) in cases) {
            setOrientationOverride(orientation)
            ImageTools.galleryDecode = { _, _ ->
                Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888).apply {
                    setPixel(0, 0, Color.RED)
                    setPixel(39, 19, Color.BLUE)
                }
            }
            val out = ImageTools.normalizeGalleryPhoto(ImportFixtures.bmp(40, 20))
            val decoded = ImportFixtures.assertJpeg(out)
            val expectedWidth = if (orientation == 3) 40 else 20
            val expectedHeight = if (orientation == 3) 20 else 40
            assertEquals("orientation $orientation swaps the sides", expectedWidth, decoded.width)
            assertEquals("orientation $orientation swaps the sides", expectedHeight, decoded.height)
            ImportFixtures.assertColor(Color.RED, decoded.getPixel(spot.first, spot.second), 70)
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
