package com.scanwhirl.app.ui.shell
import org.junit.Rule
import org.junit.rules.Timeout

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.DocumentViewerActivity
import com.scanwhirl.app.ui.TrashActions
import com.scanwhirl.app.ui.TrashActivity
import com.scanwhirl.app.ui.kit.DocCard
import com.scanwhirl.app.util.ProGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

/**
 * T27-FIX-TRASH (data loss, also in Play 1.2.2): every user-facing "Move to Trash"
 * must really move the document to Trash, not purge it.
 *
 * The library row sheet promised "You can restore it for 30 days" and called
 * `repo.deleteDocument` (permanent purge); the library multi-select did the same.
 * These tests click the real sheets, then require: gone from the library, present
 * in [ScanRepository.listTrash] with the same page count, restorable whole, and the
 * "Moved to Trash" snackbar's Undo brings it back.
 *
 * The last test is a source guard: a UI file may only call `repo.deleteDocument`
 * where a permanent purge is the documented, deliberate behaviour (explicit list).
 * It fails if any other file under the ui source tree grows such a call.
 *
 * Behaviour that must stay: a legacy Vault item is never moved to Trash (it would
 * expose Vault content in the ordinary Trash list), and discarding a just-imported
 * copy stays permanent (the picked file is untouched; the sheet says 'Discard').
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class TrashEntryPointsFixTest {


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

    // ---- the library row's Actions sheet (DocumentActionsSheet) --------------

    @Test(timeout = 60_000)
    fun `row sheet moves the document to Trash and Undo restores it`() {
        val id = DocFixtures.saved(repo, app, pages = 3, name = "Row sheet doc")

        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue("the library lists the document", poll { cards(activity).any { it.doc?.id == id } })
                cards(activity).first { it.doc?.id == id }.moreButton.performClick()
                idle()
                // UI-B5: the ⋮ opens the shared Actions sheet; its destructive row is the
                // one that must move to Trash (T27), never purge.
                val actions = latestDialog("the row's Actions sheet")
                val delete = actions.findViewById<View>(R.id.rowMoreDelete)
                assertNotNull("the sheet offers Move to Trash", delete)
                assertEquals(
                    "the move row names the Trash destination",
                    app.getString(R.string.trash_move),
                    actions.findViewById<TextView>(R.id.labelMoreDelete).text.toString()
                )
                delete!!.performClick()
                idle()

                val confirm = latestDialog("the trash confirmation")
                val title = confirm.findViewById<TextView>(R.id.swSheetTitle).text.toString()
                assertTrue("the confirmation is the Trash one, got: $title", title.contains("Trash"))
                confirm.findViewById<View>(R.id.swSheetPrimary).performClick()
                idle()

                assertTrue("the document leaves the library", poll { repo.getDocument(id) == null })
                val trashed = repo.listTrash().singleOrNull()
                assertNotNull(
                    "a permanent purge is the bug: the row sheet must move to Trash",
                    trashed
                )
                assertEquals("the trashed entry is this document", id, trashed!!.originalId)
                assertEquals("the whole document moved", 3, trashed.document.pageCount)
                assertTrue(
                    "the row is gone from the list",
                    poll { cards(activity).none { it.doc?.id == id } }
                )

                // The existing "Moved to Trash" confirmation, with the real Undo action.
                val action = pollSnackbarAction(activity)
                assertNotNull("a Moved to Trash snackbar with Undo must be shown", action)
                assertEquals(app.getString(R.string.undo), action!!.text.toString())
                action.performClick()

                assertTrue("Undo brings the document back", poll { repo.getDocument(id) != null })
                assertEquals("the whole document is back", 3, repo.getDocument(id)!!.pageCount)
                assertTrue("Trash is empty again", repo.listTrash().isEmpty())
                assertTrue("the row is back in the list", poll { cards(activity).any { it.doc?.id == id } })
            }
        }
    }

    // ---- library multi-select (LibraryScreen.confirmDeleteSelection) --------

    @Test(timeout = 60_000)
    fun `multi-select moves the documents to Trash and Undo restores both`() {
        val one = DocFixtures.saved(repo, app, pages = 3, name = "Selection one")
        val two = DocFixtures.saved(repo, app, pages = 2, name = "Selection two")

        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue("both documents are listed", poll { cards(activity).size == 2 })
                cards(activity).first { it.doc?.id == one }.performLongClick()
                idle()
                cards(activity).first { it.doc?.id == two }.performClick()
                idle()
                activity.findViewById<View>(R.id.swSelDelete).performClick()
                idle()

                val confirm = latestDialog("the multi-select trash confirmation")
                val title = confirm.findViewById<TextView>(R.id.swSheetTitle).text.toString()
                assertTrue("the sheet promises Trash, got: $title", title.contains("Trash"))
                confirm.findViewById<View>(R.id.swSheetPrimary).performClick()
                idle()

                assertTrue("both leave the library", poll {
                    repo.getDocument(one) == null && repo.getDocument(two) == null
                })
                val trashed = repo.listTrash()
                assertEquals(
                    "both are in Trash, none permanently purged",
                    setOf(one, two),
                    trashed.map { it.originalId }.toSet()
                )
                assertEquals("page counts survive", 3, trashed.first { it.originalId == one }.document.pageCount)
                assertEquals("page counts survive", 2, trashed.first { it.originalId == two }.document.pageCount)
                assertEquals("the rows are gone", 0, cards(activity).size)

                val action = pollSnackbarAction(activity)
                assertNotNull("a Moved to Trash snackbar with Undo must be shown", action)
                assertEquals(app.getString(R.string.undo), action!!.text.toString())
                action.performClick()

                assertTrue("Undo restores both", poll {
                    repo.getDocument(one) != null && repo.getDocument(two) != null
                })
                assertEquals(3, repo.getDocument(one)!!.pageCount)
                assertEquals(2, repo.getDocument(two)!!.pageCount)
                assertTrue("Trash is empty again", repo.listTrash().isEmpty())
                assertTrue("both rows are back", poll { cards(activity).size == 2 })
            }
        }
    }

    // ---- the document screen sheet (DocumentViewerActivity) ----------------

    @Test(timeout = 60_000)
    fun `document screen Move to Trash keeps the document restorable`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Viewer doc")

        ActivityScenario.launch<DocumentViewerActivity>(DocumentViewerActivity.intent(app, id)).use { scenario ->
            assertTrue("the document screen loads", poll {
                var ready = false
                scenario.onActivity { a ->
                    ready = a.findViewById<TextView>(R.id.pageCount)?.text?.isNotEmpty() == true
                }
                ready
            })
            scenario.onActivity { activity ->
                activity.openMoreSheet()
                activity.tapMoreDelete()
            }
            assertTrue("the document leaves the library", poll { repo.getDocument(id) == null })
        }

        val trashed = repo.listTrash().singleOrNull()
        assertNotNull("the document screen moves to Trash, never purges", trashed)
        assertEquals(id, trashed!!.originalId)
        assertEquals("page count survives the move", 2, trashed.document.pageCount)

        val restored = repo.restoreFromTrash(trashed.key)
        assertEquals("restore returns the same document", id, restored.id)
        assertEquals(2, restored.pageCount)
        assertTrue(repo.listTrash().isEmpty())
    }

    // ---- the document screen's Undo lands on Home --------------------------

    @Test(timeout = 60_000)
    fun `a move made on the document screen shows Undo on the shell and restores`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Pending notice doc")
        // Exactly what DocumentViewerActivity.moveToTrash does before it closes.
        val key = repo.moveToTrash(id)!!
        TrashActions.post(TrashActions.Moved(listOf(key), listOf("Pending notice doc"), 0))

        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                idle()
                val text = pollSnackbarText(activity)
                assertNotNull("Home must show the pending Moved to Trash notice", text)
                assertTrue("it names the document, got: $text", text!!.contains("Pending notice doc"))
                val action = pollSnackbarAction(activity)
                assertNotNull("it offers Undo", action)
                assertEquals(app.getString(R.string.undo), action!!.text.toString())
                action.performClick()

                assertTrue("Undo restores the document", poll { repo.getDocument(id) != null })
                assertTrue("nothing is left in Trash", repo.listTrash().isEmpty())
                assertTrue("the row comes back", poll { cards(activity).any { it.doc?.id == id } })
            }
        }
    }

    // ---- kept on purpose: a legacy Vault item is purged, never trashed -----

    @Test(timeout = 60_000)
    fun `a Vault item is deleted permanently and never appears in Trash`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Vault item")
        repo.setVaulted(id, true)
        assertTrue("fixture is a Vault item", repo.getDocument(id)!!.isVaulted)

        ActivityScenario.launch<DocumentViewerActivity>(DocumentViewerActivity.intent(app, id)).use { scenario ->
            assertTrue("the document screen loads", poll {
                var ready = false
                scenario.onActivity { a ->
                    ready = a.findViewById<TextView>(R.id.pageCount)?.text?.isNotEmpty() == true
                }
                ready
            })
            scenario.onActivity { activity ->
                activity.openMoreSheet()
                val more = ShadowDialog.getLatestDialog()
                assertNotNull("the More sheet is up", more)
                assertEquals(
                    "a Vault item says Delete, not Move to Trash",
                    app.getString(R.string.delete_document),
                    more!!.findViewById<TextView>(R.id.labelMoreDelete).text.toString()
                )
                activity.tapMoreDelete()
            }
            assertTrue("the Vault item is gone", poll { repo.getDocument(id) == null })
        }

        assertTrue(
            "Vault content must never surface in the ordinary Trash list",
            repo.listTrash().isEmpty()
        )
        assertFalse(repo.listDocuments(includeVaulted = true).any { it.id == id })
    }

    // ---- guard: no other UI file purges a library document -----------------

    @Test(timeout = 60_000)
    fun `no UI file purges a library document outside the documented purge sites`() {
        // Where a permanent purge is correct, and how many calls each file may have.
        val allowed = mapOf(
            // Legacy Vault permanent delete (Vault content never enters Trash) — the one
            // documented purge of the shared document Actions sheet (B2).
            "DocumentActionsSheet.kt" to 1,
            // Legacy Vault permanent delete inside the authenticated Vault.
            "MainActivity.kt" to 1,
            // Result Undo removes only the generated result documents (no Trash promise).
            // Lane RS adds one more: the identity-guarded discard of OUR OWN freshly produced
            // size result that did not get smaller (the source is explicitly excluded).
            "tools/ToolResultActivity.kt" to 3
        )
        val uiDir = File("src/main/java/com/scanwhirl/app/ui")
        assertTrue("the UI source tree is where the test runs: ${uiDir.absolutePath}", uiDir.isDirectory)

        // `DocumentsContract.deleteDocument(...)` is the ContentResolver API, not the repository.
        val purge = Regex("""(?<!DocumentsContract)\.deleteDocument\s*\(""")
        val offenders = mutableListOf<String>()
        uiDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val relative = file.relativeTo(uiDir).invariantSeparatorsPath
            val hits = file.readLines().withIndex()
                .filter { (_, line) -> purge.containsMatchIn(line) }
                .map { (index, line) -> "${index + 1}: ${line.trim()}" }
            if (hits.isEmpty()) return@forEach
            val budget = allowed[relative]
            if (budget == null) {
                offenders += "ui/$relative -> ${hits.joinToString(", ")}"
            } else if (hits.size > budget) {
                offenders += "ui/$relative has ${hits.size} calls (allowed $budget): ${hits.joinToString(", ")}"
            }
        }
        assertTrue(
            "A user-facing delete must call moveToTrash, not deleteDocument(). " +
                "Offenders: $offenders",
            offenders.isEmpty()
        )
    }

    // ---- UI-W8: recovery surfaces ------------------------------------------

    /**
     * UI-W8: Empty Trash over several documents whose removals do not all finish.
     * The old result line said "Some items couldn't be deleted. They are still in
     * Trash." even after one document really left Trash, which is false for the part
     * that succeeded. The person must hear how much happened and what is still to finish.
     */
    @Test(timeout = 60_000)
    fun `Empty Trash with one unfinished removal reports the part it deleted`() {
        val one = DocFixtures.saved(repo, app, pages = 1, name = "Partial one")
        val two = DocFixtures.saved(repo, app, pages = 1, name = "Partial two")
        requireNotNull(repo.moveToTrash(one))
        requireNotNull(repo.moveToTrash(two))
        var purges = 0
        // A removal that dies after the entry was renamed aside (the documented recovery
        // shape): the first document is gone, the second removal is not finished.
        ScanRepository.trashFault = { stage ->
            if (stage == ScanRepository.Companion.TrashStage.PURGE_HIDDEN && ++purges == 2) {
                throw java.io.IOException("simulated purge failure")
            }
        }
        try {
            ActivityScenario.launch(TrashActivity::class.java).use { scenario ->
                assertTrue("both rows are listed", poll { repo.listTrash().size == 2 })
                var opened = false
                poll {
                    var clicked = false
                    scenario.onActivity { activity ->
                        clicked = shadowOf(activity).clickMenuItem(R.id.action_empty_trash)
                    }
                    opened = opened || clicked
                    opened
                }
                assertTrue("Empty Trash is in the toolbar menu", opened)
                val confirm = latestDialog("the Empty Trash confirmation")
                confirm.findViewById<View>(R.id.swSheetPrimary).performClick()

                val text = pollSnackbarText(scenario)
                assertNotNull("the delete reports a result", text)
                assertNotEquals(
                    "one document left Trash, so 'they are still in Trash' for all is false",
                    app.getString(R.string.trash_delete_failed),
                    text
                )
                assertTrue(
                    "it says how many of how many left (got: $text)",
                    text!!.contains(app.getString(R.string.trash_deleted_partial, 1, 2))
                )
                assertTrue(
                    "it says a removal still finishes at next start (got: $text)",
                    text.contains(
                        app.resources.getQuantityString(R.plurals.trash_cleanup_pending, 1, 1)
                    )
                )
            }
        } finally {
            ScanRepository.resetTestSeams()
        }
    }

    /**
     * UI-W8: Restore is the reason a person opens Trash. It must be a visible labelled
     * button on every row at 200 % font, not only in the TalkBack actions menu, and
     * permanent delete stays a separate, distinctly worded control.
     */
    @Test(timeout = 60_000)
    fun `every Trash row keeps a visible labelled Restore at 200 percent`() {
        RuntimeEnvironment.setFontScale(2.0f)
        try {
            val id = DocFixtures.saved(repo, app, pages = 2, name = "Restore visible")
            repo.moveToTrash(id)
            ActivityScenario.launch(TrashActivity::class.java).use { scenario ->
                assertTrue("the row is listed", poll { repo.listTrash().size == 1 })
                scenario.onActivity { activity ->
                    val list = activity.findViewById<RecyclerView>(R.id.trashList)
                    list.measure(
                        View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)
                    )
                    list.layout(0, 0, 1080, 2400)
                    val row = list.getChildAt(0)
                    assertNotNull("the Trash row is laid out", row)

                    val restore = row.findViewById<TextView>(R.id.btnTrashRestore)
                    assertNotNull("Restore is on the row", restore)
                    assertTrue("Restore is visible at 200 %", restore!!.isShown)
                    assertEquals(
                        "the visible action is named Restore",
                        app.getString(R.string.trash_restore),
                        restore.text.toString()
                    )
                    val minTarget = (48 * activity.resources.displayMetrics.density).toInt()
                    assertTrue(
                        "Restore keeps a 48dp touch target (${restore.height}px at " +
                            "${activity.resources.displayMetrics.density} density)",
                        restore.height >= minTarget
                    )
                    val textWidth = restore.paint.measureText(restore.text.toString())
                    val room = restore.width - restore.paddingLeft - restore.paddingRight
                    assertTrue(
                        "the Restore label is not clipped at 200 % (needs ${textWidth}px, has ${room}px)",
                        textWidth <= room
                    )

                    val delete = row.findViewById<TextView>(R.id.btnTrashDelete)
                    assertNotNull("permanent delete is separate on the row", delete)
                    assertTrue("permanent delete is visible too", delete!!.isShown)
                    assertEquals(
                        "the destructive action is named in full",
                        app.getString(R.string.trash_delete_forever),
                        delete.text.toString()
                    )
                }
            }
        } finally {
            RuntimeEnvironment.setFontScale(1.0f)
        }
    }

    /** UI-W8: nothing leaves the phone on the first tap; the sheet confirms first. */
    @Test(timeout = 60_000)
    fun `Delete permanently waits for the confirmation sheet`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Confirm first")
        repo.moveToTrash(id)
        ActivityScenario.launch(TrashActivity::class.java).use { scenario ->
            assertTrue("the row is listed", poll { repo.listTrash().size == 1 })
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.btnTrashDelete).performClick()
            }
            val confirm = latestDialog("the permanent delete confirmation")
            assertEquals(
                "nothing is deleted before the person confirms",
                1,
                repo.listTrash().size
            )
            confirm.findViewById<View>(R.id.swSheetSecondary).performClick()
            idle()
            assertEquals("Cancel keeps the document in Trash", 1, repo.listTrash().size)
        }
    }

    // ---- helpers -----------------------------------------------------------

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

    /** Document screen: open More and click its delete row (Trash for ordinary docs). */
    private fun android.app.Activity.openMoreSheet() {
        findViewById<View>(R.id.btnMore).performClick()
        idle()
    }

    private fun android.app.Activity.tapMoreDelete() {
        val more = ShadowDialog.getLatestDialog()
        assertNotNull("the More sheet opens", more)
        more!!.findViewById<View>(R.id.rowMoreDelete)!!.performClick()
        idle()
        val confirm = latestDialog("the delete confirmation")
        confirm.findViewById<View>(R.id.swSheetPrimary).performClick()
        idle()
    }

    /** The newest showing titled SwSheet; fails with [what] when none is up. */
    private fun latestDialog(what: String): android.app.Dialog {
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline) {
            idle()
            val dialog = ShadowDialog.getLatestDialog()
            if (dialog != null && dialog.isShowing &&
                dialog.findViewById<View>(R.id.swSheetTitle) != null
            ) {
                return dialog
            }
            Thread.sleep(25)
        }
        throw AssertionError("$what never appeared")
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

    private fun snackbarAction(activity: Activity): TextView? =
        findById(activity.window!!.decorView, com.google.android.material.R.id.snackbar_action) as? TextView

    private fun snackbarText(activity: Activity): String? =
        (findById(activity.window!!.decorView, com.google.android.material.R.id.snackbar_text) as? TextView)
            ?.text?.toString()?.takeIf { it.isNotEmpty() }

    private fun pollSnackbarAction(activity: Activity): TextView? {
        var found: TextView? = null
        poll { snackbarAction(activity).also { found = it } != null }
        return found
    }

    private fun pollSnackbarText(activity: Activity): String? {
        var found: String? = null
        poll { snackbarText(activity).also { found = it } != null }
        return found
    }

    private fun pollSnackbarText(scenario: ActivityScenario<TrashActivity>): String? {
        var found: String? = null
        poll {
            scenario.onActivity { activity ->
                found = snackbarText(activity)
            }
            found != null
        }
        return found
    }
}
