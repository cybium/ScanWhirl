package com.scanwhirl.app.ui.stamp
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.OcrBox
import com.scanwhirl.app.util.OcrEngine
import com.scanwhirl.app.util.OcrRawLine
import com.scanwhirl.app.util.OcrRawPage
import com.scanwhirl.app.util.PageStamp
import com.scanwhirl.app.util.ProGate
import com.scanwhirl.app.util.SensitiveScan
import org.junit.After
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

/**
 * Lane C1, item 2: the "Keep last 4 digits" choice really applies to cards (unchecking hides the
 * whole number), and a find run that did not hide every found category must not claim in the saved
 * copy's name that all private info was hidden.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class C1RedactionFindTest {


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

    @After
    fun tearDown() {
        OcrEngine.rawReader = null
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
        return Robolectric.buildActivity(StampEditorActivity::class.java, intent).setup().get()
    }

    private fun hit(kind: SensitiveScan.Kind) = SensitiveScan.Hit(
        kind, "found", PageStamp.Box(0.10f, 0.10f, 0.30f, 0.12f)
    )

    private fun resultsSheet(): android.app.Dialog? {
        val dialog = ShadowDialog.getLatestDialog()
        return dialog?.takeIf { it.isShowing && it.findViewById<View>(R.id.swSheetItems) != null }
    }

    @Test
    fun `unchecking keep last 4 hides the whole card number`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Card page")
        OcrEngine.rawReader = { _, _ ->
            OcrRawPage(
                1000, 200,
                listOf(OcrRawLine("4111 1111 1111 1111", OcrBox(50, 100, 950, 140)))
            )
        }
        val screen = activity(id)
        screen.findViewById<View>(R.id.toolRedact).performClick()
        idle()
        screen.findViewById<View>(R.id.findRedactions).performClick()
        idle()
        val find = requireNotNull(ShadowDialog.getLatestDialog()) { "Find sheet" }
        assertFalse(
            "keep last 4 starts unchecked",
            find.findViewById<CheckBox>(R.id.findKeepLast4).isChecked
        )
        find.findViewById<View>(R.id.swSheetPrimary).performClick()
        assertTrue("find results appear", waitFor("find results") { resultsSheet() != null })
        resultsSheet()!!.findViewById<View>(R.id.swSheetPrimary).performClick()
        idle()

        val redacts = screen.editor.marks().filterIsInstance<PageStamp.Mark.Redact>()
        assertTrue("a card was found", redacts.isNotEmpty())
        assertTrue(
            "the whole card number must be covered, not just the first 12 digits",
            redacts.any { it.box.right >= 0.94f }
        )
    }

    @Test
    fun `a partially hidden find saves a neutral new copy`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Private data")
        val screen = activity(id)
        screen.findScanner = {
            StampEditorActivity.FindOutcome(
                listOf(0 to hit(SensitiveScan.Kind.AADHAAR), 0 to hit(SensitiveScan.Kind.CARD))
            )
        }
        screen.findViewById<View>(R.id.toolRedact).performClick()
        idle()
        screen.findViewById<View>(R.id.findRedactions).performClick()
        idle()
        ShadowDialog.getLatestDialog()!!.findViewById<View>(R.id.swSheetPrimary).performClick()
        assertTrue("find results appear", waitFor("find results") { resultsSheet() != null })
        val results = resultsSheet()!!
        // Categories are [ID, Cards]: uncheck ID so only cards are hidden.
        results.findViewById<LinearLayout>(R.id.swSheetItems).getChildAt(0).performClick()
        results.findViewById<View>(R.id.swSheetPrimary).performClick()
        idle()

        screen.findViewById<View>(R.id.saveCopy).performClick()
        idle()
        ShadowDialog.getLatestDialog()!!.findViewById<View>(R.id.swSheetPrimary).performClick()
        assertTrue(
            "a neutral new copy is saved",
            waitFor("the saved copy") {
                repo.listDocuments(includeVaulted = true).any { it.id != id }
            }
        )
        val copy = repo.listDocuments(includeVaulted = true).first { it.id != id }
        // Council §5: no forced "Redacted" label; the copy is just the source stem + collision.
        assertFalse("no forced verb suffix: ${copy.name}", copy.name.contains("Redacted"))
        assertFalse(
            "no blanket private-info claim when not every found category was hidden",
            copy.name.contains("Private info hidden")
        )
    }

    @Test
    fun `unchecking every found category clears a stale private-info label`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Stale label")
        val screen = activity(id)
        screen.findScanner = {
            StampEditorActivity.FindOutcome(listOf(0 to hit(SensitiveScan.Kind.AADHAAR)))
        }
        // First Find hides one category -> "private info hidden" label is set.
        screen.findViewById<View>(R.id.toolRedact).performClick()
        idle()
        screen.findViewById<View>(R.id.findRedactions).performClick()
        idle()
        ShadowDialog.getLatestDialog()!!.findViewById<View>(R.id.swSheetPrimary).performClick()
        assertTrue("results appear", waitFor("results") { resultsSheet() != null })
        resultsSheet()!!.findViewById<View>(R.id.swSheetPrimary).performClick()
        idle()
        assertTrue("a redaction was added", screen.editor.hasRedaction())

        // Remove it and run a second Find with every category unchecked: no new redaction, and
        // the earlier hide label must not survive onto the next copy.
        assertTrue(screen.editor.removeSelected())
        assertFalse("nothing is hidden now", screen.editor.hasRedaction())
        screen.findScanner = {
            StampEditorActivity.FindOutcome(listOf(0 to hit(SensitiveScan.Kind.CARD)))
        }
        screen.findViewById<View>(R.id.findRedactions).performClick()
        idle()
        ShadowDialog.getLatestDialog()!!.findViewById<View>(R.id.swSheetPrimary).performClick()
        assertTrue("results appear again", waitFor("results") { resultsSheet() != null })
        val results = resultsSheet()!!
        results.findViewById<LinearLayout>(R.id.swSheetItems).getChildAt(0).performClick()
        results.findViewById<View>(R.id.swSheetPrimary).performClick()
        idle()
        assertFalse("still nothing hidden", screen.editor.hasRedaction())

        screen.editor.add(PageStamp.Mark.Text("note", PageStamp.Box(0.1f, 0.1f, 0.3f, 0.12f)))
        screen.refresh()
        screen.findViewById<View>(R.id.saveCopy).performClick()
        assertTrue(
            "the copy is saved without a hide warning",
            waitFor("the saved copy") { repo.listDocuments(includeVaulted = true).size > 1 }
        )
        val saved = repo.listDocuments(includeVaulted = true).first { it.id != id }
        assertFalse("no stale private-info label", saved.name.contains("Private info hidden"))
        assertFalse("no stale redacted label", saved.name.contains("Redacted"))
    }
}
