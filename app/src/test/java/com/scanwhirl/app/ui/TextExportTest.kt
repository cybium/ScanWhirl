package com.scanwhirl.app.ui

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.ProviderInfo
import android.os.Environment
import android.os.Looper
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.util.DownloadSaver
import com.scanwhirl.app.util.FakeMediaStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowEnvironment
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.io.IOException

/**
 * M06 P0, Save Text: the OCR editor's `.txt` export uses the same transaction
 * as PDFs. A failure is reported and never crashes the editor or claims
 * "Saved". The edited text stays, and an earlier .txt is never overwritten.
 */
@RunWith(RobolectricTestRunner::class)
class TextExportTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        DownloadSaver.resetForTests()
    }

    private fun tempEditor(text: String): Intent {
        val f = File(app.cacheDir, "ocr_test_${System.nanoTime()}.txt").apply { writeText(text) }
        return Intent(app, OcrEditorActivity::class.java)
            .putExtra(OcrEditorActivity.EXTRA_TEXT_PATH, f.absolutePath)
            .putExtra(OcrEditorActivity.EXTRA_TITLE, "Receipt")
    }

    private fun awaitToast(): String {
        val deadline = System.nanoTime() + 8_000_000_000L
        while (ShadowToast.getTextOfLatestToast() == null && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        val t = ShadowToast.getTextOfLatestToast()
        assertNotNull("save must report an outcome", t)
        return t!!
    }

    @Test
    @Config(sdk = [28])
    fun `pre-Q text save failure keeps edits and the earlier txt`() {
        ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED)
        shadowOf(app).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "ScanWhirl")
        dir.deleteRecursively()
        dir.mkdirs()
        File(dir, "Receipt.txt").writeText("earlier export")

        Robolectric.buildActivity(OcrEditorActivity::class.java, tempEditor("total 42")).setup().use { c ->
            val activity = c.get()
            activity.findViewById<EditText>(R.id.editText).setText("total 42 corrected")
            DownloadSaver.faultAt = { if (it == DownloadSaver.Stage.CLOSING) throw IOException("disk full") }
            ShadowToast.reset()
            activity.findViewById<android.view.View>(R.id.btnSave).performClick()
            val toast = awaitToast()
            assertTrue("failure is reported: $toast", toast.startsWith("Could not save text"))
            assertFalse(activity.isFinishing)
            assertEquals("total 42 corrected", activity.findViewById<EditText>(R.id.editText).text.toString())
            assertEquals("earlier export", File(dir, "Receipt.txt").readText())
            assertEquals(listOf("Receipt.txt"), dir.list()!!.filter { !it.startsWith(".") })

            DownloadSaver.resetForTests()
            ShadowToast.reset()
            activity.findViewById<android.view.View>(R.id.btnSave).performClick()
            val ok = awaitToast()
            assertTrue("a new copy, earlier kept: $ok", ok.contains("earlier file was kept"))
            assertEquals("earlier export", File(dir, "Receipt.txt").readText())
            assertEquals("total 42 corrected", File(dir, "Receipt (2).txt").readText())
        }
    }

    @Test
    @Config(sdk = [35])
    fun `Q text save reports the real file and identical text is not duplicated`() {
        val media = FakeMediaStore().also { p ->
            p.attachInfo(app, ProviderInfo().apply { authority = "media"; exported = true })
            ShadowContentResolver.registerProviderInternal("media", p)
        }
        try {
            Robolectric.buildActivity(OcrEditorActivity::class.java, tempEditor("hello")).setup().use { c ->
                val activity = c.get()
                activity.findViewById<EditText>(R.id.editText).setText("hello world")
                ShadowToast.reset()
                activity.findViewById<android.view.View>(R.id.btnSave).performClick()
                assertEquals("Text saved to Documents/ScanWhirl: Receipt.txt", awaitToast())
                ShadowToast.reset()
                activity.findViewById<android.view.View>(R.id.btnSave).performClick()
                assertTrue(awaitToast().startsWith("Already saved"))
                assertEquals(1, media.scanwhirlPdfs().size)
            }
        } finally {
            media.close()
        }
    }
}
