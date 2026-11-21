package com.scanwhirl.app.util

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast
import java.io.File

/**
 * Lane P1 defect 1: ExportResultActivity's ACTION_SEND carried EXTRA_STREAM but no
 * ClipData. On Android 12+ the sharesheet reads the read grant from ClipData, so
 * a single image could not be previewed/opened by the target app. The raw JVM
 * exception was also printed in a toast; that is never user-facing text.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class P1ShareTest {

    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before fun resetFileProviderCache() {
        val f = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
        f.isAccessible = true
        (f.get(null) as MutableMap<*, *>).clear()
    }

    private fun activityFor(file: File): com.scanwhirl.app.ui.ExportResultActivity {
        val intent = com.scanwhirl.app.ui.ExportResultActivity.intent(
            app, file.path, file.path, "image/jpeg", file.name, 2048L, 1024L, 120, 80, "Resize", null
        )
        return Robolectric.buildActivity(com.scanwhirl.app.ui.ExportResultActivity::class.java, intent)
            .setup().get()
    }

    private fun clickShare(activity: android.app.Activity) {
        activity.findViewById<android.view.View>(com.scanwhirl.app.R.id.btnShareResult).performClick()
    }

    @Test fun `export share carries ClipData with the same uri mime and read grant`() {
        val dir = File(app.filesDir, "exports").apply { mkdirs() }
        val file = File(dir, "p1-share.jpg").apply { writeBytes(ImportFixtures.image()) }
        val activity = activityFor(file)

        clickShare(activity)
        val chooser = shadowOf(activity).nextStartedActivity
        assertNotNull("share must start a chooser", chooser)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        @Suppress("DEPRECATION")
        val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!

        val clip: ClipData = send.clipData
            ?: throw AssertionError("share must carry ClipData for the Android 12+ sharesheet")
        assertEquals(1, clip.itemCount)
        assertEquals("ClipData must carry the very same URI", uri, clip.getItemAt(0).uri)
        assertTrue("ClipData must advertise the MIME", clip.description.hasMimeType("image/jpeg"))
        assertTrue("share needs a read grant", send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals("no write grant may be given", 0, send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    @Test fun `a share failure never shows the raw exception message`() {
        // A file outside every FileProvider root makes getUriForFile throw. The
        // sentinel in its path must never reach a Toast.
        val dir = File(app.cacheDir, "p1_outside_root").apply { mkdirs() }
        val file = File(dir, "SW_RAW_SENTINEL.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val activity = activityFor(file)

        clickShare(activity)
        shadowOf(Looper.getMainLooper()).idle()
        val text = ShadowToast.getTextOfLatestToast().orEmpty()
        assertFalse("raw JVM text leaked to the user: $text", text.contains("SW_RAW_SENTINEL"))
        assertTrue("a localized share error must be shown instead", text.isNotBlank())
    }
}
