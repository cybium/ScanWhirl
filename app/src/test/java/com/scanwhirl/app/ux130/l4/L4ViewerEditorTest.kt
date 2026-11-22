package com.scanwhirl.app.ux130.l4
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule
import org.junit.rules.Timeout

import android.app.Application
import android.os.Looper
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.appbar.MaterialToolbar
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.DocumentViewerActivity
import com.scanwhirl.app.ui.OcrEditorActivity
import com.scanwhirl.app.ui.pagegrid.PageGridView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * UX-L4 items 1 and 2, as *behavioural* Robolectric tests: a real document is saved through the
 * repository, the real Activity is launched with an ActivityController, and the visible controls,
 * roles, sizes, menus and validation are asserted. Nothing here reads shipped source text: a
 * missing layout control or a removed menu item now fails loudly instead of passing vacuously.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class L4ViewerEditorTest {



    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val repo get() = ScanRepository(app)
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @After
    fun tearDown() {
        ShadowDialog.reset()
    }

    private fun pollUntil(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return true
            Thread.sleep(20)
        }
        idle()
        return condition()
    }

    private fun dp48(activity: android.app.Activity): Int =
        (48f * activity.resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- L4-01

    @Test(timeout = 60_000)
    fun `L4-01 visible Select pages control exists in the viewer layout`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Select pages doc")
        Robolectric.buildActivity(
            DocumentViewerActivity::class.java, DocumentViewerActivity.intent(app, id)
        ).setup().use { controller ->
            val activity = controller.get()
            assertTrue(
                "the viewer loads its pages",
                pollUntil { activity.findViewById<TextView>(R.id.pageCount)?.text?.isNotEmpty() == true }
            )
            val control = requireNotNull(
                activity.findViewById<View>(R.id.ux130_l4_select_pages)
            ) { "the viewer has no visible 'Select pages' control" }
            assertEquals(View.VISIBLE, control.visibility)
            val min = dp48(activity)
            assertTrue("'Select pages' must be at least 48dp tall (${control.minimumHeight})", control.minimumHeight >= min - 1)
            assertTrue("'Select pages' must be at least 48dp wide (${control.minimumWidth})", control.minimumWidth >= min - 1)
            assertEquals(
                app.getString(R.string.ux130_l4_select_pages),
                (control as TextView).text.toString()
            )
            assertTrue("the control must be clickable", control.hasOnClickListeners())

            control.performClick()
            idle()
            assertEquals("selection bar opens", View.VISIBLE, activity.findViewById<View>(R.id.selectionTopBar).visibility)
            assertEquals("the entry control is hidden once selection is open", View.GONE, control.visibility)
            assertTrue(
                "page tiles show their checkboxes",
                activity.findViewById<PageGridView>(R.id.pageList).checkboxesVisible
            )
        }
    }

    @Test(timeout = 60_000)
    fun `L4-01 per page Select page accessibility action exists`() {
        val id = DocFixtures.saved(repo, app, pages = 3, name = "Grid a11y doc")
        Robolectric.buildActivity(
            DocumentViewerActivity::class.java, DocumentViewerActivity.intent(app, id)
        ).setup().use { controller ->
            val activity = controller.get()
            assertTrue(
                "the viewer loads its pages",
                pollUntil { activity.findViewById<TextView>(R.id.pageCount)?.text?.isNotEmpty() == true }
            )
            val grid = activity.findViewById<PageGridView>(R.id.pageList)
            assertTrue("the grid has all three pages", pollUntil { grid.pageCount() == 3 })
            grid.refresh()
            grid.onLongPress?.invoke(0)
            idle()
            val adapter = requireNotNull(grid.adapter) { "the grid has an adapter" }
            // Prefer a genuinely attached tile. Robolectric does not always run RecyclerView's
            // layout pass during ActivityController.setup(), so fall back to a real ViewHolder
            // created and bound by the production adapter, giving it the binding position the
            // accessibility delegate reads. No production class is modified.
            val holder = grid.findViewHolderForAdapterPosition(0) ?: adapter.createViewHolder(grid, 0).also { h ->
                adapter.bindViewHolder(h, 0)
                val vh = androidx.recyclerview.widget.RecyclerView.ViewHolder::class.java
                vh.getDeclaredField("mOwnerRecyclerView").apply { isAccessible = true }.set(h, grid)
                vh.getDeclaredField("mBindingAdapter").apply { isAccessible = true }.set(h, adapter)
                vh.getDeclaredField("mPosition").apply { isAccessible = true }.set(h, 0)
            }
            val tile = holder.itemView

            val info = AccessibilityNodeInfoCompat.obtain()
            ViewCompat.onInitializeAccessibilityNodeInfo(tile, info)
            val actions = info.actionList
            val select = actions.firstOrNull { it.id == R.id.ux130_l4_a11y_select_page }
            assertNotNull("page tiles must expose a 'Select page' accessibility action", select)
            assertEquals(
                "the action must carry the spoken label",
                activity.getString(R.string.ux130_l4_select_page),
                select!!.label?.toString()
            )
            assertTrue(
                "the long-press/drag accessibility twin must remain",
                R.id.a11y_move_down in actions.map { it.id }
            )
            assertNotNull("the long-press selection route must remain", grid.onLongPress)

            // Long-press had selected this one page; actuating the a11y action toggles it off,
            // which leaves selection mode. That proves the delegate really handles the action.
            assertTrue("checkboxes are visible while selecting", grid.checkboxesVisible)
            assertTrue(
                "the Select page action is actuable",
                tile.performAccessibilityAction(R.id.ux130_l4_a11y_select_page, null)
            )
            idle()
            assertFalse("actuating the action toggles the last page off", grid.checkboxesVisible)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.selectionTopBar).visibility)
        }
    }

    @Test(timeout = 60_000)
    fun `L4-01 draft targets are 48dp and Discard failure is never silent`() {
        // A draft's three targets stay >=48dp once the runtime paint runs. The draft is seeded
        // through the real repository seam before launch so this asserts the painted bar itself,
        // not the asynchronous rotate UI (which stays inside selection mode).
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Draft doc")
        kotlinx.coroutines.runBlocking { repo.draftRotate(id, 0) }
        Robolectric.buildActivity(
            DocumentViewerActivity::class.java, DocumentViewerActivity.intent(app, id)
        ).setup().use { controller ->
            val activity = controller.get()
            assertTrue(
                "the viewer loads its pages",
                pollUntil { activity.findViewById<TextView>(R.id.pageCount)?.text?.isNotEmpty() == true }
            )
            assertTrue("a draft appears for an edited document", pollUntil {
                activity.findViewById<View>(R.id.draftBar)?.visibility == View.VISIBLE
            })
            val min = dp48(activity)
            for (viewId in listOf(R.id.btnDraftDiscard, R.id.btnDraftReplace, R.id.btnDraftSave)) {
                val target = requireNotNull(activity.findViewById<View>(viewId)) { "missing draft target $viewId" }
                assertTrue("$viewId must be at least 48dp tall (${target.minimumHeight})", target.minimumHeight >= min - 1)
                assertTrue("$viewId must be at least 48dp wide (${target.minimumWidth})", target.minimumWidth >= min - 1)
            }
        }

        // An unsafe placement explains itself instead of silently ignoring the Discard tap.
        val importId = DocFixtures.saved(repo, app, pages = 1, name = "Fresh import")
        Robolectric.buildActivity(
            DocumentViewerActivity::class.java,
            DocumentViewerActivity.intent(app, importId, true)
        ).setup().use { controller ->
            val activity = controller.get()
            activity.onBackPressedDispatcher.onBackPressed()
            idle()
            val sheet = requireNotNull(ShadowDialog.getLatestDialog()) { "the Keep/Discard sheet opens" }
            val discard = requireNotNull(sheet.findViewById<View>(R.id.swSheetSecondary)) {
                "the sheet has a Discard row"
            }
            // Force a viewport where the 148dp navigation exclusion leaves no safe place for
            // Discard. This is the exact real-world failure the message exists for.
            val decor = requireNotNull(sheet.window).decorView
            val smallHeight = (220f * activity.resources.displayMetrics.density).toInt()
            repeat(3) {
                idle()
                decor.measure(
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(smallHeight, View.MeasureSpec.EXACTLY)
                )
                decor.layout(0, 0, 1080, smallHeight)
            }
            discard.layout(0, smallHeight - 10, discard.width.coerceAtLeast(200), smallHeight)
            discard.performClick()
            idle()
            val status = requireNotNull(sheet.findViewById<TextView>(R.id.ux_uxg_discard_status)) {
                "the Keep/Discard sheet has an inline status line"
            }
            assertEquals(View.VISIBLE, status.visibility)
            assertEquals(
                activity.getString(R.string.ux130_l4_discard_unavailable),
                status.text.toString()
            )
            assertEquals(
                "the reason is announced to accessibility",
                View.IMPORTANT_FOR_ACCESSIBILITY_YES,
                status.importantForAccessibility
            )
            assertEquals(
                ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE,
                ViewCompat.getAccessibilityLiveRegion(status)
            )
            val keep = requireNotNull(sheet.findViewById<View>(R.id.swSheetPrimary)) {
                "Keep stays in the sheet"
            }
            assertTrue("Keep remains reachable", keep.isShown && keep.isEnabled)
            assertTrue("the sheet stays open for the safe choice", sheet.isShowing)
            assertNull(
                "the reason must be inside the sheet, not a Snackbar painted behind it",
                activity.findViewById<View>(com.google.android.material.R.id.snackbar_text)
            )
        }
    }

    // ---------------------------------------------------------------- L4-02

    @Test(timeout = 60_000)
    fun `L4-02 all-pages actions are grouped under one menu and hidden on one page`() {
        // Two pages: one named submenu with one Copy-all route.
        val two = DocFixtures.saved(repo, app, pages = 2, name = "Two pages")
        repo.updateOcrPage(two, "page_000.jpg", "first")
        repo.updateOcrPage(two, "page_001.jpg", "second")
        Robolectric.buildActivity(
            OcrEditorActivity::class.java,
            android.content.Intent(app, OcrEditorActivity::class.java)
                .putExtra(OcrEditorActivity.EXTRA_DOC_ID, two)
        ).setup().use { controller ->
            val activity = controller.get()
            val submenu = requireNotNull(toolbarMenu(activity).findItem(R.id.ux130_l4_all_pages)) {
                "the OCR menu has no 'All pages' submenu"
            }
            assertTrue("All pages is visible for a multi-page document", submenu.isVisible)
            val children = requireNotNull(submenu.subMenu) { "All pages is a submenu" }
            assertEquals("Copy all, Share all and Save as .txt live under All pages", 3, children.size())
            val copyAll = (0 until children.size()).count { children.getItem(it).itemId == R.id.ocr_copy_all }
            assertEquals("exactly one Copy-all route", 1, copyAll)
            val titles = (0 until children.size()).map { children.getItem(it).title.toString() }
            assertTrue(
                "the submenu offers a Save as .txt route",
                activity.getString(R.string.ux130_l4_save_as_txt) in titles
            )
        }

        // One page: the whole scope disappears (the page-scoped actions stay).
        val one = DocFixtures.saved(repo, app, pages = 1, name = "One page")
        repo.updateOcrPage(one, "page_000.jpg", "only")
        Robolectric.buildActivity(
            OcrEditorActivity::class.java,
            android.content.Intent(app, OcrEditorActivity::class.java)
                .putExtra(OcrEditorActivity.EXTRA_DOC_ID, one)
        ).setup().use { controller ->
            val activity = controller.get()
            val submenu = requireNotNull(toolbarMenu(activity).findItem(R.id.ux130_l4_all_pages)) {
                "the All pages item must still exist in the menu resource"
            }
            assertFalse("All pages is hidden on a one-page document", submenu.isVisible)
        }
    }

    @Test(timeout = 60_000)
    fun `L4-02 Finish editing replaces Done and unsaved exit separates Discard edits`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Finish")
        repo.updateOcrPage(id, "page_000.jpg", "old")
        Robolectric.buildActivity(
            OcrEditorActivity::class.java,
            android.content.Intent(app, OcrEditorActivity::class.java)
                .putExtra(OcrEditorActivity.EXTRA_DOC_ID, id)
        ).setup().use { controller ->
            val activity = controller.get()
            val toggle = activity.findViewById<TextView>(R.id.btnEditText)
            assertEquals(activity.getString(R.string.ocr_w5_edit_text), toggle.text.toString())

            toggle.performClick()
            idle()
            assertEquals(
                "edit mode uses the decided label, not the old Done concept",
                activity.getString(R.string.ux130_l4_finish_editing),
                toggle.text.toString()
            )
            assertEquals(toggle.text.toString(), toggle.contentDescription?.toString())

            toggle.performClick()
            idle()
            assertEquals(activity.getString(R.string.ocr_w5_edit_text), toggle.text.toString())

            activity.findViewById<EditText>(R.id.editText).setText("old and new")
            activity.onBackPressedDispatcher.onBackPressed()
            idle()
            val sheet = requireNotNull(ShadowDialog.getLatestDialog()) { "the unsaved-changes sheet opens" }
            val rows = requireNotNull(sheet.findViewById<ViewGroup>(R.id.swSheetItems)) { "the option rows exist" }
            val labels = (0 until rows.childCount).map {
                (rows.getChildAt(it) as ViewGroup).contentDescription?.toString()
            }
            assertTrue(
                "an explicit Save as .txt route exists",
                activity.getString(R.string.ux130_l4_save_as_txt) in labels
            )
            assertTrue(
                "Discard edits is a separate route",
                activity.getString(R.string.ux130_l4_discard_edits) in labels
            )

            rows.getChildAt(labels.indexOf(activity.getString(R.string.ux130_l4_discard_edits))).performClick()
            idle()
            val confirm = requireNotNull(ShadowDialog.getLatestDialog()) { "Discard edits is confirmed" }
            assertEquals(
                activity.getString(R.string.ux130_l4_discard_edits_confirm),
                confirm.findViewById<TextView>(R.id.swSheetTitle)?.text?.toString()
            )
            assertEquals(
                activity.getString(R.string.ux_uxg_ocr_discard_warning),
                confirm.findViewById<TextView>(R.id.swSheetMessage)?.text?.toString()
            )
            val keepEditing = requireNotNull(confirm.findViewById<TextView>(R.id.swSheetPrimary)) {
                "Keep editing is the safe primary"
            }
            assertEquals(
                activity.getString(R.string.ocr_w5_keep_editing),
                keepEditing.text.toString()
            )
            val discardEdits = requireNotNull(confirm.findViewById<TextView>(R.id.swSheetSecondary)) {
                "Discard edits is the separated secondary"
            }
            assertEquals(
                activity.getString(R.string.ux130_l4_discard_edits),
                discardEdits.text.toString()
            )
            assertEquals(
                "Discard edits must be danger-styled, not the ordinary safe cyan",
                androidx.core.content.ContextCompat.getColor(activity, R.color.laser_danger),
                discardEdits.currentTextColor
            )
        }
    }

    @Test(timeout = 60_000)
    fun `L4-02 baseline metadata is at least 12sp and reflows`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Meta")
        DocFixtures.withOcr(repo, id) // guarantees the honest footnote actually renders
        Robolectric.buildActivity(
            OcrEditorActivity::class.java,
            android.content.Intent(app, OcrEditorActivity::class.java)
                .putExtra(OcrEditorActivity.EXTRA_DOC_ID, id)
        ).setup().use { controller ->
            val activity = controller.get()
            val note = requireNotNull(activity.findViewById<TextView>(R.id.ocrFootNote)) {
                "the baseline metadata view is missing"
            }
            assertTrue("the footnote renders text", note.text.isNotEmpty())
            val minPx = 12f * activity.resources.displayMetrics.scaledDensity
            assertTrue(
                "baseline metadata must be at least 12sp (${note.textSize}px, min $minPx)",
                note.textSize >= minPx - 0.5f
            )
            assertFalse(
                "the footnote must reflow, not ellipsize",
                note.ellipsize == android.text.TextUtils.TruncateAt.END
            )
        }
    }

    /** The toolbar owns the menu; inflate only when the framework has not done it yet. */
    private fun toolbarMenu(activity: OcrEditorActivity): Menu {
        val menu = activity.findViewById<MaterialToolbar>(R.id.toolbar).menu
        if (menu.findItem(R.id.ux130_l4_all_pages) == null) activity.onCreateOptionsMenu(menu)
        return menu
    }
}
