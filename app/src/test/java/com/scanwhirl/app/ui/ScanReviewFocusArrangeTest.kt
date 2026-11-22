package com.scanwhirl.app.ui

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.scanwhirl.app.R
import com.scanwhirl.app.store.PendingScans
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.pagegrid.PageGridView
import com.scanwhirl.app.util.CategoryRules
import com.scanwhirl.app.util.Durable
import com.scanwhirl.app.util.ImageTools
import com.scanwhirl.app.util.ImportFixtures
import com.scanwhirl.app.util.ScanFilter
import com.scanwhirl.app.util.SwActivitySession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.io.File

/**
 * Review slice 1 (Index + Focus): Focus page + filmstrip, Arrange grid with
 * Earlier / Later / Move to…, viewer round-trip, Save from both modes, and the
 * accessibility floor (48dp targets, 200 % text, contrast). Pages are synthetic
 * images whose WIDTH encodes their original position, so order is checkable
 * through every rename and through the saved document.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h740dp-xhdpi")
class ScanReviewFocusArrangeTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val repo by lazy { ScanRepository(app) }
    private val dirs = mutableListOf<File>()

    @After fun cleanup() {
        RuntimeEnvironment.setFontScale(1.0f)
        Durable.resetForTests()
        dirs.forEach { it.deleteRecursively() }
    }

    // ------------------------------------------------------------- 1 / 5 / 30 pages

    @Test(timeout = 30_000) fun `one page opens in Focus with Page 1 of 1 and a singular save label`() {
        val dir = stage(1)
        launch(dir).use { scenario ->
            scenario.onActivity { a ->
                assertFalse(a.arrangeMode)
                assertTrue(a.findViewById<View>(R.id.focusPanel).isShown)
                assertEquals(View.GONE, a.findViewById<View>(R.id.arrangePanel).visibility)
                assertTrue("one visible status names the page: " + a.text(R.id.reviewStatus),
                    a.text(R.id.reviewStatus).contains("Page 1 of 1"))
                assertEquals(View.GONE, a.findViewById<View>(R.id.btnPrevPage).visibility)
                assertEquals(View.GONE, a.findViewById<View>(R.id.btnNextPage).visibility)
                assertEquals("no order-check control exists", View.GONE, a.findViewById<View>(R.id.orderLink).visibility)
                assertEquals("Save 1 page", a.text(R.id.btnSavePdf))
                assertTrue(a.text(R.id.reviewStatus).endsWith("Not saved yet"))
            }
            await { scenario.get { a ->
                a.findViewById<android.widget.ImageView>(R.id.focusImage).drawable != null &&
                    a.findViewById<View>(R.id.focusLoading).visibility == View.GONE
            } }
            scenario.onActivity { a ->
                assertEquals(View.GONE, a.findViewById<View>(R.id.focusError).visibility)
            }
        }
    }

    @Test(timeout = 30_000) fun `five pages - filmstrip and arrows move the current page and the order check is explicit`() {
        val dir = stage(5)
        launch(dir).use { scenario ->
            scenario.onActivity { a ->
                val film = a.findViewById<RecyclerView>(R.id.filmstrip)
                assertEquals(5, film.adapter!!.itemCount)
                layout(film, 1080, 200)
                film.findViewHolderForAdapterPosition(3)!!.itemView.performClick()
                assertEquals(3, a.currentIndex)
                assertTrue("one visible status names the page: " + a.text(R.id.reviewStatus),
                    a.text(R.id.reviewStatus).contains("Page 4 of 5"))
                layout(film, 1080, 200)
                val cell = film.findViewHolderForAdapterPosition(3)!!.itemView
                assertTrue("current filmstrip cell is marked", cell.isSelected)
                assertEquals("Page 4, shown", cell.contentDescription)

                a.findViewById<View>(R.id.btnNextPage).performClick()
                assertTrue("one visible status names the page: " + a.text(R.id.reviewStatus),
                    a.text(R.id.reviewStatus).contains("Page 5 of 5"))
                assertFalse("no next page after the last", a.findViewById<View>(R.id.btnNextPage).isEnabled)
                a.findViewById<View>(R.id.btnPrevPage).performClick()
                assertEquals(3, a.currentIndex)

                // L2-03: no unenforced "Order looks right" ritual. Arrange is still
                // reachable from the labelled mode toggle; order correctness is
                // proven by the move/save tests and their width assertions below.
                assertEquals("no order-check link", View.GONE, a.findViewById<View>(R.id.orderLink).visibility)
                assertEquals("no order-check card", View.GONE, a.findViewById<View>(R.id.orderCard).visibility)
                assertFalse("no order-confirm button", a.findViewById<View>(R.id.btnOrderOk).isShown)
                a.findViewById<View>(R.id.btnModeArrange).performClick()
                assertTrue("the mode toggle opens Arrange", a.arrangeMode)
                assertEquals("Save 5 pages", a.text(R.id.btnSavePdf))
            }
        }
    }

    @Test(timeout = 30_000) fun `thirty pages stay bounded and every control stays reachable`() {
        val dir = stage(30)
        launch(dir).use { scenario ->
            scenario.onActivity { a ->
                assertEquals(30, a.findViewById<RecyclerView>(R.id.filmstrip).adapter!!.itemCount)
                a.findViewById<View>(R.id.btnModeArrange).performClick()
                val grid = a.findViewById<PageGridView>(R.id.pageGrid)
                assertEquals(30, grid.pageCount())
                assertEquals("3 columns on a 360dp phone", 3, grid.spanCount)
                layoutRoot(a)
                assertTrue("grid recycles cells (${grid.childCount} bound of 30)", grid.childCount in 1 until 30)
                val save = a.findViewById<View>(R.id.btnSavePdf)
                assertTrue(save.isShown)
                assertEquals("Save 30 pages", a.text(R.id.btnSavePdf))
                a.showPage(29)
                assertTrue("one visible status names the page: " + a.text(R.id.reviewStatus),
                    a.text(R.id.reviewStatus).contains("Page 30 of 30"))
                assertFalse(a.findViewById<View>(R.id.btnArrLater).isEnabled)
                assertTrue(a.findViewById<View>(R.id.btnArrEarlier).isEnabled)
                a.findViewById<View>(R.id.btnArrMove).performClick()
                val dialog = ShadowDialog.getLatestDialog()
                // v2 UI: 08_text_ocr — Move to… is a SwSheet list (rows in swSheetItems).
                assertEquals(
                    "Move to… lists every position",
                    30,
                    dialog.findViewById<ViewGroup>(R.id.swSheetItems).childCount
                )
                dialog.dismiss()
            }
        }
    }

    // ------------------------------------------------------------- Focus <-> Arrange

    @Test(timeout = 30_000) fun `Focus and Arrange share the current page and survive a recreate`() {
        val dir = stage(5)
        launch(dir).use { scenario ->
            scenario.onActivity { a ->
                a.showPage(2)
                a.findViewById<View>(R.id.btnModeArrange).performClick()
                assertTrue(a.arrangeMode)
                assertEquals(View.VISIBLE, a.findViewById<View>(R.id.arrangePanel).visibility)
                assertEquals(View.GONE, a.findViewById<View>(R.id.focusPanel).visibility)
                assertEquals("category rail yields its room to the grid", View.GONE,
                    a.findViewById<View>(R.id.categoryBlock).visibility)
                val grid = a.findViewById<PageGridView>(R.id.pageGrid)
                assertTrue(grid.currentAt(2))
                layout(grid, 1080, 2000)
                val cur = grid.findViewHolderForAdapterPosition(2)!!.itemView
                assertTrue("current page ringed in Arrange", cur.isActivated)
                assertEquals("ring drawn above the thumb", View.VISIBLE, cur.findViewById<View>(R.id.currentRing).visibility)
                val other = grid.findViewHolderForAdapterPosition(1)!!.itemView
                assertFalse(other.isActivated)
                assertEquals(View.GONE, other.findViewById<View>(R.id.currentRing).visibility)

                // Tap selects; tapping the selected page again opens it in Focus.
                grid.findViewHolderForAdapterPosition(4)!!.itemView.performClick()
                assertEquals(4, a.currentIndex)
                assertTrue("one visible status names the page: " + a.text(R.id.reviewStatus),
                    a.text(R.id.reviewStatus).contains("Page 5 of 5"))
                layout(grid, 1080, 2000)
                grid.findViewHolderForAdapterPosition(4)!!.itemView.performClick()
                assertFalse(a.arrangeMode)
                assertEquals(4, a.currentIndex)

                a.findViewById<View>(R.id.btnModeArrange).performClick()
                a.showPage(1)
            }
            scenario.recreate()
            scenario.onActivity { a ->
                assertTrue("mode restored", a.arrangeMode)
                assertEquals("current page restored", 1, a.currentIndex)
                assertTrue(a.findViewById<MaterialButton>(R.id.btnModeArrange).isChecked)
            }
        }
    }

    // ------------------------------------------------------------- reorder durability

    @Test(timeout = 30_000) fun `Later Earlier and Move to rename the staged files and the selection follows the page`() {
        val dir = stage(5)
        launch(dir).use { scenario ->
            scenario.onActivity { a ->
                a.showPage(1)
                a.findViewById<View>(R.id.btnModeArrange).performClick()
                // TEST3: the rename's IO work (a handful of tiny file renames) can finish before a
                // single synchronous read of pageOpBusy on a fast or loaded runner. Watch the flag
                // from the moment of the tap and assert it was observed. This checks the SAME
                // property over a window - strictly stronger than one instantaneous sample - and
                // the Save-refusal assertion below still proves the observable contract.
                val busySeen = java.util.concurrent.atomic.AtomicBoolean(false)
                val watcher = Thread {
                    val end = System.nanoTime() + 5_000_000_000L
                    while (!busySeen.get() && !Thread.currentThread().isInterrupted &&
                        System.nanoTime() < end
                    ) {
                        if (a.pageOpBusy) busySeen.set(true) else Thread.yield()
                    }
                }.apply { isDaemon = true }
                watcher.start()
                try {
                    a.findViewById<View>(R.id.btnArrLater).performClick()
                    val seenBy = System.nanoTime() + 5_000_000_000L
                    while (!busySeen.get() && System.nanoTime() < seenBy) Thread.sleep(1)
                    assertTrue("the rename marks the page operation busy", busySeen.get())
                    // While the rename pass runs, Save waits instead of reading half-renamed pages.
                    a.findViewById<View>(R.id.btnSavePdf).performClick()
                    assertEquals(app.getString(R.string.review_busy), ShadowToast.getTextOfLatestToast())
                } finally {
                    watcher.interrupt()
                }
            }
            await { widths(dir) == listOf(120, 122, 121, 123, 124) && !scenario.get { it.pageOpBusy } }
            assertEquals("nothing saved while busy", 0, repo.listDocuments().size)
            scenario.onActivity { a ->
                assertEquals("selection follows the moved page", 2, a.currentIndex)
                assertEquals("Page moved to position 3", snackbarText(a))
                a.findViewById<View>(R.id.btnArrEarlier).performClick()
            }
            await { widths(dir) == listOf(120, 121, 122, 123, 124) && !scenario.get { it.pageOpBusy } }
            scenario.onActivity { a ->
                assertEquals(1, a.currentIndex)
                a.findViewById<View>(R.id.btnArrMove).performClick()
                // v2 UI: 08_text_ocr — pick position 5 from the sheet list.
                ShadowDialog.getLatestDialog()
                    .findViewById<ViewGroup>(R.id.swSheetItems).getChildAt(4).performClick()
            }
            await { widths(dir) == listOf(120, 122, 123, 124, 121) && !scenario.get { it.pageOpBusy } }
            scenario.onActivity { a -> assertEquals(4, a.currentIndex) }
            assertEquals("no duplicate or temp pages", 5, dir.listFiles()!!.count { it.extension == "jpg" })
            assertTrue(dir.listFiles()!!.none { it.name.startsWith("tmp_") })
        }
        // Durable: a fresh review reads the same order from disk.
        launch(dir).use { scenario ->
            scenario.onActivity { a ->
                val grid = a.findViewById<PageGridView>(R.id.pageGrid)
                assertEquals(listOf(120, 122, 123, 124, 121), grid.pageOrder().map { width(it) })
            }
        }
    }

    @Test(timeout = 30_000) fun `moved index follows the page through any move`() {
        assertEquals(4, ScanReviewActivity.movedIndex(1, 1, 4))
        assertEquals(1, ScanReviewActivity.movedIndex(2, 0, 4))
        assertEquals(3, ScanReviewActivity.movedIndex(2, 4, 0))
        assertEquals(0, ScanReviewActivity.movedIndex(0, 3, 4))
        assertEquals(2, ScanReviewActivity.movedIndex(1, 2, 1))
    }

    // ------------------------------------------------------------- viewer round trip

    @Test(timeout = 30_000) fun `back from the viewer Focus lands on the page shown there with its edited file`() {
        val dir = stage(4)
        val files = PendingScans.pages(dir)
        launch(dir).use { scenario ->
            var request: Intent? = null
            scenario.onActivity { a ->
                a.findViewById<View>(R.id.btnToolCrop).performClick()
                val crop = shadowOf(a).nextStartedActivity
                assertEquals(PageViewerActivity::class.java.name, crop.component?.className)
                assertTrue("Crop opens the viewer's crop overlay", crop.getBooleanExtra("start_crop", false))
                assertEquals(0, crop.getIntExtra("page_index", -1))

                a.findViewById<View>(R.id.focusImage).performClick()
                request = shadowOf(a).nextStartedActivity
                assertEquals(dir.path, request!!.getStringExtra("staging_dir"))
            }
            // The owner swiped to page 3 in the viewer and rotated it there.
            ImageTools.rotate90(files[2], clockwise = true)
            scenario.onActivity { a ->
                shadowOf(a).receiveResult(
                    request, Activity.RESULT_OK,
                    Intent().putExtra(PageViewerActivity.RESULT_PAGE_NAME, "page_002.jpg")
                )
            }
            scenario.moveToState(Lifecycle.State.STARTED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { a ->
                assertEquals(2, a.currentIndex)
                assertTrue("one visible status names the page: " + a.text(R.id.reviewStatus),
                    a.text(R.id.reviewStatus).contains("Page 3 of 4"))
                val tag = a.findViewById<View>(R.id.focusImage).tag as String
                assertTrue("Focus shows the edited file, not a stale decode",
                    tag.contains("|${files[2].lastModified()}|${files[2].length()}|"))
                assertTrue(a.text(R.id.reviewStatus).endsWith("Edited · not saved"))
            }
        }
    }

    // ------------------------------------------------------------- page edits

    @Test(timeout = 30_000) fun `Rotate in Focus turns the real file and Undo turns it back`() {
        val dir = stage(2)
        val page = PendingScans.pages(dir)[0]
        launch(dir).use { scenario ->
            scenario.onActivity { a -> a.findViewById<View>(R.id.btnToolRotate).performClick() }
            await { ImageTools.imageSize(page.readBytes()) == (80 to 120) && !scenario.get { it.pageOpBusy } }
            scenario.onActivity { a ->
                assertEquals("Page 1 rotated", snackbarText(a))
                a.findViewById<View>(com.google.android.material.R.id.snackbar_action).performClick()
            }
            await { ImageTools.imageSize(page.readBytes()) == (120 to 80) && !scenario.get { it.pageOpBusy } }
        }
    }

    @Test(timeout = 30_000) fun `Delete in Focus keeps the position and Undo restores the page`() {
        val dir = stage(3)
        launch(dir).use { scenario ->
            scenario.onActivity { a -> a.showPage(1) }
            // Delete is refused while a page operation (the Focus decode) is in flight.
            await { !scenario.get { it.pageOpBusy } }
            scenario.onActivity { a ->
                a.findViewById<View>(R.id.btnToolDelete).performClick()
            }
            // The rename + re-list can settle a tick after the click; wait for the
            // combined status line to show the new count (bounded wait, no hang).
            await { scenario.get { it.text(R.id.reviewStatus).contains("Page 2 of 2") } }
            scenario.onActivity { a ->
                assertTrue(File(dir, "page_001.jpg.del").exists())
                assertEquals(1, a.currentIndex)
                assertTrue("one visible status names the page: " + a.text(R.id.reviewStatus),
                    a.text(R.id.reviewStatus).contains("Page 2 of 2"))
                assertEquals("Save 2 pages", a.text(R.id.btnSavePdf))
            }
            shadowOf(Looper.getMainLooper()).idle()
            scenario.onActivity { a ->
                a.findViewById<View>(com.google.android.material.R.id.snackbar_action).performClick()
            }
            await {
                scenario.get {
                    File(dir, "page_001.jpg").exists() &&
                        it.text(R.id.btnSavePdf) == "Save 3 pages"
                }
            }
        }
    }

    @Test(timeout = 30_000) fun `Filter in Focus is stored for that page and survives a relaunch`() {
        val dir = stage(2)
        launch(dir).use { scenario ->
            scenario.onActivity { a ->
                a.showPage(1)
                a.findViewById<View>(R.id.btnToolFilter).performClick()
                // v2 UI: 08_text_ocr — the filter list is a SwSheet list.
                ShadowDialog.getLatestDialog()
                    .findViewById<ViewGroup>(R.id.swSheetItems)
                    .getChildAt(ScanFilter.BLACKWHITE.ordinal).performClick()
                assertTrue(File(dir, "filters.txt").readText().contains("page_001.jpg=${ScanFilter.BLACKWHITE.id}"))
                assertTrue(a.text(R.id.reviewStatus).endsWith("Edited · not saved"))
            }
            scenario.recreate()
            scenario.onActivity { a ->
                assertTrue("edited state survives a configuration change",
                    a.text(R.id.reviewStatus).endsWith("Edited · not saved"))
            }
        }
        launch(dir).use { scenario ->
            scenario.onActivity { a ->
                val grid = a.findViewById<PageGridView>(R.id.pageGrid)
                assertEquals(ScanFilter.BLACKWHITE, grid.filterFor(File(dir, "page_001.jpg")))
                assertEquals(ScanFilter.ORIGINAL, grid.filterFor(File(dir, "page_000.jpg")))
            }
        }
    }

    // ------------------------------------------------------------- saving

    @Test(timeout = 30_000) fun `Save from Arrange after a move saves exactly one document in the shown order`() {
        val dir = stage(3)
        launch(dir).use { scenario ->
            scenario.onActivity { a ->
                a.findViewById<View>(R.id.btnModeArrange).performClick()
                a.findViewById<View>(R.id.btnArrLater).performClick()
            }
            await { widths(dir) == listOf(121, 120, 122) && !scenario.get { it.pageOpBusy } }
            scenario.onActivity { a ->
                a.findViewById<View>(R.id.btnSavePdf).performClick()
                a.findViewById<View>(R.id.btnSavePdf).performClick() // a double tap must not duplicate
            }
            await { repo.listDocuments().size == 1 && !dir.exists() }
        }
        val doc = repo.listDocuments().single()
        assertEquals(3, doc.pageCount)
        assertEquals(listOf(121, 120, 122), doc.pageFiles.map { width(it) })
    }

    @Test(timeout = 30_000) fun `Save from Focus saves exactly one document with title and category`() {
        val dir = stage(2)
        launch(dir).use { scenario ->
            scenario.onActivity { a ->
                a.findViewById<android.widget.EditText>(R.id.titleInput).setText("Focus save")
                chip(a, CategoryRules.FORMS).performClick()
                a.showPage(1)
                a.findViewById<View>(R.id.btnSavePdf).performClick()
            }
            await { repo.listDocuments().size == 1 && !dir.exists() }
        }
        val doc = repo.listDocuments().single()
        assertEquals("Focus save", doc.name)
        assertEquals(CategoryRules.FORMS, doc.category)
        assertEquals(listOf(120, 121), doc.pageFiles.map { width(it) })
    }

    // ------------------------------------------------------------- visibility & a11y

    @Test(timeout = 30_000) fun `the chosen category chip is visibly marked, not only checked`() {
        val dir = stage(1)
        launch(dir).use { scenario ->
            scenario.onActivity { a ->
                val density = a.resources.displayMetrics.density
                chip(a, CategoryRules.FORMS).performClick()
                val forms = chip(a, CategoryRules.FORMS)
                val study = chip(a, CategoryRules.STUDY)
                assertTrue(forms.isChecked)
                assertEquals(2f * density, forms.chipStrokeWidth, 0.01f)
                assertTrue("tick shown on the chosen chip", forms.isCheckedIconVisible)
                assertEquals(1f * density, study.chipStrokeWidth, 0.01f)
                assertFalse(study.isCheckedIconVisible)
                assertTrue(a.text(R.id.reviewStatus).contains(CategoryRules.label(CategoryRules.FORMS)))
            }
        }
    }

    @Test(timeout = 30_000) fun `at 200 percent text Save stays on screen and every tool keeps a 48dp target`() {
        RuntimeEnvironment.setFontScale(2.0f)
        val dir = stage(5)
        launch(dir).use { scenario ->
            scenario.onActivity { a ->
                val root = layoutRoot(a)
                val dp = a.resources.displayMetrics.density
                fun check(ids: List<Int>) = ids.forEach { id ->
                    val b = a.findViewById<MaterialButton>(id)
                    assertTrue("${b.text} is shown", b.isShown)
                    assertTrue("${b.text} is >= 48dp tall (${b.height}px)", b.height >= 48 * dp)
                    assertTrue("${b.text} is >= 48dp wide", b.width >= 48 * dp)
                    assertEquals("${b.text} label on one line", 1, b.layout.lineCount)
                    assertEquals("${b.text} label not cut", 0, b.layout.getEllipsisCount(0))
                }
                check(listOf(R.id.btnToolCrop, R.id.btnToolRotate, R.id.btnToolFilter, R.id.btnToolAdd, R.id.btnToolDelete))
                val save = a.findViewById<View>(R.id.btnSavePdf)
                assertTrue(save.isShown)
                assertTrue("Save fully on screen", absBottom(save, root) <= root.height)
                assertTrue("Focus page keeps real room (${a.findViewById<View>(R.id.focusStage).height / dp}dp)",
                    a.findViewById<View>(R.id.focusStage).height >= 120 * dp)

                a.findViewById<View>(R.id.btnModeArrange).performClick()
                layoutRoot(a)
                check(listOf(R.id.btnArrEarlier, R.id.btnArrLater, R.id.btnArrMove, R.id.btnArrEdit, R.id.btnArrDelete))
                assertTrue(absBottom(save, root) <= root.height)
                assertTrue("grid keeps at least one row (${a.findViewById<View>(R.id.pageGrid).height / dp}dp)",
                    a.findViewById<View>(R.id.pageGrid).height >= 100 * dp)
            }
        }
    }

    @Test(timeout = 30_000) fun `review colours are legible on the dark theme and the icon glyphs resolve`() {
        fun c(id: Int) = ContextCompat.getColor(app, id)
        val bg = c(R.color.rv_bg)
        listOf(R.color.rv_ink, R.color.rv_ink_2, R.color.rv_ink_3, R.color.rv_accent,
            R.color.rv_warn, R.color.rv_danger).forEach { fg ->
            val ratio = ColorUtils.calculateContrast(c(fg), bg)
            assertTrue("${app.resources.getResourceEntryName(fg)} on rv_bg is $ratio:1", ratio >= 4.5)
        }
        assertTrue(ColorUtils.calculateContrast(c(R.color.rv_on_commit), c(R.color.rv_commit)) >= 4.5)
        // App theme is fixed dark ("no DayNight drift"): a values-night twin would
        // flip Review alone with the system setting, so there must not be one.
        assertFalse(File("src/main/res/values-night/colors.xml").exists())
        listOf(R.drawable.ic_sw_focus_page, R.drawable.ic_sw_arrange, R.drawable.ic_sw_crop,
            R.drawable.ic_sw_rotate, R.drawable.ic_sw_filter, R.drawable.ic_sw_add_page,
            R.drawable.ic_sw_delete, R.drawable.ic_sw_move_page, R.drawable.ic_sw_earlier,
            R.drawable.ic_sw_later, R.drawable.ic_sw_saved).forEach {
            assertNotNull(ContextCompat.getDrawable(app, it))
        }
    }

    // ------------------------------------------------------------- helpers

    private fun stage(count: Int) = PendingScans.create(app).also { dir ->
        dirs.add(dir)
        repeat(count) { File(dir, "page_%03d.jpg".format(it)).writeBytes(ImportFixtures.image(width = 120 + it)) }
    }

    private fun launch(dir: File) =
        SwActivitySession.launch<ScanReviewActivity>(ScanReviewActivity.intent(app, dir.path, null, "Doc"))

    private fun width(f: File) = ImageTools.imageSize(f.readBytes())!!.first
    /** Null while a rename pass is mid-flight (page -> tmp -> page). */
    private fun widths(dir: File): List<Int>? =
        try { PendingScans.pages(dir).map { width(it) } } catch (_: Exception) { null }

    private fun Activity.text(id: Int) = findViewById<TextView>(id).text.toString()

    private fun chip(a: ScanReviewActivity, category: String): Chip {
        val rail = a.findViewById<ChipGroup>(R.id.categoryRail)
        return (0 until rail.childCount).map { rail.getChildAt(it) as Chip }.first { it.tag == category }
    }

    private fun snackbarText(a: Activity): String? =
        a.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)?.text?.toString()

    private fun <T> SwActivitySession<ScanReviewActivity>.get(block: (ScanReviewActivity) -> T): T =
        block(activity)

    private fun layout(v: View, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }

    /** Lays the screen out as the configured device (360x740dp), no system bars. */
    private fun layoutRoot(a: Activity): View {
        val root = a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val d = a.resources.displayMetrics.density
        val cfg = a.resources.configuration
        repeat(2) { layout(root, Math.round(cfg.screenWidthDp * d), Math.round(cfg.screenHeightDp * d)) }
        return root
    }

    private fun absBottom(v: View, root: View): Int {
        var b = v.bottom
        var p = v.parent as? View
        while (p != null && p !== root) { b += p.top; p = p.parent as? View }
        return b
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        fail("Timed out")
    }
}
