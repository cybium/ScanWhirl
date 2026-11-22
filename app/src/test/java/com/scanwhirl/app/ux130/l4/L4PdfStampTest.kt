package com.scanwhirl.app.ux130.l4
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.store.vault.VaultSession
import com.scanwhirl.app.ui.FlowRowLayout
import com.scanwhirl.app.ui.PdfViewerActivity
import com.scanwhirl.app.ui.stamp.StampEditorActivity
import com.scanwhirl.app.util.SecureView
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

/**
 * UX-L4 items 3 and 4, as behavioural Robolectric tests: real activities are launched with an
 * ActivityController over real document fixtures (with the PdfRenderer seam faked, as the other
 * reader tests do), and the named controls, a11y actions, menu items, validation and text sizes
 * are asserted on the live view hierarchy. No shipped source text is read.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class L4PdfStampTest {


    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val repo get() = ScanRepository(app)
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private class FakePages(override val pageCount: Int) : PdfViewerActivity.PdfPages {
        override fun render(index: Int, maxDim: Int) = null
        override fun close() = Unit
    }

    private lateinit var defaultFactory: (ParcelFileDescriptor) -> PdfViewerActivity.PdfPages
    private lateinit var defaultPreview: (List<File>, File) -> Unit

    @Before
    fun setUp() {
        defaultFactory = PdfViewerActivity.pagesFactory
        defaultPreview = PdfViewerActivity.previewBuilder
        PdfViewerActivity.pagesFactory = { FakePages(3) }
        PdfViewerActivity.previewBuilder = { _, out -> out.writeText("fake preview") }
    }

    @After
    fun tearDown() {
        PdfViewerActivity.pagesFactory = defaultFactory
        PdfViewerActivity.previewBuilder = defaultPreview
        VaultSession.close()
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

    private fun openReader(name: String, block: (PdfViewerActivity) -> Unit) {
        val id = DocFixtures.saved(repo, app, pages = 3, name = name)
        Robolectric.buildActivity(
            PdfViewerActivity::class.java, PdfViewerActivity.intent(app, id, name)
        ).setup().use { controller ->
            val activity = controller.get()
            assertTrue(
                "the reader loads",
                pollUntil {
                    activity.findViewById<TextView>(R.id.pdfPageIndicator)?.text?.isNotEmpty() == true
                }
            )
            block(activity)
        }
    }

    private fun forceLayout(view: View) {
        if (view.width == 0 || view.height == 0) {
            view.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
            )
            view.layout(0, 0, 1080, 1920)
        }
    }

    private fun dp48(activity: android.app.Activity): Int =
        (48f * activity.resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- L4-03

    @Test(timeout = 60_000)
    fun `L4-03 page indicator is a named 48dp Go to page control`() {
        openReader("Go to page doc") { activity ->
            val indicator = requireNotNull(activity.findViewById<View>(R.id.pdfPageIndicator)) {
                "the PDF page indicator is missing"
            }
            val min = dp48(activity)
            assertTrue("indicator must be at least 48dp wide (${indicator.minimumWidth})", indicator.minimumWidth >= min - 1)
            assertTrue("indicator must be at least 48dp tall (${indicator.minimumHeight})", indicator.minimumHeight >= min - 1)
            assertTrue("the indicator must be tappable", indicator.isClickable)
            assertEquals(
                activity.getString(R.string.ux130_l4_go_to_page),
                indicator.contentDescription?.toString()
            )
            assertEquals(
                activity.getString(R.string.ux130_l4_close_zoom),
                requireNotNull(activity.findViewById<View>(R.id.btnZoomClose)).contentDescription?.toString()
            )

            indicator.performClick()
            idle()
            val sheet = ShadowDialog.getLatestDialog()
                ?: throw AssertionError("tapping the indicator opens Go to page")
            assertTrue(
                "Go to page is the lifecycle-managed kit SwSheet, not a raw AlertDialog",
                sheet.findViewById<View>(R.id.swSheetRoot) != null
            )
            assertTrue("the Go to page sheet is showing", sheet.isShowing)
            val input = requireNotNull(sheet.findViewById<EditText>(R.id.swSheetInput)) {
                "the Go to page sheet has a page-number input"
            }
            assertEquals(activity.getString(R.string.ux130_l4_go_to_page), input.hint?.toString())
            val primary = requireNotNull(sheet.findViewById<TextView>(R.id.swSheetPrimary)) {
                "the Go to page sheet has a Go to page action"
            }
            assertEquals(activity.getString(R.string.ux130_l4_go_to_page), primary.text.toString())

            // Out of range is rejected with a localized message; the stay-open primary keeps the
            // sheet in place and nothing changes.
            input.setText("99")
            primary.performClick()
            idle()
            assertEquals(
                activity.getString(R.string.ux130_l4_go_to_page_invalid, 3),
                input.error?.toString()
            )
            assertTrue("the sheet stays open on invalid input", sheet.isShowing)

            // A valid page moves the reader there and closes the sheet.
            input.setText("2")
            primary.performClick()
            idle()
            assertFalse("the sheet closes on a valid page", sheet.isShowing)
            assertTrue(
                "the reader shows the requested page",
                pollUntil {
                    activity.findViewById<TextView>(R.id.pdfPageIndicator)?.text?.toString()
                        ?.contains("2") == true
                }
            )

            // Vault mode keeps the private page number off screenshots: the same sheet is
            // FLAG_SECURE while the reader is in Vault mode.
            PdfViewerActivity::class.java.getDeclaredField("vaultMode")
                .apply { isAccessible = true }.set(activity, true)
            VaultSession.open(ByteArray(32) { 1 })
            ShadowDialog.reset()
            indicator.performClick()
            idle()
            val secureSheet = requireNotNull(ShadowDialog.getLatestDialog()) {
                "the Go to page sheet opens in Vault mode"
            }
            assertTrue(
                "the Go to page sheet must be FLAG_SECURE over a private document",
                SecureView.isSecure(secureSheet.window)
            )
        }
    }

    @Test(timeout = 60_000)
    fun `L4-03 page preview and zoom actions are spoken`() {
        openReader("Spoken preview doc") { activity ->
            val pager = activity.findViewById<RecyclerView>(R.id.pdfPager)
            forceLayout(pager)
            idle()
            val preview = requireNotNull(pager.findViewWithTag<ImageView>(0)) {
                "the first PDF page preview is rendered"
            }
            assertEquals(
                activity.getString(R.string.ux130_l4_page_zoom, 1, 3),
                preview.contentDescription?.toString()
            )
            val info = AccessibilityNodeInfoCompat.obtain()
            ViewCompat.onInitializeAccessibilityNodeInfo(preview, info)
            val zoom = info.actionList.firstOrNull { it.id == R.id.ux130_l4_zoom_page }
            assertNotNull("the preview exposes a Zoom page action", zoom)
            assertEquals(
                activity.getString(R.string.ux130_l4_zoom_page),
                zoom!!.label?.toString()
            )
            assertTrue("the preview itself is tappable", preview.hasOnClickListeners())
        }
    }

    @Test(timeout = 60_000)
    fun `L4-03 reader More holds nonduplicated reader actions`() {
        openReader("Reader More doc") { activity ->
            activity.findViewById<View>(R.id.readerDockMore).performClick()
            idle()
            val sheet = requireNotNull(ShadowDialog.getLatestDialog()) { "reader More opens a sheet" }
            assertEquals(
                "Go to page replaces Find as the reader More primary",
                activity.getString(R.string.ux130_l4_go_to_page),
                sheet.findViewById<TextView>(R.id.swSheetPrimary)?.text?.toString()
            )
            val texts = mutableListOf<String>()
            collectText(sheet.window!!.decorView, texts)
            assertFalse(
                "reader More must not duplicate the toolbar Find action",
                activity.getString(R.string.reader_find) in texts
            )

            // Vault mode keeps the restriction: no Share row in this sheet.
            PdfViewerActivity::class.java.getDeclaredField("vaultMode").apply { isAccessible = true }.set(activity, true)
            ShadowDialog.reset()
            activity.findViewById<View>(R.id.readerDockMore).performClick()
            idle()
            val secureSheet = requireNotNull(ShadowDialog.getLatestDialog()) { "the secure sheet opens" }
            val secureTexts = mutableListOf<String>()
            collectText(secureSheet.window!!.decorView, secureTexts)
            assertFalse(
                "a Vault reader More must not offer Share",
                activity.getString(R.string.share_pdf) in secureTexts
            )
            assertEquals(
                activity.getString(R.string.ux130_l4_go_to_page),
                secureSheet.findViewById<TextView>(R.id.swSheetPrimary)?.text?.toString()
            )
        }
    }

    private fun collectText(view: View, out: MutableList<String>) {
        (view as? TextView)?.let {
            it.text?.toString()?.let(out::add)
            it.contentDescription?.toString()?.let(out::add)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) collectText(view.getChildAt(i), out)
        }
    }

    // ---------------------------------------------------------------- L4-04

    @Test(timeout = 60_000)
    fun `L4-04 stamp actions have Button role and focus`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Stamp role")
        Robolectric.buildActivity(
            StampEditorActivity::class.java,
            Intent(app, StampEditorActivity::class.java)
                .putExtra(StampEditorActivity.EXTRA_DOC_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ).setup().use { controller ->
            val activity = controller.get()
            val min = dp48(activity)
            // The painted action row announces a Button role and takes keyboard focus.
            for (viewId in listOf(R.id.deleteMark, R.id.undo, R.id.redo, R.id.saveCopy)) {
                val action = requireNotNull(activity.findViewById<View>(viewId)) { "stamp action $viewId missing" }
                assertEquals(
                    "${activity.resources.getResourceEntryName(viewId)} must be announced as a Button",
                    "android.widget.Button",
                    action.createAccessibilityNodeInfo().className?.toString()
                )
                assertTrue("$viewId must be keyboard focusable", action.isFocusable)
            }
            // Every action in this row keeps a >=48dp target (the original L4-04 contract).
            for (viewId in listOf(
                R.id.deleteMark, R.id.findRedactions, R.id.undo, R.id.redo, R.id.saveCopy
            )) {
                val action = requireNotNull(activity.findViewById<View>(viewId)) { "stamp action $viewId missing" }
                assertTrue("$viewId must be at least 48dp tall (${action.minimumHeight})", action.minimumHeight >= min - 1)
            }
        }
    }

    @Test(timeout = 60_000)
    fun `L4-04 reader dock metadata is at least 12sp`() {
        openReader("Reader dock doc") { activity ->
            val dock = requireNotNull(activity.findViewById<View>(R.id.readerDock)) {
                "the reader dock is missing"
            }
            assertTrue("the dock must keep its wrapping FlowRowLayout", dock is FlowRowLayout)
            val minPx = 12f * activity.resources.displayMetrics.scaledDensity
            for (cellId in listOf(
                R.id.readerDockShare, R.id.readerDockPages, R.id.readerDockText,
                R.id.readerDockSign, R.id.readerDockMore
            )) {
                val cell = requireNotNull(activity.findViewById<ViewGroup>(cellId)) { "dock cell $cellId missing" }
                val label = cell.getChildAt(cell.childCount - 1) as? TextView
                    ?: throw AssertionError("dock cell $cellId has no label")
                assertTrue(
                    "$cellId metadata must be at least 12sp (${label.textSize}px, min $minPx)",
                    label.textSize >= minPx - 0.5f
                )
            }
        }
    }
}
