package com.scanwhirl.app.ui

import android.app.Application
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * v2 UI: 08_text_ocr — Page / Text switch, the honest footer and the Pro
 * "To Word" action (Coming soon until L4's writer lands).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class OcrTextScreenTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
    }

    private fun launch(block: (OcrEditorActivity) -> Unit) {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Invoice 0231")
        DocFixtures.withOcr(repo, id)
        val intent = Intent(app, OcrEditorActivity::class.java)
            .putExtra(OcrEditorActivity.EXTRA_DOC_ID, id)
            .putExtra(OcrEditorActivity.EXTRA_TITLE, "Invoice 0231")
        ActivityScenario.launch<OcrEditorActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                shadowOf(android.os.Looper.getMainLooper()).idle()
                block(activity)
            }
        }
    }

    @Test
    fun `Page and Text switch panes and Text stays editable`() {
        launch { activity ->
            val edit = activity.findViewById<EditText>(R.id.editText)
            val page = activity.findViewById<View>(R.id.ocrPagePane)
            assertTrue("Text is the default pane", edit.isVisible)
            assertFalse(page.isVisible)

            activity.findViewById<View>(R.id.ocrModePage).performClick()
            assertFalse(edit.isVisible)
            assertTrue(page.isVisible)

            activity.findViewById<View>(R.id.ocrModeText).performClick()
            assertTrue(edit.isVisible)
            assertFalse(page.isVisible)
        }
    }

    @Test
    fun `footer promises offline reading`() {
        launch { activity ->
            assertEquals(
                activity.getString(R.string.ocr_foot_note),
                activity.findViewById<TextView>(R.id.ocrFootNote).text.toString()
            )
        }
    }

    @Test
    fun `To Word opens an honest Coming soon sheet`() {
        launch { activity ->
            activity.findViewById<View>(R.id.btnToWord).performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val dialog = ShadowDialog.getLatestDialog()
            assertNotNull(dialog)
            assertEquals(
                activity.getString(R.string.ocr_to_word_soon),
                dialog.findViewById<TextView>(R.id.swSheetMessage).text.toString()
            )
            dialog.dismiss()
        }
    }

    @Test
    fun `the four action chips carry their icons and labels`() {
        launch { activity ->
            val expected = mapOf(
                R.id.btnCopy to R.string.ocr_copy_btn,
                R.id.btnShare to R.string.ocr_share_btn,
                R.id.btnSave to R.string.ocr_save_btn,
                R.id.btnToWord to R.string.ocr_to_word_pro
            )
            for ((chipId, labelRes) in expected) {
                val chip = activity.findViewById<View>(chipId)
                assertNotNull("chip $chipId exists", chip)
                val icon = chip.findViewById<android.widget.ImageView>(R.id.swChipIcon)
                assertNotNull("chip $chipId has an icon view", icon)
                assertNotNull("chip $chipId has an icon drawable", icon.drawable)
                assertEquals(
                    activity.getString(labelRes),
                    chip.findViewById<TextView>(R.id.swChipLabel).text.toString()
                )
            }
        }
    }

    @Test
    fun `Tidy text opens one sheet whose options apply the fixes`() {
        launch { activity ->
            val edit = activity.findViewById<EditText>(R.id.editText)
            edit.setText("first line\nsecond line")
            activity.findViewById<View>(R.id.btnTidyText).performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val dialog = ShadowDialog.getLatestDialog()
            assertNotNull("the tidy sheet opens", dialog)
            val body = dialog.findViewById<ViewGroup>(R.id.swSheetContent).getChildAt(0) as ViewGroup
            assertEquals("three tidy options", 3, body.childCount)
            dialog.findViewById<View>(R.id.ocrTidyJoin).performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals("first line second line", edit.text.toString())
        }
    }

    @Test
    fun `undo then redo sit on the word-count line and start disabled`() {
        launch { activity ->
            val undo = activity.findViewById<View>(R.id.btnUndo)
            val redo = activity.findViewById<View>(R.id.btnRedo)
            val row = undo.parent as ViewGroup
            assertTrue("Undo comes before Redo", row.indexOfChild(undo) < row.indexOfChild(redo))
            assertEquals("Undo starts disabled", 0.38f, undo.alpha, 0.01f)
            assertEquals("Redo starts disabled", 0.38f, redo.alpha, 0.01f)

            activity.findViewById<EditText>(R.id.editText).setText("changed")
            assertEquals("Undo enables after typing", 1f, undo.alpha, 0.01f)
        }
    }

    @Test
    fun `the footer is one short line and the top bar carries the language chip`() {
        launch { activity ->
            val footer = activity.findViewById<TextView>(R.id.ocrFootNote)
            assertEquals(1, footer.maxLines)
            assertEquals(
                activity.getString(R.string.ocr_lang_english),
                activity.findViewById<View>(R.id.ocrLangChip)
                    .findViewById<TextView>(R.id.swChipLabel).text.toString()
            )
        }
    }
}
