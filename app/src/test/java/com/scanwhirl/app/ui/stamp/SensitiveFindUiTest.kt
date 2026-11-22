package com.scanwhirl.app.ui.stamp
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.PageStamp
import com.scanwhirl.app.util.ProGate
import com.scanwhirl.app.util.SensitiveScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * Owner decision: Redact is now "Find and hide numbers". The Redact tool shows a Find button;
 * Find lists checkable groups with counts and seeds adjustable redaction boxes. The Aadhaar
 * quick path survives as the "Mask Aadhaar" preset chip inside Find.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SensitiveFindUiTest {


    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private lateinit var app: Application
    private lateinit var repo: ScanRepository
    private var controller: ActivityController<StampEditorActivity>? = null

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null)
        // The Find sheets are process-wide ShadowDialog state. A dialog left by a previous
        // test made getLatestDialog() return the stale sheet on loaded runners.
        ShadowDialog.reset()
    }

    @After
    fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        controller = null
        ShadowDialog.reset()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun activity(id: String): StampEditorActivity {
        val intent = Intent(app, StampEditorActivity::class.java)
            .putExtra(StampEditorActivity.EXTRA_DOC_ID, id)
        val c = Robolectric.buildActivity(StampEditorActivity::class.java, intent).setup()
        controller = c
        return c.get()
    }

    private fun hit(kind: SensitiveScan.Kind) = SensitiveScan.Hit(
        kind, "found", PageStamp.Box(0.10f, 0.10f, 0.30f, 0.12f)
    )

    private fun openFind(screen: StampEditorActivity): android.app.Dialog {
        screen.findViewById<View>(R.id.toolRedact).performClick()
        idle()
        val find = screen.findViewById<View>(R.id.findRedactions)
        assertTrue("the Redact editor shows Find", find.isShown)
        find.performClick()
        idle()
        return requireNotNull(ShadowDialog.getLatestDialog()) { "Find sheet" }
    }

    private fun title(dialog: android.app.Dialog): String? =
        dialog.findViewById<TextView>(R.id.swSheetTitle)?.text?.toString()

    @Test(timeout = 30_000)
    fun `choices come first then groups become adjustable boxes`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Find doc")
        val screen = activity(id)
        screen.findScanner = {
            StampEditorActivity.FindOutcome(
                listOf(
                    0 to hit(SensitiveScan.Kind.AADHAAR),
                    0 to hit(SensitiveScan.Kind.PAN),
                    1 to hit(SensitiveScan.Kind.CARD)
                )
            )
        }

        val find = openFind(screen)
        assertEquals("Hide private info", title(find))
        for (choice in listOf(
            R.id.findChoicePhone, R.id.findChoiceEmail, R.id.findChoiceId,
            R.id.findChoiceCards, R.id.findChoiceOther
        )) {
            val view = find.findViewById<android.widget.CheckBox>(choice)
            assertNotNull("choice $choice", view)
            assertTrue("choice $choice is on by default", view!!.isChecked)
        }
        assertFalse(
            "keep-last-4 is off by default",
            find.findViewById<android.widget.CheckBox>(R.id.findKeepLast4)!!.isChecked
        )

        find.findViewById<View>(R.id.swSheetPrimary).performClick()
        idle()

        val results = requireNotNull(ShadowDialog.getLatestDialog())
        assertEquals("Found 3 items", title(results))
        val items = results.findViewById<ViewGroup>(R.id.swSheetItems)
        assertEquals(2, items.childCount)
        val labels = (0 until items.childCount).map {
            ((items.getChildAt(it) as ViewGroup).getChildAt(0) as TextView).text.toString()
        }
        assertTrue("ID group: $labels", labels.any { it == "ID numbers (Aadhaar, PAN, driving licence, passport, VID) (2)" })
        assertTrue(
            "Card & bank group: $labels",
            labels.any { it == "Card & bank numbers (cards, IBAN, account numbers) (1)" }
        )

        results.findViewById<View>(R.id.swSheetPrimary).performClick()
        idle()

        assertEquals("two boxes on page 1", 2, screen.editor.marks(0).size)
        assertEquals("one box on page 2", 1, screen.editor.marks(1).size)
        assertTrue("the copy will burn pixels", screen.editor.hasRedaction())
        assertTrue(
            "the boxes stay editable redactions",
            screen.editor.marks(0).all { it is PageStamp.Mark.Redact }
        )
    }

    @Test(timeout = 30_000)
    fun `only the chosen kinds are scanned`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Choice doc")
        val screen = activity(id)
        screen.findScanner = {
            StampEditorActivity.FindOutcome(
                listOf(
                    0 to hit(SensitiveScan.Kind.AADHAAR),
                    0 to hit(SensitiveScan.Kind.PAN),
                    1 to hit(SensitiveScan.Kind.CARD)
                )
            )
        }
        val find = openFind(screen)
        // Turn off ID numbers: only the card on page 2 may be found.
        find.findViewById<android.widget.CheckBox>(R.id.findChoiceId).isChecked = false
        find.findViewById<View>(R.id.swSheetPrimary).performClick()
        idle()

        val results = requireNotNull(ShadowDialog.getLatestDialog())
        assertEquals("Found 1 item", title(results))
        val items = results.findViewById<ViewGroup>(R.id.swSheetItems)
        assertEquals(1, items.childCount)
        val label = ((items.getChildAt(0) as ViewGroup).getChildAt(0) as TextView).text.toString()
        assertTrue("card only: $label", label.startsWith("Card & bank numbers"))

        results.findViewById<View>(R.id.swSheetPrimary).performClick()
        idle()
        assertTrue("IDs on page 1 are untouched", screen.editor.marks(0).isEmpty())
        assertEquals("the card is hidden", 1, screen.editor.marks(1).size)
    }

    @Test(timeout = 30_000)
    fun `no hits is said plainly and adds nothing`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Empty find")
        val screen = activity(id)
        screen.findScanner = { StampEditorActivity.FindOutcome(emptyList()) }
        val find = openFind(screen)
        find.findViewById<View>(R.id.swSheetPrimary).performClick()
        idle()

        assertTrue("nothing is added", screen.editor.marks(0).isEmpty())
        val snackbar = screen.findViewById<TextView>(
            com.google.android.material.R.id.snackbar_text
        )
        assertEquals(app.getString(R.string.cdf1_find_none_body), snackbar?.text?.toString())
    }

    @Test(timeout = 30_000)
    fun `the mask tool no longer exists in the registry`() {
        assertNull(com.scanwhirl.app.ui.kit.ToolRegistry.byId("mask_aadhaar"))
        val redact = com.scanwhirl.app.ui.kit.ToolRegistry.byId("redact")
        assertNotNull(redact)
        assertTrue(
            "Redact advertises Hide private info in search",
            redact!!.keywords.contains("private")
        )
    }
}
