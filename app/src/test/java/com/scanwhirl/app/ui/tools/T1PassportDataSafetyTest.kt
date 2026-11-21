package com.scanwhirl.app.ui.tools

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import android.widget.TextView
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * T1 capture data safety: the Passport crop screen must never lose a picked photo on rotation,
 * must not offer Apply before a photo exists, and must crop an EXIF-rotated photo upright.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class T1PassportDataSafetyTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun jpeg(width: Int = 900, height: Int = 560): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).drawColor(Color.WHITE)
            return ByteArrayOutputStream().also {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)
            }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    /** A normal landscape photo whose EXIF says the camera was turned 90 degrees. */
    private fun orientedJpeg(): ByteArray {
        val file = File(app.cacheDir, "t1_passport_oriented.jpg")
        file.writeBytes(jpeg(900, 560))
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        return file.readBytes()
    }

    /** Drives the private picker-callback path with a local file, no system picker under Robolectric. */
    private fun load(activity: PassportActivity, bytes: ByteArray) {
        val file = File(app.cacheDir, "t1_passport_pick.jpg")
        file.writeBytes(bytes)
        val method = PassportActivity::class.java.getDeclaredMethod("load", Uri::class.java)
        method.isAccessible = true
        method.invoke(activity, Uri.fromFile(file))
    }

    private fun applyEnabled(activity: PassportActivity): Boolean =
        activity.findViewById<TextView>(R.id.toolCropApply).isEnabled

    private fun cropSize(activity: PassportActivity): Pair<Int, Int> = activity.loadedImageSizeForTest()

    private fun waitForImage(scenario: ActivityScenario<PassportActivity>): Boolean = waitUntil {
        var ready = false
        scenario.onActivity { ready = cropSize(it) != (0 to 0) }
        ready
    }

    private fun waitUntil(condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        idle()
        return condition()
    }

    @Test
    fun `apply is disabled until a photo is loaded`() {
        ActivityScenario.launch(PassportActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                assertFalse("Apply must not do anything before a photo is picked", applyEnabled(activity))
            }
        }
    }

    @Test
    fun `rotation keeps the picked photo and the crop frame`() {
        ActivityScenario.launch(PassportActivity::class.java).use { scenario ->
            scenario.onActivity { load(it, jpeg()) }
            assertTrue("the photo loads", waitForImage(scenario))
            var before = 0 to 0
            scenario.onActivity { before = cropSize(it) }

            scenario.recreate()
            idle()
            assertTrue("the photo survives rotation", waitForImage(scenario))
            scenario.onActivity { activity ->
                assertTrue("Apply is usable again after rotation", applyEnabled(activity))
                assertEquals("the same image is still cropped", before, cropSize(activity))
            }
        }
    }

    @Test
    fun `an EXIF rotated photo is cropped upright`() {
        ActivityScenario.launch(PassportActivity::class.java).use { scenario ->
            scenario.onActivity { load(it, orientedJpeg()) }
            assertTrue("the oriented photo loads", waitForImage(scenario))
            scenario.onActivity { activity ->
                val (width, height) = cropSize(activity)
                assertEquals("90 degree EXIF must turn the 900x560 photo upright", 560, width)
                assertEquals(900, height)
            }
        }
    }
}
