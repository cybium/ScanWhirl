package com.scanwhirl.app.ui.stamp

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.OcrEngine
import com.scanwhirl.app.util.PageStamp
import com.scanwhirl.app.util.ProGate
import com.scanwhirl.app.util.SensitiveScan
import org.junit.After
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
 * Policy review item 3: "Hide private info" must distinguish a page whose text was read and
 * matched nothing from a page whose text could not be read at all. A failed read is never
 * reported as "no private info", and the user is offered the manual box tool.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RedactFindHonestyTest {

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

    private fun pollUntil(timeoutMs: Long = 8000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(50)
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

    /** Redact → Find, then press its primary button; waits for the scan coroutine. */
    private fun runFind(screen: StampEditorActivity) {
        screen.findViewById<View>(R.id.toolRedact).performClick()
        idle()
        screen.findViewById<View>(R.id.findRedactions).performClick()
        idle()
        val find = requireNotNull(ShadowDialog.getLatestDialog()) { "Find sheet" }
        find.findViewById<View>(R.id.swSheetPrimary).performClick()
        idle()
    }

    private fun sheetMessage(dialog: android.app.Dialog): String? =
        dialog.findViewById<TextView>(R.id.swSheetMessage)?.text?.toString()

    private fun snackbar(screen: StampEditorActivity): String? =
        screen.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)?.text?.toString()

    @Test
    fun `text read and nothing matched says to check the page`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Clean page")
        val screen = activity(id)
        screen.findScanner = { StampEditorActivity.FindOutcome(emptyList(), emptyList()) }
        runFind(screen)

        assertEquals(
            "a checked page without matches may say so, with a caveat",
            app.getString(R.string.cdf1_find_none_body),
            snackbar(screen)
        )
    }

    @Test
    fun `an unreadable page is never called clean and offers the box tool`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Unreadable page")
        val screen = activity(id)
        screen.findScanner = { StampEditorActivity.FindOutcome(emptyList(), listOf(0)) }
        runFind(screen)

        assertNull(
            "the clean message must never appear when reading failed",
            snackbar(screen)?.takeIf { it == app.getString(R.string.cdf1_find_none_body) }
        )
        val warning = requireNotNull(ShadowDialog.getLatestDialog()) { "warning sheet" }
        assertEquals(app.getString(R.string.cdf1_find_unreadable_one), sheetMessage(warning))
        assertFalse("a failed read is not reported as a count of hits", screen.editor.hasRedaction())

        // The notice's action arms the manual box tool.
        warning.findViewById<View>(R.id.swSheetPrimary).performClick()
        idle()
        assertEquals(
            app.getString(R.string.stamp_hint_redact),
            screen.findViewById<TextView>(R.id.hint)?.text?.toString()
        )
        assertEquals(
            View.VISIBLE,
            screen.findViewById<View>(R.id.findRedactions).visibility
        )
    }

    @Test
    fun `several unreadable pages say how many`() {
        val id = DocFixtures.saved(repo, app, pages = 3, name = "Partly unreadable")
        val screen = activity(id)
        screen.findScanner = { StampEditorActivity.FindOutcome(emptyList(), listOf(0, 2)) }
        runFind(screen)

        val warning = requireNotNull(ShadowDialog.getLatestDialog())
        assertEquals(
            app.resources.getQuantityString(R.plurals.find_unreadable_pages, 2, 2),
            sheetMessage(warning)
        )
    }

    @Test
    fun `hits on readable pages still warn about the unreadable ones`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Half readable")
        val screen = activity(id)
        screen.findScanner = {
            StampEditorActivity.FindOutcome(
                listOf(0 to hit(SensitiveScan.Kind.PHONE)),
                listOf(1)
            )
        }
        runFind(screen)

        val results = requireNotNull(ShadowDialog.getLatestDialog()) { "results sheet" }
        assertNotNull("the hit is still offered", results.findViewById<View>(R.id.swSheetItems))
        assertEquals(
            "the counts do not cover the page that could not be read",
            app.getString(R.string.cdf1_find_unreadable_one),
            sheetMessage(results)
        )
    }

    @Test
    fun `a real OCR failure is not turned into nothing found`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "OCR failure")
        // Neither the element pass nor the line pass can read this page.
        OcrEngine.rawReader = { _, _ -> throw IllegalStateException("text model is missing") }
        val screen = activity(id)
        assertNull("the real path is under test", screen.findScanner)
        runFind(screen)

        val warning = pollUntil {
            ShadowDialog.getLatestDialog()?.findViewById<TextView>(R.id.swSheetMessage)
                ?.text?.toString() == app.getString(R.string.cdf1_find_unreadable_one)
        }
        assertTrue("an OCR failure must be reported as unreadable", warning)
        assertNull(
            "no 'nothing found' snackbar after a failed read",
            snackbar(screen)?.takeIf { it == app.getString(R.string.cdf1_find_none_body) }
        )
    }
}
