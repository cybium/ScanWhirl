package com.scanwhirl.app.ui.stamp

import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.PageStamp
import com.scanwhirl.app.util.ProGate
import com.scanwhirl.app.util.SensitiveScan
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * G3 — editor fixes F1 and F3.
 *
 * F3: the "private info hidden" decision belongs to the marks that are on the page at Save time,
 * not to a Find flag that stays latched after Undo / Delete removes every Redact box.
 * F1: the persistent receipt keeps the screen open, but it must not keep the Save lock held.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class G3EditorFixesTest {

    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitFor(label: String, timeoutMs: Long = 8_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        idle()
        return condition()
    }

    private fun activity(id: String): StampEditorActivity {
        val intent = Intent(app, StampEditorActivity::class.java)
            .putExtra(StampEditorActivity.EXTRA_DOC_ID, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return Robolectric.buildActivity(StampEditorActivity::class.java, intent).setup().get()
    }

    private fun resultsSheet(): android.app.Dialog? {
        val dialog = ShadowDialog.getLatestDialog()
        return dialog?.takeIf { it.isShowing && it.findViewById<View>(R.id.swSheetItems) != null }
    }

    /** The part of the receipt body after the copy name, so the generated name need not match. */
    private fun receiptTail(): String =
        app.getString(R.string.cdf1_redact_receipt_body, "").trimStart()

    private fun receiptShowing(): Boolean {
        val dialog = ShadowDialog.getLatestDialog() ?: return false
        val message = dialog.findViewById<TextView>(R.id.swSheetMessage)?.text?.toString().orEmpty()
        return dialog.isShowing && message.contains(receiptTail())
    }

    /** Find with every category kept: the ordinary "private info hidden" flow. */
    private fun findAndHide(screen: StampEditorActivity) {
        screen.findScanner = {
            StampEditorActivity.FindOutcome(
                listOf(
                    0 to SensitiveScan.Hit(
                        SensitiveScan.Kind.PHONE, "found", PageStamp.Box(0.10f, 0.10f, 0.30f, 0.12f)
                    )
                )
            )
        }
        screen.runFindForTest()
        idle()
        assertTrue("find results appear", waitFor("find results") { resultsSheet() != null })
        resultsSheet()!!.findViewById<View>(R.id.swSheetPrimary).performClick()
        idle()
        assertTrue("Find added a redaction", screen.editor.hasRedaction())
    }

    private fun confirmBurnInWarning(): android.app.Dialog {
        val warning = ShadowDialog.getLatestDialog()
        assertNotNull("redaction destroys pixels; warn before writing", warning)
        assertEquals(
            "the mandatory redaction review is shown",
            app.getString(R.string.j34_check_title),
            warning!!.findViewById<TextView>(R.id.swSheetTitle)?.text?.toString()
        )
        return warning
    }

    @Test
    fun `undoing every Find redaction saves a plain copy with no redaction receipt`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Undo then save")
        val screen = activity(id)
        findAndHide(screen)

        // The person removes the only redaction and keeps editing. hideMode is still latched,
        // but nothing is hidden any more.
        assertTrue("the Find box is selected", screen.editor.removeSelected())
        assertFalse("no redaction remains", screen.editor.hasRedaction())
        screen.editor.add(PageStamp.Mark.Text("note", PageStamp.Box(0.1f, 0.3f, 0.4f, 0.36f)))
        screen.refresh()

        screen.findViewById<View>(R.id.saveCopy).performClick()
        idle()
        assertEquals("plain edits need only the name sheet",
            app.getString(R.string.j34_name_title),
            ShadowDialog.getLatestDialog()!!.findViewById<TextView>(R.id.swSheetTitle).text.toString())
        J34TestFlow.confirmName()
        assertTrue(
            "the plain copy is saved and the shared result opens",
            waitFor("the plain copy") {
                repo.listDocuments(includeVaulted = true).any { it.id != id } &&
                    shadowOf(screen).peekNextStartedActivity()?.component?.className ==
                    com.scanwhirl.app.ui.tools.ToolResultActivity::class.java.name
            }
        )
        val latest = ShadowDialog.getLatestDialog()
        assertTrue(
            "no false 'original still has private details' receipt after the undo",
            latest?.isShowing != true ||
                latest.findViewById<TextView>(R.id.swSheetTitle)?.text?.toString() !=
                app.getString(R.string.v2v_redact_receipt_title)
        )
        assertFalse("a plain copy must not show the redaction receipt", receiptShowing())
        StampSaveRunner.consume(id)
    }

    @Test
    fun `Find save with its redactions kept still shows the hidden-label receipt`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Kept redaction")
        val screen = activity(id)
        findAndHide(screen)

        screen.findViewById<View>(R.id.saveCopy).performClick()
        idle()
        confirmBurnInWarning()
        J34TestFlow.completeReview()
        J34TestFlow.confirmName()
        assertTrue("the persistent receipt still appears", waitFor("receipt") { receiptShowing() })
        assertFalse("the redaction outcome keeps the screen open", screen.isFinishing)
        StampSaveRunner.consume(id)
    }

    @Test
    fun `a manual Redact-tool save still gets the burn-in warning`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Manual redact")
        val screen = activity(id)
        // No Find ran: hideMode is false. The warning must still follow the real mark.
        screen.editor.add(PageStamp.Mark.Redact(PageStamp.Box(0.1f, 0.1f, 0.4f, 0.2f)))
        screen.refresh()
        screen.findViewById<View>(R.id.saveCopy).performClick()
        idle()
        confirmBurnInWarning()
        assertFalse("nothing is written before the person confirms", screen.isFinishing)
    }

    @Test
    fun `persistent receipt releases the saving lock so Back exits normally`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Receipt unlock")
        val screen = activity(id)
        findAndHide(screen)

        // Occupy the runner with a save we control so "Saving" is deterministic. Its meta matches
        // what writeCopy derives from the marks, so the editor joins this promise.
        val copy = repo.getDocument(id)!!
        val release = CompletableDeferred<Unit>()
        StampSaveRunner.submit(id, SaveMeta(screen.editor.edits(), R.string.private_info_label, J34TestFlow.COPY_NAME)) {
            release.await()
            copy
        }
        screen.findViewById<View>(R.id.saveCopy).performClick()
        idle()
        confirmBurnInWarning()
        J34TestFlow.completeReview()
        J34TestFlow.confirmName()
        idle()
        val saveButton = screen.findViewById<TextView>(R.id.saveCopy)
        assertEquals(
            "the editor joined the running write",
            app.getString(R.string.c1_still_saving),
            saveButton.text.toString()
        )
        assertFalse("Save is disabled while the write runs", saveButton.isEnabled)

        release.complete(Unit)
        assertTrue("the persistent receipt appears", waitFor("receipt") { receiptShowing() })
        assertFalse("the screen stays open for the receipt", screen.isFinishing)
        assertEquals(
            "the Save button label is restored after delivery",
            app.getString(R.string.j34_review),
            saveButton.text.toString()
        )
        assertFalse("duplicate Save is disabled after delivery", saveButton.isEnabled)
        // A genuinely new edit proves delivery released the write lock, rather than merely
        // changing the button label while leaving the old saving flag latched.
        screen.editor.add(PageStamp.Mark.Text("new edit", PageStamp.Box(.1f,.5f,.3f,.6f)))
        screen.refresh()
        assertTrue("new edits can be reviewed after delivery", saveButton.isEnabled)

        screen.onBackPressedDispatcher.onBackPressed()
        idle()
        val exitSheet = ShadowDialog.getLatestDialog()
        assertNotNull("Back reaches the normal exit confirmation", exitSheet)
        assertEquals(
            "Back is no longer swallowed by a 'still saving' notice",
            app.getString(R.string.j34_save_changes),
            exitSheet!!.findViewById<TextView>(R.id.swSheetTitle)?.text?.toString()
        )
        assertFalse("the person may keep editing behind the receipt", screen.isFinishing)
        StampSaveRunner.consume(id)
    }
}
