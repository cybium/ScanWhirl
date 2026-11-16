package com.scanwhirl.app.ui

import android.app.Application
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.PdfInvariants
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.kit.SwTool
import com.scanwhirl.app.ui.kit.ToolHost
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
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

    /** A ToolHost for the registry launch, like the shell. */
    class Host : AppCompatActivity(), ToolHost {
        override fun open(tool: SwTool) = Unit
        override fun comingSoon(tool: SwTool) = Unit
    }

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
            val previous = repo.listTrash().filter { it.originalId == id && it.previousVersion }
            assertEquals("exactly one previous version", 1, previous.size)
            assertNull(
                "a completed Replace no longer blocks the 30-day sweep",
                previous.single().previousToken
            )
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
                assertTrue(grid.startDragAt(0))
                grid.moveItem(0, 0)
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
    fun `insert pages writes the draft and discard drops them`() {
        val target = DocFixtures.saved(repo, app, pages = 2)
        val source = DocFixtures.saved(repo, app, pages = 1, name = "Source")
        val d0 = digest(target)
        ActivityScenario.launch<DocumentViewerActivity>(DocumentViewerActivity.intent(app, target)).use { scenario ->
            var cells = 0
            assertTrue("the grid loads", pollUntil {
                scenario.onActivity { cells = it.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.pageList).adapter?.itemCount ?: 0 }
                cells == 2
            })
            scenario.onActivity { activity ->
                activity.insertFiles(repo.getDocument(source)!!.pageFiles, 1)
            }
            assertTrue(
                "the page lands in the draft",
                pollUntil { runBlocking { repo.draftPages(target) }.size == 3 }
            )
            assertEquals("the base changed before Save", d0, digest(target))

            var barShown = false
            assertTrue(pollUntil {
                scenario.onActivity { barShown = it.findViewById<android.view.View>(R.id.draftBar)?.visibility == android.view.View.VISIBLE }
                barShown
            })
            scenario.onActivity { it.findViewById<android.view.View>(R.id.btnDraftDiscard)!!.performClick() }
            idle()
            assertNotNull("Discard asks first", sheet())
            primary()
            assertTrue(pollUntil { runBlocking { repo.draftOf(target) } == null })
            assertEquals(2, runBlocking { repo.draftPages(target) }.size)
            assertEquals(d0, digest(target))
        }
    }

    @Test
    fun `the organize pages tool opens the grid for the picked document`() {
        DocFixtures.saved(repo, app, pages = 1)
        val host = Robolectric.buildActivity(Host::class.java).setup().get()
        val tool = requireNotNull(
            com.scanwhirl.app.ui.kit.ToolRegistry.byId("organize_pages")
        )
        tool.launch(host, tool)
        idle()
        val sheet = requireNotNull(sheet()) { "the document chooser opens" }
        val rows = requireNotNull(sheet.findViewById<LinearLayout>(R.id.swSheetItems))
        rows.getChildAt(0).performClick()
        idle()
        val started = requireNotNull(shadowOf(host).nextStartedActivity)
        assertEquals(DocumentViewerActivity::class.java.name, started.component?.className)
    }

    @Test
    fun `a long press drag moves the tile and the draft`() {
        val id = DocFixtures.saved(repo, app, pages = 3)
        val baseFirst = repo.getDocument(id)!!.pageFiles.first().path
        val d0 = digest(id)
        ActivityScenario.launch<DocumentViewerActivity>(DocumentViewerActivity.intent(app, id)).use { scenario ->
            var cells = 0
            assertTrue(pollUntil {
                scenario.onActivity { cells = it.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.pageList).adapter?.itemCount ?: 0 }
                cells == 3
            })
            var moved = false
            scenario.onActivity { activity ->
                val grid = activity.findViewById<com.scanwhirl.app.ui.pagegrid.PageGridView>(R.id.pageList)
                grid.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY)
                )
                grid.layout(0, 0, 1080, 1920)
                moved = grid.simulateDrag(0, 2)
            }
            assertTrue("the drag must start and report a drop", moved)
            assertTrue(
                "the draft order must change",
                pollUntil {
                    runBlocking { repo.draftPages(id) }.firstOrNull()?.path != baseFirst
                }
            )
            assertEquals("the base changed during a drag", d0, digest(id))
        }
    }

    @Test
    fun `selection more has rotate duplicate insert and extract and duplicate works`() {
        val id = DocFixtures.saved(repo, app, pages = 3)
        val d0 = digest(id)
        ActivityScenario.launch<DocumentViewerActivity>(DocumentViewerActivity.intent(app, id)).use { scenario ->
            var cells = 0
            assertTrue(pollUntil {
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
                grid.startDragAt(0)
                activity.findViewById<android.view.View>(R.id.btnSelMore).performClick()
            }
            idle()
            val sheet = requireNotNull(sheet())
            val rows = requireNotNull(sheet.findViewById<LinearLayout>(R.id.draftMoreRows))
            val labels = (0 until rows.childCount).map { i ->
                (rows.getChildAt(i) as LinearLayout).getChildAt(1) as TextView
            }.map { it.text.toString() }
            assertTrue(labels.contains(app.getString(R.string.draft_rotate_left)))
            assertTrue(labels.contains(app.getString(R.string.draft_rotate_right)))
            assertTrue(labels.contains(app.getString(R.string.draft_duplicate)))
            assertTrue(labels.contains(app.getString(R.string.draft_insert_here)))
            assertTrue(labels.contains(app.getString(R.string.draft_extract_new)))
            assertFalse("Move to is for 10+ pages", labels.contains(app.getString(R.string.draft_move_to)))

            // Duplicate row (index 2) copies the selected page into the draft.
            (rows.getChildAt(2)).performClick()
            assertTrue(
                "duplicate must add a draft page",
                pollUntil { runBlocking { repo.draftPages(id) }.size == 4 }
            )
            assertEquals(d0, digest(id))
        }
    }

    @Test
    fun `insert pages at the start middle and end in the organizer`() {
        fun insertedWidths(position: Int): List<Int> {
            val target = DocFixtures.saved(repo, app, pages = 3)
            val source = runBlocking {
                repo.createDocument(
                    "Insert source",
                    listOf(com.scanwhirl.app.util.ImportFixtures.image(width = 300, height = 400))
                )
            }
            val src = repo.getDocument(source.id)!!.pageFiles
            ActivityScenario.launch<DocumentViewerActivity>(DocumentViewerActivity.intent(app, target)).use { scenario ->
                var cells = 0
                assertTrue(pollUntil {
                    scenario.onActivity { cells = it.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.pageList).adapter?.itemCount ?: 0 }
                    cells == 3
                })
                scenario.onActivity { it.insertFiles(src, position) }
                assertTrue("insert lands", pollUntil { runBlocking { repo.draftPages(target) }.size == 4 })
                val pages = runBlocking { repo.draftPages(target) }
                return pages.map {
                    requireNotNull(
                        com.scanwhirl.app.util.ImageTools.imageSize(it.readBytes())
                    ).first
                }
            }
            return emptyList()
        }

        assertEquals("at the start", listOf(300, 200, 220, 240), insertedWidths(0))
        assertEquals("after page 2", listOf(200, 220, 300, 240), insertedWidths(2))
        assertEquals("at the end", listOf(200, 220, 240, 300), insertedWidths(3))
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
                    app.getString(
                        R.string.draft_header,
                        activity.resources.getQuantityString(R.plurals.draft_pages, 3, 3),
                        activity.resources.getQuantityString(R.plurals.draft_changes, 1, 1)
                    ),
                    activity.findViewById<TextView>(R.id.draftHeader).text.toString()
                )
            }
        }
    }

    /**
     * B10: a pending delete whose async commit has already started is invisible to
     * `commitPendingDeletesNow` (the paths were removed from the set), so Replace republished the
     * page the user deleted and the late job then wrote a phantom draft.
     */
    @Test
    fun `Replace waits for a pending delete that is already committing`() {
        val id = DocFixtures.saved(repo, app, pages = 3)
        ActivityScenario.launch<DocumentViewerActivity>(DocumentViewerActivity.intent(app, id)).use { scenario ->
            var cells = 0
            assertTrue("the grid loads", pollUntil {
                scenario.onActivity { cells = it.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.pageList).adapter?.itemCount ?: 0 }
                cells == 3
            })

            val gate = java.util.concurrent.CountDownLatch(1)
            val entered = java.util.concurrent.CountDownLatch(1)
            lateinit var activity: DocumentViewerActivity
            scenario.onActivity { a ->
                activity = a
                a.deleteCommitGate = {
                    entered.countDown()
                    gate.await()
                }
            }
            scenario.onActivity { a ->
                a.toggleSelected(0)
                a.findViewById<android.view.View>(R.id.btnSelDelete)!!.performClick()
            }
            idle()
            primary()
            scenario.onActivity { it.commitPendingDelete() }
            assertTrue(
                "the delete job is running",
                entered.await(5, java.util.concurrent.TimeUnit.SECONDS)
            )

            val replace = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
                .async { activity.replaceOriginalNow() }
            Thread.sleep(300) // let Replace reach the pending deletes
            gate.countDown()
            val replaced = runBlocking { replace.await() }

            assertEquals(
                "Replace must not republish the page the user deleted",
                2,
                replaced.document.pageFiles.size
            )
            idle()
            assertNull(
                "no phantom draft may be re-created by the late delete job",
                runBlocking { repo.draftOf(id) }
            )
        }
    }
}
