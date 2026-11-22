package com.scanwhirl.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.ImageTools
import com.scanwhirl.app.util.ScanFilter
import com.scanwhirl.app.util.SwActivitySession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * E1 defect 2: on a saved document the chosen filter is baked into a replacement jpg by
 * ScanRepository.draftFilter. The viewer must preview the file as saved (no second application)
 * and keep the selected filter across a rotation that gives the page a new file name.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PageViewerFilterTest {

    private val context: Application
        get() = ApplicationProvider.getApplicationContext()

    private val repo get() = ScanRepository(context)

    private fun launch(id: String) = SwActivitySession.launch<PageViewerActivity>(
        PageViewerActivity.intent(context, id, 0)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    )

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun waitFor(label: String, timeoutMs: Long = 20_000, check: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            idle()
            if (check()) return
            Thread.sleep(5)
        }
        throw AssertionError("timed out waiting for $label")
    }

    private fun shownSheetItems(): LinearLayout? {
        val dialog = ShadowDialog.getLatestDialog()
        if (dialog == null || !dialog.isShowing) return null
        return dialog.findViewById(R.id.swSheetItems)
    }

    /** Opens the filter sheet and clicks the filter at [index] in ScanFilter.uiFilters. */
    private fun pickFilter(index: Int) {
        currentScenario?.onActivity { screen ->
            screen.findViewById<View>(R.id.btnFilter).performClick()
        }
        waitFor("the filter sheet to open") { shownSheetItems() != null }
        val items = shownSheetItems()!!
        items.getChildAt(index).performClick()
        idle()
        // A sheet left open here would satisfy the next waitFor with a stale dialog.
        waitFor("the filter sheet to close") { shownSheetItems() == null }
    }

    /** Checked row index of the currently shown filter sheet, or -1 when no row is selected. */
    private fun checkedFilterRow(): Int {
        val items = shownSheetItems() ?: error("filter sheet did not open")
        for (i in 0 until items.childCount) if (items.getChildAt(i).isSelected) return i
        return -1
    }

    private fun openSheet() {
        currentScenario?.onActivity { screen -> screen.findViewById<View>(R.id.btnFilter).performClick() }
        waitFor("the filter sheet to open") { shownSheetItems() != null }
    }

    private var currentScenario: SwActivitySession<PageViewerActivity>? = null

    @org.junit.Before
    fun setUp() {
        // ShadowDialog is process-wide; a sheet left by an earlier test in the same fork
        // otherwise makes shownSheetItems() inspect the wrong dialog.
        ShadowDialog.reset()
    }

    @org.junit.After
    fun tearDown() {
        currentScenario = null
        ShadowDialog.reset()
    }

    private fun displayedBitmap(): Bitmap? =
        pageImage()?.let { (it.first.drawable as? BitmapDrawable)?.bitmap }

    /** The page image plus its render key (path|mtime|length|filter), or null before bind. */
    private fun pageImage(): Pair<ImageView, String>? {
        var result: Pair<ImageView, String>? = null
        currentScenario?.onActivity { screen ->
            val pager = screen.findViewById<RecyclerView>(R.id.pager)
            val image = pager.getChildAt(0)?.findViewById<ImageView>(R.id.pageImage)
            if (image?.tag != null) result = image to image.tag.toString()
        }
        return result
    }

    private fun differs(a: Bitmap, b: Bitmap): Boolean {
        if (a.width != b.width || a.height != b.height) return true
        val xs = listOf(a.width / 4, a.width / 2, (a.width * 3f / 4f).toInt().coerceAtMost(a.width - 1))
        val ys = listOf(a.height / 4, a.height / 2, (a.height * 3f / 4f).toInt().coerceAtMost(a.height - 1))
        for (x in xs) for (y in ys) if (a.getPixel(x, y) != b.getPixel(x, y)) return true
        return false
    }

    @Test(timeout = 30_000)
    fun `preview shows the saved file and does not apply the filter twice`() {
        val id = DocFixtures.saved(repo, context, pages = 1)
        currentScenario = launch(id)
        currentScenario!!.use { scenario ->
            idle()
            // uiFilters = [ORIGINAL, ENHANCE, COLOUR, GREY, BLACKWHITE]; pick ENHANCE.
            pickFilter(1)
            waitFor("the filter replacement to be written") {
                runBlocking { repo.draftPages(id) }.first().name.startsWith("r_")
            }
            val savedFile = runBlocking { repo.draftPages(id) }.first()
            val asSaved = ImageTools.decodeSampled(savedFile.readBytes(), 2048)
            // The pager sets its render tag BEFORE the background decode finishes, so waiting
            // only for the tag and a non-null bitmap samples the previous page's pixels. Wait
            // for the displayed bitmap to actually be the saved file; a real double-filter
            // bug will time out here rather than produce a misleading assertion.
            waitFor("the preview to re-render the saved file") {
                pageImage()?.second?.contains(savedFile.name) == true &&
                    displayedBitmap()?.let { !differs(it, asSaved) } == true
            }
            val shown = displayedBitmap()!!
            assertFalse(
                "the preview must be the file exactly as saved, not the filter applied again",
                differs(shown, asSaved)
            )
        }
        currentScenario = null
    }

    @Test(timeout = 30_000)
    fun `a filter choice is dropped with the page it was set on`() {
        val id = DocFixtures.saved(repo, context, pages = 3)
        currentScenario = launch(id)
        currentScenario!!.use { scenario ->
            idle()
            pickFilter(1) // ENHANCE on page 0
            waitFor("the filter replacement to be written") {
                runBlocking { repo.draftPages(id) }.first().name.startsWith("r_")
            }
            // applyFilter writes filterChoice after the replacement file appears. Wait for that
            // in-memory choice too, otherwise a late write can land after the delete's remap.
            waitFor("the filter choice to be recorded") {
                var chosen: ScanFilter? = null
                scenario.onActivity { chosen = it.selectedFilter(0) }
                chosen == ScanFilter.ENHANCE
            }
            // Delete the filtered page. Opening the filter sheet commits the pending delete
            // before it reads the checked row, so indices must have been remapped already.
            scenario.onActivity { screen ->
                screen.findViewById<View>(R.id.btnDelete).performClick()
            }
            idle()
            scenario.onActivity { screen ->
                screen.findViewById<View>(R.id.btnFilter).performClick()
            }
            waitFor("the filter sheet after the delete") { shownSheetItems() != null }
            scenario.onActivity { screen ->
                assertEquals(
                    "the deleted page's filter must not move onto its neighbour",
                    ScanFilter.ORIGINAL,
                    screen.selectedFilter(0)
                )
            }
        }
        currentScenario = null
    }

    @Test(timeout = 30_000)
    fun `the selected filter survives a rotation that renames the page`() {
        val id = DocFixtures.saved(repo, context, pages = 1)
        currentScenario = launch(id)
        currentScenario!!.use { scenario ->
            idle()
            pickFilter(1) // ENHANCE
            waitFor("the filter replacement to be written") {
                runBlocking { repo.draftPages(id) }.first().name.startsWith("r_")
            }
            val beforeRotate = runBlocking { repo.draftPages(id) }.first().name

            scenario.onActivity { screen -> screen.findViewById<View>(R.id.btnRotateRight).performClick() }
            waitFor("the rotation replacement to be written") {
                runBlocking { repo.draftPages(id) }.first().name != beforeRotate
            }

            openSheet()
            assertEquals(
                "the sheet must still show Enhance selected after rotation",
                1,
                checkedFilterRow()
            )
            assertNotNull("a rotated replacement exists", runBlocking { repo.draftPages(id) }.first())
        }
        currentScenario = null
    }
}
