package com.scanwhirl.app.ui.shell
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule
import org.junit.rules.Timeout

import android.app.Application
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
import com.scanwhirl.app.ui.DocumentViewerActivity
import com.scanwhirl.app.ui.TrashActions
import com.scanwhirl.app.ui.kit.DocCard
import com.scanwhirl.app.util.ProGate
import com.scanwhirl.app.util.ShareUtils
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.robolectric.shadows.ShadowToast
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



    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null)
        TrashActions.takePending() // no notice left over from another test
        // androidx FileProvider caches its roots per authority in a static map; each Robolectric
        // test gets a fresh cacheDir, so a stale entry makes share/export URI creation fail.
        val cache = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
        cache.isAccessible = true
        (cache.get(null) as MutableMap<*, *>).clear()
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

    @Test(timeout = 60_000)
    fun `the row overflow opens the Actions sheet bound to that row's document`() {
        val older = DocFixtures.saved(repo, app, pages = 2, name = "Rent 2025")
        val shown = DocFixtures.saved(repo, app, pages = 3, name = "Rent 2026")

        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val sheet = openRowActions(activity, shown)
                assertEquals(
                    "the sheet is the shared Document actions sheet",
                    app.getString(R.string.v3_actions_title),
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

    @Test(timeout = 60_000)
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

    @Test(timeout = 60_000)
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

    @Test(timeout = 60_000)
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

    @Test(timeout = 60_000)
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

    @Test(timeout = 60_000)
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

    // --- 7. W1: Share from the row sheet sends that row's own bytes --------------

    /**
     * W1: two near-identical Indian document names. Share is tapped on the newer row's sheet and
     * the recipient must get THAT row's saved PDF, byte for byte, never the older twin.
     */
    @Test(timeout = 60_000)
    fun `Share from the row sheet sends the tapped row's own PDF`() {
        val older = DocFixtures.saved(repo, app, pages = 2, name = "Electricity bill Oct - FAKE")
        val shown = DocFixtures.saved(repo, app, pages = 3, name = "Electricity bill Oct (2) - FAKE")
        val olderBytes = repo.getDocument(older)!!.pdfFile.readBytes()
        val shownBytes = repo.getDocument(shown)!!.pdfFile.readBytes()
        assertFalse("the fixtures really differ", olderBytes.contentEquals(shownBytes))

        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val sheet = openRowActions(activity, shown)
                val share = requireNotNull(
                    sheet.window?.decorView?.findViewWithTag<View>("docActions:share")
                ) { "the row sheet has a Share row" }
                share.performClick()
                assertTrue(
                    "Share opens the chooser",
                    poll { shadowOf(activity).peekNextStartedActivity() != null }
                )
                val chooser = shadowOf(activity).peekNextStartedActivity()
                assertEquals(Intent.ACTION_CHOOSER, chooser.action)
                val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                assertEquals(Intent.ACTION_SEND, send?.action)
                assertNotNull("the chooser carries one stream", send?.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))
            }
        }

        val shared = sharedCopy("Electricity bill Oct (2) - FAKE")
        assertTrue("the tapped row's PDF was prepared: ${shared.absolutePath}", shared.isFile)
        assertArrayEquals(
            "the shared bytes are the tapped row's saved PDF",
            shownBytes,
            shared.readBytes()
        )
        assertFalse(
            "the older twin was never handed to the chooser",
            sharedCopy("Electricity bill Oct - FAKE").exists()
        )
        assertArrayEquals(
            "the older document is untouched",
            olderBytes,
            repo.getDocument(older)!!.pdfFile.readBytes()
        )
    }

    /** W1: a row whose document left the library after the sheet opened says so; no chooser. */
    @Test(timeout = 60_000)
    fun `Share from a row whose document was deleted says so and sends nothing`() {
        val shown = DocFixtures.saved(repo, app, pages = 2, name = "Water bill Aug - FAKE")

        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val sheet = openRowActions(activity, shown)
                repo.deleteDocument(shown)
                requireNotNull(
                    sheet.window?.decorView?.findViewWithTag<View>("docActions:share")
                ).performClick()
                val expected = app.getString(R.string.w1_doc_gone)
                assertTrue(
                    "the removed document is named out loud, was '${ShadowToast.getTextOfLatestToast()}'",
                    poll { ShadowToast.getTextOfLatestToast() == expected }
                )
                assertNull("nothing reached the chooser", shadowOf(activity).peekNextStartedActivity())
            }
        }
        assertFalse(
            "no shared copy was written",
            sharedCopy("Water bill Aug - FAKE").exists()
        )
    }

    /**
     * W1: a row whose document has an unsaved page edit asks before sharing, and "Finish editing
     * first" opens that document (nothing is shared or committed).
     */
    @Test(timeout = 60_000)
    fun `Share from a row with an unsaved draft asks and Finish editing opens the document`() {
        val shown = DocFixtures.saved(repo, app, pages = 2, name = "Electricity bill Sep - FAKE")
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                repo.draftRotate(shown, 0, true)
            }
        }
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val sheet = openRowActions(activity, shown)
                requireNotNull(
                    sheet.window?.decorView?.findViewWithTag<View>("docActions:share")
                ).performClick()

                var question: android.app.Dialog? = null
                assertTrue(
                    "the unsaved page-change question appears",
                    poll {
                        val dialog = ShadowDialog.getLatestDialog()
                        val matches = dialog != null && dialog.isShowing &&
                            dialog.findViewById<TextView>(R.id.swSheetTitle)?.text?.toString() ==
                            app.getString(R.string.w1_draft_action_title)
                        if (matches) question = dialog
                        matches
                    }
                )
                assertNull("no chooser before the answer", shadowOf(activity).peekNextStartedActivity())

                val answers = requireNotNull(
                    question!!.findViewById<ViewGroup>(R.id.swSheetItems)
                )
                (answers.getChildAt(0) as View).performClick() // Finish editing first
                assertTrue(
                    "Finish editing first opens the document so the edits can be finished",
                    poll {
                        shadowOf(activity).peekNextStartedActivity()?.component?.className ==
                            DocumentViewerActivity::class.java.name
                    }
                )
                assertEquals(
                    "the opened screen is that row's document",
                    shown,
                    shadowOf(activity).peekNextStartedActivity()?.getStringExtra("doc_id")
                )
                assertNull(
                    "Finish editing first shares nothing",
                    sharedCopy("Electricity bill Sep - FAKE").takeIf { it.exists() }
                )
            }
        }
    }

    // --- helpers -------------------------------------------------------------

    /** Where ShareUtils puts the file it hands to the chooser (cache, provider-readable). */
    private fun sharedCopy(name: String): File {
        val shareDir = File(app.cacheDir, "documents/share")
        val fileName = ShareUtils.sanitizeName(name) + ".pdf"
        return shareDir.walkTopDown().firstOrNull { it.isFile && it.name == fileName }
            ?: File(shareDir, fileName)
    }

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
