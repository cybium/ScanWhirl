package com.scanwhirl.app.ui.tools

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
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
 * T1: the ID card's JPEG cache copies are written once at capture time on IO, not rewritten on the
 * main thread every time onSaveInstanceState runs.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class T1IdCardCaptureTimeWriteTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun saveState(activity: android.app.Activity) {
        val method = android.app.Activity::class.java
            .getDeclaredMethod("onSaveInstanceState", Bundle::class.java)
        method.isAccessible = true
        method.invoke(activity, Bundle())
    }

    private fun jpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(900, 560, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).drawColor(Color.RED)
            return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
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
    fun `the front is cached when captured and onSaveInstanceState writes no jpeg`() {
        val front = File(app.cacheDir, "id_card_state/front.jpg")
        ActivityScenario.launch(IdCardActivity::class.java).use { scenario ->
            scenario.onActivity { it.acceptCapture(IdCardActivity.Side.FRONT, jpeg()) }
            assertTrue("the capture writes its own cache copy", waitUntil { front.isFile && front.length() > 0 })

            front.delete()
            scenario.onActivity { activity -> saveState(activity) }
            assertFalse("onSaveInstanceState must only store paths, not write JPEGs on main", front.exists())
        }
    }
}
