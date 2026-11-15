package com.scanwhirl.app.ui.shell

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.LibraryMeta
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.kit.DocCard
import com.scanwhirl.app.util.BatchRename
import com.scanwhirl.app.util.ProGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

/**
 * v2 UI: 03_tools_hub — Folders & tags and Batch rename: metadata round-trip,
 * folder semantics (no document is ever lost), selection mode, tag search and
 * Pro gating.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class LibraryOrganisationTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null) // test build: Pro
    }

    @After
    fun tearDown() {
        ProGate.setTestOverride(app, null)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun launch(block: (ShellActivity) -> Unit) {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                block(activity)
            }
        }
    }

    private fun library(activity: ShellActivity): LibraryScreen {
        val host = activity.findViewById<ViewGroup>(R.id.swTabContent)
        val scroll = host.getChildAt(0) as ScrollView
        return scroll.getChildAt(0) as LibraryScreen
    }

    private fun cards(activity: ShellActivity): List<DocCard> {
        val list = activity.findViewById<ViewGroup>(R.id.swDocList)
        return (0 until list.childCount).map { list.getChildAt(it) as DocCard }
    }

    // ---- metadata ---------------------------------------------------------

    @Test
    fun `old metadata reads as no folder and no tags and survives a folder write`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Old format")
        // Rewrite meta.txt the way an old build left it: three fixed lines only.
        val meta = File(File(repo.docsDir, id), "meta.txt")
        meta.writeText("Old format\n1700000000000\nother\n")

        val before = repo.getDocument(id)!!
        assertNull("old documents have no folder", before.folder)
        assertTrue("old documents have no tags", before.tags.isEmpty())

        repo.setFolder(listOf(id), "Holidays")
        repo.addTag(listOf(id), "trip")
        val after = repo.getDocument(id)!!
        assertEquals("Holidays", after.folder)
        assertEquals(listOf("trip"), after.tags)
        assertEquals("name line is untouched", "Old format", meta.readLines()[0])
        assertEquals("createdAt is untouched", "1700000000000", meta.readLines()[1])
        assertEquals("category is untouched", "other", meta.readLines()[2])
    }

    @Test
    fun `folder and tag encodings round-trip commas unicode and duplicates`() {
        val lines = mutableListOf("Doc", "1", "other", "exports=content://x")
        LibraryMeta.write(lines, "  Tax   2026 ", listOf("GST, 18%", "gst, 18%", "लेखा", ""))
        val read = LibraryMeta.read(lines)
        assertEquals("Tax 2026", read.folder)
        assertEquals("case-insensitive dedupe, comma-safe", listOf("GST, 18%", "लेखा"), read.tags)
        assertTrue("unrelated lines survive", lines.contains("exports=content://x"))
    }

    // ---- folder semantics -------------------------------------------------

    @Test
    fun `rename and delete a folder never lose a document`() {
        val a = DocFixtures.saved(repo, app, pages = 1, name = "Folder A")
        val b = DocFixtures.saved(repo, app, pages = 1, name = "Folder B")
        assertTrue(repo.createFolder("Tax"))
        assertTrue("created folders survive empty", repo.listFolders().contains("Tax"))

        repo.setFolder(listOf(a, b), "Tax")
        assertEquals("Tax", repo.getDocument(a)!!.folder)

        assertTrue(repo.renameFolder("Tax", "Tax 2026"))
        assertEquals("Tax 2026", repo.getDocument(a)!!.folder)
        assertEquals("Tax 2026", repo.getDocument(b)!!.folder)
        assertEquals(listOf("Tax 2026"), repo.listFolders())

        repo.deleteFolder("Tax 2026")
        val all = repo.listDocuments()
        assertTrue("documents survive folder deletion", all.any { it.id == a } && all.any { it.id == b })
        assertNull(repo.getDocument(a)!!.folder)
        assertNull(repo.getDocument(b)!!.folder)
        assertTrue("folder is gone", repo.listFolders().isEmpty())
    }

    @Test
    fun `moving to a new folder creates it and moving to All clears it`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Move me")
        repo.setFolder(listOf(id), "Bills")
        assertTrue(repo.listFolders().contains("Bills"))
        assertEquals("Bills", repo.getDocument(id)!!.folder)
        repo.setFolder(listOf(id), null)
        assertNull(repo.getDocument(id)!!.folder)
        assertTrue("the explicit folder stays", repo.listFolders().contains("Bills"))
    }

    // ---- selection mode ---------------------------------------------------

    @Test
    fun `checks appear only in selection mode, after a long-press`() {
        DocFixtures.saved(repo, app, pages = 1, name = "First")
        DocFixtures.saved(repo, app, pages = 1, name = "Second")
        launch { activity ->
            val all = cards(activity)
            assertEquals(2, all.size)
            assertTrue("no check before selecting", all.none { it.selectionBadge.isVisible })
            assertFalse("bar hidden", activity.findViewById<View>(R.id.swSelectionBar).isVisible)

            all[0].performLongClick()
            idle()
            val after = cards(activity)
            assertTrue("the pressed card is checked", after[0].selectionBadge.isVisible)
            assertFalse("the other card is not", after[1].selectionBadge.isVisible)
            assertTrue("bar appears", activity.findViewById<View>(R.id.swSelectionBar).isVisible)
            assertFalse("share folds into the bar", after[0].shareButton.isVisible)

            after[1].performClick()
            idle()
            assertTrue("tapping adds to the selection", cards(activity)[1].selectionBadge.isVisible)
            assertEquals(
                activity.resources.getQuantityString(R.plurals.sel_count, 2, 2),
                activity.findViewById<TextView>(R.id.swSelCount).text.toString()
            )
        }
    }

    @Test
    fun `the Batch action opens BatchActivity with the selected ids`() {
        val one = DocFixtures.saved(repo, app, pages = 1, name = "One")
        val two = DocFixtures.saved(repo, app, pages = 1, name = "Two")
        launch { activity ->
            val all = cards(activity)
            all[0].performLongClick()
            idle()
            all[1].performClick()
            idle()
            // Drop anything the shell started while loading.
            while (shadowOf(activity).nextStartedActivity != null) Unit

            activity.findViewById<View>(R.id.swSelBatch).performClick()
            idle()
            val started = shadowOf(activity).nextStartedActivity
            assertNotNull("Batch opens the Batch screen", started)
            assertEquals(
                com.scanwhirl.app.ui.tools.BatchActivity::class.java.name,
                started!!.component?.className
            )
            assertEquals(
                "both selected ids are passed, nothing else",
                setOf(one, two),
                started.getStringArrayListExtra(
                    com.scanwhirl.app.ui.tools.BatchActivity.EXTRA_DOC_IDS
                )?.toSet()
            )
        }
    }

    @Test
    fun `search also matches a tag`() {
        val tagged = DocFixtures.saved(repo, app, pages = 1, name = "Alpha")
        DocFixtures.saved(repo, app, pages = 1, name = "Beta")
        repo.addTag(listOf(tagged), "holiday")
        launch { activity ->
            activity.findViewById<EditText>(R.id.swHomeSearch).setText("holiday")
            idle()
            val shown = cards(activity)
            assertEquals(1, shown.size)
            assertEquals("Alpha", shown.single().doc?.name)
        }
    }

    // ---- Pro gating -------------------------------------------------------

    @Test
    fun `Free shows the Pro screen for New folder and Tags`() {
        ProGate.setTestOverride(app, false)
        DocFixtures.saved(repo, app, pages = 1, name = "Gated")
        launch { activity ->
            val folderRow = activity.findViewById<ViewGroup>(R.id.swFolderRow)
            val newFolder = folderRow.getChildAt(folderRow.childCount - 1)
            newFolder.performClick()
            idle()
            assertTrue(
                "New folder in Free opens the Pro screen",
                activity.findViewById<View>(R.id.swProScreen)?.isVisible == true
            )
            activity.findViewById<View>(R.id.swProClose).performClick()
            idle()

            cards(activity).first().performLongClick()
            idle()
            activity.findViewById<View>(R.id.swSelTags).performClick()
            idle()
            assertTrue(
                "Tags in Free opens the Pro screen",
                activity.findViewById<View>(R.id.swProScreen)?.isVisible == true
            )
        }
    }

    // ---- batch rename -----------------------------------------------------

    @Test
    fun `batch rename previews and applies a pattern, and undo restores the names`() {
        val one = DocFixtures.saved(repo, app, pages = 1, name = "Invoice old 1")
        val two = DocFixtures.saved(repo, app, pages = 1, name = "Invoice old 2")
        val preview = BatchRename.preview(
            "Bill {n}", listOf(BatchRename.Item("A", 0), BatchRename.Item("B", 0), BatchRename.Item("C", 0))
        )
        assertEquals(listOf("Bill 1", "Bill 2", "Bill 3"), preview)

        launch { activity ->
            val lib = library(activity)
            lib.startBatchRename()
            idle()
            cards(activity).first { it.doc?.id == one }.performClick()
            idle()
            cards(activity).first { it.doc?.id == two }.performClick()
            idle()
            assertEquals(
                "two documents selected",
                activity.resources.getQuantityString(R.plurals.sel_count, 2, 2),
                activity.findViewById<TextView>(R.id.swSelCount).text.toString()
            )
            // v2 UI: 74_motion — the bar overlays the list, it does not shrink it.
            assertEquals(
                "the selection bar is an overlay",
                R.id.swListHost,
                (activity.findViewById<View>(R.id.swSelectionBar).parent as View).id
            )
            assertTrue(
                "the list keeps its rows (inset, not shrunk)",
                (activity.findViewById<View>(R.id.swDocList)).paddingTop >=
                    90 * activity.resources.displayMetrics.density
            )
            activity.findViewById<View>(R.id.swSelRename).performClick()
            idle()

            val sheet = ShadowDialog.getLatestDialog()
            assertNotNull("batch rename sheet opens", sheet)
            val input = sheet.findViewById<EditText>(R.id.swBatchInput)
            assertNotNull("pattern field", input)
            input.setText("Bill {n}")
            val shownPreview = sheet.findViewById<TextView>(R.id.swBatchPreview).text.toString()
            assertTrue("live preview shows the first names: $shownPreview", shownPreview.contains("Bill 1"))
            assertTrue(shownPreview.contains("Bill 2"))
            sheet.findViewById<View>(R.id.swSheetPrimary).performClick()
            idle()

            val names = listOf(one, two).map { repo.getDocument(it)!!.name }
            assertTrue("both renamed", names.all { it.startsWith("Bill ") })
            assertNotEquals(names[0], names[1])

            library(activity).undoLastBatchRename()
            idle()
            assertEquals("undo restores the first name", "Invoice old 1", repo.getDocument(one)!!.name)
            assertEquals("undo restores the second name", "Invoice old 2", repo.getDocument(two)!!.name)

            // v2 UI: 74_motion — the Undo action is anchored above the nav and
            // carries its words for TalkBack.
            val snack = library(activity).lastUndoSnackbar
            assertNotNull("the Undo snackbar is shown", snack)
            assertNotNull("it is anchored above the nav", snack!!.view.tag)
            val action = snack.view.findViewById<TextView>(
                com.google.android.material.R.id.snackbar_action
            )
            assertEquals("Undo", action.text.toString())
            assertEquals("Undo", action.contentDescription.toString())
        }
    }
}
