package com.scanwhirl.app.ui

import android.Manifest
import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.chip.ChipGroup
import com.google.mlkit.common.MlKitException
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.DownloadSaver
import com.scanwhirl.app.util.OcrEngine
import com.scanwhirl.app.util.OcrLanguage
import com.scanwhirl.app.util.OcrLanguageSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowEnvironment
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * W5 (winner spec lane 5): the Text screen opens read-first. The page's text is
 * readable and selectable, a clear "Page n of m" indicator is shown, editing is an
 * explicit mode, "Copy page" and "Copy all pages" are separate actions, "Save edits"
 * keeps the library text only, "Save .txt" writes a file, a failed .txt write keeps
 * the screen open with Retry and says the library text is kept, and re-reading asks
 * before it replaces edits.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OcrReadFirstTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val repo = ScanRepository(app)

    @Suppress("DEPRECATION")
    private val dir = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
        DownloadSaver.SUBDIR
    )

    @Before
    fun setUp() {
        ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED)
        shadowOf(app).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        dir.deleteRecursively()
        dir.mkdirs()
        DownloadSaver.resetForTests()
    }

    @After
    fun tearDown() {
        DownloadSaver.resetForTests()
        OcrEngine.rawReader = null
    }

    private fun seed(name: String, vararg texts: String): String {
        val id = DocFixtures.saved(repo, app, pages = texts.size, name = name)
        texts.forEachIndexed { i, text ->
            repo.updateOcrPage(id, "page_%03d.jpg".format(i), text)
        }
        return id
    }

    private fun intent(id: String, title: String): Intent =
        Intent(app, OcrEditorActivity::class.java)
            .putExtra(OcrEditorActivity.EXTRA_DOC_ID, id)
            .putExtra(OcrEditorActivity.EXTRA_TITLE, title)

    private fun launch(id: String, title: String, block: (OcrEditorActivity) -> Unit) {
        Robolectric.buildActivity(OcrEditorActivity::class.java, intent(id, title))
            .setup().use { controller ->
                shadowOf(Looper.getMainLooper()).idle()
                block(controller.get())
            }
    }

    private fun idleUntil(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + 8_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    private fun clipboardText(): String? {
        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return cm.primaryClip?.getItemAt(0)?.text?.toString()
    }

    private fun chipLabel(activity: OcrEditorActivity, chipId: Int): String =
        activity.findViewById<View>(chipId)
            .findViewById<TextView>(R.id.swChipLabel)
            .text.toString()

    @Test
    fun `text opens read only with a page indicator and explicit edit action`() {
        val id = seed("Read first", "पहला पन्ना\nsecond line", "दूसरा पन्ना")
        launch(id, "Read first") { activity ->
            val edit = activity.findViewById<EditText>(R.id.editText)
            assertTrue("recognised text is shown", edit.text.toString().contains("पहला पन्ना"))
            assertTrue("the text can be selected", edit.isTextSelectable)
            assertNull("read mode has no typing key listener", edit.keyListener)

            val indicator = activity.findViewById<TextView>(R.id.ocrPageIndicator)
            assertEquals("Page 1 of 2", indicator.text.toString())

            assertEquals("Copy page", chipLabel(activity, R.id.btnCopy))
            assertTrue(activity.findViewById<View>(R.id.btnCopyAll).isVisible)
            assertTrue(activity.findViewById<View>(R.id.btnEditText).isVisible)
            assertFalse("Save edits belongs to edit mode", activity.findViewById<View>(R.id.btnSaveEdits).isVisible)

            val tabs = activity.findViewById<ChipGroup>(R.id.pageTabs)
            tabs.getChildAt(1).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Page 2 of 2", indicator.text.toString())
        }
    }

    @Test
    fun `Edit text is an explicit mode and Save edits appears only there`() {
        val id = seed("Edit mode", "one")
        launch(id, "Edit mode") { activity ->
            val edit = activity.findViewById<EditText>(R.id.editText)
            val toggle = activity.findViewById<TextView>(R.id.btnEditText)
            val saveEdits = activity.findViewById<View>(R.id.btnSaveEdits)

            toggle.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertNotNull("edit mode installs a typing listener", edit.keyListener)
            assertFalse("selection-only mode is off while editing", edit.isTextSelectable)
            assertTrue(saveEdits.isVisible)
            assertFalse("Copy all pages is a read-mode action", activity.findViewById<View>(R.id.btnCopyAll).isVisible)
            assertEquals(activity.getString(R.string.ocr_w5_done_editing), toggle.text.toString())

            toggle.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertNull("Done returns to read-only text", edit.keyListener)
            assertFalse(saveEdits.isVisible)
            assertEquals(activity.getString(R.string.ocr_w5_edit_text), toggle.text.toString())
        }
    }

    @Test
    fun `Copy page copies this page and Copy all pages copies every page with Hindi intact`() {
        val hindiPageOne = "कुल राशि ₹१२३.४५"
        val hindiPageTwo = "नमस्ते दुनिया"
        val id = seed("Copy both", hindiPageOne, hindiPageTwo)
        launch(id, "Copy both") { activity ->
            activity.findViewById<View>(R.id.btnCopy).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Copy page copies the current page only", hindiPageOne, clipboardText())

            activity.findViewById<View>(R.id.btnCopyAll).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(
                "Copy all pages keeps both pages, the headings and the Devanagari text",
                "Page 1\n$hindiPageOne\n\nPage 2\n$hindiPageTwo",
                clipboardText()
            )
        }
    }

    @Test
    fun `Save edits keeps the library text and writes no txt file`() {
        val id = seed("Library only", "original")
        launch(id, "Library only") { activity ->
            activity.findViewById<View>(R.id.btnEditText).performClick()
            activity.findViewById<EditText>(R.id.editText).setText("corrected café")
            activity.findViewById<View>(R.id.btnSaveEdits).performClick()

            assertTrue(idleUntil { repo.ocrDoc(id)["page_000.jpg"] == "corrected café" })
            assertTrue("the edited marker clears after the library save", idleUntil {
                !activity.findViewById<TextView>(R.id.wordCount).text
                    .contains("edited", ignoreCase = true)
            })
            assertFalse("Save edits is not a file export", File(dir, "Library only - text.txt").exists())
            assertFalse("the screen stays open", activity.isFinishing)
        }
    }

    @Test
    fun `a failed txt write keeps the screen with Retry and says the text is in the document`() {
        val id = seed("Retry file", "old text")
        launch(id, "Retry file") { activity ->
            activity.findViewById<View>(R.id.btnEditText).performClick()
            activity.findViewById<EditText>(R.id.editText).setText("old text corrected")
            DownloadSaver.faultAt = { stage ->
                if (stage == DownloadSaver.Stage.CLOSING) throw IOException("disk full")
            }
            ShadowToast.reset()
            activity.findViewById<View>(R.id.btnSave).performClick()

            assertTrue("the failure is reported", idleUntil {
                ShadowToast.getTextOfLatestToast()?.startsWith("Could not save text") == true
            })
            assertFalse("a failed .txt write never closes the screen", activity.isFinishing)
            assertFalse(File(dir, "Retry file - text.txt").exists())

            val banner = activity.findViewById<View>(R.id.ocrTxtFailure)
            assertTrue("the failure stays on screen", banner.isVisible)
            assertEquals(
                activity.getString(R.string.ocr_w5_txt_failed_kept),
                activity.findViewById<TextView>(R.id.ocrTxtFailureText).text.toString()
            )
            assertTrue(
                "the library text is already stored",
                repo.ocrDoc(id)["page_000.jpg"] == "old text corrected"
            )

            DownloadSaver.resetForTests()
            activity.findViewById<View>(R.id.btnTxtRetry).performClick()
            assertTrue("Retry writes the .txt", idleUntil {
                File(dir, "Retry file - text.txt").isFile
            })
            assertEquals(
                "old text corrected",
                File(dir, "Retry file - text.txt").readText(StandardCharsets.UTF_8)
            )
            assertFalse("the banner clears after a successful Retry", banner.isVisible)
        }
    }

    @Test
    fun `re-reading asks first and Cancel keeps the corrections`() {
        val id = seed("Ask first", "saved café correction")
        val beforeLanguage = OcrLanguageSettings.get(app)
        launch(id, "Ask first") { activity ->
            activity.findViewById<View>(R.id.btnEditText).performClick()
            val edit = activity.findViewById<EditText>(R.id.editText)
            edit.setText("my correction")

            activity.findViewById<View>(R.id.ocrLangChip).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val languageSheet = ShadowDialog.getLatestDialog()
            assertNotNull("the language sheet opens", languageSheet)
            (languageSheet.findViewById<android.view.ViewGroup>(R.id.swSheetItems)
                .getChildAt(2) as View).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val sheet = ShadowDialog.getLatestDialog()
            assertNotNull("changing the text language asks before it reads again", sheet)
            assertEquals(
                activity.getString(R.string.ocr_w5_reread_title),
                sheet.findViewById<TextView>(R.id.swSheetTitle).text.toString()
            )
            assertEquals("nothing was replaced yet", "my correction", edit.text.toString())

            sheet.findViewById<View>(R.id.swSheetSecondary).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Cancel keeps the edit", "my correction", edit.text.toString())
            assertEquals("Cancel keeps saved corrections", "saved café correction", repo.ocrDoc(id)["page_000.jpg"])
            assertEquals("Cancel keeps the recognition language", beforeLanguage, OcrLanguageSettings.get(app))
            assertFalse("the editor stays open", activity.isFinishing)
        }
    }

    @Test
    fun `confirmed Hindi reread reports an unavailable model without storing blank text`() {
        val id = seed("Unavailable Hindi", "old recognition")
        OcrEngine.rawReader = { _, _ ->
            throw MlKitException("Model unavailable", MlKitException.UNAVAILABLE)
        }
        launch(id, "Unavailable Hindi") { activity ->
            ShadowToast.reset()
            activity.findViewById<View>(R.id.ocrLangChip).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            ShadowDialog.getLatestDialog()
                .findViewById<android.view.ViewGroup>(R.id.swSheetItems)
                .getChildAt(2).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            ShadowDialog.getLatestDialog().findViewById<View>(R.id.swSheetPrimary).performClick()

            val expected = activity.getString(R.string.ocr_unavailable_toast)
            assertTrue("the editor explains why it could not reread", idleUntil {
                ShadowToast.getTextOfLatestToast() == expected
            })
            assertEquals(OcrLanguage.HINDI, OcrLanguageSettings.get(app))
            assertTrue("no page was stored as read", repo.ocrDoc(id).isEmpty())
            assertTrue("a future read can retry", repo.ocrStore.isPending(id))
        }
    }

    @Test
    fun `back with edits saves to the library first and keeps the text without a file`() {
        val id = seed("Back save", "start")
        launch(id, "Back save") { activity ->
            activity.findViewById<View>(R.id.btnEditText).performClick()
            activity.findViewById<EditText>(R.id.editText).setText("kept in the library")

            activity.onBackPressedDispatcher.onBackPressed()
            val sheet = ShadowDialog.getLatestDialog()
            assertNotNull("back with edits asks first", sheet)
            assertEquals(
                activity.getString(R.string.ocr_w5_save_edits),
                sheet.findViewById<TextView>(R.id.swSheetPrimary).text.toString()
            )
            sheet.findViewById<View>(R.id.swSheetPrimary).performClick()

            assertTrue("Save edits stores the text", idleUntil {
                repo.ocrDoc(id)["page_000.jpg"] == "kept in the library"
            })
            assertTrue("the screen closes after the library save", idleUntil { activity.isFinishing })
            assertFalse(File(dir, "Back save - text.txt").exists())
        }
    }
}
