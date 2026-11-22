package com.scanwhirl.app.ui.tools
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.Looper
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.util.PrivacySweep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * T1: PrivacySweep runs in ScanWhirlApp.onCreate on every process start, including a process-death
 * restore. A fresh capture cache must survive that sweep so IdCard/Passport/Business card can
 * restore the work; only abandoned entries older than a day are reclaimed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class T1CaptureSweepAgeTest {


    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Before
    fun setUp() {
        app.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun jpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).drawColor(Color.WHITE)
            return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun write(relative: String): File = File(app.cacheDir, relative).apply {
        parentFile?.mkdirs()
        writeBytes(jpeg())
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
    fun `fresh capture caches in all three dirs survive the cold-start sweep`() {
        val fresh = listOf(
            write("t1_passport/photo.jpg"),
            write("t1_business_card/card.jpg"),
            write("id_card_state/front.jpg")
        )

        PrivacySweep.run(app)

        fresh.forEach { assertTrue("a fresh capture was swept: ${it.path}", it.isFile) }
    }

    @Test
    fun `a fresh IdCard capture survives the sweep and restores after process death`() {
        val front = write("id_card_state/front.jpg")
        val saved = Bundle().apply {
            putBoolean("id_card_has_state", true)
            putInt("id_card_pending_side", 0)
            putString("t1_id_front_path", front.absolutePath)
        }

        // A new process starts: ScanWhirlApp.onCreate runs the sweep before any screen is shown.
        PrivacySweep.run(app)
        assertTrue("the fresh capture must still be on disk", front.isFile)

        val controller = Robolectric.buildActivity(
            IdCardActivity::class.java, IdCardActivity.intent(app)
        ).setup(saved)
        idle()
        assertEquals(
            "the restored flow continues at the back step, not a fresh front",
            app.getString(R.string.tools_id_back_title),
            ShadowDialog.getLatestDialog()?.findViewById<TextView>(R.id.swSheetTitle)?.text.toString()
        )
        controller.pause().stop().destroy()
    }

    @Test
    fun `a fresh Passport capture survives the sweep and restores after process death`() {
        val photo = write("t1_passport/photo.jpg")
        val saved = Bundle().apply { putString("t1_passport_image_path", photo.absolutePath) }

        PrivacySweep.run(app)
        assertTrue("the fresh photo must still be on disk", photo.isFile)

        val controller = Robolectric.buildActivity(
            PassportActivity::class.java, PassportActivity.intent(app)
        ).setup(saved)
        assertTrue("the photo is restored into the crop surface", waitUntil {
            controller.get().loadedImageSizeForTest() != (0 to 0)
        })
        assertEquals(120, controller.get().loadedImageSizeForTest().first)
        controller.pause().stop().destroy()
    }

    @Test
    fun `a capture cache entry older than a day is swept`() {
        val old = System.currentTimeMillis() - 25L * 60 * 60 * 1000
        val stale = write("id_card_state/front.jpg")
        stale.setLastModified(old)
        stale.parentFile!!.setLastModified(old)

        PrivacySweep.run(app)

        assertFalse("an abandoned capture must be reclaimed", stale.exists())
    }
}
