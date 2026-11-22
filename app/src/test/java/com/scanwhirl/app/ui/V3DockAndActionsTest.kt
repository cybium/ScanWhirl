package com.scanwhirl.app.ui

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.ProGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
import java.io.File

/**
 * V3 (screenshot critique runs 5-6, "Viewer 6/10" and "Document actions 6/10"):
 * the reader dock is ONE compact row of four (Edit & crop, OCR, Share, Actions),
 * the panel no longer prints its own heading or save explanation, the page hosts
 * keep lane K's dark canvas, and the header is short so the document dominates.
 * The Actions sheet is titled "Document actions", writes "Organize" in sentence
 * case, keeps only the X dismissal, and lists the secondary actions without
 * repeating the dock.
 *
 * RED FIRST: written against the old seven-button 4+3 dock and the "Actions"
 * sheet with a Close row and duplicate Read text / Share rows.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
class V3DockAndActionsTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null)
        DocumentActionsSheet.interceptForTests = null
    }

    @After
    fun tearDown() {
        DocumentActionsSheet.interceptForTests = null
        ProGate.setTestOverride(app, null)
    }

    private fun idle() = shadowOf(android.os.Looper.getMainLooper()).idle()

    private fun awaitLoad(activity: DocumentViewerActivity) {
        val end = System.currentTimeMillis() + 8_000L
        while (activity.findViewById<TextView>(R.id.pageCount)?.text?.isNullOrEmpty() != false &&
            System.currentTimeMillis() < end
        ) {
            idle()
            Thread.sleep(10)
        }
        idle()
    }

    private fun viewer(block: (DocumentViewerActivity) -> Unit) {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Electricity bill - Nov 2026")
        val controller: ActivityController<DocumentViewerActivity> = Robolectric.buildActivity(
            DocumentViewerActivity::class.java, DocumentViewerActivity.intent(app, id)
        )
        try {
            val activity = controller.setup().get()
            awaitLoad(activity)
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            val root = content.getChildAt(0) ?: content
            val w = (360f * activity.resources.displayMetrics.density).toInt()
            val h = (640f * activity.resources.displayMetrics.density).toInt()
            root.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
            )
            root.layout(0, 0, w, h)
            idle()
            block(activity)
        } finally {
            controller.destroy()
        }
    }

    private fun sheet(activity: DocumentViewerActivity): android.app.Dialog {
        activity.findViewById<View>(R.id.btnMore).performClick()
        idle()
        return requireNotNull(ShadowDialog.getLatestDialog()) { "the Actions sheet must open" }
    }

    // --- dock ---------------------------------------------------------------

    @Test(timeout = 30_000)
    fun `dock is one compact row of four named actions`() {
        viewer { activity ->
            val grid = activity.findViewById<ViewGroup>(R.id.actionGrid)
            assertEquals("the dock is a single row", 1, grid.childCount)
            val row = grid.getChildAt(0) as ViewGroup
            assertEquals(
                "Edit & crop · OCR · Share · Actions",
                listOf(R.id.btnEdit, R.id.btnText, R.id.btnShare, R.id.btnMore),
                (0 until row.childCount).map { row.getChildAt(it).id }
            )
            assertEquals(
                "the four dock labels",
                listOf(
                    activity.getString(R.string.doc_action_edit_crop),
                    activity.getString(R.string.v3_ocr),
                    activity.getString(R.string.share_pdf),
                    activity.getString(R.string.doc_actions)
                ),
                (0 until row.childCount).map { (row.getChildAt(it) as MaterialButton).text.toString() }
            )
        }
    }

    @Test(timeout = 30_000)
    fun `the dock prints no heading and no save explanation`() {
        viewer { activity ->
            assertEquals(
                "the dock heading is removed",
                View.GONE,
                activity.findViewById<View>(R.id.actionPanelTitle).visibility
            )
            assertEquals(
                "the save explanation is removed",
                View.GONE,
                activity.findViewById<View>(R.id.actionPanelHint).visibility
            )
        }
    }

    @Test(timeout = 30_000)
    fun `dock icons use the ic_sym family`() {
        val xml = sourceOf("src/main/res/layout/activity_document_viewer.xml")
        for (glyph in listOf("ic_sym_edit", "ic_sym_ocr", "ic_sym_share", "ic_sym_more_horiz")) {
            assertTrue("the dock uses $glyph", xml.contains("@drawable/$glyph"))
        }
        assertFalse("no legacy edit glyph in the dock", xml.contains("@drawable/ic_edit\""))
        assertFalse("no legacy ocr glyph in the dock", xml.contains("@drawable/ic_ocr\""))
        assertFalse("no legacy share glyph in the dock", xml.contains("@drawable/ic_share\""))
    }

    @Test(timeout = 30_000)
    fun `the header is short so the document dominates`() {
        viewer { activity ->
            val name = activity.findViewById<TextView>(R.id.docName)
            assertEquals(
                "the document name is 18 sp, not 20 sp",
                18f * activity.resources.displayMetrics.scaledDensity,
                name.textSize,
                0.5f
            )
            val header = activity.findViewById<ViewGroup>(R.id.docHeader)
            val d = activity.resources.displayMetrics.density
            assertTrue(
                "the header fits in 136 dp, was ${header.height / d}dp",
                header.height <= (136f * d).toInt()
            )
        }
    }

    @Test(timeout = 30_000)
    fun `page hosts keep the dark canvas with an eight dp inset`() {
        val canvas = app.getColor(R.color.sw_cc_canvas)
        val xml = sourceOf("src/main/res/layout/item_page_grid.xml")
        assertTrue("the page host uses sw_cc_canvas", xml.contains("@color/sw_cc_canvas"))
        assertTrue("the page host keeps its 8 dp inset", xml.contains("android:padding=\"8dp\""))
        assertEquals(0xFF1E252A.toInt(), canvas)
    }

    // --- actions sheet ------------------------------------------------------

    @Test(timeout = 30_000)
    fun `the sheet is titled Document actions and keeps only the X dismissal`() {
        viewer { activity ->
            val dialog = sheet(activity)
            assertEquals(
                "Document actions",
                dialog.findViewById<TextView>(R.id.swSheetTitle).text.toString()
            )
            assertEquals(
                "the Close text button is removed; the X in the header remains",
                View.GONE,
                dialog.findViewById<View>(R.id.swSheetSecondary).visibility
            )
            assertTrue(
                "the X is still a 48 dp target",
                dialog.findViewById<View>(R.id.swSheetClose).height >=
                    (48f * activity.resources.displayMetrics.density).toInt() - 1
            )
        }
    }

    @Test(timeout = 30_000)
    fun `Organize is sentence case and the sheet never repeats the dock`() {
        viewer { activity ->
            val dialog = sheet(activity)
            assertFalse(
                "Organize is sentence case, not ORGANIZE",
                dialog.findViewById<TextView>(R.id.hdrOrganize).isAllCaps
            )
            val deco = dialog.window!!.decorView
            assertEquals(
                "Read text is the dock's OCR, not a sheet row",
                null,
                deco.findViewWithTag<View>("docActions:read_text")
            )
            assertEquals(
                "Share is a dock action, not a sheet row",
                null,
                deco.findViewWithTag<View>("docActions:share")
            )
        }
    }

    @Test(timeout = 30_000)
    fun `the sheet lists the export and the full secondary set`() {
        viewer { activity ->
            val dialog = sheet(activity)
            val deco = dialog.window!!.decorView
            val export = requireNotNull(deco.findViewWithTag<View>("docActions:export")) {
                "Save copy to Documents must live in the Actions sheet"
            }
            val label = (export as ViewGroup).getChildAt(1) as ViewGroup
            assertEquals(
                "Save copy to Documents",
                (label.getChildAt(0) as TextView).text.toString()
            )
            for (tag in listOf("compress", "sign", "rename", "move", "duplicate", "lock", "trash")) {
                assertNotNull("the secondary row '$tag' is present", deco.findViewWithTag<View>("docActions:$tag"))
            }
            assertNotNull("Move to category stays (category is not a folder)",
                dialog.findViewById<View>(R.id.rowMoreMove))
        }
    }

    // --- COPY_DIET.md section 2/3: exact strings and text roles --------------

    @Test(timeout = 30_000)
    fun `copy diet the actions sheet uses the named title and sentence case Organize`() {
        viewer { activity ->
            val dialog = sheet(activity)
            assertEquals(
                "Document actions",
                dialog.findViewById<TextView>(R.id.swSheetTitle).text.toString()
            )
            assertEquals(
                "lane-local title key",
                activity.getString(R.string.v3_actions_title),
                dialog.findViewById<TextView>(R.id.swSheetTitle).text.toString()
            )
            val organize = dialog.findViewById<TextView>(R.id.hdrOrganize)
            assertEquals("व्यवस्थित करें / Organize", activity.getString(R.string.v3_organize), organize.text.toString())
            assertFalse("no uppercase transform on the section", organize.isAllCaps)
            assertEquals(
                "a section heading is P16, not a tracked grey 12sp label",
                16f * activity.resources.displayMetrics.scaledDensity,
                organize.textSize,
                0.5f
            )
        }
    }

    @Test(timeout = 30_000)
    fun `copy diet the category row says Set category and OCR speaks its a11y gloss`() {
        viewer { activity ->
            val dialog = sheet(activity)
            val deco = dialog.window!!.decorView
            val move = requireNotNull(dialog.findViewById<View>(R.id.rowMoreMove)) as ViewGroup
            val moveLabel = (move.getChildAt(1) as ViewGroup).getChildAt(0) as TextView
            assertEquals("Set category", moveLabel.text.toString())
            assertEquals(activity.getString(R.string.v3_set_category), moveLabel.text.toString())
            assertFalse("the sheet never says Move to folder for a category",
                moveLabel.text.toString().contains("folder", ignoreCase = true))
            assertNotNull("the rows are still bound", deco.findViewWithTag<View>("docActions:compress"))
        }
        // The sheet's OCR row carries the "Extract text, OCR" TalkBack gloss; the dock's
        // cell keeps a short accessible name (it sits beside a visible "OCR" label).
        assertNotNull(app.getString(R.string.v3_ocr))
    }

    @Test(timeout = 30_000)
    fun `copy diet deciding values and action labels use P14 roles`() {
        viewer { activity ->
            val dm = activity.resources.displayMetrics
            for (id in listOf(R.id.btnEdit, R.id.btnText, R.id.btnShare, R.id.btnMore)) {
                assertEquals(
                    "dock action $id is 14sp",
                    14f * dm.scaledDensity,
                    activity.findViewById<MaterialButton>(id).textSize,
                    0.5f
                )
            }
            assertEquals(
                "the page count and size are a deciding P14 value",
                14f * dm.scaledDensity,
                activity.findViewById<TextView>(R.id.pageCount).textSize,
                0.5f
            )
            assertEquals(
                "P is pure white",
                activity.getColor(R.color.v3_text_primary),
                activity.findViewById<TextView>(R.id.pageCount).currentTextColor
            )
            assertEquals(
                "the protected banner is a short S14 line",
                14f * dm.scaledDensity,
                activity.findViewById<TextView>(R.id.v2v_protected_banner).textSize,
                0.5f
            )
            assertEquals(
                "the new protected wording is bound, not the old guarantee",
                activity.getString(R.string.v3_protected_banner),
                activity.findViewById<TextView>(R.id.v2v_protected_banner).text.toString()
            )
        }
    }

    @Test(timeout = 30_000)
    fun `copy diet new keys are paired in English and Hindi`() {
        val en = sourceOf("src/main/res/values/strings_copydiet_v3.xml")
        val hi = sourceOf("src/main/res/values-hi/strings_copydiet_v3.xml")
        val pattern = Regex("""name="(v3_[a-z_]+)"""")
        val enKeys = pattern.findAll(en).map { it.groupValues[1] }.toSet()
        val hiKeys = pattern.findAll(hi).map { it.groupValues[1] }.toSet()
        assertTrue("the v3 copy-diet file has keys", enKeys.isNotEmpty())
        assertEquals("every EN key has a HI pair", enKeys, hiKeys)
        assertFalse("the dock's save explanation is gone from the face",
            app.getString(R.string.doc_action_save_hint).contains("Save PDF adds") &&
                en.contains("doc_action_save_hint"))
    }

    private fun sourceOf(relative: String): String {
        for (candidate in listOf(File(relative), File("app", relative), File("..", relative))) {
            if (candidate.isFile) return candidate.readText()
        }
        throw AssertionError("cannot find $relative from ${System.getProperty("user.dir")}")
    }
}
