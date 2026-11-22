package com.scanwhirl.app.form1

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.ui.tools.ToolResultActivity
import com.scanwhirl.app.ui.tools.form.FormReadyActivity
import com.scanwhirl.app.util.PrivacySweep
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * SECURITY_JUDGE R2 N3 (MEDIUM): FORM1 plaintext caches need a clear owner,
 * real-finish cleanup and an age-bounded crash sweep.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class Sec2Form1CacheTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        File(app.cacheDir, "form1_pick").deleteRecursively()
        File(app.cacheDir, "form1_out").deleteRecursively()
        PrivacySweep.resetForTests()
    }

    @After
    fun tearDown() {
        PrivacySweep.resetForTests()
        File(app.cacheDir, "form1_pick").deleteRecursively()
        File(app.cacheDir, "form1_out").deleteRecursively()
    }

    private fun cache(relative: String, ageMs: Long = 0L): File = File(app.cacheDir, relative).apply {
        parentFile?.mkdirs()
        writeBytes(byteArrayOf(1, 2, 3))
        setLastModified(System.currentTimeMillis() - ageMs)
    }

    @Test(timeout = 30_000)
    fun `both form1 caches are age-gated capture caches`() {
        assertTrue("picked photos are swept by age", "form1_pick" in PrivacySweep.CAPTURE_CACHE_DIRS)
        assertTrue("generated outputs are swept by age", "form1_out" in PrivacySweep.CAPTURE_CACHE_DIRS)
        val old = PrivacySweep.CAPTURE_CACHE_MAX_AGE_MS + 60_000L
        val recentPick = cache("form1_pick/photo.jpg")
        val oldPick = cache("form1_pick/old.jpg", old)
        val recentOut = cache("form1_out/recent.jpg")
        val oldOut = cache("form1_out/old.jpg", old)

        PrivacySweep.run(app)

        assertTrue("restorable picked work within 24h survives", recentPick.exists())
        assertFalse(oldPick.exists())
        assertTrue("restorable output within 24h survives", recentOut.exists())
        assertFalse(oldOut.exists())
    }

    @Test(timeout = 30_000)
    fun `a real finish deletes the form1 caches that were not handed off`() {
        val controller = Robolectric.buildActivity(FormReadyActivity::class.java).setup()
        val pick = cache("form1_pick/photo.jpg")
        val out = cache("form1_out/form_ready.jpg")
        var destroyed = false
        try {
            controller.get().finish()
            controller.destroy()
            destroyed = true
            assertFalse("picked plaintext is gone on a real finish", pick.exists())
            assertFalse("an un-handed-off output is gone", out.exists())
        } finally {
            if (!destroyed) controller.destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `rotation keeps restorable form1 work`() {
        val controller = Robolectric.buildActivity(FormReadyActivity::class.java).setup()
        val pick = cache("form1_pick/photo.jpg")
        val out = cache("form1_out/form_ready.jpg")
        try {
            controller.recreate()
            assertTrue("rotation must not delete the picked photo", pick.exists())
            assertTrue("rotation must not delete the generated output", out.exists())
        } finally {
            controller.destroy()
        }
    }

    @Test(timeout = 30_000)
    fun `the result screen owns a handed-off form1 output`() {
        val output = cache("form1_out/form_ready_1.jpg")
        val intent = ToolResultActivity.intent(
            app, "form1", files = listOf(output), mime = "image/jpeg"
        )
        val controller = Robolectric.buildActivity(ToolResultActivity::class.java, intent).setup()
        var destroyed = false
        try {
            controller.get().finish()
            controller.destroy()
            destroyed = true
            assertFalse("the result screen deletes its form1 cache output on a real finish", output.exists())
        } finally {
            if (!destroyed) controller.destroy()
        }
    }
}
