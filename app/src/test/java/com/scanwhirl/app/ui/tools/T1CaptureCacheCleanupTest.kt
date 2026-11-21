package com.scanwhirl.app.ui.tools

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * T1 privacy cleanup: the plaintext card photos are deleted when the screen actually closes, but
 * survive a rotation so the restored screen can still use them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class T1CaptureCacheCleanupTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private val cardText = "Rahul Sharma\nAcme Technologies\nM: +91 98765 43210\n"

    private fun jpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(900, 560, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).drawColor(Color.WHITE)
            return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun loadPassport(activity: PassportActivity, bytes: ByteArray) {
        val file = File(app.cacheDir, "t1_cleanup_pick.jpg")
        file.writeBytes(bytes)
        val method = PassportActivity::class.java.getDeclaredMethod("load", Uri::class.java)
        method.isAccessible = true
        method.invoke(activity, Uri.fromFile(file))
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

    @Before
    fun setUp() {
        BusinessCardActivity.recognizeForTest = { cardText }
    }

    @After
    fun tearDown() {
        BusinessCardActivity.recognizeForTest = null
    }

    @Test
    fun `finishing Passport deletes the cache photo`() {
        val dir = File(app.cacheDir, "t1_passport")
        ActivityScenario.launch(PassportActivity::class.java).use { scenario ->
            scenario.onActivity { loadPassport(it, jpeg()) }
            assertTrue(waitUntil { dir.resolve("photo.jpg").isFile })
            scenario.onActivity { it.finish() }
        }
        assertFalse("the plaintext photo must not survive a finished screen", dir.exists())
    }

    @Test
    fun `rotation keeps the Passport cache photo`() {
        val photo = File(app.cacheDir, "t1_passport/photo.jpg")
        ActivityScenario.launch(PassportActivity::class.java).use { scenario ->
            scenario.onActivity { loadPassport(it, jpeg()) }
            assertTrue(waitUntil { photo.isFile })
            scenario.recreate()
            idle()
            assertTrue("rotation must keep the photo for the restored screen", photo.isFile)
        }
    }

    @Test
    fun `finishing Business card deletes the cache card`() {
        val dir = File(app.cacheDir, "t1_business_card")
        ActivityScenario.launch(BusinessCardActivity::class.java).use { scenario ->
            scenario.onActivity { it.acceptCapture(jpeg()) }
            assertTrue("the form appears after OCR", waitUntil {
                var ready = false
                scenario.onActivity { ready = it.findViewById<EditText>(R.id.bcName) != null }
                ready
            })
            assertTrue(dir.resolve("card.jpg").isFile)
            scenario.onActivity { it.finish() }
        }
        assertFalse("the plaintext card must not survive a finished screen", dir.exists())
    }

    @Test
    fun `rotation keeps the Business card cache card`() {
        val card = File(app.cacheDir, "t1_business_card/card.jpg")
        ActivityScenario.launch(BusinessCardActivity::class.java).use { scenario ->
            scenario.onActivity { it.acceptCapture(jpeg()) }
            assertTrue(waitUntil { card.isFile })
            scenario.recreate()
            idle()
            assertTrue("rotation must keep the card for the restored screen", card.isFile)
        }
    }
}
