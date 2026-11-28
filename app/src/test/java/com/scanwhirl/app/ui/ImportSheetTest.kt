package com.scanwhirl.app.ui
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

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
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.ui.tools.PdfImportSheets
import com.scanwhirl.app.util.PdfImportBudget
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
 * Slice 4 — the Import chooser. W8: the chooser is opened through the LIVE library
 * entry (swEmptyImport) on ShellActivity; the picker-intent assertions and the
 * cancel-writes-nothing assertion are kept at full strength against that live flow.
 * The legacy Quick sort / Quick actions / Tools sheets died with MainActivity and
 * are covered by ShellScreenTest / SelectionBarPinTest / TrashEntryPointsFixTest / ShellProGateTest.
 */
abstract class ImportSheetBase {

    @get:Rule val resetDialogShadows = ResetDialogShadowsRule()
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

    private fun withImportSheet(block: (ShellActivity, Dialog) -> Unit) {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.swEmptyImport).performClick()
                val sheet = ShadowDialog.getLatestDialog()
                assertNotNull("Import opens a sheet", sheet)
                assertTrue(sheet!!.isShowing)
                assertNotNull("it is the chooser", sheet.findViewById<View>(R.id.importPhotos))
                block(activity, sheet)
            }
        }
    }

    /**
     * W8: the live one-tap Photos picker is the Create sheet's Photos row (the Library
     * Import chooser shows the auto-crop sheet instead). Drive that live route for the
     * picker-intent assertions.
     */
    private fun withCreateSheet(block: (ShellActivity, Dialog) -> Unit) {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.swNavScan).performClick()
                val sheet = ShadowDialog.getLatestDialog()
                assertNotNull("Create sheet opens", sheet)
                block(activity, sheet!!)
            }
        }
    }

    private fun firstImage(row: View): android.widget.ImageView =
        (row as ViewGroup).getChildAt(0) as android.widget.ImageView

    private fun docCount(): Int = repo.listDocuments().size

    private fun View.allText(): String {
        val out = StringBuilder()
        fun walk(v: View) {
            if (v is TextView) out.append(v.text).append(' ')
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(this)
        return out.toString()
    }

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
            assertEquals(app.getString(R.string.import_photos_desc), photosDesc)
            assertEquals(app.getString(R.string.import_pdf_desc), pdfDesc)
            assertTrue("PDF copy says the pages become editable", pdfDesc.contains("editable"))
            val all = listOf(photosTitle, photosDesc, pdfTitle, pdfDesc)
                .joinToString(" ").lowercase()
            for (banned in listOf("coming", "wp-06", "lossless", "tap order", "order you tapped")) {
                assertFalse("no '$banned' claim", all.contains(banned))
            }
            val orderNote = sheet.findViewById<TextView>(R.id.importOrderNote)!!
            assertFalse("the order note is not shown in the chooser", orderNote.isVisible)
            assertTrue(orderNote.text.contains("page order"))

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
        withCreateSheet { activity, sheet ->
            val photos = sheet.findViewById<View>(R.id.swCreatePhotos)!!
            photos.performClick()
            assertFalse("sheet closes on choice", sheet.isShowing)
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
    fun `a picked pdf still shows the localized import notice and cancel imports nothing`() {
        // W8: MainActivity's old "rasterize confirmation" string no longer exists in main
        // (D1 gap, grep: pdf_rasterize_note only in MainActivity + string resource). The live
        // shell routes through DevicePdfGate/PdfImportSheets: keep the honesty + no-write
        // assertions against that live gate.
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val plan = PdfImportBudget.plan(100, 2_000_000_000L)
                var decision: String? = null
                val gate = PdfImportSheets.ask(activity, plan) { decision = it.javaClass.simpleName }
                gate.show()
                val text = gate.view.allText()
                assertTrue("the sheet names the page count",
                    text.contains(app.getString(R.string.tools_import_plan_title, 100)))
                assertTrue("the sheet states the size/free facts before any write",
                    text.contains(app.getString(R.string.tools_import_plan_msg,
                        PdfImportSheets.formatSize(plan.estimateBytes),
                        PdfImportSheets.formatSize(plan.freeBytes))))
                gate.view.findViewById<View>(R.id.swSheetSecondary).performClick()
                assertEquals("the cancelled decision is Cancel", "Cancel", decision)
                assertEquals("cancel imports nothing", 0, docCount())
            }
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
            assertFalse("sheet is dismissed", sheet.isShowing)
            assertNull(shadowOf(activity).nextStartedActivityForResult)
        }
        withCreateSheet { activity, sheet ->
            sheet.findViewById<View>(R.id.swCreatePhotos)!!.performClick()
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
