package com.scanwhirl.app.ui

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import androidx.core.view.isVisible

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class UiSmokeTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
    }

    private fun contentUri(bytes: ByteArray): Uri {
        val uri = Uri.parse("content://test/smoke_${System.nanoTime()}.jpg")
        shadowOf(app.contentResolver).registerInputStream(uri, bytes.inputStream())
        return uri
    }

    private fun jpegBytes(): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(310, 444, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bmp).drawColor(android.graphics.Color.WHITE)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun seedDoc(name: String, pages: Int = 1): String = runBlocking {
        val uris = (0 until pages).map { contentUri(jpegBytes()) }
        repo.saveScan(uris, null, name).id
    }

    private fun forceLayout(rv: androidx.recyclerview.widget.RecyclerView) {
        if (rv.childCount == 0) {
            val w = android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY)
            val h = android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY)
            rv.measure(w, h)
            rv.layout(0, 0, 1080, 1920)
        }
    }

    @Test
    fun `main list shows saved documents`() {
        val id = seedDoc("Smoke doc")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.scanList)
                val empty = activity.findViewById<android.view.View>(R.id.emptyState)
                assertEquals(1, list.adapter!!.itemCount)
                assertFalse(empty.isVisible)
                assertEquals(app.getString(R.string.app_name),
                    (activity.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).title))
                forceLayout(list)
                assertNotNull(list.findViewHolderForAdapterPosition(0))
            }
        }
        assertTrue(repo.listDocuments().any { it.id == id })
    }

    @Test
    fun `long press enters selection mode and back exits it`() {
        seedDoc("Select me")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.scanList)
                forceLayout(list)
                val card = list.findViewHolderForAdapterPosition(0)!!.itemView
                val toolbar = activity.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
                val fab = activity.findViewById<android.view.View>(R.id.fabScan)

                // normal mode initially
                assertEquals(app.getString(R.string.app_name), toolbar.title)
                assertTrue(fab.isVisible)

                // long press -> selection mode with 1 selected
                card.performLongClick()
                assertEquals(app.resources.getQuantityString(R.plurals.selected_count, 1, 1), toolbar.title)
                assertFalse(fab.isVisible)

                // back -> exits selection mode
                activity.onBackPressed()
                assertEquals(app.getString(R.string.app_name), toolbar.title)
                assertTrue(fab.isVisible)
            }
        }
    }

    @Test
    fun `tools row visible on main screen with resize and compress tools`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val row = activity.findViewById<android.view.View>(R.id.toolsRow)
                assertTrue("tools row visible when empty", row.isVisible)

                val labels = mutableListOf<android.widget.TextView>()
                collectTextViews(row, labels)
                val texts = labels.map { it.text.toString() }
                assertTrue("Resize label present: $texts", app.getString(R.string.tool_resize) in texts)
                assertTrue("Compress label present: $texts", app.getString(R.string.tool_compress) in texts)

                assertTrue(activity.findViewById<android.view.View>(R.id.toolResize).isClickable)
                assertTrue(activity.findViewById<android.view.View>(R.id.toolCompress).isClickable)
            }
        }
    }

    private fun collectTextViews(v: android.view.View, out: MutableList<android.widget.TextView>) {
        if (v is android.widget.TextView) out.add(v)
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) collectTextViews(v.getChildAt(i), out)
        }
    }

    @Test
    fun `compress tool opens source picker dialog`() {
        seedDoc("Compressible")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<android.view.View>(R.id.toolCompress).performClick()
                val dialog = ShadowDialog.getLatestDialog()
                assertNotNull("compress dialog should appear", dialog)
                assertTrue((dialog as android.app.Dialog).isShowing)
                dialog.dismiss()
            }
        }
    }

    @Test
    fun `viewer displays document name and page count`() {
        val id = seedDoc("Viewer doc", pages = 2)
        val intent = DocumentViewerActivity.intent(app, id)
        ActivityScenario.launch<com.scanwhirl.app.ui.DocumentViewerActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val toolbar = activity.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
                val pageCount = activity.findViewById<android.widget.TextView>(R.id.pageCount)
                val pages = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.pageList)
                assertEquals("Viewer doc", toolbar.title)
                assertEquals("2 pages", pageCount.text.toString())
                assertEquals(2, pages.adapter!!.itemCount)
            }
        }
    }

    @Test
    fun `viewer page grid is a vertical two-column PageGridView`() {
        val id = seedDoc("Grid doc", pages = 4)
        val intent = DocumentViewerActivity.intent(app, id)
        ActivityScenario.launch<com.scanwhirl.app.ui.DocumentViewerActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val pages = activity.findViewById<com.scanwhirl.app.ui.pagegrid.PageGridView>(R.id.pageList)
                val lm = pages.layoutManager as androidx.recyclerview.widget.GridLayoutManager
                assertEquals(2, lm.spanCount)
                assertEquals(
                    androidx.recyclerview.widget.LinearLayoutManager.VERTICAL,
                    lm.orientation
                )
                assertEquals(4, pages.pageCount())
            }
        }
    }

    @Test
    fun `viewer tap opens the full-screen page view`() {
        val id = seedDoc("Rotate flow")
        val before = com.scanwhirl.app.util.PdfGenerator.readJpegInfo(
            repo.listDocuments().first { it.id == id }.pageFiles[0].readBytes())

        val intent = DocumentViewerActivity.intent(app, id)
        ActivityScenario.launch<com.scanwhirl.app.ui.DocumentViewerActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val pages = activity.findViewById<com.scanwhirl.app.ui.pagegrid.PageGridView>(R.id.pageList)
                forceLayout(pages)
                pages.findViewHolderForAdapterPosition(0)!!.itemView.performClick()

                val started = shadowOf(activity).nextStartedActivity
                assertEquals(
                    PageViewerActivity::class.java.name,
                    started?.component?.className
                )
            }
        }

        // rotate via repository directly (the pager's Rotate button uses the same call)
        runBlocking { repo.rotatePage(id, 0) }
        val after = com.scanwhirl.app.util.PdfGenerator.readJpegInfo(
            repo.listDocuments().first { it.id == id }.pageFiles[0].readBytes())
        assertEquals(before.width, after.height)
        assertEquals(before.height, after.width)
    }

    @Test
    fun `page viewer shows pager, indicator and rotate filter delete`() {
        val id = seedDoc("Paged doc", pages = 3)
        val intent = PageViewerActivity.intent(app, id, 1)
        ActivityScenario.launch<PageViewerActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val pager = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.pager)
                assertEquals(3, pager.adapter!!.itemCount)
                assertNotNull(activity.findViewById<android.view.View>(R.id.btnClose))
                assertNotNull(activity.findViewById<android.view.View>(R.id.btnRotate))
                assertNotNull(activity.findViewById<android.view.View>(R.id.btnFilter))
                assertNotNull(activity.findViewById<android.view.View>(R.id.btnDelete))
                val indicator = activity.findViewById<android.widget.TextView>(R.id.pageIndicator)
                assertEquals("2 / 3", indicator.text.toString())
            }
        }
    }

    @Test
    fun `page viewer delete is deferred until undo expires`() {
        val id = seedDoc("Paged delete", pages = 3)
        val intent = PageViewerActivity.intent(app, id, 0)
        ActivityScenario.launch<PageViewerActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val pager = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.pager)
                forceLayout(pager) // snap view must exist for currentPosition()
                activity.findViewById<android.view.View>(R.id.btnDelete).performClick()
                // Not deleted yet: the file survives while Undo is offered…
                assertEquals(3, repo.listDocuments().first { it.id == id }.pageFiles.size)
                // Simulate the snackbar timing out.
                activity.commitPendingDelete()
            }
            val gone = pollUntil {
                repo.listDocuments().first { it.id == id }.pageFiles.size == 2
            }
            assertTrue("page file should be deleted after the Undo window", gone)
        }
    }

    @Test
    fun `dark mode is the app default`() {
        assertEquals(
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES,
            androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode()
        )
    }

    @Test
    fun `status bar color matches the toolbar surface`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val expected = androidx.core.content.ContextCompat.getColor(app, R.color.sf_surface)
                val typed = android.util.TypedValue()
                assertTrue(
                    "theme should define android:statusBarColor",
                    activity.theme.resolveAttribute(android.R.attr.statusBarColor, typed, true)
                )
                assertEquals(expected, typed.data)
            }
        }
    }

    @Test
    fun `scan button starts the Google document scanner flow without crashing`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val fab = activity.findViewById<android.view.View>(R.id.fabScan)
                assertNotNull("FAB should be on screen", fab)
                fab.performClick()
            }
        }
    }

    @Test
    fun `review screen shows title, filter chips, and save buttons`() {
        val staging = java.io.File(app.cacheDir, "pending_review_${System.nanoTime()}")
            .apply { mkdirs() }
        java.io.File(staging, "page_000.jpg").writeBytes(jpegBytes())
        java.io.File(staging, "page_001.jpg").writeBytes(jpegBytes())

        val intent = ScanReviewActivity.intent(app, staging.path, null, "Document 7")
        ActivityScenario.launch<ScanReviewActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val title = activity.findViewById<android.widget.EditText>(R.id.titleInput)
                assertEquals("Document 7", title.text.toString())

                val original = activity.findViewById<android.widget.CompoundButton>(R.id.chipOriginal)
                assertTrue(original.isChecked)

                // Selecting Enhance moves the check and keeps save buttons ready.
                activity.findViewById<android.widget.CompoundButton>(R.id.chipEnhance).isChecked = true
                assertFalse(original.isChecked)
                assertTrue(activity.findViewById<android.view.View>(R.id.btnSavePdf).isEnabled)
                assertTrue(activity.findViewById<android.view.View>(R.id.btnSaveShare).isEnabled)

                // The B/W filter chip exists and takes over the selection.
                val bw = activity.findViewById<android.widget.CompoundButton>(R.id.chipBlackWhite)
                bw.isChecked = true
                assertFalse(original.isChecked)
                assertFalse(activity.findViewById<android.widget.CompoundButton>(R.id.chipEnhance).isChecked)
            }
        }
        staging.deleteRecursively()
    }

    // ------------------------------------------------------------------
    // Commit (b): drag reorder + long-press menu + deferred Undo delete
    // ------------------------------------------------------------------

    private fun coloredJpeg(color: Int): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(
            64, 64, android.graphics.Bitmap.Config.ARGB_8888
        )
        bmp.eraseColor(color)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun dominantColor(file: java.io.File): String {
        val bmp = android.graphics.BitmapFactory.decodeFile(file.path) ?: return "unreadable"
        val c = bmp.getPixel(bmp.width / 2, bmp.height / 2)
        bmp.recycle()
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return when {
            r > 150 && g < 120 && b < 120 -> "red"
            g > 150 && r < 120 && b < 120 -> "green"
            b > 150 && r < 120 && g < 120 -> "blue"
            r > 150 && g > 150 && b < 120 -> "yellow"
            else -> "other:$r,$g,$b"
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

    @Test
    fun `grid moveItem reorders pages`() {
        val files = (0 until 4).map { i ->
            java.io.File(app.cacheDir, "grid_$i.jpg").apply { writeBytes(jpegBytes()) }
        }
        val grid = com.scanwhirl.app.ui.pagegrid.PageGridView(app)
        grid.submit(files)
        assertEquals(4, grid.pageCount())
        grid.moveItem(0, 3)
        assertEquals(files[0], grid.pageAt(3))
        assertEquals(files[1], grid.pageAt(0))
        grid.moveItem(3, 1)
        assertEquals(files[0], grid.pageAt(1))
    }

    @Test
    fun `viewer long-press menu offers only save, rotate and delete`() {
        val id = seedDoc("Menu doc", pages = 2)
        val intent = DocumentViewerActivity.intent(app, id)
        ActivityScenario.launch<com.scanwhirl.app.ui.DocumentViewerActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                // The menu is opened by a stationary long-press (device gesture);
                // its content is what matters here.
                activity.showPageActions(0)

                val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
                val texts = (0 until dialog.listView.count).map {
                    dialog.listView.adapter.getItem(it).toString()
                }
                assertEquals(
                    listOf(
                        app.getString(R.string.save_this_page),
                        app.getString(R.string.action_rotate),
                        app.getString(R.string.action_delete_page)
                    ),
                    texts
                )
                dialog.dismiss()
            }
        }
    }

    @Test
    fun `page delete is deferred until undo expires`() {
        val id = seedDoc("Deleteme", pages = 2)
        val intent = DocumentViewerActivity.intent(app, id)
        ActivityScenario.launch<com.scanwhirl.app.ui.DocumentViewerActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                activity.showPageActions(0)

                val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
                dialog.listView.performItemClick(null, 2, 2) // "Delete page"

                // Not deleted yet: the file survives while Undo is offered…
                assertEquals(2, repo.listDocuments().first { it.id == id }.pageFiles.size)
                // …and the page is hidden from the grid immediately.
                val grid = activity.findViewById<com.scanwhirl.app.ui.pagegrid.PageGridView>(R.id.pageList)
                assertEquals(1, grid.pageCount())

                // Simulate the snackbar timing out.
                activity.commitPendingDelete()
            }
            val gone = pollUntil {
                repo.listDocuments().first { it.id == id }.pageFiles.size == 1
            }
            assertTrue("page file should be deleted after the Undo window", gone)
        }
    }

    @Test
    fun `undo keeps the page on disk`() {
        val id = seedDoc("Undome", pages = 2)
        val intent = DocumentViewerActivity.intent(app, id)
        ActivityScenario.launch<com.scanwhirl.app.ui.DocumentViewerActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                // The menu is opened by a stationary long-press (device gesture).
                activity.showPageActions(0)
                val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
                dialog.listView.performItemClick(null, 2, 2) // "Delete page"
            }
            shadowOf(android.os.Looper.getMainLooper()).idle()

            scenario.onActivity { activity ->
                val action = activity.findViewById<android.view.View>(
                    com.google.android.material.R.id.snackbar_action
                )
                assertNotNull("Undo action should be on screen", action)
                action.performClick()
                // If Undo failed to clear the pending delete, this would wipe the file.
                activity.commitPendingDelete()
            }

            val stillThere = pollUntil(3000) {
                repo.listDocuments().first { it.id == id }.pageFiles.size == 2
            }
            assertTrue("page must survive an Undo", stillThere)
        }
    }

    @Test
    fun `drag reorder persists the new page order`() {
        val doc = runBlocking {
            repo.createDocument(
                "Colors",
                listOf(
                    coloredJpeg(android.graphics.Color.RED),
                    coloredJpeg(android.graphics.Color.GREEN),
                    coloredJpeg(android.graphics.Color.BLUE),
                    coloredJpeg(android.graphics.Color.YELLOW)
                )
            )
        }
        val intent = DocumentViewerActivity.intent(app, doc.id)
        ActivityScenario.launch<com.scanwhirl.app.ui.DocumentViewerActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                // Drag page 1 (red) to the last slot.
                activity.reorderPages(0, 3)
            }
            val persisted = pollUntil {
                val pages = repo.listDocuments().first { it.id == doc.id }.pageFiles
                pages.size == 4 &&
                    dominantColor(pages[0]) == "green" &&
                    dominantColor(pages[3]) == "red"
            }
            val pages = repo.listDocuments().first { it.id == doc.id }.pageFiles
            assertTrue(
                "order not persisted: ${pages.joinToString { dominantColor(it) }}",
                persisted
            )
        }
    }
}
