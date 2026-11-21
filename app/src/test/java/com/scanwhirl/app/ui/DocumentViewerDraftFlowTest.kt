package com.scanwhirl.app.ui

import android.app.Application
import android.app.Dialog
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.pagegrid.PageGridView
import com.scanwhirl.app.ui.stamp.StampEditorActivity
import com.scanwhirl.app.util.ImageTools
import com.scanwhirl.app.util.ProGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
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
 * DV lane: the document viewer's page-edit draft flows must never silently drop an edit.
 *
 * Red first: on the base tree "Finish editing first" is a no-op (the dispatcher's default
 * onFinishEditing is an empty lambda), Sign's draft question never names the signed copy,
 * and page selection is not saved across recreation.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
class DocumentViewerDraftFlowTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null)
        repo = ScanRepository(app)
        DocumentViewerActivity.actionTraceForTests = null
    }

    @After
    fun tearDown() {
        DocumentViewerActivity.actionTraceForTests = null
        ProGate.setTestOverride(app, null)
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun pollUntil(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            idle()
            if (condition()) return true
            Thread.sleep(20)
        }
        idle()
        return condition()
    }

    private fun draftChange(id: String) {
        runBlocking { withContext(Dispatchers.IO) { repo.draftRotate(id, 0, true) } }
    }

    private fun draftChanges(id: String): Int =
        runBlocking { withContext(Dispatchers.IO) { repo.draftOf(id)?.changes ?: 0 } }

    private fun draftPages(id: String): List<java.io.File> =
        runBlocking { withContext(Dispatchers.IO) { repo.draftPages(id) } }

    private fun draftWidths(id: String): List<Int> = draftPages(id).map { file ->
        ImageTools.imageSize(file.readBytes())!!.first
    }

    private fun visibleCount(scenario: ActivityScenario<DocumentViewerActivity>): Int {
        var count = -1
        scenario.onActivity { count = it.findViewById<PageGridView>(R.id.pageList).pageCount() }
        return count
    }

    private fun loaded(scenario: ActivityScenario<DocumentViewerActivity>): Boolean {
        var ready = false
        scenario.onActivity {
            ready = it.findViewById<TextView>(R.id.pageCount)?.text?.isNotEmpty() == true
        }
        return ready
    }

    private fun shownSheet(): Dialog? = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }

    private fun sheetWithTitle(title: String): Dialog? = shownSheet()?.takeIf {
        it.findViewById<TextView>(R.id.swSheetTitle)?.text?.toString() == title
    }

    private fun awaitSheet(title: String, timeoutMs: Long = 10_000): Dialog {
        var found: Dialog? = null
        assertTrue(
            "the sheet titled '$title' must appear",
            pollUntil(timeoutMs) { sheetWithTitle(title).also { found = it } != null }
        )
        return found!!
    }

    private fun rowLabels(dialog: Dialog): List<String> {
        val list = requireNotNull(dialog.findViewById<ViewGroup>(R.id.swSheetItems)) {
            "the sheet lists its named answers"
        }
        return (0 until list.childCount).map { index ->
            val row = list.getChildAt(index) as ViewGroup
            (0 until row.childCount).map { row.getChildAt(it) }
                .filterIsInstance<TextView>().first().text.toString()
        }
    }

    private fun clickRow(dialog: Dialog, label: String) {
        val list = requireNotNull(dialog.findViewById<ViewGroup>(R.id.swSheetItems))
        val row = (0 until list.childCount).map { list.getChildAt(it) }.first {
            (it as ViewGroup).getChildAt(0) is TextView &&
                (it.getChildAt(0) as TextView).text.toString() == label
        }
        row.performClick()
        idle()
    }

    private fun nextStarted(scenario: ActivityScenario<DocumentViewerActivity>): Intent? {
        var intent: Intent? = null
        scenario.onActivity { intent = shadowOf(it).peekNextStartedActivity() }
        return intent
    }

    // ---------------------------------------------------------------- defect 1

    /** "Finish editing first" must open the draft's own save choices, not close the question. */
    @Test
    fun `finish editing first opens the draft save choices`() {
        val id = DocFixtures.saved(repo, app, pages = 3, name = "Finish editing")
        draftChange(id)

        ActivityScenario.launch<DocumentViewerActivity>(DocumentViewerActivity.intent(app, id)).use { scenario ->
            assertTrue(pollUntil { loaded(scenario) })
            scenario.onActivity { it.findViewById<View>(R.id.btnSign).performClick() }

            val question = awaitSheet(app.getString(R.string.w1_draft_action_title))
            assertEquals(
                "Finish editing first is the first answer",
                app.getString(R.string.w1_draft_finish_first),
                rowLabels(question)[0]
            )
            clickRow(question, app.getString(R.string.w1_draft_finish_first))

            val choices = awaitSheet(app.getString(R.string.draft_back_title))
            assertTrue(
                "the draft's Save as new copy choice is offered",
                rowLabels(choices).contains(app.getString(R.string.draft_save_new))
            )
            assertTrue(
                "the draft's Replace original choice is offered",
                rowLabels(choices).contains(app.getString(R.string.draft_replace))
            )
            assertTrue(
                "the person can still keep editing",
                rowLabels(choices).contains(app.getString(R.string.draft_keep_editing))
            )
        }
        assertEquals("opening the choices commits nothing yet", 1, draftChanges(id))
    }

    /** After a successful save the original action must run on the saved result. */
    @Test
    fun `finish editing then replace commits edits and continues Sign`() {
        ProGate.setTestOverride(app, true)
        val id = DocFixtures.saved(repo, app, pages = 3, name = "Finish then sign")
        val originalFirstPage = repo.getDocument(id)!!.pageFiles[0].readBytes()
        draftChange(id)

        ActivityScenario.launch<DocumentViewerActivity>(DocumentViewerActivity.intent(app, id)).use { scenario ->
            assertTrue(pollUntil { loaded(scenario) })
            scenario.onActivity { it.findViewById<View>(R.id.btnSign).performClick() }

            val question = awaitSheet(app.getString(R.string.w1_draft_action_title))
            clickRow(question, app.getString(R.string.w1_draft_finish_first))
            val choices = awaitSheet(app.getString(R.string.draft_back_title))
            clickRow(choices, app.getString(R.string.draft_replace))

            val confirm = awaitSheet(app.getString(R.string.draft_replace_title, "Finish then sign"))
            confirm.findViewById<View>(R.id.swSheetPrimary).performClick()
            idle()

            assertTrue(
                "Replace commits the edits and Sign opens the editor",
                pollUntil {
                    runBlocking { repo.draftOf(id) } == null &&
                        nextStarted(scenario)?.component?.className == StampEditorActivity::class.java.name
                }
            )
            val started = requireNotNull(nextStarted(scenario)) { "the stamp editor was started" }
            assertEquals(id, started.getStringExtra(StampEditorActivity.EXTRA_DOC_ID))
            assertFalse(
                "the published first page carries the rotated edit",
                originalFirstPage.contentEquals(repo.getDocument(id)!!.pageFiles[0].readBytes())
            )
        }
    }

    // ---------------------------------------------------------------- defect 3

    /** Ticked pages and selection mode survive configuration recreation. */
    @Test
    fun `selection survives rotation`() {
        val id = DocFixtures.saved(repo, app, pages = 3, name = "Selection rotation")
        ActivityScenario.launch<DocumentViewerActivity>(DocumentViewerActivity.intent(app, id)).use { scenario ->
            assertTrue(pollUntil { loaded(scenario) })
            scenario.onActivity { activity ->
                activity.toggleSelected(0)
                activity.toggleSelected(1)
            }
            var marked = ""
            scenario.onActivity { marked = it.findViewById<TextView>(R.id.selCount).text.toString() }
            assertTrue("two pages are ticked before rotation", marked.contains("2"))

            scenario.recreate()
            val expected = app.resources.getQuantityString(R.plurals.selected_count, 2, 2)
            assertTrue(
                "the count is restored after rotation",
                pollUntil {
                    var text = ""
                    scenario.onActivity {
                        text = it.findViewById<TextView>(R.id.selCount)?.text?.toString().orEmpty()
                    }
                    text == expected
                }
            )
            scenario.onActivity { activity ->
                assertTrue("selection mode is restored", activity.findViewById<View>(R.id.selectionTopBar).isShown)
                val grid = activity.findViewById<PageGridView>(R.id.pageList)
                assertTrue("checkboxes are restored", grid.checkboxesVisible)
                assertTrue("page 1 stays ticked", grid.checkedAt(0))
                assertTrue("page 2 stays ticked", grid.checkedAt(1))
                assertFalse("page 3 is not ticked", grid.checkedAt(2))
            }
        }
    }

    // ---------------------------------------------------------------- defect 2

    /** Two deleted pages with Undo pending must not shift a subsequent move-to-end. */
    @Test
    fun `move to end after two pending deletes keeps the intended order`() {
        val id = DocFixtures.saved(repo, app, pages = 5, name = "Move after deletes")
        ActivityScenario.launch<DocumentViewerActivity>(DocumentViewerActivity.intent(app, id)).use { scenario ->
            assertTrue(pollUntil { loaded(scenario) && visibleCount(scenario) == 5 })
            scenario.onActivity { activity ->
                activity.toggleSelected(1)
                activity.toggleSelected(2)
                activity.deleteSelectedPages()
            }
            assertTrue("two pages are hidden while Undo is pending", pollUntil { visibleCount(scenario) == 3 })
            scenario.onActivity { it.reorderPages(0, 2) }

            assertTrue(
                "the move lands in the draft",
                pollUntil {
                    val widths = draftWidths(id)
                    widths == listOf(260, 280, 200)
                }
            )
            assertEquals(listOf(260, 280, 200), draftWidths(id))
        }
    }

    /** The typed "Move to…" route must agree with the drag route after pending deletes. */
    @Test
    fun `move to end by number after two pending deletes keeps the intended order`() {
        val id = DocFixtures.saved(repo, app, pages = 5, name = "Move to after deletes")
        ActivityScenario.launch<DocumentViewerActivity>(DocumentViewerActivity.intent(app, id)).use { scenario ->
            assertTrue(pollUntil { loaded(scenario) && visibleCount(scenario) == 5 })
            scenario.onActivity { activity ->
                activity.toggleSelected(1)
                activity.toggleSelected(2)
                activity.deleteSelectedPages()
            }
            assertTrue("two pages are hidden while Undo is pending", pollUntil { visibleCount(scenario) == 3 })
            scenario.onActivity { activity ->
                activity.toggleSelected(0)
                activity.moveSelectedPage(2)
            }
            assertTrue(
                "the typed move lands in the draft",
                pollUntil { draftWidths(id) == listOf(260, 280, 200) }
            )
            assertEquals(listOf(260, 280, 200), draftWidths(id))
        }
    }

    // ---------------------------------------------------------------- defect 4

    /** Sign names the consequence: the signed copy does not carry unsaved edits. */
    @Test
    fun `Sign says the signed copy excludes unsaved edits in English`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Signed later")
        draftChange(id)
        ActivityScenario.launch<DocumentViewerActivity>(DocumentViewerActivity.intent(app, id)).use { scenario ->
            assertTrue(pollUntil { loaded(scenario) })
            scenario.onActivity { it.findViewById<View>(R.id.btnSign).performClick() }
            val question = awaitSheet(app.getString(R.string.w1_draft_action_title))
            val message = question.findViewById<TextView>(R.id.swSheetMessage)?.text?.toString().orEmpty()
            assertEquals(
                "the sheet names the signed copy and its saved bytes",
                app.getString(R.string.w1_draft_sign_msg, "Signed later"),
                message
            )
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "hi-w360dp-h640dp-xhdpi")
    fun `Sign says the signed copy excludes unsaved edits in Hindi`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Signed later")
        draftChange(id)
        ActivityScenario.launch<DocumentViewerActivity>(DocumentViewerActivity.intent(app, id)).use { scenario ->
            assertTrue(pollUntil { loaded(scenario) })
            scenario.onActivity { it.findViewById<View>(R.id.btnSign).performClick() }
            val question = awaitSheet(app.getString(R.string.w1_draft_action_title))
            val message = question.findViewById<TextView>(R.id.swSheetMessage)?.text?.toString().orEmpty()
            assertEquals(
                "Hindi says the signed copy excludes unsaved edits",
                app.getString(R.string.w1_draft_sign_msg, "Signed later"),
                message
            )
        }
    }
}
