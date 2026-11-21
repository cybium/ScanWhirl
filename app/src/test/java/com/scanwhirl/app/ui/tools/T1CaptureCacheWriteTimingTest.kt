package com.scanwhirl.app.ui.tools

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.util.ImageTools
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * T1: the cache path must be published from inside the IO block, immediately after a complete
 * (tmp + rename) write. A rotation in that window must still see a whole photo, never none/partial.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class T1CaptureCacheWriteTimingTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun saveState(activity: android.app.Activity): Bundle {
        val method = android.app.Activity::class.java
            .getDeclaredMethod("onSaveInstanceState", Bundle::class.java)
        method.isAccessible = true
        return Bundle().also { method.invoke(activity, it) }
    }

    private fun jpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(900, 560, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).drawColor(Color.WHITE)
            return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    @After
    fun tearDown() {
        ImageTools.thumbProbe = null
    }

    @Test
    fun `Passport publishes the cache path before the IO block returns to main`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        ImageTools.thumbProbe = { _, _, _, _ ->
            // decodeSampled runs after writeImageCache in the same IO block. Holding here means
            // the write finished but the main continuation has not run yet.
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
        }
        try {
            ActivityScenario.launch(PassportActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val file = File(app.cacheDir, "t1_timing_pick.jpg")
                    file.writeBytes(jpeg())
                    val method = PassportActivity::class.java.getDeclaredMethod("load", Uri::class.java)
                    method.isAccessible = true
                    method.invoke(activity, Uri.fromFile(file))
                }
                assertTrue("the IO write reached the probe", entered.await(10, TimeUnit.SECONDS))
                var savedPath: String? = null
                scenario.onActivity { activity ->
                    val bundle = saveState(activity)
                    savedPath = bundle.keySet().mapNotNull { bundle.getString(it) }
                        .firstOrNull { it.contains("t1_passport") }
                }
                assertNotNull("the path must be in saved state even before main resumes", savedPath)
                val photo = File(savedPath!!)
                assertTrue("the whole photo is at the final path", photo.isFile && photo.length() > 0)
                release.countDown()
                idle()
            }
        } finally {
            release.countDown()
            ImageTools.thumbProbe = null
        }
    }
}
