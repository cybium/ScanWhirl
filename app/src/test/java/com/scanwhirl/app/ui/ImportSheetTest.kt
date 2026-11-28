package com.scanwhirl.app.ui
import com.scanwhirl.app.util.ResetDialogShadowsRule
import org.junit.Rule

import android.app.Application
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.Animatable
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

/**
 * Slice 4 — the Import chooser. W8: the legacy Home-hosted Quick sort, Quick actions
 * and Tools sheet tests were deleted with MainActivity; those sheets are covered by
 * ShellScreenTest / SelectionBarPinTest / TrashEntryPointsFixTest / ShellProGateTest.
 * The sheet is now driven directly with recording callbacks, so its contract (one
 * choice, no import side effect, honest copy, real 48dp targets, 200% fit) is proven
 * without depending on a dead host. The host routes belong to PhotoImportTest,
 * ImportPdfFixFlowTest and PdfImportSheetsTest.
 *
 * Host-test limits: Robolectric proves wiring, copy, geometry and contrast on a
 * synthetic screen; it does not prove how the sheets look on a device.
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

    private class Choice {
        var photos = 0
        var pdf = 0
    }

    private fun withImportSheet(block: (Choice, Dialog) -> Unit) {
        ActivityScenario.launch(ShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val choice = Choice()
                val sheet = ImportSheet.show(activity, { choice.photos++ }, { choice.pdf++ })
                block(choice, sheet)
            }
        }
    }

    private fun firstImage(row: View): android.widget.ImageView =
        (row as ViewGroup).getChildAt(0) as android.widget.ImageView

    private fun docCount(): Int = repo.listDocuments().size

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
    fun `photos tap is accepted once and never imports by itself`() {
        withImportSheet { choice, sheet ->
            sheet.findViewById<View>(R.id.importPhotos)!!.performClick()
            assertFalse("sheet closes on choice", sheet.isShowing)
            sheet.findViewById<View>(R.id.importPhotos)!!.performClick() // double tap
            sheet.findViewById<View>(R.id.importPdf)!!.performClick()
            assertEquals("one Photos choice", 1, choice.photos)
            assertEquals("no second or other choice", 0, choice.pdf)
            assertEquals("no document created by choosing", 0, docCount())
        }
    }

    @Test
    fun `pdf tap is accepted once and never imports by itself`() {
        withImportSheet { choice, sheet ->
            sheet.findViewById<View>(R.id.importPdf)!!.performClick()
            sheet.findViewById<View>(R.id.importPdf)!!.performClick()
            assertEquals("one PDF choice", 1, choice.pdf)
            assertEquals("no Photos choice", 0, choice.photos)
            assertEquals("no document created by choosing", 0, docCount())
        }
    }

    @Test
    fun `choosing a row writes nothing - the host owns the import gate and its honest notice`() {
        // W8: the legacy rasterize-confirmation assertion was pinned to MainActivity's
        // edit-PDF door. The live shell routes PDFs through DevicePdfGate, whose localized
        // refusal/decision copy and "cancel imports nothing" are proven by
        // PdfImportSheetsTest and ImportFailureWordingTest. At this layer the safety
        // invariant is that the chooser itself never writes a document.
        withImportSheet { choice, sheet ->
            sheet.findViewById<View>(R.id.importPdf)!!.performClick()
            assertEquals(1, choice.pdf)
            assertEquals("choosing is not importing", 0, docCount())
            assertFalse("the chooser closed", sheet.isShowing)
        }
    }

    @Test
    fun `close and cancel dismiss without choosing or side effects`() {
        withImportSheet { choice, sheet ->
            sheet.findViewById<View>(R.id.importClose)!!.performClick()
            assertFalse("close button dismisses", sheet.isShowing)
            assertEquals(0, choice.photos + choice.pdf)
            assertEquals(0, docCount())
        }
        withImportSheet { choice, sheet ->
            sheet.cancel() // what Back / scrim tap do
            assertFalse("sheet is dismissed", sheet.isShowing)
            assertEquals(0, choice.photos + choice.pdf)
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
