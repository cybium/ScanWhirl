package com.scanwhirl.app.ui.stamp

import android.app.Application
import android.content.Intent
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.PageStamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * L5 Phase B: the Stamp editor screen. The engine is covered by PageStampTest; here the
 * screen's contract: the page strip, the tool row (no dead button), the armed draw modes,
 * undo/redo, Suggest and the "Save as new copy" action.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class StampEditorActivityTest {

    private val context: Application
        get() = ApplicationProvider.getApplicationContext()

    private val repo get() = ScanRepository(context)

    private fun activity(id: String, tool: String? = null): StampEditorActivity {
        val intent = Intent(context, StampEditorActivity::class.java)
            .putExtra(StampEditorActivity.EXTRA_DOC_ID, id)
            .putExtra(StampEditorActivity.EXTRA_TOOL, tool)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return Robolectric.buildActivity(StampEditorActivity::class.java, intent).setup().get()
    }

    @Test
    fun `the page strip moves through the document`() {
        val id = DocFixtures.saved(repo, context, pages = 2)
        val screen = activity(id)

        assertEquals(2, screen.editor.pageCount)
        assertEquals("1 / 2", screen.findViewById<TextView>(R.id.pageIndicator).text.toString())
        assertTrue(screen.findViewById<android.view.View>(R.id.nextPage).isEnabled)
        assertFalse(screen.findViewById<android.view.View>(R.id.prevPage).isEnabled)

        screen.findViewById<android.view.View>(R.id.nextPage).performClick()
        assertEquals(1, screen.editor.currentPage)
        assertEquals("2 / 2", screen.findViewById<TextView>(R.id.pageIndicator).text.toString())

        screen.findViewById<android.view.View>(R.id.prevPage).performClick()
        assertEquals(0, screen.editor.currentPage)
    }

    @Test
    fun `every tool button is wired and armed modes are real`() {
        val id = DocFixtures.saved(repo, context, pages = 1)
        val screen = activity(id)
        val tools = listOf(
            R.id.toolSignature, R.id.toolText, R.id.toolDraw, R.id.toolHighlight,
            R.id.toolWatermark, R.id.toolPageNumbers, R.id.toolStamp, R.id.toolRedact,
            R.id.toolSuggest
        )
        tools.forEach { toolId ->
            val button = screen.findViewById<android.view.View>(toolId)
            assertTrue("tool $toolId must be enabled", button.isEnabled)
            assertTrue("tool $toolId must do something", button.hasOnClickListeners())
        }

        screen.findViewById<android.view.View>(R.id.toolDraw).performClick()
        assertEquals(StampCanvasView.Tool.DRAW, screen.findViewById<StampCanvasView>(R.id.canvas).tool)
        assertEquals(android.view.View.VISIBLE, screen.findViewById<android.view.View>(R.id.hint).visibility)

        screen.findViewById<android.view.View>(R.id.toolRedact).performClick()
        assertEquals(StampCanvasView.Tool.REDACT, screen.findViewById<StampCanvasView>(R.id.canvas).tool)
    }

    @Test
    fun `a redaction box becomes a mark and undo redo step through it`() {
        val id = DocFixtures.saved(repo, context, pages = 1)
        val screen = activity(id)
        val canvas = screen.findViewById<StampCanvasView>(R.id.canvas)
        screen.findViewById<android.view.View>(R.id.toolRedact).performClick()

        canvas.listener?.onBox(
            StampCanvasView.Tool.REDACT,
            PageStamp.Box(0.1f, 0.1f, 0.6f, 0.3f)
        )

        assertEquals(1, screen.editor.marks().size)
        assertTrue(screen.editor.marks().single() is PageStamp.Mark.Redact)
        assertTrue("save is available", screen.findViewById<android.view.View>(R.id.saveCopy).isEnabled)
        assertTrue("delete is available", screen.findViewById<android.view.View>(R.id.deleteMark).isEnabled)

        screen.findViewById<android.view.View>(R.id.undo).performClick()
        assertTrue(screen.editor.marks().isEmpty())
        assertFalse(screen.findViewById<android.view.View>(R.id.saveCopy).isEnabled)

        screen.findViewById<android.view.View>(R.id.redo).performClick()
        assertEquals(1, screen.editor.marks().size)
    }

    @Test
    fun `a redaction warns that it is permanent before saving`() {
        val id = DocFixtures.saved(repo, context, pages = 1)
        val screen = activity(id)
        val canvas = screen.findViewById<StampCanvasView>(R.id.canvas)
        screen.findViewById<android.view.View>(R.id.toolRedact).performClick()
        canvas.listener?.onBox(
            StampCanvasView.Tool.REDACT,
            PageStamp.Box(0.1f, 0.1f, 0.6f, 0.3f)
        )
        val before = repo.listDocuments().size

        screen.findViewById<android.view.View>(R.id.saveCopy).performClick()

        val sheet = requireNotNull(ShadowDialog.getLatestDialog()) { "a warning sheet opens" }
        val message = sheet.findViewById<TextView>(R.id.swSheetMessage)?.text?.toString().orEmpty()
        assertTrue("the warning must be explicit, got: $message", message.contains("removed for good"))
        assertEquals(
            "nothing is written before the user confirms",
            before,
            repo.listDocuments().size
        )
    }

    @Test
    fun `page numbers apply to every page as one undo step`() {
        val id = DocFixtures.saved(repo, context, pages = 3)
        val screen = activity(id)
        screen.findViewById<android.view.View>(R.id.toolPageNumbers).performClick()

        for (page in 0 until 3) assertEquals(1, screen.editor.marks(page).size)
        assertTrue(screen.editor.marks(0).single() is PageStamp.Mark.PageNumber)

        screen.findViewById<android.view.View>(R.id.undo).performClick()
        for (page in 0 until 3) assertTrue(screen.editor.marks(page).isEmpty())
    }

    @Test
    fun `text sheet adds the typed text`() {
        val id = DocFixtures.saved(repo, context, pages = 1)
        val screen = activity(id)
        screen.findViewById<android.view.View>(R.id.toolText).performClick()

        val sheet = requireNotNull(ShadowDialog.getLatestDialog()) { "a text sheet opens" }
        val input = sheet.findViewById<EditText>(R.id.stampTextInput)!!
        input.setText("SELF-ATTESTED")
        sheet.findViewById<android.view.View>(R.id.swSheetPrimary)!!.performClick()

        val mark = screen.editor.marks().single() as PageStamp.Mark.Text
        assertEquals("SELF-ATTESTED", mark.text)
    }

    @Test
    fun `suggest turns recognised sensitive lines into redaction boxes`() {
        val id = DocFixtures.saved(repo, context, pages = 1)
        val doc = requireNotNull(repo.getDocument(id))
        val page = doc.pageFiles.single()
        val (width, height) = com.scanwhirl.app.util.PageReplace.bounds(page)!!
        repo.ocrStore.putPage(
            id,
            page.name,
            "Aadhaar 1234 5678 9012",
            OcrPage(width, height, listOf(OcrLine("Aadhaar 1234 5678 9012", 10, 10, width - 10, 40)))
        )

        val screen = activity(id)
        screen.findViewById<android.view.View>(R.id.toolSuggest).performClick()

        assertEquals(1, screen.editor.marks().size)
        assertTrue(screen.editor.marks().single() is PageStamp.Mark.Redact)
    }

    @Test
    fun `the requested tool is armed on launch`() {
        val id = DocFixtures.saved(repo, context, pages = 1)
        val screen = activity(id, StampEditorActivity.TOOL_HIGHLIGHT)
        assertEquals(
            StampCanvasView.Tool.HIGHLIGHT,
            screen.findViewById<StampCanvasView>(R.id.canvas).tool
        )
    }

    @Test
    fun `the first back with unsaved marks asks to discard`() {
        val id = DocFixtures.saved(repo, context, pages = 1)
        val screen = activity(id)
        screen.editor.add(PageStamp.Mark.Redact(PageStamp.Box(0.1f, 0.1f, 0.5f, 0.3f)))

        screen.onBackPressedDispatcher.onBackPressed()

        val sheet = requireNotNull(ShadowDialog.getLatestDialog()) { "the prompt must open on the first Back" }
        assertEquals(
            context.getString(R.string.stamp_discard_title),
            sheet.findViewById<TextView>(R.id.swSheetTitle)?.text?.toString()
        )
        assertFalse("the editor must stay open behind the prompt", screen.isFinishing)
    }

    @Test
    fun `back with no marks closes at once`() {
        val id = DocFixtures.saved(repo, context, pages = 1)
        val screen = activity(id)

        screen.onBackPressedDispatcher.onBackPressed()

        assertTrue("no marks: Back closes the editor", screen.isFinishing)
        assertNull("no prompt without edits", ShadowDialog.getLatestDialog())
    }

    @Test
    fun `the stamp tool row scrolls clear of the last tile`() {
        val id = DocFixtures.saved(repo, context, pages = 1)
        val screen = activity(id)
        val scroll = screen.findViewById<android.widget.HorizontalScrollView>(R.id.toolScroll)
        assertFalse("end padding must be drawn outside the clip", scroll.clipToPadding)
        assertTrue("the last tool tile needs end padding", scroll.paddingEnd > 0)
    }

    @Test
    fun `a locked document is refused`() {
        val id = DocFixtures.locked(repo, context)
        val screen = activity(id)
        assertTrue("the editor must close on a protected document", screen.isFinishing)
    }
}
