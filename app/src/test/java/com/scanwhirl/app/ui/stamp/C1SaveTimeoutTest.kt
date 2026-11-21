package com.scanwhirl.app.ui.stamp

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.PageStamp
import com.scanwhirl.app.util.ProGate
import com.scanwhirl.app.util.SensitiveScan
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.robolectric.shadows.ShadowToast

/**
 * Lane C1, item 4: Back is held only for a bounded time during a Save, and leaving after that
 * timeout must not lose the receipt when the write eventually finishes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class C1SaveTimeoutTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null)
        ShadowToast.reset()
    }

    @After
    fun tearDown() {
        StampEditorActivity.saveTimeoutMs = 60_000L
        ShadowToast.reset()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitFor(label: String, timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(20)
        }
        idle()
        return condition()
    }

    private fun controller(id: String) = Robolectric.buildActivity(
        StampEditorActivity::class.java,
        Intent(app, StampEditorActivity::class.java)
            .putExtra(StampEditorActivity.EXTRA_DOC_ID, id)
    ).setup()

    private fun activity(id: String): StampEditorActivity = controller(id).get()

    private val mark = PageStamp.Mark.Text("x", PageStamp.Box(0.1f, 0.1f, 0.3f, 0.12f))

    private fun snackbar(screen: StampEditorActivity): String? =
        screen.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)?.text?.toString()

    private fun resultsSheet(): android.app.Dialog? {
        val dialog = ShadowDialog.getLatestDialog()
        return dialog?.takeIf { it.isShowing && it.findViewById<View>(R.id.swSheetItems) != null }
    }

    @Test
    fun `a timed out exit reports the redacted fallback receipt from the screen's own Save`() {
        StampEditorActivity.saveTimeoutMs = 0
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Slow save")
        val before = repo.listDocuments(includeVaulted = true).size
        val firstController = controller(id)
        val screen = firstController.get()

        // Drive a real Find with two categories and uncheck one, so the editor is in hide mode
        // with the NEUTRAL redacted label. The screen's own SaveMeta then carries exactly
        // SaveMeta(edits, c1_redacted_label), matching the gated write below.
        screen.findScanner = {
            StampEditorActivity.FindOutcome(
                listOf(
                    0 to SensitiveScan.Hit(SensitiveScan.Kind.AADHAAR, "found", PageStamp.Box(0.1f, 0.1f, 0.3f, 0.12f)),
                    0 to SensitiveScan.Hit(SensitiveScan.Kind.CARD, "found", PageStamp.Box(0.1f, 0.2f, 0.3f, 0.22f))
                )
            )
        }
        screen.findViewById<View>(R.id.toolRedact).performClick()
        idle()
        screen.findViewById<View>(R.id.findRedactions).performClick()
        idle()
        ShadowDialog.getLatestDialog()!!.findViewById<View>(R.id.swSheetPrimary).performClick()
        assertTrue("find results appear", waitFor("find results") { resultsSheet() != null })
        val results = resultsSheet()!!
        // Categories are [ID, Cards]: unchecking ID leaves only cards hidden -> "Redacted".
        results.findViewById<LinearLayout>(R.id.swSheetItems).getChildAt(0).performClick()
        results.findViewById<View>(R.id.swSheetPrimary).performClick()
        idle()
        assertTrue("the find added a redaction", screen.editor.hasRedaction())

        val edits = screen.editor.edits()
        val gate = CompletableDeferred<Unit>()
        var writes = 0
        // Create the receiver first (onCreate), then submit: the runner captures the app context
        // here, so the app-context fallback really runs after the screen is destroyed. The gated
        // write is the one the screen's Save JOINS - no second, generic save may be started.
        StampSaveRunner.submit(id, SaveMeta(edits, R.string.c1_redacted_label)) {
            writes++
            gate.await()
            withContext(Dispatchers.IO) { repo.stampDocument(id, edits, null) }
        }
        try {
            screen.findViewById<View>(R.id.saveCopy).performClick()
            idle()
            // Redaction destroys pixels, so Save asks for confirmation first.
            ShadowDialog.getLatestDialog()!!.findViewById<View>(R.id.swSheetPrimary).performClick()
            idle()
            assertEquals(
                "Save joined the running write instead of starting another",
                app.getString(R.string.c1_still_saving),
                screen.findViewById<TextView>(R.id.saveCopy).text.toString()
            )

            ShadowToast.reset()
            screen.onBackPressedDispatcher.onBackPressed()
            idle()
            assertEquals(
                "an honest message replaces 'please wait' once the bounded wait expires",
                app.getString(R.string.c1_saving_timed_out),
                snackbar(screen)
            )
            assertTrue("Back now leaves the screen", screen.isFinishing)

            // Destroying the screen clears its receipt target; the runner must use the fallback.
            firstController.pause().stop().destroy()
            idle()
            gate.complete(Unit)
            assertTrue(
                "the single write creates one copy",
                waitFor("the copy") { repo.listDocuments(includeVaulted = true).size == before + 1 }
            )
            Thread.sleep(150)
            idle()
            assertEquals("exactly one stampDocument write", 1, writes)
            assertEquals("exactly one copy", before + 1, repo.listDocuments(includeVaulted = true).size)
            assertEquals("exactly one receipt toast", 1, ShadowToast.shownToastCount())
            val receipt = ShadowToast.getTextOfLatestToast()!!
            assertTrue("the redacted receipt: $receipt", receipt.startsWith("Saved a redacted copy:"))
            assertFalse("no generic saved-copy toast", receipt.startsWith("Saved as a new copy:"))
        } finally {
            gate.complete(Unit)
            StampSaveRunner.consume(id)
        }
    }

    @Test
    fun `a timed out exit then reopen and Save makes exactly one copy`() {
        StampEditorActivity.saveTimeoutMs = 0
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Slow reopen")
        val firstController = controller(id)
        val first = firstController.get()
        first.editor.add(mark)
        first.refresh()
        val edits = first.editor.edits()

        val before = repo.listDocuments(includeVaulted = true).size
        val gate = CompletableDeferred<Unit>()
        var writes = 0
        StampSaveRunner.submit(id, SaveMeta(edits, 0)) {
            writes++
            gate.await()
            withContext(Dispatchers.IO) { ScanRepository(app).stampDocument(id, edits, null) }
        }
        first.findViewById<View>(R.id.saveCopy).performClick()
        idle()

        // The bounded wait expires and the first screen really goes away.
        first.onBackPressedDispatcher.onBackPressed()
        idle()
        assertTrue("the first screen finished after the timeout", first.isFinishing)
        firstController.pause().stop().destroy()
        idle()

        // A fresh editor with the same marks presses Save while the first write is still running.
        val secondController = controller(id)
        val second = secondController.get()
        second.editor.add(mark)
        second.refresh()
        second.findViewById<View>(R.id.saveCopy).performClick()
        idle()
        assertEquals(
            "the reopened editor joins the running write instead of starting another",
            app.getString(R.string.c1_still_saving),
            second.findViewById<TextView>(R.id.saveCopy).text.toString()
        )

        gate.complete(Unit)
        assertTrue(
            "the single write completes",
            waitFor("the copy") { repo.listDocuments(includeVaulted = true).size >= before + 1 }
        )
        Thread.sleep(200)
        idle()
        assertEquals("the gated write ran exactly once", 1, writes)
        assertEquals(
            "reopening and pressing Save must not create a second copy",
            before + 1,
            repo.listDocuments(includeVaulted = true).size
        )
        assertEquals("exactly one receipt toast", 1, ShadowToast.shownToastCount())
        secondController.pause().stop().destroy()
    }
}
