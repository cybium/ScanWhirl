package com.scanwhirl.app.ui.shell

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.DocumentViewerActivity
import com.scanwhirl.app.ui.TrashActions
import com.scanwhirl.app.ui.kit.DocCard
import com.scanwhirl.app.util.ProGate
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
import java.io.File

/**
 * UI-B5: a Library row's overflow (⋮) opens the SAME named Actions sheet the document
 * screen's dock uses - bound to that row's document id - not the old three-line sheet.
 *
 * Every assertion is made from the library list itself, with two near-identical
 * documents so a sheet that reached for "the newest document" would fail: doc A is
 * older, doc B is newer (first in a newest-first list) and is the one whose ⋮ is tapped.
 *
 * Red first: written against the pre-B5 row sheet, which had no header, no binder, no
 * Rename/Move/Duplicate/Protect rows and no disabled reason for a locked document.
 *
 * T27 stays guarded: Move to Trash from this sheet must MOVE (restorable), never purge,
 * and the anchored "Moved to Trash" Undo notice must still be the one the shell shows.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
class LibraryRowActionsTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null)
        TrashActions.takePending() // no notice left over from another test
    }

    @After
    fun tearDown() {
        TrashActions.takePending()
        ProGate.setTestOverride(app, null)
        ProGate.setPro(app, false)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun poll(timeoutMs: Long = 8_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        idle()
        return condition()
    }

    private fun cards(activity: ShellActivity): List<DocCard> {
        val list = activity.findViewById<ViewGroup>(R.id.swDocList)
        return (0 until list.childCount).map { list.getChildAt(it) as DocCard }
    }

    /** The newest showing SwSheet (titled): fails with [what] when none is up. */
    private fun latestDialog(what: String): android.app.Dialog {
        var found: android.app.Dialog? = null
        poll {
            val dialog = ShadowDialog.getLatestDialog()
            if (dialog != null && dialog.isShowing &&
                dialog.findViewById<View>(R.id.swSheetTitle) != null
            ) {
                found = dialog
                true
            } else {
                false
            }
        }
        return requireNotNull(found) { "$what never appeared" }
    }

    private fun sheetTitle(dialog: android.app.Dialog): String =
        dialog.findViewById<TextView>(R.id.swSheetTitle).text.toString()

    private fun sheetHeaderName(dialog: android.app.Dialog): String {
        val name = requireNotNull(
            dialog.window?.decorView?.findViewWithTag<TextView>("docActions:name")
        ) { "the Actions sheet must repeat the row's document name in its header" }
        return name.text.toString()
    }

    /** Opens the ⋮ Actions sheet for the row holding [id] and returns it. */
    private fun openRowActions(activity: ShellActivity, id: String): android.app.Dialog {
        assertTrue(
            "the row for $id is listed",
            poll { cards(activity).any { it.doc?.id == id } }
        )
        cards(activity).first { it.doc?.id == id }.moreButton.performClick()
        idle()
        return latestDialog("the row's Actions sheet")
    }

    private fun rowOf(dialog: android.app.Dialog, id: Int, label: String): View =
        requireNotNull(dialog.findViewById<View>(id)) { "the Actions sheet has no '$label' row" }

    // --- 1. the ⋮ opens the same bound sheet, not the old one -------------------

    @Test
    fun `the row overflow opens the Actions sheet bound to that row's document`() {
        val older = DocFixtures.saved(repo, app, pages = 2, name = "Rent 2025")
        val shown = DocFixtures.saved(repo, app, pages = 3, name = "Rent 2026")

        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val sheet = openRowActions(activity, shown)
                assertEquals(
                    "the sheet is the shared Actions sheet",
                    app.getString(R.string.doc_actions),
                    sheetTitle(sheet)
                )
                assertEquals(
                    "the header names the row's own document, not the newest one",
                    "Rent 2026",
                    sheetHeaderName(sheet)
                )
                assertFalse(
                    "the header never names the other document",
                    sheetHeaderName(sheet).contains("Rent 2025")
                )
                // The shared sheet's named rows exist for this document.
                rowOf(sheet, R.id.rowMoreRename, "Rename")
                rowOf(sheet, R.id.rowMoreMove, "Move to category")
                rowOf(sheet, R.id.rowMoreDelete, "Move to Trash")
                assertNotNull(repo.getDocument(older))
            }
        }
    }

    // --- 2. the row itself still opens the document ---------------------------

    @Test
    fun `tapping the row still opens that document`() {
        val older = DocFixtures.saved(repo, app, pages = 2, name = "Tap me")
        val newer = DocFixtures.saved(repo, app, pages = 1, name = "Not me")

        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue("both rows are listed", poll { cards(activity).size == 2 })
                cards(activity).first { it.doc?.id == older }.performClick()
                idle()
                val started = shadowOf(activity).nextStartedActivity
                assertNotNull("the row opens the document screen", started)
                assertEquals(
                    DocumentViewerActivity::class.java.name,
                    started.component?.className
                )
                assertEquals(
                    "it opens the tapped document, not the newest",
                    older,
                    started.getStringExtra("doc_id")
                )
                assertEquals("the other row is untouched", "Not me", repo.getDocument(newer)!!.name)
            }
        }
    }

    // --- 3. Rename shows in the list immediately -------------------------------

    @Test
    fun `Rename from the row sheet updates that row in the library list`() {
        val other = DocFixtures.saved(repo, app, pages = 2, name = "Stay the same")
        val shown = DocFixtures.saved(repo, app, pages = 3, name = "Old name")

        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val sheet = openRowActions(activity, shown)
                rowOf(sheet, R.id.rowMoreRename, "Rename").performClick()
                idle()

                val rename = latestDialog("the Rename sheet")
                val input = requireNotNull(
                    rename.window?.decorView?.findViewById<android.widget.EditText>(R.id.swSheetInput)
                ) { "the Rename sheet has an input" }
                input.setText("New name")
                rename.findViewById<View>(R.id.swSheetPrimary).performClick()
                idle()

                assertTrue(
                    "the renamed row shows its new name without leaving the list",
                    poll {
                        cards(activity).any { it.doc?.id == shown && it.nameView.text.toString() == "New name" }
                    }
                )
                assertEquals("the repository has the new name", "New name", repo.getDocument(shown)!!.name)
                assertEquals(
                    "the other row keeps its name",
                    "Stay the same",
                    cards(activity).first { it.doc?.id == other }.nameView.text.toString()
                )
            }
        }
    }

    // --- 4. Move to Trash from the row sheet: moves, never purges (T27) --------

    @Test
    fun `Move to Trash from the row sheet trashes only that document and Undo restores it`() {
        val kept = DocFixtures.saved(repo, app, pages = 2, name = "Keep me")
        val shown = DocFixtures.saved(repo, app, pages = 3, name = "Trash me")
        val pdf: File = repo.getDocument(shown)!!.pdfFile
        assertTrue("fixture has a real PDF", pdf.isFile)
        val pdfBytes = pdf.length()
        val pagesBefore = repo.getDocument(shown)!!.pageFiles.map { it.absolutePath }

        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val sheet = openRowActions(activity, shown)
                rowOf(sheet, R.id.rowMoreDelete, "Move to Trash").performClick()
                idle()

                val confirm = latestDialog("the Trash confirmation")
                assertTrue(
                    "the confirmation names the Trash destination, got: ${sheetTitle(confirm)}",
                    sheetTitle(confirm).contains(repo.getDocument(shown)!!.name)
                )
                confirm.findViewById<View>(R.id.swSheetPrimary).performClick()

                assertTrue("the document leaves the library", poll { repo.getDocument(shown) == null })
                assertTrue(
                    "the row is gone from the list",
                    poll { cards(activity).none { it.doc?.id == shown } }
                )
                assertEquals("the other document stays", 1, cards(activity).size)
                assertNotNull(repo.getDocument(kept))

                val trashed = repo.listTrash().singleOrNull()
                assertNotNull("T27: a permanent purge is the bug - it must move to Trash", trashed)
                assertEquals(shown, trashed!!.originalId)
                assertEquals("the whole document moved", 3, trashed.document.pageCount)
                // A move relocates the folder; nothing is deleted, so the same bytes are
                // still on this phone inside the Trash entry.
                assertTrue(
                    "the PDF is preserved in Trash, not deleted",
                    trashed.document.pdfFile.isFile &&
                        trashed.document.pdfFile.length() == pdfBytes
                )
                assertEquals(
                    "no page file is deleted",
                    pagesBefore.size,
                    trashed.document.pageFiles.size
                )

                // The same shell "Moved to Trash" notice the multi-select path shows.
                val action = pollSnackbarAction(activity)
                assertNotNull("a Moved to Trash snackbar with Undo must be shown", action)
                assertEquals(app.getString(R.string.undo), action!!.text.toString())
                val text = snackbarText(activity)
                assertTrue("it names the moved document, got: $text", text!!.contains("Trash me"))

                action.performClick()
                assertTrue("Undo brings the document back", poll { repo.getDocument(shown) != null })
                assertEquals("the whole document is back", 3, repo.getDocument(shown)!!.pageCount)
                assertTrue(
                    "Undo puts the PDF back at its original path, byte-for-byte",
                    pdf.isFile && pdf.length() == pdfBytes
                )
                assertTrue("Trash is empty again", repo.listTrash().isEmpty())
                assertTrue(
                    "the row is back in the list",
                    poll { cards(activity).any { it.doc?.id == shown } }
                )
            }
        }
    }

    // --- 5. a locked document: visible rows, disabled with the reason ---------

    @Test
    fun `a locked document's row sheet disables the page rows and shows the reason`() {
        val id = DocFixtures.locked(repo, app, password = "1234", pages = 2)

        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val sheet = openRowActions(activity, id)
                val docName = repo.getDocument(id)!!.name
                val reason = app.getString(R.string.locked_source_refused, docName)

                // Rename is allowed on a locked document (a name is not a page).
                assertTrue(
                    "Rename stays enabled",
                    sheet.findViewById<View>(R.id.rowMoreRename).isEnabled
                )
                for ((key, row) in listOf(
                    "compress" to sheet.findViewById<View>(R.id.rowMoreCompress),
                    "sign" to sheet.window?.decorView?.findViewWithTag<View>("docActions:sign"),
                    "read_text" to sheet.window?.decorView?.findViewWithTag<View>("docActions:read_text")
                )) {
                    assertNotNull("row '$key' exists so its reason is visible", row)
                    assertFalse("row '$key' is disabled for a locked document", row!!.isEnabled)
                    assertTrue(
                        "row '$key' says why, was '${row.contentDescription}'",
                        row.contentDescription?.toString()?.contains(reason) == true
                    )
                }
                // The destructive row stays reachable (it is the ordinary Trash move).
                assertTrue(
                    "Move to Trash stays enabled",
                    sheet.findViewById<View>(R.id.rowMoreDelete).isEnabled
                )
            }
        }
    }

    // --- 6. the English words are the shared sheet's, not a second vocabulary --

    @Test
    fun `the row sheet speaks the shared English row words`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Words")

        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val sheet = openRowActions(activity, id)
                val trash = requireNotNull(
                    sheet.findViewById<TextView>(R.id.labelMoreDelete)
                )
                assertEquals(app.getString(R.string.trash_move), trash.text.toString())
                assertEquals("no second sheet vocabulary", "Move to Trash", trash.text.toString())
                val renameRow = requireNotNull(
                    sheet.window?.decorView?.findViewWithTag<View>("docActions:rename")
                )
                assertTrue(
                    "the Rename row carries the shared label",
                    renameRow.contentDescription?.toString() == app.getString(R.string.rename)
                )
            }
        }
    }

    // --- helpers -------------------------------------------------------------

    private fun findById(root: View, id: Int): View? {
        if (root.id == id) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findById(root.getChildAt(i), id)?.let { return it }
            }
        }
        return null
    }

    private fun snackbarAction(activity: ShellActivity): TextView? =
        findById(activity.window!!.decorView, com.google.android.material.R.id.snackbar_action) as? TextView

    private fun snackbarText(activity: ShellActivity): String? =
        (findById(activity.window!!.decorView, com.google.android.material.R.id.snackbar_text) as? TextView)
            ?.text?.toString()?.takeIf { it.isNotEmpty() }

    private fun pollSnackbarAction(activity: ShellActivity): TextView? {
        var found: TextView? = null
        poll { snackbarAction(activity).also { found = it } != null }
        return found
    }
}
