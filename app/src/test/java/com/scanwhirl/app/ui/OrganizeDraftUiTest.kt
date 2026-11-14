package com.scanwhirl.app.ui

import android.app.Application
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.PdfInvariants
import com.scanwhirl.app.store.ScanRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

/**
 * PM-2.2c: the Organize screen writes page edits to the draft. Rotate/delete/move/reorder never
 * change the saved document (I7); the bar offers Discard (always), Replace (with a previous
 * version in Trash) and Save as new copy (I1/I8).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class OrganizeDraftUiTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)

    private fun digest(id: String) =
        PdfInvariants.dirDigest(File(repo.docsDir, id))

    private fun idle() = shadowOf(android.os.Looper.getMainLooper()).idle()

    private fun pollUntil(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        idle()
        return condition()
    }

    private fun sheet() = ShadowDialog.getLatestDialog()

    private fun primary() {
        requireNotNull(sheet()).findViewById<android.view.View>(R.id.swSheetPrimary)!!.performClick()
        idle()
    }

    private fun draftChanges(id: String): Int = runBlocking { repo.draftOf(id)?.changes ?: 0 }

    @Test
    fun `rotating in the editor leaves the saved document byte identical until Save`() {
        val id = DocFixtures.saved(repo, app, pages = 3)
        val d0 = digest(id)
        ActivityScenario.launch<DocumentViewerActivity>(DocumentViewerActivity.intent(app, id)).use { scenario ->
            scenario.onActivity { activity ->
                activity.toggleSelected(0)
                activity.rotateSelected(true)
            }
            assertTrue(
                "the draft must appear",
                pollUntil { draftChanges(id) == 1 || digest(id) != d0 }
            )
            assertEquals("the saved document changed before Save", d0, digest(id))
        }
    }

    @Test
    fun `back with changes offers Save as new copy Discard and Keep editing`() {
        val id = DocFixtures.saved(repo, app, pages = 3)
        ActivityScenario.launch<DocumentViewerActivity>(DocumentViewerActivity.intent(app, id)).use { scenario ->
            scenario.onActivity { activity ->
                activity.toggleSelected(0)
                activity.rotateSelected(true)
            }
            assertTrue(pollUntil { draftChanges(id) == 1 })
            // The activity's own draft must be loaded before Back can prompt.
            var barShown = false
            assertTrue(pollUntil {
                scenario.onActivity { barShown = it.findViewById<android.view.View>(R.id.draftBar)?.visibility == android.view.View.VISIBLE }
                barShown
            })
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            idle()

            val dialog = requireNotNull(sheet()) { "the Back prompt must open" }
            assertEquals(
                app.getString(R.string.draft_back_title),
                dialog.findViewById<TextView>(R.id.swSheetTitle)?.text?.toString()
            )
            assertEquals(
                app.getString(R.string.draft_save_new),
                dialog.findViewById<TextView>(R.id.swSheetPrimary)?.text?.toString()
            )
            assertEquals(
                app.getString(R.string.draft_discard),
                dialog.findViewById<TextView>(R.id.swSheetSecondary)?.text?.toString()
            )
            dialog.dismiss()
        }
    }

    @Test
    fun `discard restores the original page list`() {
        val id = DocFixtures.saved(repo, app, pages = 3)
        val d0 = digest(id)
        val basePaths = repo.getDocument(id)!!.pageFiles.map { it.path }
        ActivityScenario.launch<DocumentViewerActivity>(DocumentViewerActivity.intent(app, id)).use { scenario ->
            scenario.onActivity { activity ->
                activity.toggleSelected(0)
                activity.rotateSelected(true)
            }
            assertTrue(pollUntil { draftChanges(id) == 1 })
        }

        // Reopen: the draft survives, so the second edit starts from a known empty selection.
        ActivityScenario.launch<DocumentViewerActivity>(DocumentViewerActivity.intent(app, id)).use { scenario ->
            var barShown = false
            assertTrue(pollUntil {
                scenario.onActivity { barShown = it.findViewById<android.view.View>(R.id.draftBar)?.visibility == android.view.View.VISIBLE }
                barShown
            })
            scenario.onActivity { activity ->
                activity.toggleSelected(0)
                activity.rotateSelected(true)
            }
            assertTrue(pollUntil { draftChanges(id) >= 2 })

            scenario.onActivity { it.findViewById<android.view.View>(R.id.btnDraftDiscard)!!.performClick() }
            idle()
            assertNotNull("Discard asks first", sheet())
            primary()

            assertTrue(pollUntil { runBlocking { repo.draftOf(id) } == null })
            assertEquals(d0, digest(id))
            assertEquals(
                "the grid shows the original pages again",
                basePaths,
                runBlocking { repo.draftPages(id) }.map { it.path }
            )
        }
    }

    @Test
    fun `Save as new copy creates the edited copy and keeps the original`() {
        val id = DocFixtures.saved(repo, app, pages = 3, name = "Draft save")
        val doc = repo.getDocument(id)!!
        val d0 = digest(id)
        ActivityScenario.launch<DocumentViewerActivity>(DocumentViewerActivity.intent(app, id)).use { scenario ->
            scenario.onActivity { activity ->
                activity.toggleSelected(0)
                activity.deleteSelectedPages()
                activity.commitPendingDelete()
            }
            assertTrue(pollUntil { draftChanges(id) == 1 })

            scenario.onActivity { it.findViewById<android.view.View>(R.id.btnDraftSave)!!.performClick() }
            assertTrue(pollUntil { runCatching { sheet()?.findViewById<EditText>(R.id.swSheetInput) != null }.getOrDefault(false) })
            val input = sheet()!!.findViewById<EditText>(R.id.swSheetInput)!!
            assertEquals(app.getString(R.string.draft_edited_name, doc.name), input.text.toString())
            primary()

            assertTrue(pollUntil { repo.listDocuments().size == 2 })
            val copy = repo.listDocuments().first { it.id != id }
            assertEquals(2, copy.pageFiles.size)
            assertEquals("Save as new changed the original", d0, digest(id))
            var finishing = false
            assertTrue(pollUntil {
                scenario.onActivity { finishing = it.isFinishing }
                finishing
            })
        }
    }

    @Test
    fun `Replace original asks to confirm and puts the previous version in Trash`() {
        val id = DocFixtures.saved(repo, app, pages = 3, name = "Draft replace")
        val doc = repo.getDocument(id)!!
        ActivityScenario.launch<DocumentViewerActivity>(DocumentViewerActivity.intent(app, id)).use { scenario ->
            scenario.onActivity { activity ->
                activity.toggleSelected(0)
                activity.deleteSelectedPages()
                activity.commitPendingDelete()
            }
            assertTrue(pollUntil { draftChanges(id) == 1 })

            scenario.onActivity { it.findViewById<android.view.View>(R.id.btnDraftReplace)!!.performClick() }
            idle()
            val dialog = requireNotNull(sheet())
            assertEquals(
                app.getString(R.string.draft_replace_title, doc.name),
                dialog.findViewById<TextView>(R.id.swSheetTitle)?.text?.toString()
            )
            primary()

            assertTrue(
                "Replace publishes in place",
                pollUntil { repo.getDocument(id)?.pageFiles?.size == 2 }
            )
            val previous = repo.listTrash().filter { it.originalId == id && it.previousToken != null }
            assertEquals("exactly one previous version", 1, previous.size)
            assertEquals(3, previous.single().document.pageFiles.size)
            assertNull("Replace must clear the draft", runBlocking { repo.draftOf(id) })
        }

        // The Trash row names the previous version.
        ActivityScenario.launch<TrashActivity>(android.content.Intent(app, TrashActivity::class.java)).use { trash ->
            var label: String? = null
            assertTrue("the Trash list loads", pollUntil {
                trash.onActivity { activity ->
                    val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.trashList)
                    if (list.adapter?.itemCount == 1) {
                        if (list.childCount == 0) {
                            list.measure(
                                android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                                android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY)
                            )
                            list.layout(0, 0, 1080, 1920)
                        }
                        label = list.findViewHolderForAdapterPosition(0)
                            ?.itemView?.findViewById<TextView>(R.id.trashName)?.text?.toString()
                    }
                }
                label == app.getString(R.string.trash_previous_version, doc.name)
            })
            assertEquals(app.getString(R.string.trash_previous_version, doc.name), label)
        }
    }

    @Test
    fun `selection bar rotate and delete act on the draft`() {
        val id = DocFixtures.saved(repo, app, pages = 3)
        val d0 = digest(id)
        ActivityScenario.launch<DocumentViewerActivity>(DocumentViewerActivity.intent(app, id)).use { scenario ->
            var cells = 0
            assertTrue("the grid loads", pollUntil {
                scenario.onActivity { cells = it.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.pageList).adapter?.itemCount ?: 0 }
                cells == 3
            })
            scenario.onActivity { activity ->
                val grid = activity.findViewById<com.scanwhirl.app.ui.pagegrid.PageGridView>(R.id.pageList)
                grid.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY)
                )
                grid.layout(0, 0, 1080, 1920)
                grid.findViewHolderForAdapterPosition(0)!!.itemView.performLongClick()
                activity.findViewById<android.view.View>(R.id.btnSelRotate)!!.performClick()
            }
            assertTrue("rotate writes to the draft", pollUntil { draftChanges(id) == 1 })
            assertEquals("the saved document changed", d0, digest(id))

            var selectedLabel = ""
            assertTrue("the rotated page stays selected", pollUntil {
                scenario.onActivity {
                    selectedLabel = it.findViewById<TextView>(R.id.selCount)?.text?.toString().orEmpty()
                }
                selectedLabel.isNotEmpty() && !selectedLabel.startsWith("0")
            })
            scenario.onActivity { it.findViewById<android.view.View>(R.id.btnSelDelete)!!.performClick() }
            idle()
            val confirm = requireNotNull(sheet()) { "Delete asks first" }
            assertTrue(
                "the confirmation names the pages",
                confirm.findViewById<TextView>(R.id.swSheetTitle)!!.text.contains("Delete")
            )
            primary()
            scenario.onActivity { it.commitPendingDelete() }
            assertTrue("delete writes to the draft", pollUntil { runBlocking { repo.draftPages(id) }.size == 2 })
            assertEquals("the saved document still has all pages", d0, digest(id))
        }
    }

    @Test
    fun `reopening after process death shows the draft`() {
        val id = DocFixtures.saved(repo, app, pages = 3)
        ActivityScenario.launch<DocumentViewerActivity>(DocumentViewerActivity.intent(app, id)).use { scenario ->
            scenario.onActivity { activity ->
                activity.toggleSelected(0)
                activity.rotateSelected(true)
            }
            assertTrue(pollUntil { draftChanges(id) == 1 })
        }

        // A new repository stands in for a new process: the draft is read back from disk.
        val fresh = ScanRepository(app)
        assertEquals(1, runBlocking { fresh.draftOf(id) }!!.changes)
        assertEquals(true, runBlocking { fresh.draftPages(id) }[0].path.contains("drafts"))

        ActivityScenario.launch<DocumentViewerActivity>(DocumentViewerActivity.intent(app, id)).use { scenario ->
            var visible = false
            assertTrue("the draft bar must be visible", pollUntil {
                scenario.onActivity { activity ->
                    visible = activity.findViewById<android.view.View>(R.id.draftBar)?.visibility ==
                        android.view.View.VISIBLE
                }
                visible
            })
            scenario.onActivity { activity ->
                assertEquals(
                    app.getString(R.string.draft_header, 3, 1),
                    activity.findViewById<TextView>(R.id.draftHeader).text.toString()
                )
            }
        }
    }
}
