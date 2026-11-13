package com.scanwhirl.app.ui

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Animatable
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.CategoryRules
import com.scanwhirl.app.util.ImportFixtures
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

/**
 * Slice 4 — the Import chooser and the Home-accessible sheets.
 *
 * Host-test limits: Robolectric proves wiring, copy, geometry and contrast on a
 * synthetic screen; it does not prove how the sheets look on a device.
 */
abstract class ImportSheetBase {

    protected lateinit var app: Application
    protected lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        com.scanwhirl.app.util.ProGate.setPro(app, false)
        com.scanwhirl.app.util.ProGate.setTestOverride(app, null)
        RuntimeEnvironment.setFontScale(1.0f)
    }

    @After
    fun tearDown() {
        RuntimeEnvironment.setFontScale(1.0f)
    }

    // ------------------------------------------------------------------ Import

    @Test
    fun `import chooser names Photos and PDF with distinct vector icons and honest copy`() {
        withImportSheet { _, sheet ->
            val photosTitle = sheet.findViewById<TextView>(R.id.importPhotosTitle)!!.text.toString()
            val pdfTitle = sheet.findViewById<TextView>(R.id.importPdfTitle)!!.text.toString()
            assertEquals(app.getString(R.string.import_photos), photosTitle)
            assertEquals(app.getString(R.string.import_pdf), pdfTitle)
            assertNotEquals(photosTitle, pdfTitle)

            val photosDesc = sheet.findViewById<TextView>(R.id.importPhotosDesc)!!.text.toString()
            val pdfDesc = sheet.findViewById<TextView>(R.id.importPdfDesc)!!.text.toString()
            // M08 copy: one plain line per row, no "Review" promise in the chooser.
            assertEquals(app.getString(R.string.import_photos_desc), photosDesc)
            assertEquals(app.getString(R.string.import_pdf_desc), pdfDesc)
            assertTrue("PDF copy says the pages become editable", pdfDesc.contains("editable"))
            val all = listOf(photosTitle, photosDesc, pdfTitle, pdfDesc)
                .joinToString(" ").lowercase()
            for (banned in listOf("coming", "wp-06", "lossless", "tap order", "order you tapped")) {
                assertFalse("no '$banned' claim", all.contains(banned))
            }

            // M08: the order note is kept in the layout but hidden — the picker
            // order is still explained in Review, not in the chooser.
            val orderNote = sheet.findViewById<TextView>(R.id.importOrderNote)!!
            assertFalse("the order note is not shown in the chooser", orderNote.isVisible)
            assertTrue(orderNote.text.contains("different order"))

            val photosIcon = firstImage(sheet.findViewById(R.id.importPhotos))
            val pdfIcon = firstImage(sheet.findViewById(R.id.importPdf))
            assertEquals(R.drawable.ic_sw_photos, shadowOf(photosIcon.drawable).createdFromResId)
            assertEquals(R.drawable.ic_sw_pdf_file, shadowOf(pdfIcon.drawable).createdFromResId)
            assertFalse("icons are static (no looping motion)", photosIcon.drawable is Animatable)
            assertFalse(pdfIcon.drawable is Animatable)
        }
    }

    @Test
    fun `photos tap dismisses and opens the multi-image gallery picker once`() {
        withImportSheet { activity, sheet ->
            sheet.findViewById<View>(R.id.importPhotos)!!.performClick()
            assertFalse("sheet closes on choice", sheet.isShowing)
            sheet.findViewById<View>(R.id.importPhotos)!!.performClick() // double tap
            sheet.findViewById<View>(R.id.importPdf)!!.performClick()
            val shadow = shadowOf(activity)
            val req = shadow.nextStartedActivityForResult
            assertNotNull("one picker launched", req)
            assertEquals(Intent.ACTION_GET_CONTENT, req.intent.action)
            assertEquals("image/*", req.intent.type)
            assertTrue(req.intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
            assertNull("no second launch", shadow.nextStartedActivityForResult)
            assertEquals("no document created by choosing", 0, docCount())
        }
    }

    @Test
    fun `pdf tap opens the PDF document picker once and creates nothing`() {
        withImportSheet { activity, sheet ->
            sheet.findViewById<View>(R.id.importPdf)!!.performClick()
            sheet.findViewById<View>(R.id.importPdf)!!.performClick()
            val shadow = shadowOf(activity)
            val req = shadow.nextStartedActivityForResult
            assertNotNull(req)
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, req.intent.action)
            assertTrue(req.intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)!!
                .contains("application/pdf"))
            assertNull("no second launch", shadow.nextStartedActivityForResult)
            assertEquals(0, docCount())
        }
    }

    @Test
    fun `a picked pdf still shows the rasterize confirmation and cancel imports nothing`() {
        withImportSheet { activity, sheet ->
            sheet.findViewById<View>(R.id.importPdf)!!.performClick()
            val req = shadowOf(activity).nextStartedActivityForResult
            shadowOf(activity).receiveResult(
                req.intent, Activity.RESULT_OK,
                Intent().setData(Uri.parse("content://slice4/doc.pdf"))
            )
            val confirm = ShadowDialog.getLatestDialog()!!
            assertTrue(confirm.isShowing)
            val text = confirm.window!!.decorView.allText()
            assertTrue("title kept", text.contains(app.getString(R.string.edit_pdf_note_title)))
            assertTrue("rasterize warning kept", text.contains(app.getString(R.string.pdf_rasterize_note)))
            confirm.findViewById<View>(android.R.id.button2).performClick() // Cancel
            assertEquals("cancel imports nothing", 0, docCount())
        }
    }

    @Test
    fun `cancelled pickers and back or close dismiss without side effects`() {
        withImportSheet { activity, sheet ->
            sheet.findViewById<View>(R.id.importClose)!!.performClick()
            assertFalse("close button dismisses", sheet.isShowing)
            assertNull(shadowOf(activity).nextStartedActivityForResult)
        }
        withImportSheet { activity, sheet ->
            sheet.cancel() // what Back / scrim tap do
            assertFalse(sheet.isShowing)
            assertNull(shadowOf(activity).nextStartedActivityForResult)
        }
        withImportSheet { activity, sheet ->
            sheet.findViewById<View>(R.id.importPhotos)!!.performClick()
            val req = shadowOf(activity).nextStartedActivityForResult
            shadowOf(activity).clearNextStartedActivities()
            shadowOf(activity).receiveResult(req.intent, Activity.RESULT_CANCELED, null)
            assertNull("no selection → Review is not started", shadowOf(activity).nextStartedActivity)
            assertEquals(0, docCount())
        }
    }

    @Test
    fun `import rows are real buttons for TalkBack and targets are at least 48dp`() {
        withImportSheet { _, sheet ->
            val d = app.resources.displayMetrics.density
            for (id in intArrayOf(R.id.importPhotos, R.id.importPdf)) {
                val row = sheet.findViewById<View>(id)!!
                assertTrue(row.isClickable && row.isFocusable)
                val info = AccessibilityNodeInfo.obtain()
                row.onInitializeAccessibilityNodeInfo(info)
                assertEquals(android.widget.Button::class.java.name, info.className.toString())
                assertTrue("row ≥ 48dp tall", row.minimumHeight >= 48 * d || row.measuredHeight >= 48 * d)
            }
            val close = sheet.findViewById<View>(R.id.importClose)!!
            assertEquals(app.getString(R.string.close), close.contentDescription)
            assertEquals(48 * d, close.layoutParams.width.toFloat(), 0.5f)
            assertEquals(48 * d, close.layoutParams.height.toFloat(), 0.5f)
            assertTrue("title is a heading",
                androidx.core.view.ViewCompat.isAccessibilityHeading(sheet.findViewById(R.id.importTitle)!!))
        }
    }

    @Test
    fun `at 200 percent text every import option and the close button stay reachable`() {
        RuntimeEnvironment.setFontScale(2.0f)
        withImportSheet { _, sheet ->
            val scroll = sheet.findViewById<View>(R.id.importPhotos)!!.parent.parent as View
            assertTrue("content lives in a NestedScrollView", scroll is NestedScrollView)
            val w = app.resources.displayMetrics.widthPixels
            val h = app.resources.displayMetrics.heightPixels
            scroll.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.AT_MOST)
            )
            scroll.layout(0, 0, scroll.measuredWidth, scroll.measuredHeight)
            val content = (scroll as ViewGroup).getChildAt(0)
            assertTrue(
                "taller content scrolls inside the sheet instead of clipping",
                content.measuredHeight <= scroll.measuredHeight || scroll.canScrollVertically(1)
            )
            for (id in intArrayOf(R.id.importPhotosDesc, R.id.importPdfDesc, R.id.importOrderNote)) {
                val tv = sheet.findViewById<TextView>(id)!!
                assertNull("copy is never ellipsized away", tv.ellipsize)
                assertEquals("copy has no line cap", Int.MAX_VALUE, tv.maxLines)
            }
            // The close button is in the scroll content's first row → reachable at scroll 0.
            val close = sheet.findViewById<View>(R.id.importClose)!!
            assertTrue(close.top >= 0)
        }
    }

    @Test
    fun `import text has AA contrast on the ink sheet and row surfaces`() {
        fun c(res: Int) = ContextCompat.getColor(app, res)
        fun ratio(fg: Int, bg: Int) = ColorUtils.calculateContrast(fg, bg)
        assertTrue(ratio(c(R.color.rv_ink), c(R.color.rv_surface_2)) >= 4.5)
        assertTrue(ratio(c(R.color.rv_ink_2), c(R.color.rv_surface_2)) >= 4.5)
        assertTrue(ratio(c(R.color.rv_ink_3), c(R.color.rv_surface)) >= 4.5)
        assertTrue(ratio(c(R.color.rv_danger), c(R.color.rv_surface)) >= 4.5)
        assertNotEquals(Color.TRANSPARENT, c(R.color.rv_surface))
    }

    // -------------------------------------------------- Quick sort count truth

    @Test
    fun `quick sort counts Unsorted plus Other as to organize and never calls Other unsorted`() {
        seed("Notes", CategoryRules.STUDY)
        seed("Bill", CategoryRules.UNSORTED)
        seed("Misc", CategoryRules.OTHER)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // M08: the Home nudge is retired; the queue is reached through
                // the Tools sheet's Quick sort tile.
                assertFalse("the retired nudge stays hidden",
                    activity.findViewById<View>(R.id.nudge).isVisible)
                openQuickSort(activity)
                val sheet = ShadowDialog.getLatestDialog()!!
                val progress = sheet.findViewById<TextView>(R.id.qsProgress)!!
                assertEquals(app.getString(R.string.qs_progress, 1, 2), progress.text.toString())
                // "Other" is part of the queue but is never called Unsorted.
                assertFalse("Other is not Unsorted",
                    progress.text.toString().lowercase().contains("unsorted"))
                // Chip rail's Unsorted chip counts only the one Unsorted doc.
                val unsortedChip = activity.findViewById<View>(R.id.chipRail).allChips()
                    .first { it.text.startsWith("Unsorted") }
                assertTrue("Unsorted chip shows 1 (got ${unsortedChip.text})",
                    unsortedChip.text.toString().trim().endsWith("1"))
            }
        }
    }

    // ------------------------------------------------ Home-accessible sheets

    @Test
    fun `quick sort sheet scrolls and its X and Skip stay`() {
        seed("Bill", CategoryRules.UNSORTED)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                openQuickSort(activity)
                val sheet = ShadowDialog.getLatestDialog()!!
                val close = sheet.findViewById<View>(R.id.qsClose)!!
                assertEquals(app.getString(R.string.close), close.contentDescription)
                var p: View = sheet.findViewById(R.id.qsChips)!!
                var scrolls = false
                while (p.parent is View) { p = p.parent as View; if (p is NestedScrollView) scrolls = true }
                assertTrue("quick sort content scrolls at large font", scrolls)
                val group = sheet.findViewById<ViewGroup>(R.id.qsChips)!!
                assertTrue((0 until group.childCount).any { group.getChildAt(it).tag == "skip" })
                // Category save + skip still behave (existing contract).
                (0 until group.childCount).map { group.getChildAt(it) }
                    .first { it.tag == CategoryRules.RECEIPTS }.performClick()
                assertTrue(pollUntil { docByName("Bill")?.category == CategoryRules.RECEIPTS })
                close.performClick()
                assertFalse(sheet.isShowing)
            }
        }
    }

    @Test
    fun `quick actions sheet keeps its five rows and Move to Trash is reversible`() {
        seed("Lecture notes", CategoryRules.STUDY)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val list = activity.findViewById<RecyclerView>(R.id.scanList)
                assertTrue(pollUntil { list.adapter!!.itemCount == 1 })
                forceLayout(list)
                list.findViewHolderForAdapterPosition(0)!!.itemView.performLongClick()
                val sheet = ShadowDialog.getLatestDialog()!!
                for (id in intArrayOf(R.id.qaEditPages, R.id.qaRename, R.id.qaMove, R.id.qaShare, R.id.qaDelete)) {
                    val row = sheet.findViewById<TextView>(id)!!
                    assertTrue(row.isClickable)
                }
                var p: View = sheet.findViewById(R.id.qaDelete)!!
                var scrolls = false
                while (p.parent is View) { p = p.parent as View; if (p is NestedScrollView) scrolls = true }
                assertTrue("rows scroll at large font", scrolls)
                sheet.findViewById<View>(R.id.qaDelete)!!.performClick()
                assertFalse(sheet.isShowing)
            }
            // M07: no confirm (the move is reversible); the document waits in Trash.
            assertTrue(pollUntil { docCount() == 0 })
            assertEquals("kept whole in Trash, not deleted", 1, ScanRepository(app).listTrash().size)
        }
    }

    @Test
    fun `tools sheet keeps every tile and the Free and Pro split`() {
        // Test builds are Pro by default; force the Free preview to exercise the gate.
        com.scanwhirl.app.util.ProGate.setTestOverride(app, false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.btnTools).performClick()
                val tools = ShadowDialog.getLatestDialog()!!
                // M08: six live tiles in one grid; no PRO band, and no Vault tile
                // without a legacy PIN (new Vault setup is disabled).
                for (id in intArrayOf(R.id.tile_ocr, R.id.tile_merge, R.id.tile_compress,
                    R.id.tile_resize, R.id.tile_quick_sort, R.id.tile_lock)) {
                    assertNotNull("tile $id", tools.findViewById<View>(id))
                }
                assertNull("no Vault tile without a legacy PIN", tools.findViewById<View>(R.id.tile_vault))
                assertFalse("no PRO section while Pro is not sold",
                    tools.findViewById<View>(R.id.sheetPro).isVisible)
                // Free user tapping a Pro tile gets the Pro panel, not the flow.
                tools.findViewById<View>(R.id.tile_lock)!!.performClick()
                assertNotNull(ShadowDialog.getLatestDialog()!!.findViewById<View>(R.id.proDossierStatus))
            }
        }
    }

    // ------------------------------------------------------------- helpers

    /** M08: Quick sort is opened from the Tools sheet, not from the retired nudge. */
    private fun openQuickSort(activity: Activity) {
        activity.findViewById<View>(R.id.btnTools).performClick()
        ShadowDialog.getLatestDialog()!!.findViewById<View>(R.id.tile_quick_sort)!!.performClick()
    }

    private fun withImportSheet(block: (MainActivity, Dialog) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.btnImport).performClick()
                val sheet = ShadowDialog.getLatestDialog()
                assertNotNull("Import opens a sheet", sheet)
                assertTrue(sheet!!.isShowing)
                assertNotNull("it is the new chooser", sheet.findViewById<View>(R.id.importPhotos))
                block(activity, sheet)
            }
        }
    }

    private fun firstImage(row: View): ImageView =
        (row as ViewGroup).getChildAt(0) as ImageView

    private fun View.allText(): String {
        val out = StringBuilder()
        fun walk(v: View) {
            if (v is TextView) out.append(v.text).append(' ')
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(this)
        return out.toString()
    }

    private fun View.allChips(): List<TextView> {
        val out = mutableListOf<TextView>()
        fun walk(v: View) {
            if (v is com.google.android.material.chip.Chip) out += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(this)
        return out
    }

    private fun docCount(): Int = repo.listDocuments().size

    private fun docByName(name: String) = repo.listDocuments().firstOrNull { it.name == name }

    private fun seed(name: String, category: String?): String = runBlocking {
        val uri = Uri.parse("content://slice4_${System.nanoTime()}.jpg")
        shadowOf(app.contentResolver).registerInputStream(uri, ImportFixtures.image().inputStream())
        repo.saveScan(listOf(uri), null, name, category = category).id
    }

    private fun forceLayout(rv: RecyclerView) {
        if (rv.childCount == 0) {
            val w = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
            val h = View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
            rv.measure(w, h)
            rv.layout(0, 0, 1080, 1920)
        }
    }

    private fun pollUntil(timeoutMs: Long = 8000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(50)
        }
        shadowOf(android.os.Looper.getMainLooper()).idle()
        return condition()
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ImportSheetTest : ImportSheetBase()

/** 320dp-wide phone, the case that clips first. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-xhdpi")
class ImportSheetNarrowTest : ImportSheetBase()
