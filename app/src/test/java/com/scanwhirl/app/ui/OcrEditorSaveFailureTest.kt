package com.scanwhirl.app.ui
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.Durable
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OcrEditorSaveFailureTest {

    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repo = ScanRepository(context)
    private val id = "editor_failure_${System.nanoTime()}"
    private val dir = File(repo.docsDir, id).apply { mkdirs() }

    @After fun cleanup() { Durable.resetForTests(); dir.deleteRecursively() }

    private fun awaitToast() {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (ShadowToast.getTextOfLatestToast() == null && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertNotNull("save must report an outcome", ShadowToast.getTextOfLatestToast())
    }

    @Test fun `Save failure retains edits and back-dialog Save stays open until successful retry`() {
        repo.updateOcrPage(id, "page_000.jpg", "original")
        val intent = Intent(context, OcrEditorActivity::class.java).putExtra(OcrEditorActivity.EXTRA_DOC_ID, id)
        Robolectric.buildActivity(OcrEditorActivity::class.java, intent).setup().use { controller ->
            val activity = controller.get()
            val editor = activity.findViewById<EditText>(R.id.editText)
            editor.setText("corrected")
            Durable.beforeFileSync = { if (it.name == "ocr.txt.tmp") throw IOException("injected write failure") }
            ShadowToast.reset()
            activity.findViewById<android.view.View>(R.id.btnSave).performClick()
            awaitToast()
            assertNotEquals(activity.getString(R.string.ocr_saved), ShadowToast.getTextOfLatestToast())
            assertEquals("original", repo.ocrDoc(id)["page_000.jpg"])
            assertEquals("corrected", editor.text.toString())
            assertTrue(activity.findViewById<TextView>(R.id.wordCount).text.contains("edited", ignoreCase = true))

            activity.onBackPressedDispatcher.onBackPressed()
            // v2 UI: 08_text_ocr — the back confirm is a SwSheet; its primary stays
            // open until the save really lands.
            val dialog = ShadowDialog.getLatestDialog()
            ShadowToast.reset()
            dialog.findViewById<android.view.View>(R.id.swSheetPrimary).performClick()
            awaitToast()
            assertFalse(activity.isFinishing)

            dialog.dismiss()
            Durable.resetForTests()
            ShadowToast.reset()
            activity.onBackPressedDispatcher.onBackPressed()
            ShadowDialog.getLatestDialog()
                .findViewById<android.view.View>(R.id.swSheetPrimary).performClick()
            awaitToast()
            assertEquals("corrected", repo.ocrDoc(id)["page_000.jpg"])
            assertTrue(activity.isFinishing)
        }
    }

    @Test fun `missing document is not reported as a successful Save`() {
        repo.updateOcrPage(id, "page_000.jpg", "original")
        val intent = Intent(context, OcrEditorActivity::class.java).putExtra(OcrEditorActivity.EXTRA_DOC_ID, id)
        Robolectric.buildActivity(OcrEditorActivity::class.java, intent).setup().use { controller ->
            val activity = controller.get()
            activity.findViewById<EditText>(R.id.editText).setText("correction")
            dir.deleteRecursively()
            ShadowToast.reset()
            activity.findViewById<android.view.View>(R.id.btnSave).performClick()
            awaitToast()
            assertNotEquals(activity.getString(R.string.ocr_saved), ShadowToast.getTextOfLatestToast())
            assertFalse(dir.exists())
            assertFalse(activity.isFinishing)
        }
    }
}
