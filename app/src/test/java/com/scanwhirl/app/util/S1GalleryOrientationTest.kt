package com.scanwhirl.app.util

import android.graphics.Bitmap
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
