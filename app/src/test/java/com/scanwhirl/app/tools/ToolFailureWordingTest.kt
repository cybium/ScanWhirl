package com.scanwhirl.app.tools

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.kit.SwProgress
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.util.ProGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast
import java.io.IOException
import java.io.InputStream

/**
 * H2-5: tool failures keep their localized prefix and add a localized reason. The raw
 * system/JVM message of the exception is never what the person reads.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ToolFailureWordingTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        ProGate.setTestOverride(app, null)
    }

    /** Only the failure paths below run, so the host is never asked for anything. */
    private object NoHost : HomeHost {
        override fun refreshList() = Unit
        override fun showProgress(titleRes: Int): SwProgress = error("not used")
        override fun toast(res: Int, duration: Int) = Unit
        override fun toast(message: CharSequence, duration: Int) = Unit
    }

    /**
     * P1: the picked image is copied off the main thread, so the failure toast is
     * posted after the IO task resumes. Wait for it instead of asserting on the
     * very next main-looper pass; the wording assertions below are unchanged.
     */
    private fun awaitToast(): String? {
        val deadline = System.currentTimeMillis() + 5000L
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            ShadowToast.getTextOfLatestToast()?.let { return it }
            Thread.sleep(20L)
        }
        return ShadowToast.getTextOfLatestToast()
    }

    @Test
    fun `resize names an unreadable file in the person's language`() {
        val uri = Uri.parse("content://tool_wording/unreadable_${System.nanoTime()}.jpg")
        shadowOf(app.contentResolver).registerInputStream(uri, object : InputStream() {
            override fun read(): Int = throw IOException("java.io.FileNotFoundException: /secret/path")
        })
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                ShadowToast.reset()
                ResizeFlow(activity, ScanRepository(activity), NoHost).start(uri)
                val shown = awaitToast()
                assertEquals(
                    activity.getString(
                        R.string.resize_failed, activity.getString(R.string.f8_reason_unreadable)
                    ),
                    shown
                )
                assertFalse("no raw system text", shown.orEmpty().contains("/secret"))
                assertFalse("no raw system text", shown.orEmpty().contains("FileNotFound"))
            }
        }
    }

    @Test
    fun `resize names a file that is not an image`() {
        val uri = Uri.parse("content://tool_wording/text_${System.nanoTime()}.jpg")
        shadowOf(app.contentResolver).registerInputStream(
            uri, "not a picture at all".toByteArray().inputStream()
        )
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                ShadowToast.reset()
                ResizeFlow(activity, ScanRepository(activity), NoHost).start(uri)
                assertEquals(
                    activity.getString(
                        R.string.resize_failed, activity.getString(R.string.f8_reason_not_image)
                    ),
                    awaitToast()
                )
            }
        }
    }
}
