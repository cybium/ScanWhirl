package com.scanwhirl.app.ui.shell
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Application
import android.net.Uri
import android.os.Looper
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.PendingScans
import com.scanwhirl.app.util.ImageTools
import com.scanwhirl.app.util.ImportFixtures
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.ProGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.io.File

/**
 * H2-5: the live Import PDF route (ShellActivity, not the legacy home) must say why a
 * PDF could not be imported in the person's language. The library's raw English
 * exception text ("The file is empty") must never reach the sheet.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ImportFailureWordingTest {


    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        ProGate.setTestOverride(app, null)
        Prefs.setIndexBackfillDone(app, true)
        Prefs.setShowGreeting(app, false)
    }

    private fun uriWith(bytes: ByteArray, name: String): Uri {
        val uri = Uri.parse("content://import_wording/${name}_${System.nanoTime()}.pdf")
        shadowOf(app.contentResolver).registerInputStream(uri, bytes.inputStream())
        return uri
    }

    /** The message the import-failure sheet shows, waiting for the IO coroutine to finish. */
    private fun importFailureMessage(activity: ShellActivity, uri: Uri): String {
        activity.importPdf(uri)
        val deadline = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = ShadowDialog.getLatestDialog()
            val message = dialog?.findViewById<TextView>(R.id.swSheetMessage)
            if (message != null && message.text.isNotEmpty()) return message.text.toString()
            Thread.sleep(20)
        }
        assertNotNull("the import failure sheet never appeared", null)
        error("unreachable")
    }

    @Test
    fun `an empty PDF names the localized empty reason`() {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val message = importFailureMessage(activity, uriWith(ByteArray(0), "empty"))
                assertEquals(activity.getString(R.string.import_failed_empty), message)
                assertFalse("no raw library text", message.contains("The file is empty"))
            }
        }
    }

    @Test
    fun `a file that is not a PDF names the localized damaged reason`() {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val message = importFailureMessage(
                    activity,
                    uriWith("This is a text file renamed to .pdf".toByteArray(), "text")
                )
                assertEquals(activity.getString(R.string.import_failed_damaged), message)
                assertFalse("no raw library text", message.contains("This file is not a PDF"))
            }
        }
    }

    /** H2-5: an unexpected exception still gets a reviewed sentence, never its JVM text. */
    @Test
    fun `an unknown failure falls back to the localized save reason`() {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val message = activity.importFailureMessage(
                    java.io.IOException("java.io.FileNotFoundException: /storage/secret.pdf")
                )
                assertEquals(activity.getString(R.string.import_failed_save), message)
                assertFalse("no raw system text", message.contains("/storage"))
                assertFalse("no raw system text", message.contains("FileNotFound"))
            }
        }
    }

    /**
     * H2-8: a gallery photo that cannot fit the phone's memory is refused with an honest
     * localized sentence, and the staging folder it started is gone (no partial scan).
     */
    @Test
    fun `a photo too large to decode is refused honestly and leaves nothing behind`() {
        val uri = uriWith(ImportFixtures.bmp(40, 30), "huge.heic")
        ImageTools.galleryDecode = { _, _ -> throw OutOfMemoryError("forced for the test") }
        try {
            ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    ShadowToast.reset()
                    activity.importPhotos(listOf(uri))
                    val deadline = System.currentTimeMillis() + 8000
                    while (ShadowToast.getTextOfLatestToast() == null &&
                        System.currentTimeMillis() < deadline
                    ) {
                        shadowOf(Looper.getMainLooper()).idle()
                        if (ShadowToast.getTextOfLatestToast() == null) Thread.sleep(20)
                    }
                    assertEquals(
                        activity.getString(
                            R.string.stage_failed,
                            activity.getString(R.string.f8_stage_page_too_large, 1)
                        ),
                        ShadowToast.getTextOfLatestToast()
                    )
                    assertFalse(
                        "no raw exception text",
                        ShadowToast.getTextOfLatestToast().contains("OutOfMemory")
                    )
                    assertFalse(
                        "the staging folder was removed",
                        File(activity.filesDir, PendingScans.ROOT).listFiles().orEmpty().any { it.isDirectory }
                    )
                    assertTrue("no unfinished scan is listed", PendingScans.all(activity).isEmpty())
                }
            }
        } finally {
            ImageTools.galleryDecode = null
        }
    }
}

/**
 * The same route in Hindi: the sheet must show the reviewed values-hi sentence, never
 * an English one or a raw system message.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "hi")
class ImportFailureWordingHindiTest {


    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        ProGate.setTestOverride(app, null)
        Prefs.setIndexBackfillDone(app, true)
        Prefs.setShowGreeting(app, false)
    }

    @Test
    fun `an empty PDF is refused in Hindi`() {
        val uri = Uri.parse("content://import_wording/empty_${System.nanoTime()}.pdf")
        shadowOf(app.contentResolver).registerInputStream(uri, ByteArray(0).inputStream())
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val hindi = activity.getString(R.string.import_failed_empty)
                assertNotEquals(
                    "the Hindi resource really is active",
                    "This PDF has no pages, so nothing was imported.",
                    hindi
                )
                activity.importPdf(uri)
                val deadline = System.currentTimeMillis() + 8000
                var shown: String? = null
                while (shown == null && System.currentTimeMillis() < deadline) {
                    shadowOf(Looper.getMainLooper()).idle()
                    shown = ShadowDialog.getLatestDialog()
                        ?.findViewById<TextView>(R.id.swSheetMessage)
                        ?.text?.toString()?.takeIf { it.isNotEmpty() }
                    if (shown == null) Thread.sleep(20)
                }
                assertEquals(hindi, shown)
                assertFalse("English never appears", shown!!.contains("The file is empty"))
                assertFalse("English never appears", shown.contains("no pages, so nothing was imported"))
            }
        }
    }
}
