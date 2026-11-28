package com.scanwhirl.app.ui.pagegrid

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.DocumentViewerActivity
import com.scanwhirl.app.util.ResetDialogShadowsRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Identity, cancellation and protection are independent of screenshot acceptance. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class J5OrganizeIntegrityTest {
    @get:Rule val dialogs = ResetDialogShadowsRule()
    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
    }

    private fun waitFor(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(20)
        }
        assertTrue("the actual operation completes", condition())
    }

    private fun open(id: String, block: (DocumentViewerActivity, PageGridView) -> Unit) {
        ActivityScenario.launch<DocumentViewerActivity>(
            DocumentViewerActivity.organizeIntent(app, id)
        ).use { scenario ->
            scenario.onActivity { a ->
                waitFor { a.findViewById<TextView>(R.id.pageCount).text.isNotEmpty() }
                val grid = a.findViewById<PageGridView>(R.id.pageList)
                grid.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY))
                grid.layout(0, 0, 720, 1200)
                block(a, grid)
                grid.cancelDrag()
            }
        }
    }

    private fun actual(id: String): List<File> = runBlocking { repo.draftPages(id) }

    @Test fun `drag reorder keeps page identity and content`() {
        val id = DocFixtures.saved(repo, app, pages = 3, name = "Admission form")
        val before = actual(id)
        val bytes = before.associateWith { it.readBytes() }
        open(id) { _, grid ->
            assertEquals(3, grid.spanCount)
            assertTrue(grid.simulateDrag(2, 0))
            waitFor { actual(id) == listOf(before[2], before[0], before[1]) }
            actual(id).forEach { assertArrayEquals(bytes.getValue(it), it.readBytes()) }
            assertEquals(before, repo.getDocument(id)!!.pageFiles)
        }
    }

    @Test fun `cancelled drag restores the exact order`() {
        val id = DocFixtures.saved(repo, app, pages = 6)
        open(id) { activity, grid ->
            val before = grid.pageOrder()
            activity.toggleSelected(1)
            activity.toggleSelected(3)
            assertTrue(grid.startDragAt(1))
            grid.previewDragTo(0)
            assertNotEquals(before, grid.pageOrder())
            activity.onBackPressedDispatcher.onBackPressed()
            assertEquals(before, grid.pageOrder())
            assertTrue(grid.checkedAt(1))
            assertTrue(grid.checkedAt(3))
            assertNull(runBlocking { repo.draftOf(id) })
            assertEquals(before, actual(id))
        }
    }

    @Test fun `group drag keeps relative order`() {
        val id = DocFixtures.saved(repo, app, pages = 6)
        val before = actual(id)
        open(id) { activity, grid ->
            activity.toggleSelected(1)
            activity.toggleSelected(3)
            assertTrue(grid.simulateDrag(1, 0))
            val expected = listOf(before[1], before[3], before[0], before[2], before[4], before[5])
            waitFor { actual(id) == expected }
            assertEquals(1, runBlocking { repo.draftOf(id) }!!.history.size)
            activity.undoOrganize()
            waitFor { actual(id) == before }
            assertEquals(0, runBlocking { repo.draftOf(id) }!!.changes)
            assertTrue(grid.checkedAt(1))
            assertTrue(grid.checkedAt(3))
        }
    }

    @Test fun `the last page cannot be deleted`() {
        val id = DocFixtures.saved(repo, app, pages = 1)
        val before = actual(id).single().readBytes()
        open(id) { activity, _ ->
            activity.toggleSelected(0)
            assertFalse(activity.findViewById<View>(R.id.btnSelDelete).isEnabled)
            activity.deleteSelectedPages() // Defensive guard, not only disabled styling.
            assertEquals(1, actual(id).size)
            assertArrayEquals(before, actual(id).single().readBytes())
            assertNull(runBlocking { repo.draftOf(id) })
        }
    }

    @Test fun `TalkBack move action reorders`() {
        val id = DocFixtures.saved(repo, app, pages = 3)
        val before = actual(id)
        open(id) { _, grid ->
            val page3 = grid.findViewHolderForAdapterPosition(2)!!.itemView
            assertTrue(page3.performAccessibilityAction(R.string.j5_move_first, null))
            waitFor { actual(id).first() == before[2] }
            assertEquals(before.toSet(), actual(id).toSet())
        }
    }

    @Test fun `provenance shows only on moved pages`() {
        val id = DocFixtures.saved(repo, app, pages = 3)
        open(id) { activity, grid ->
            val third = grid.pageAt(2)!!
            assertTrue(grid.simulateDrag(2, 0))
            waitFor { actual(id).first() == third && grid.provenanceFor(third, 0) == 3 }
            assertNull(grid.provenanceFor(grid.pageAt(1)!!, 1))
            assertNull(grid.provenanceFor(grid.pageAt(2)!!, 2))
            activity.reorderPages(0, 2)
            waitFor { actual(id).last() == third && grid.provenanceFor(third, 2) == null }
        }
    }

    @Test fun `protected docs block selected extract and share`() {
        val id = DocFixtures.locked(repo, app, password = "1234", pages = 3)
        val original = repo.getDocument(id)!!.pdfFile.readBytes()
        ActivityScenario.launch<DocumentViewerActivity>(
            DocumentViewerActivity.intent(app, id)
        ).use { scenario ->
            scenario.onActivity { activity ->
                waitFor { activity.findViewById<TextView>(R.id.pageCount).text.isNotEmpty() }
                activity.toggleSelected(0)
                for (button in listOf(R.id.btnSelShare, R.id.btnSelExtract)) {
                    assertFalse(activity.findViewById<View>(button).isEnabled)
                    activity.findViewById<View>(button).performClick()
                }
                assertNull(shadowOf(activity).peekNextStartedActivity())
                assertFalse(activity.findViewById<PageGridView>(R.id.pageList).dragEnabled)
                assertArrayEquals(original, repo.getDocument(id)!!.pdfFile.readBytes())
                assertNull(runBlocking { repo.draftOf(id) })
            }
        }
    }
}
