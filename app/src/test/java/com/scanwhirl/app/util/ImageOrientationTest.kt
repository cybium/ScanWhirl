package com.scanwhirl.app.util

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * v2 UI: 08_text_ocr — a landscape photo is shown in its real orientation on the
 * Text screen's Page tab: EXIF orientation is applied, so the bitmap is upright
 * (a 90°-rotated tag swaps width and height).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ImageOrientationTest {

    @Test
    fun `decodeOriented applies the photo's EXIF rotation`() {
        val dir = ApplicationProvider.getApplicationContext<android.app.Application>().cacheDir
        val file = File(dir, "landscape_${System.nanoTime()}.jpg")
        val bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()

        val exif = ExifInterface(file.absolutePath)
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
        exif.saveAttributes()

        val upright = ImageTools.decodeOriented(file, 200)
        assertNotNull("the page decodes", upright)
        assertEquals("rotated 90°: width becomes the short side", 20, upright!!.width)
        assertEquals(40, upright.height)
        upright.recycle()
        file.delete()
    }

    @Test
    fun `a page without EXIF keeps its size`() {
        val dir = ApplicationProvider.getApplicationContext<android.app.Application>().cacheDir
        val file = File(dir, "plain_${System.nanoTime()}.jpg")
        val bitmap = Bitmap.createBitmap(30, 18, Bitmap.Config.ARGB_8888)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()

        val page = ImageTools.decodeOriented(file, 200)
        assertNotNull(page)
        assertEquals(30, page!!.width)
        assertEquals(18, page.height)
        page.recycle()
        file.delete()
    }

    @Test
    fun `a gallery photo is normalised to a real JPEG and garbage fails clearly`() {
        val bitmap = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888)
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()
        val normalized = ImageTools.normalizeGalleryPhoto(out.toByteArray())
        assertTrue("a real JPEG comes back", normalized.size > 0)
        assertEquals(
            "the normalised page is JPEG",
            PageImageFormat.Format.JPEG,
            PageImageFormat.check(normalized)
        )

        val failure = runCatching { ImageTools.normalizeGalleryPhoto(byteArrayOf(1, 2, 3, 4)) }
        assertTrue(
            "unreadable bytes explain themselves",
            failure.exceptionOrNull()?.message?.contains("cannot be read") == true
        )
    }
}
