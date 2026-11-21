package com.scanwhirl.app.ui

import android.Manifest
import android.app.Application
import android.content.Intent
import android.os.Environment
import android.os.Looper
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.DownloadSaver
import org.junit.After
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
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowEnvironment
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * H5/H6 (found on device, 1.2.2): the Text editor's "Save .txt" wrote no file in
 * document mode and still cleared the "edited" marker; the Unsaved-changes sheet's
 * "Save .txt" closed the editor and lost the edits. Save .txt must write
 * "<doc name> - text.txt" to Documents/ScanWhirl and only then claim the save.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OcrTextFileSaveFixTextTest {

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
    }

    private fun seed(name: String, text: String): String {
        val id = DocFixtures.saved(repo, app, pages = 1, name = name)
        repo.updateOcrPage(id, "page_000.jpg", text)
        return id
    }

    private fun editorIntent(id: String, title: String): Intent =
        Intent(app, OcrEditorActivity::class.java)
            .putExtra(OcrEditorActivity.EXTRA_DOC_ID, id)
            .putExtra(OcrEditorActivity.EXTRA_TITLE, title)

    private fun awaitToast(): String {
        val deadline = System.nanoTime() + 8_000_000_000L
        while (ShadowToast.getTextOfLatestToast() == null && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        val toast = ShadowToast.getTextOfLatestToast()
        assertNotNull("save must report an outcome", toast)
        return toast!!
    }

    private fun wordCount(activity: OcrEditorActivity): String =
        activity.findViewById<TextView>(R.id.wordCount).text.toString()

    @Test
    fun `Save txt writes a utf8 file named after the document and only then clears edited`() {
        val id = seed("Invoice 0231", "total 42")
        Robolectric.buildActivity(OcrEditorActivity::class.java, editorIntent(id, "Invoice 0231"))
            .setup().use { controller ->
                val activity = controller.get()
                val edited = "caf\u00e9 total 42 corrected"
                activity.findViewById<EditText>(R.id.editText).setText(edited)
                assertTrue("the edit is marked", wordCount(activity).contains("edited", ignoreCase = true))
                ShadowToast.reset()

                activity.findViewById<android.view.View>(R.id.btnSave).performClick()
                val toast = awaitToast()

                val file = File(dir, "Invoice 0231 - text.txt")
                assertTrue(
                    "Save .txt must write the file; Documents/ScanWhirl holds ${dir.list()?.toList()}",
                    file.isFile
                )
                assertEquals(edited, file.readText(StandardCharsets.UTF_8))
                assertTrue("the confirmation names the file: $toast", toast.contains("Invoice 0231 - text.txt"))
                assertFalse(
                    "the edited marker is only cleared after the write",
                    wordCount(activity).contains("edited", ignoreCase = true)
                )
                assertEquals(
                    "the corrected text is still kept in the document",
                    edited,
                    repo.ocrDoc(id)["page_000.jpg"]
                )
            }
    }

    @Test
    fun `a failed txt write keeps the edits and the edited marker`() {
        val id = seed("Receipt", "original text")
        Robolectric.buildActivity(OcrEditorActivity::class.java, editorIntent(id, "Receipt"))
            .setup().use { controller ->
                val activity = controller.get()
                val edited = "original text corrected"
                activity.findViewById<EditText>(R.id.editText).setText(edited)
                DownloadSaver.faultAt = { stage ->
                    if (stage == DownloadSaver.Stage.CLOSING) throw IOException("disk full")
                }
                ShadowToast.reset()

                activity.findViewById<android.view.View>(R.id.btnSave).performClick()
                val toast = awaitToast()

                assertTrue("the failure is reported: $toast", toast.startsWith("Could not save text"))
                assertFalse("no .txt is claimed", File(dir, "Receipt - text.txt").exists())
                assertEquals(edited, activity.findViewById<EditText>(R.id.editText).text.toString())
                assertTrue(
                    "the edits are still unsaved",
                    wordCount(activity).contains("edited", ignoreCase = true)
                )
                assertFalse("the editor stays open", activity.isFinishing)
            }
    }

    @Test
    fun `Unsaved changes Save txt writes the file before closing the editor`() {
        val id = seed("Notes", "old")
        Robolectric.buildActivity(OcrEditorActivity::class.java, editorIntent(id, "Notes"))
            .setup().use { controller ->
                val activity = controller.get()
                val edited = "old and new"
                activity.findViewById<EditText>(R.id.editText).setText(edited)
                ShadowToast.reset()

                activity.onBackPressedDispatcher.onBackPressed()
                val sheet = ShadowDialog.getLatestDialog()
                assertNotNull("the unsaved-changes sheet opens", sheet)
                // W5: the sheet's primary keeps edits in the library; the explicit
                // "Save .txt and close" row writes the file. This test covers the file.
                val rows = sheet.findViewById<android.view.ViewGroup>(R.id.swSheetItems)
                assertEquals(
                    activity.getString(R.string.ux130_l4_save_as_txt),
                    (rows.getChildAt(0) as ViewGroup).contentDescription?.toString()
                )
                rows.getChildAt(0).performClick()
                awaitToast()

                val file = File(dir, "Notes - text.txt")
                assertTrue("the edits were saved before closing", file.isFile)
                assertEquals(edited, file.readText(StandardCharsets.UTF_8))
                assertTrue("the editor closes after the save", activity.isFinishing)
            }
    }
}
