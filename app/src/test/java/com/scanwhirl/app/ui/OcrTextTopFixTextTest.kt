package com.scanwhirl.app.ui

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * N1 (found on device): the Text screen opened with the text box scrolled to the
 * bottom, so the end of the page was read first. A page must open at its first line,
 * on first open and after switching pages, with no keyboard.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OcrTextTopFixTextTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val repo = ScanRepository(app)

    private fun longText(marker: String): String =
        (1..40).joinToString("\n") { "Line $it of the $marker page" }

    private fun launch(id: String, block: (OcrEditorActivity) -> Unit) {
        val intent = Intent(app, OcrEditorActivity::class.java)
            .putExtra(OcrEditorActivity.EXTRA_DOC_ID, id)
        Robolectric.buildActivity(OcrEditorActivity::class.java, intent).setup().use { controller ->
            shadowOf(Looper.getMainLooper()).idle()
            block(controller.get())
        }
    }

    /** Real measure/layout so TextView's bring-cursor-into-view can scroll the box. */
    private fun layout(activity: OcrEditorActivity): EditText {
        val editor = activity.findViewById<EditText>(R.id.editText)
        val width = View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY)
        val height = View.MeasureSpec.makeMeasureSpec(160, View.MeasureSpec.EXACTLY)
        editor.measure(width, height)
        editor.layout(0, 0, 360, 160)
        shadowOf(Looper.getMainLooper()).idle()
        return editor
    }

    @Test
    fun `a document opens at the top of its text without the keyboard`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Reading order")
        repo.updateOcrPage(id, "page_000.jpg", longText("first"))
        launch(id) { activity ->
            val editor = layout(activity)
            assertEquals("the caret sits at the first character", 0, editor.selectionStart)
            assertEquals("the box starts at its first line", 0, editor.scrollY)
            val mode = activity.window.attributes.softInputMode
            assertTrue(
                "opening the screen does not ask for the keyboard (mode=$mode)",
                mode and android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE ==
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
            )
        }
    }

    @Test
    fun `switching pages also starts at the top`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Two pages")
        repo.updateOcrPage(id, "page_000.jpg", longText("first"))
        repo.updateOcrPage(id, "page_001.jpg", longText("second"))
        launch(id) { activity ->
            val tabs = activity.findViewById<ChipGroup>(R.id.pageTabs)
            assertEquals(2, tabs.childCount)
            (tabs.getChildAt(1) as Chip).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val editor = layout(activity)
            assertTrue(
                "the second page is shown",
                editor.text.toString().contains("second")
            )
            assertEquals("the caret sits at the first character", 0, editor.selectionStart)
            assertEquals("the box starts at its first line", 0, editor.scrollY)
            assertFalse(editor.text.isEmpty())
        }
    }
}
