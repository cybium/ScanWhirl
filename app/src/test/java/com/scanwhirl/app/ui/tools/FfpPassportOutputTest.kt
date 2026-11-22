package com.scanwhirl.app.ui.tools

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.util.PassportOutputOptions
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * FF-P behavioural tests: the Passport tool must hand the result screen a real asset for the chosen
 * job — a 630 x 810 JPG for the mission photo example, or a PDF with an exact 35 x 45 mm box on A4.
 * The picked original is never written to. Every test destroys its controller in a finally block.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class FfpPassportOutputTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private fun flatJpeg(width: Int = 1200, height: Int = 1000): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).drawColor(Color.WHITE)
            return ByteArrayOutputStream().also {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
            }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun controller(): ActivityController<PassportActivity> =
        Robolectric.buildActivity(PassportActivity::class.java)

    @Test(timeout = 30_000)
    fun photo_file_is_a_real_630_x_810_jpeg_not_a_pdf_or_archive() {
        val controller = controller()
        try {
            val output = controller.setup().get().run {
                acceptPhotoBytesForTest(flatJpeg())
                selectNeedForTest(PassportOutputOptions.Need.PHOTO_FILE)
                useMissionForTest()
                produceForTest()
            }
            assertEquals("photo-file job must emit JPEG", PassportOutputOptions.MIME_JPEG, output.mime)
            assertTrue("must be a .jpg file", output.file.name.endsWith(".jpg"))
            val decoded = BitmapFactory.decodeFile(output.file.absolutePath)
            assertEquals(630, decoded.width)
            assertEquals(810, decoded.height)
            decoded.recycle()
            val header = output.file.readBytes().copyOf(4)
            assertFalse("not a PDF", String(header, StandardCharsets.ISO_8859_1).startsWith("%PDF"))
            assertFalse("not a ZIP", header[0] == 0x50.toByte() && header[1] == 0x4B.toByte())
        } finally {
            controller.destroy()
        }
    }

    @Test(timeout = 30_000)
    fun custom_10_to_200_kb_band_miss_is_reported_honestly() {
        val controller = controller()
        try {
            val output = controller.setup().get().run {
                acceptPhotoBytesForTest(flatJpeg())
                selectNeedForTest(PassportOutputOptions.Need.PHOTO_FILE)
                setCustomForTest(630, 810, 10, 200)
                produceForTest()
            }
            assertTrue("receipt must admit the band was missed", output.note.contains("miss", ignoreCase = true))
            val decoded = BitmapFactory.decodeFile(output.file.absolutePath)
            assertEquals("a band miss must not quietly change pixels", 630, decoded.width)
            assertEquals(810, decoded.height)
            decoded.recycle()
        } finally {
            controller.destroy()
        }
    }

    @Test(timeout = 30_000)
    fun print_sheet_is_a_pdf_with_exact_a4_35_x_45_mm_box() {
        val controller = controller()
        try {
            val output = controller.setup().get().run {
                acceptPhotoBytesForTest(flatJpeg())
                selectNeedForTest(PassportOutputOptions.Need.PRINT_SHEET)
                selectSheetForTest(PassportOutputOptions.Sheet.A4)
                produceForTest()
            }
            assertEquals(PassportOutputOptions.MIME_PDF, output.mime)
            assertTrue(output.file.name.endsWith(".pdf"))
            val pdf = String(output.file.readBytes(), StandardCharsets.ISO_8859_1)
            assertTrue("A4 MediaBox", pdf.contains("/MediaBox [0 0 595.276 841.890]"))
            assertTrue("35 x 45 mm box", pdf.contains("99.213 0 0 127.559"))
            assertTrue("receipt names the physical box", output.note.contains("35"))
        } finally {
            controller.destroy()
        }
    }

    @Test(timeout = 30_000)
    fun ten_by_fifteen_sheet_receipt_is_not_labelled_a4() {
        val controller = controller()
        try {
            val output = controller.setup().get().run {
                acceptPhotoBytesForTest(flatJpeg())
                selectNeedForTest(PassportOutputOptions.Need.PRINT_SHEET)
                selectSheetForTest(PassportOutputOptions.Sheet.CM_10X15)
                produceForTest()
            }
            assertFalse("receipt must not call a 10 x 15 cm sheet A4", output.note.contains("A4"))
            assertTrue(output.note.contains("10"))
            val pdf = String(output.file.readBytes(), StandardCharsets.ISO_8859_1)
            assertTrue(pdf.contains("/MediaBox [0 0 283.465 425.197]"))
        } finally {
            controller.destroy()
        }
    }

    @Test(timeout = 30_000)
    fun original_picked_file_is_left_unchanged() {
        val controller = controller()
        try {
            val original = File(app.cacheDir, "ffp_original_pick.jpg")
            val bytes = flatJpeg()
            original.writeBytes(bytes)
            val before = original.readBytes()
            val output = controller.setup().get().run {
                loadUriForTest(android.net.Uri.fromFile(original))
                selectNeedForTest(PassportOutputOptions.Need.PHOTO_FILE)
                useMissionForTest()
                // load() decodes off the main thread; pump the looper until the photo is ready.
                val deadline = System.currentTimeMillis() + 8_000
                while (loadedImageSizeForTest() == 0 to 0 && System.currentTimeMillis() < deadline) {
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                    Thread.sleep(20)
                }
                produceForTest()
            }
            assertTrue("a photo was loaded", output.file.length() > 0L)
            assertArrayEquals("the original pick must not be modified", before, original.readBytes())
        } finally {
            controller.destroy()
        }
    }
}
