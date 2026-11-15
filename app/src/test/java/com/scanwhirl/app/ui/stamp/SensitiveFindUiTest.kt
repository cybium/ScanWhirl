package com.scanwhirl.app.ui.stamp

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
 * Owner decision: Redact is now "Find and hide numbers". The Redact tool shows a Find button;
 * Find lists checkable groups with counts and seeds adjustable redaction boxes. The Aadhaar
 * quick path survives as the "Mask Aadhaar" preset chip inside Find.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SensitiveFindUiTest {

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

    private fun activity(id: String): StampEditorActivity {
        val intent = Intent(app, StampEditorActivity::class.java)
            .putExtra(StampEditorActivity.EXTRA_DOC_ID, id)
        return Robolectric.buildActivity(StampEditorActivity::class.java, intent).setup().get()
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

    @Test
    fun `find lists groups and seeds adjustable redaction boxes`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Find doc")
        val screen = activity(id)
        screen.findScanner = {
            listOf(
                0 to hit(SensitiveScan.Kind.AADHAAR),
                0 to hit(SensitiveScan.Kind.PAN),
                1 to hit(SensitiveScan.Kind.CARD)
            )
        }

        val find = openFind(screen)
        assertEquals("Find and hide numbers", title(find))
        assertNotNull("the Aadhaar preset stays reachable", find.findViewById<View>(R.id.findPresetAadhaar))
        assertNotNull("whole-number option", find.findViewById<View>(R.id.findWholeAadhaar))

        find.findViewById<View>(R.id.swSheetPrimary).performClick()
        idle()

        val results = requireNotNull(ShadowDialog.getLatestDialog())
        assertEquals("Found 3 items", title(results))
        val items = results.findViewById<ViewGroup>(R.id.swSheetItems)
        assertEquals(3, items.childCount)
        val labels = (0 until items.childCount).map {
            ((items.getChildAt(it) as ViewGroup).getChildAt(0) as TextView).text.toString()
        }
        assertTrue("Aadhaar count: $labels", labels.any { it == "Aadhaar (1)" })
        assertTrue("PAN count: $labels", labels.any { it == "PAN (1)" })
        assertTrue("Card count: $labels", labels.any { it == "Card numbers (1)" })

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

    @Test
    fun `the mask aadhaar preset opens the old options`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Preset doc")
        val screen = activity(id)
        val find = openFind(screen)

        find.findViewById<View>(R.id.findPresetAadhaar).performClick()
        idle()

        val options = requireNotNull(ShadowDialog.getLatestDialog())
        assertEquals("Mask Aadhaar", title(options))
        assertNotNull("number/VID/QR groups", options.findViewById<View>(R.id.swSheetItems))
    }

    @Test
    fun `no hits is said plainly and adds nothing`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Empty find")
        val screen = activity(id)
        screen.findScanner = { emptyList() }
        val find = openFind(screen)
        find.findViewById<View>(R.id.swSheetPrimary).performClick()
        idle()

        assertTrue("nothing is added", screen.editor.marks(0).isEmpty())
        val snackbar = screen.findViewById<TextView>(
            com.google.android.material.R.id.snackbar_text
        )
        assertEquals(app.getString(R.string.find_none), snackbar?.text?.toString())
    }

    @Test
    fun `the mask tool no longer exists in the registry`() {
        assertNull(com.scanwhirl.app.ui.kit.ToolRegistry.byId("mask_aadhaar"))
        val redact = com.scanwhirl.app.ui.kit.ToolRegistry.byId("redact")
        assertNotNull(redact)
        assertTrue(
            "Redact advertises find in search",
            redact!!.keywords.contains("aadhaar")
        )
    }
}
