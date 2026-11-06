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
import org.junit.Assert.assertNull
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
                    activity.findViewById<android.widget.TextView>(R.id.appNameText).text.toString())
                forceLayout(list)
                assertNotNull(list.findViewHolderForAdapterPosition(0))
            }
        }
        assertTrue(repo.listDocuments().any { it.id == id })
    }

    @Test
    fun `main list view opens the pdf viewer and edit opens the editor`() {
        val id = seedDoc("Open me")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.scanList)
                forceLayout(list)
                val row = list.findViewHolderForAdapterPosition(0)!!.itemView

                // Explicit view icon -> real PDF viewer.
                row.findViewById<android.view.View>(R.id.btnView).performClick()
                val viewIntent = shadowOf(activity).nextStartedActivity
                assertEquals(PdfViewerActivity::class.java.name, viewIntent?.component?.className)
                assertEquals(id, viewIntent.getStringExtra("doc_id"))

                // Explicit edit icon -> page-grid editor.
                row.findViewById<android.view.View>(R.id.btnEdit).performClick()
                val editIntent = shadowOf(activity).nextStartedActivity
                assertEquals(
                    DocumentViewerActivity::class.java.name,
                    editIntent?.component?.className
                )
            }
        }
    }

    @Test
    fun `tapping a saved scan opens the pdf viewer, not the editor`() {
        seedDoc("Tap me")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.scanList)
                forceLayout(list)
                list.findViewHolderForAdapterPosition(0)!!.itemView.performClick()
                val started = shadowOf(activity).nextStartedActivity
                assertEquals(PdfViewerActivity::class.java.name, started?.component?.className)
            }
        }
    }

    @Test
    fun `tools are a two by two grid and settings lives in the toolbar`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull(activity.findViewById<android.view.View>(R.id.toolResize))
                assertNotNull(activity.findViewById<android.view.View>(R.id.toolCompress))
                assertNotNull(activity.findViewById<android.view.View>(R.id.toolEdit))
                assertNotNull(activity.findViewById<android.view.View>(R.id.toolMerge))

                val menu = activity.findViewById<
                    com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).menu
                assertNotNull(menu.findItem(R.id.action_settings))
            }
        }
    }

    @Test
    fun `edit tool opens the pdf picker`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<android.view.View>(R.id.toolEdit).performClick()
                val started = shadowOf(activity).nextStartedActivity
                assertEquals(android.content.Intent.ACTION_OPEN_DOCUMENT, started?.action)
            }
        }
    }

    @Test
    fun `merge builder offers add and merge`() {
        ActivityScenario.launch(MergePdfActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull(activity.findViewById<android.view.View>(R.id.btnAddPdf))
                val merge = activity.findViewById<android.view.View>(R.id.btnMerge)
                assertNotNull(merge)
                assertFalse("merge needs at least 2 PDFs", merge.isEnabled)
            }
        }
    }

    @Test
    fun `search filters the scan list by name`() {
        seedDoc("Alpha report")
        seedDoc("Beta receipt")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.scanList)
                forceLayout(list)
                assertEquals(2, list.adapter!!.itemCount)

                activity.findViewById<android.widget.EditText>(R.id.searchInput).setText("Alpha")
                val filtered = pollUntil { list.adapter!!.itemCount == 1 }
                assertTrue("search should leave only Alpha", filtered)
            }
        }
    }

    @Test
    fun `sort by name puts the alphabetical first`() {
        seedDoc("Zebra")
        seedDoc("Apple")
        com.scanwhirl.app.util.Prefs.setSortMode(app, com.scanwhirl.app.util.Prefs.SORT_NAME)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.scanList)
                forceLayout(list)
                val first = list.findViewHolderForAdapterPosition(0)!!.itemView
                    .findViewById<android.widget.TextView>(R.id.scanName).text.toString()
                assertEquals("Apple", first)
            }
        }
        com.scanwhirl.app.util.Prefs.setSortMode(app, com.scanwhirl.app.util.Prefs.SORT_DATE)
    }

    @Test
    fun `merge tool asks scans or device PDFs`() {
        seedDoc("Merge one")
        seedDoc("Merge two")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<android.view.View>(R.id.toolMerge).performClick()
                val chooser = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
                assertTrue("merge chooser should be showing", chooser.isShowing)
                // Item 0 = merge saved scans -> the scan picker dialog.
                chooser.listView.performItemClick(null, 0, 0)
                val picker = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
                assertTrue("scan picker should be showing", picker.isShowing)
                picker.dismiss()
            }
        }
    }

    @Test
    fun `scan rows show page badge and compact meta`() {
        seedDoc("Badge doc", pages = 3)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val list = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.scanList)
                forceLayout(list)
                val row = list.findViewHolderForAdapterPosition(0)!!.itemView
                assertEquals(
                    "3",
                    row.findViewById<android.widget.TextView>(R.id.pageBadge).text.toString()
                )
                val meta = row.findViewById<android.widget.TextView>(R.id.scanMeta).text.toString()
                assertTrue(
                    "meta should start with time: $meta",
                    Regex("^\\d{1,2}:\\d{2} · \\d{1,2}/\\d{1,2}/\\d{2} · ").containsMatchIn(meta)
                )
            }
        }
    }

    @Test
    fun `export result screen shows real sizes and actions`() {
        val img = java.io.File(app.cacheDir, "result_${System.nanoTime()}.jpg")
        img.writeBytes(jpegBytes())
        val intent = ExportResultActivity.intent(
            app,
            outputPath = img.path,
            previewPath = img.path,
            mime = "image/jpeg",
            displayName = img.name,
            beforeBytes = 4096,
            afterBytes = 1024,
            width = 310,
            height = 444,
            title = "Test result",
            warning = null
        )
        ActivityScenario.launch<ExportResultActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull(activity.findViewById<android.view.View>(R.id.btnSaveResult))
                assertNotNull(activity.findViewById<android.view.View>(R.id.btnShareResult))
                val stats = activity.findViewById<android.widget.TextView>(R.id.resultStats)
                    .text.toString()
                assertTrue("before size missing: $stats", stats.contains("4 KB"))
                assertTrue("after size missing: $stats", stats.contains("1 KB"))
                assertTrue("dimensions missing: $stats", stats.contains("310 × 444 px"))
                assertNotNull(activity.findViewById<android.view.View>(R.id.resultPreview))
            }
        }
    }

    @Test
    fun `settings exposes theme, font, language and viewer`() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull(activity.findViewById<android.view.View>(R.id.rowTheme))
                assertNotNull(activity.findViewById<android.view.View>(R.id.rowFont))
                assertNotNull(activity.findViewById<android.view.View>(R.id.rowGreeting))
                assertNotNull(activity.findViewById<android.view.View>(R.id.switchGreeting))
                assertNotNull(activity.findViewById<android.view.View>(R.id.rowWallpaper))
                assertNotNull(activity.findViewById<android.view.View>(R.id.switchWallpaper))
                assertNotNull(activity.findViewById<android.view.View>(R.id.rowLanguage))
                assertNotNull(activity.findViewById<android.view.View>(R.id.rowViewer))
                assertNotNull(activity.findViewById<android.view.View>(R.id.rowAbout))
                assertNotNull(activity.findViewById<android.view.View>(R.id.rowPrivacy))
                val subTheme = activity.findViewById<android.widget.TextView>(R.id.subTheme)
                assertTrue(subTheme.text.isNotEmpty())
            }
        }
    }

    @Test
    fun `home shows a time greeting and settings can turn it off`() {
        com.scanwhirl.app.util.Prefs.setShowGreeting(app, true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val toolbar = activity.findViewById<
                    com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
                assertNotNull("greeting subtitle should be shown", toolbar.subtitle)
            }
        }
        com.scanwhirl.app.util.Prefs.setShowGreeting(app, false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val toolbar = activity.findViewById<
                    com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
                assertTrue("greeting should be off", toolbar.subtitle == null)
            }
        }
        com.scanwhirl.app.util.Prefs.setShowGreeting(app, true)
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

                // normal mode: the custom title row carries the name now
                assertEquals(app.getString(R.string.app_name),
                    activity.findViewById<android.widget.TextView>(R.id.appNameText).text.toString())
                assertTrue(activity.findViewById<android.view.View>(R.id.titleRow).isVisible)
                assertTrue(fab.isVisible)

                // long press -> selection mode with 1 selected
                card.performLongClick()
                assertEquals(app.resources.getQuantityString(R.plurals.selected_count, 1, 1), toolbar.title)
                assertFalse(fab.isVisible)

                // back -> exits selection mode, title row comes back
                activity.onBackPressed()
                assertEquals(app.getString(R.string.app_name),
                    activity.findViewById<android.widget.TextView>(R.id.appNameText).text.toString())
                assertTrue(activity.findViewById<android.view.View>(R.id.titleRow).isVisible)
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
                assertNotNull(activity.findViewById<android.view.View>(R.id.btnRotateRight))
                assertNotNull(activity.findViewById<android.view.View>(R.id.btnCrop))
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
    fun `scan button opens the in-house scanner flow without crashing`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val fab = activity.findViewById<android.view.View>(R.id.fabScan)
                assertNotNull("FAB should be on screen", fab)
                fab.performClick()
            }
        }
    }

    @Test
    fun `review screen shows title, page grid, and save as pdf`() {
        val staging = java.io.File(app.cacheDir, "pending_review_${System.nanoTime()}")
            .apply { mkdirs() }
        java.io.File(staging, "page_000.jpg").writeBytes(jpegBytes())
        java.io.File(staging, "page_001.jpg").writeBytes(jpegBytes())

        val intent = ScanReviewActivity.intent(app, staging.path, null, "Document 7")
        ActivityScenario.launch<ScanReviewActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val title = activity.findViewById<android.widget.EditText>(R.id.titleInput)
                assertEquals("Document 7", title.text.toString())

                // The shared page grid replaces the old chips row + adapter.
                val grid =
                    activity.findViewById<com.scanwhirl.app.ui.pagegrid.PageGridView>(R.id.pageGrid)
                assertEquals(2, grid.pageCount())
                // Per-page filters default to Original (no look applied).
                assertEquals(
                    com.scanwhirl.app.util.ScanFilter.ORIGINAL,
                    grid.filterFor(java.io.File(staging, "page_000.jpg"))
                )

                // One primary action; the toolbar overflow holds the page actions.
                assertTrue(activity.findViewById<android.view.View>(R.id.btnSavePdf).isEnabled)
                val menu =
                    activity.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).menu
                assertNotNull(menu.findItem(R.id.action_rotate_left))
                assertNotNull(menu.findItem(R.id.action_rotate_right))
                assertNotNull(menu.findItem(R.id.action_delete_page))
                assertNotNull(menu.findItem(R.id.action_add_pages))

                // A checkbox tick shows the selection bar; back clears it, not the scan.
                activity.toggleSelect(0)
                assertEquals(
                    android.view.View.VISIBLE,
                    activity.findViewById<android.view.View>(R.id.selectBar).visibility
                )
                activity.onBackPressedDispatcher.onBackPressed()
                assertEquals(
                    android.view.View.GONE,
                    activity.findViewById<android.view.View>(R.id.selectBar).visibility
                )
                assertTrue("back with a selection must not discard the scan", staging.exists())
            }
        }
        staging.deleteRecursively()
    }

    @Test
    fun `review tap opens the full-screen page view`() {
        val staging = java.io.File(app.cacheDir, "pending_review_${System.nanoTime()}")
            .apply { mkdirs() }
        java.io.File(staging, "page_000.jpg").writeBytes(jpegBytes())

        val intent = ScanReviewActivity.intent(app, staging.path, null, "Doc")
        ActivityScenario.launch<ScanReviewActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val grid =
                    activity.findViewById<com.scanwhirl.app.ui.pagegrid.PageGridView>(R.id.pageGrid)
                forceLayout(grid)
                grid.findViewHolderForAdapterPosition(0)!!.itemView.performClick()

                val started = shadowOf(activity).nextStartedActivity
                assertEquals(
                    PageViewerActivity::class.java.name,
                    started?.component?.className
                )
                assertEquals(staging.path, started.getStringExtra("staging_dir"))
            }
        }
        staging.deleteRecursively()
    }

    @Test
    fun `review delete is deferred until undo expires`() {
        val staging = java.io.File(app.cacheDir, "pending_review_${System.nanoTime()}")
            .apply { mkdirs() }
        java.io.File(staging, "page_000.jpg").writeBytes(jpegBytes())
        java.io.File(staging, "page_001.jpg").writeBytes(jpegBytes())
        val victimPath = java.io.File(staging, "page_000.jpg").path

        val intent = ScanReviewActivity.intent(app, staging.path, null, "Doc")
        ActivityScenario.launch<ScanReviewActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val grid =
                    activity.findViewById<com.scanwhirl.app.ui.pagegrid.PageGridView>(R.id.pageGrid)
                activity.toggleSelect(0) // tick page 1
                activity.deleteSelected()

                // Hidden immediately (renamed to .del) but still on disk…
                assertEquals(1, grid.pageCount())
                assertTrue(java.io.File(staging, "page_000.jpg.del").exists())
                assertFalse(java.io.File(staging, "page_000.jpg").exists())

                // …until the Undo window is over.
                activity.commitPendingDeletes(listOf(victimPath))
            }
            val gone = pollUntil { !java.io.File(staging, "page_000.jpg.del").exists() }
            assertTrue("page file should be deleted after the Undo window", gone)
        }
        staging.deleteRecursively()
    }

    @Test
    fun `review undo keeps the page on disk`() {
        val staging = java.io.File(app.cacheDir, "pending_review_${System.nanoTime()}")
            .apply { mkdirs() }
        java.io.File(staging, "page_000.jpg").writeBytes(jpegBytes())
        java.io.File(staging, "page_001.jpg").writeBytes(jpegBytes())
        val victimPath = java.io.File(staging, "page_000.jpg").path

        val intent = ScanReviewActivity.intent(app, staging.path, null, "Doc")
        ActivityScenario.launch<ScanReviewActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val grid =
                    activity.findViewById<com.scanwhirl.app.ui.pagegrid.PageGridView>(R.id.pageGrid)
                activity.toggleSelect(0)
                activity.deleteSelected()
            }
            shadowOf(android.os.Looper.getMainLooper()).idle()

            scenario.onActivity { activity ->
                val action = activity.findViewById<android.view.View>(
                    com.google.android.material.R.id.snackbar_action
                )
                assertNotNull("Undo action should be on screen", action)
                action.performClick()
                // If Undo failed to restore, this would wipe the file.
                activity.commitPendingDeletes(listOf(victimPath))
            }

            val restored = pollUntil(3000) {
                java.io.File(staging, "page_000.jpg").exists() &&
                    !java.io.File(staging, "page_000.jpg.del").exists()
            }
            assertTrue("page must survive an Undo", restored)
        }
        staging.deleteRecursively()
    }

    @Test
    fun `add pages appends with continuing numbering`() {
        val staging = java.io.File(app.cacheDir, "pending_review_${System.nanoTime()}")
            .apply { mkdirs() }
        java.io.File(staging, "page_000.jpg").writeBytes(jpegBytes())
        java.io.File(staging, "page_001.jpg").writeBytes(jpegBytes())

        val intent = ScanReviewActivity.intent(app, staging.path, null, "Doc")
        ActivityScenario.launch<ScanReviewActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                activity.appendStagedPages(listOf(contentUri(jpegBytes())))
            }
            val added = pollUntil { java.io.File(staging, "page_002.jpg").exists() }
            assertTrue("new page should be staged with the next number", added)

            scenario.onActivity { activity ->
                val grid =
                    activity.findViewById<com.scanwhirl.app.ui.pagegrid.PageGridView>(R.id.pageGrid)
                assertEquals(3, grid.pageCount())
            }
        }
        staging.deleteRecursively()
    }

    @Test
    fun `review save opens the saved document`() {
        val staging = java.io.File(app.cacheDir, "pending_review_${System.nanoTime()}")
            .apply { mkdirs() }
        java.io.File(staging, "page_000.jpg").writeBytes(jpegBytes())

        val intent = ScanReviewActivity.intent(app, staging.path, null, "Doc")
        ActivityScenario.launch<ScanReviewActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<android.view.View>(R.id.btnSavePdf).performClick()
            }

            var opened: android.content.Intent? = null
            val done = pollUntil {
                scenario.onActivity { activity ->
                    opened = shadowOf(activity).peekNextStartedActivity()
                }
                opened != null
            }
            assertTrue("save should open the document viewer", done)
            assertEquals(
                DocumentViewerActivity::class.java.name,
                opened?.component?.className
            )
            assertTrue("staging must be cleaned up after save", !staging.exists())
        }
    }

    @Test
    fun `staging page viewer shows pager, indicator and controls`() {
        val staging = java.io.File(app.cacheDir, "pending_review_${System.nanoTime()}")
            .apply { mkdirs() }
        java.io.File(staging, "page_000.jpg").writeBytes(jpegBytes())
        java.io.File(staging, "page_001.jpg").writeBytes(jpegBytes())

        val intent = PageViewerActivity.stagingIntent(app, staging.path, 1)
        ActivityScenario.launch<PageViewerActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val pager = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.pager)
                assertEquals(2, pager.adapter!!.itemCount)
                val indicator = activity.findViewById<android.widget.TextView>(R.id.pageIndicator)
                assertEquals("2 / 2", indicator.text.toString())
                assertNotNull(activity.findViewById<android.view.View>(R.id.btnClose))
                assertNotNull(activity.findViewById<android.view.View>(R.id.btnRotateRight))
                assertNotNull(activity.findViewById<android.view.View>(R.id.btnCrop))
                assertNotNull(activity.findViewById<android.view.View>(R.id.btnFilter))
                assertNotNull(activity.findViewById<android.view.View>(R.id.btnDelete))
            }
        }
        staging.deleteRecursively()
    }

    @Test
    fun `staging filter choice persists for the review screen`() {
        val staging = java.io.File(app.cacheDir, "pending_review_${System.nanoTime()}")
            .apply { mkdirs() }
        java.io.File(staging, "page_000.jpg").writeBytes(jpegBytes())
        java.io.File(staging, "page_001.jpg").writeBytes(jpegBytes())

        val intent = PageViewerActivity.stagingIntent(app, staging.path, 0)
        ActivityScenario.launch<PageViewerActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<android.view.View>(R.id.btnFilter).performClick()
                val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
                dialog.listView.performItemClick(null, 3, 3) // B/W
            }
            val persisted = pollUntil {
                java.io.File(staging, "filters.txt")
                    .let { it.exists() && it.readText().contains("page_000.jpg=bw") }
            }
            assertTrue("filter choice should be written for the review screen", persisted)
            // The untouched sibling keeps the default (no look applied).
            val text = java.io.File(staging, "filters.txt").readText()
            assertTrue(text.contains("page_001.jpg=original"))
        }
        staging.deleteRecursively()
    }

    // ------------------------------------------------------------------
    // Commit (e): manual corner-drag crop
    // ------------------------------------------------------------------

    @Test
    fun `crop view drags a corner and reports the rect`() {
        val view = CropView(app)
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1000, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(1000, android.view.View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, 1000, 1000)
        val bmp = android.graphics.Bitmap.createBitmap(
            640, 640, android.graphics.Bitmap.Config.ARGB_8888
        )
        view.setImage(bmp)

        // Untouched crop covers the whole image.
        val full = view.getCropRect()
        assertEquals(0f, full.left, 0.01f)
        assertEquals(0f, full.top, 0.01f)
        assertEquals(640f, full.right, 0.01f)
        assertEquals(640f, full.bottom, 0.01f)

        // Drag the top-left corner to view (200, 150) -> image (128, 96).
        view.onTouchEvent(
            android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, 0f, 0f, 0)
        )
        view.onTouchEvent(
            android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_MOVE, 200f, 150f, 0)
        )
        view.onTouchEvent(
            android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_UP, 200f, 150f, 0)
        )

        val crop = view.getCropRect()
        assertEquals(128f, crop.left, 2f)
        assertEquals(96f, crop.top, 2f)
        assertEquals(640f, crop.right, 0.01f) // other corners stay put
        assertEquals(640f, crop.bottom, 0.01f)
        bmp.recycle()
    }

    @Test
    fun `page viewer crop shrinks the saved page`() {
        val id = seedDoc("Cropdoc")
        val before = com.scanwhirl.app.util.ImageTools.imageSize(
            repo.listDocuments().first { it.id == id }.pageFiles[0].readBytes())
        assertEquals(310 to 444, before)

        val intent = PageViewerActivity.intent(app, id, 0)
        ActivityScenario.launch<PageViewerActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<android.view.View>(R.id.btnCrop).performClick()
            }

            var visible = false
            assertTrue("crop overlay should open", pollUntil {
                scenario.onActivity { a ->
                    visible = a.findViewById<android.view.View>(R.id.cropOverlay).visibility ==
                        android.view.View.VISIBLE
                }
                visible
            })

            var ready = false
            assertTrue("crop view should have its image", pollUntil {
                scenario.onActivity { a ->
                    ready = a.findViewById<CropView>(R.id.cropView).getCropRect().width() > 0f
                }
                ready
            })

            scenario.onActivity { a ->
                val cropView = a.findViewById<CropView>(R.id.cropView)
                cropView.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY)
                )
                cropView.layout(0, 0, 1080, 1920)

                // Drag the top-left corner in (image 310x444 fitCentered in 1080x1920).
                val scale = kotlin.math.min(1080f / 310f, 1920f / 444f)
                val drawnTop = (1920f - 444f * scale) / 2f
                cropView.onTouchEvent(
                    android.view.MotionEvent.obtain(
                        0, 0, android.view.MotionEvent.ACTION_DOWN, 0f, drawnTop, 0)
                )
                cropView.onTouchEvent(
                    android.view.MotionEvent.obtain(
                        0, 0, android.view.MotionEvent.ACTION_MOVE, 216f, 450f, 0)
                )
                cropView.onTouchEvent(
                    android.view.MotionEvent.obtain(
                        0, 0, android.view.MotionEvent.ACTION_UP, 216f, 450f, 0)
                )
                a.findViewById<android.view.View>(R.id.btnApplyCrop).performClick()
            }

            val cropped = pollUntil {
                // The crop rewrites the file in place; a poll may catch the
                // zero-length window, when listDocuments() omits the page.
                val page = repo.listDocuments().firstOrNull { it.id == id }
                    ?.pageFiles?.firstOrNull() ?: return@pollUntil false
                val size = com.scanwhirl.app.util.ImageTools.imageSize(page.readBytes())
                size != null && size.first in 240..256 && size.second in 360..376
            }
            assertTrue("page should be cropped to ~(248, 368)", cropped)
        }
    }

    @Test
    fun `staging crop writes the staged page`() {
        val staging = java.io.File(app.cacheDir, "pending_review_${System.nanoTime()}")
            .apply { mkdirs() }
        java.io.File(staging, "page_000.jpg").writeBytes(jpegBytes())

        val intent = PageViewerActivity.stagingIntent(app, staging.path, 0)
        ActivityScenario.launch<PageViewerActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<android.view.View>(R.id.btnCrop).performClick()
            }

            var ready = false
            assertTrue("crop view should have its image", pollUntil {
                scenario.onActivity { a ->
                    val overlayOpen = a.findViewById<android.view.View>(R.id.cropOverlay).visibility ==
                        android.view.View.VISIBLE
                    ready = overlayOpen &&
                        a.findViewById<CropView>(R.id.cropView).getCropRect().width() > 0f
                }
                ready
            })

            scenario.onActivity { a ->
                val cropView = a.findViewById<CropView>(R.id.cropView)
                cropView.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY)
                )
                cropView.layout(0, 0, 1080, 1920)
                val scale = kotlin.math.min(1080f / 310f, 1920f / 444f)
                val drawnTop = (1920f - 444f * scale) / 2f
                cropView.onTouchEvent(
                    android.view.MotionEvent.obtain(
                        0, 0, android.view.MotionEvent.ACTION_DOWN, 0f, drawnTop, 0)
                )
                cropView.onTouchEvent(
                    android.view.MotionEvent.obtain(
                        0, 0, android.view.MotionEvent.ACTION_MOVE, 216f, 450f, 0)
                )
                cropView.onTouchEvent(
                    android.view.MotionEvent.obtain(
                        0, 0, android.view.MotionEvent.ACTION_UP, 216f, 450f, 0)
                )
                a.findViewById<android.view.View>(R.id.btnApplyCrop).performClick()
            }

            val cropped = pollUntil {
                val size = com.scanwhirl.app.util.ImageTools.imageSize(
                    java.io.File(staging, "page_000.jpg").readBytes())
                size != null && size.first in 240..256 && size.second in 360..376
            }
            assertTrue("staged page should be cropped in place", cropped)
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
    fun `grid can move page three in front of page one`() {
        val files = (0 until 3).map { i ->
            java.io.File(app.cacheDir, "front_$i.jpg").apply { writeBytes(jpegBytes()) }
        }
        val grid = com.scanwhirl.app.ui.pagegrid.PageGridView(app)
        grid.submit(files)
        // A cross-row move (row 2 col 1 -> row 1 col 1) is a plain index move.
        grid.moveItem(2, 0)
        assertEquals(files[2], grid.pageAt(0))
        assertEquals(files[0], grid.pageAt(1))
        assertEquals(files[1], grid.pageAt(2))
    }

    @Test
    fun `grid drop target works horizontally and across rows`() {
        val id = seedDoc("Targets", pages = 4)
        val intent = DocumentViewerActivity.intent(app, id)
        ActivityScenario.launch<com.scanwhirl.app.ui.DocumentViewerActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val grid = activity.findViewById<
                    com.scanwhirl.app.ui.pagegrid.PageGridView>(R.id.pageList)
                forceLayout(grid)

                fun centerOf(pos: Int): Pair<Float, Float> {
                    val view = grid.findViewHolderForAdapterPosition(pos)!!.itemView
                    return ((view.left + view.right) / 2f) to ((view.top + view.bottom) / 2f)
                }

                val c0 = centerOf(0)
                val c1 = centerOf(1)
                val c2 = centerOf(2)

                // Drag page 3 (index 2) over page 1 (index 0): a cross-row drop.
                assertEquals(0, grid.targetForPoint(c0.first, c0.second, 2))
                // Drag page 3 over index 1: a purely horizontal target.
                assertEquals(1, grid.targetForPoint(c1.first, c1.second, 2))
                // Drag page 1 down over page 3 (index 2).
                assertEquals(2, grid.targetForPoint(c2.first, c2.second, 0))
            }
        }
    }

    @Test
    fun `page cells keep a fixed 3 by 4 height`() {
        val cell = com.scanwhirl.app.ui.pagegrid.AspectRatioLayout(app)
        cell.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(300, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(2000, android.view.View.MeasureSpec.AT_MOST)
        )
        assertEquals(300, cell.measuredWidth)
        assertEquals(400, cell.measuredHeight)
    }

    @Test
    fun `viewer toolbar offers rotate, delete and save page actions`() {
        val id = seedDoc("Menu doc", pages = 2)
        val intent = DocumentViewerActivity.intent(app, id)
        ActivityScenario.launch<com.scanwhirl.app.ui.DocumentViewerActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val menu = activity.findViewById<
                    com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).menu
                assertNotNull(menu.findItem(R.id.action_rotate_left))
                assertNotNull(menu.findItem(R.id.action_rotate_right))
                assertNotNull(menu.findItem(R.id.action_delete_page))
                assertNotNull(menu.findItem(R.id.action_save_page))

                // Ticking a page only selects it: no popup appears.
                val grid = activity.findViewById<
                    com.scanwhirl.app.ui.pagegrid.PageGridView>(R.id.pageList)
                forceLayout(grid)
                grid.findViewHolderForAdapterPosition(0)!!.itemView
                    .findViewById<android.widget.CheckBox>(R.id.checkSelect).performClick()
                assertEquals(
                    app.resources.getQuantityString(R.plurals.selected_count, 1, 1),
                    activity.supportActionBar?.subtitle
                )
                assertNull("selecting a page must not open a dialog", ShadowDialog.getLatestDialog())
            }
        }
    }

    @Test
    fun `page delete is deferred until undo expires`() {
        val id = seedDoc("Deleteme", pages = 2)
        val intent = DocumentViewerActivity.intent(app, id)
        ActivityScenario.launch<com.scanwhirl.app.ui.DocumentViewerActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                activity.toggleSelected(0)
                activity.deleteSelectedPages()

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
                activity.toggleSelected(0)
                activity.deleteSelectedPages()
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

    // ------------------------------------------------------------------
    // Coverage gaps: parsing, honesty caps, generators, resilience
    // ------------------------------------------------------------------

    @Test
    fun `size parsing handles kb mb bare and invalid input`() {
        val parse = com.scanwhirl.app.util.ImageTools::parseSizeInput
        assertEquals(1024L, parse("1 KB"))
        assertEquals(1_048_576L, parse("1 MB"))
        assertEquals(1_572_864L, parse("1.5mb"))
        assertEquals(512_000L, parse("500"))  // bare number = 500 KB
        assertEquals(2048L, parse("2k"))
        assertNull(parse("nonsense"))
        assertNull(parse(""))
        assertNull(parse(null))
    }

    @Test
    fun `resize caps output under the requested max bytes`() {
        // A striped source so it has room to shrink.
        val bmp = android.graphics.Bitmap.createBitmap(
            600, 600, android.graphics.Bitmap.Config.ARGB_8888
        )
        val canvas = android.graphics.Canvas(bmp)
        for (y in 0 until 600 step 8) {
            val paint = android.graphics.Paint().apply {
                color = if ((y / 8) % 2 == 0) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            }
            canvas.drawRect(0f, y.toFloat(), 600f, (y + 4).toFloat(), paint)
        }
        val src = com.scanwhirl.app.util.ImageTools.encodeJpeg(bmp, 95)
        bmp.recycle()

        val result = com.scanwhirl.app.util.ImageTools.resize(
            src,
            com.scanwhirl.app.util.ImageTools.ResizeOptions(maxBytes = 8_000, dpi = 0)
        )
        assertTrue(
            "output ${result.bytes.size} B should fit under 8000 B",
            result.bytes.size <= 8_000
        )
    }

    @Test
    fun `download saver unique names never collide`() {
        val taken = mutableSetOf("Report.pdf", "Report (2).pdf")
        val name = com.scanwhirl.app.util.DownloadSaver.uniqueName("Report.pdf") { it in taken }
        assertEquals("Report (3).pdf", name)
        taken.add(name)
        assertEquals(
            "Report (4).pdf",
            com.scanwhirl.app.util.DownloadSaver.uniqueName("Report.pdf") { it in taken }
        )
        // A free name passes through unchanged.
        assertEquals(
            "Other.pdf",
            com.scanwhirl.app.util.DownloadSaver.uniqueName("Other.pdf") { it in taken }
        )
    }

    @Test
    fun `readJpegInfo skips large app segments before the frame header`() {
        // A fake APP1 (Exif-style) segment ahead of the SOF marker.
        val payload = ByteArray(4096) { 'A'.code.toByte() }
        val app1 = byteArrayOf(
            0xFF.toByte(), 0xE1.toByte(),
            (((payload.size + 2) shr 8) and 0xFF).toByte(), ((payload.size + 2) and 0xFF).toByte()
        ) + payload
        val base = jpegBytes()
        val withExif = base.copyOfRange(0, 2) + app1 + base.copyOfRange(2, base.size)

        val info = com.scanwhirl.app.util.PdfGenerator.readJpegInfo(withExif)
        assertEquals(310, info.width)
        assertEquals(444, info.height)
    }

    @Test
    fun `pdf generator stays valid from one page to many`() {
        val dir = java.io.File(app.cacheDir, "pdfgen_${System.nanoTime()}").apply { mkdirs() }
        for (pageCount in intArrayOf(1, 7)) {
            val files = (0 until pageCount).map { i ->
                val f = java.io.File(dir, "p$i.jpg")
                f.writeBytes(jpegBytes())
                f
            }
            val out = java.io.File(dir, "out$pageCount.pdf")
            com.scanwhirl.app.util.PdfGenerator.imagesToPdf(files, out)
            val text = out.readText(Charsets.ISO_8859_1)
            assertTrue("page count should be $pageCount", "/Count $pageCount" in text)
            assertTrue("xref present", "startxref" in text)
            val tail = out.readBytes()
            assertTrue(
                "eof marker",
                tail.size >= 5 &&
                    tail[tail.size - 5] == 0x25.toByte() && // %
                    tail[tail.size - 4] == 0x45.toByte() && // E
                    tail[tail.size - 3] == 0x4F.toByte() && // O
                    tail[tail.size - 2] == 0x46.toByte() && // F
                    tail[tail.size - 1] == 0x0A.toByte()
            )
            files.forEach { it.delete() }
            out.delete()
        }
        dir.deleteRecursively()
    }

    @Test
    fun `compress to target never leaves a page larger than its share`() {
        val doc = seedDoc("Target fit", pages = 3)
        val pages = repo.listDocuments().first { it.id == doc }.pageFiles
        val original = pages[0].readBytes()
        val bmp = android.graphics.BitmapFactory.decodeByteArray(original, 0, original.size)!!
        val fitted = com.scanwhirl.app.util.ImageTools.compressToFit(bmp, 6_000).bytes
        bmp.recycle()
        assertTrue("fitted ${fitted.size} B must fit its share", fitted.size <= 6_000)
    }

    @Test
    fun `blank wallpaper uri is ignored without crashing`() {
        com.scanwhirl.app.util.Prefs.setShowWallpaper(app, true)
        com.scanwhirl.app.util.Prefs.setWallpaperUri(app, "")
        assertNull(com.scanwhirl.app.util.Wallpaper.drawable(app))
        com.scanwhirl.app.util.Prefs.setShowWallpaper(app, false)
    }

    // ------------------------------------------------------------------
    // test18: Pro features + regression guards
    // ------------------------------------------------------------------

    @Test
    fun `pro gate mirrors the build flag`() {
        assertEquals(
            com.scanwhirl.app.BuildConfig.TEST_BUILD,
            com.scanwhirl.app.util.ProGate.isPro(app)
        )
    }

    @Test
    fun `home toolbar shows the crown badge matching the build`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val badge = activity.findViewById<android.widget.TextView>(R.id.proBadge)
                assertEquals(
                    if (com.scanwhirl.app.BuildConfig.TEST_BUILD) "PRO" else "FREE",
                    badge.text.toString()
                )
                // Badge sits in the custom title row next to the app name.
                assertTrue(activity.findViewById<android.view.View>(R.id.titleRow).isVisible)
                assertNull(activity.findViewById<
                    com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).title)
            }
        }
    }

    @Test
    fun `greeting stays plain text without emoji`() {
        for (hour in 0..23) {
            val text = com.scanwhirl.app.util.Greeting.forHour(hour)
            assertTrue("greeting must be emoji-free: $text", text.none { it.code > 0x2500 })
        }
    }

    @Test
    fun `encryptor adds the standard password dictionary`() = runBlocking {
        val doc = seedDoc("Protect me", pages = 1)
        val pdf = repo.listDocuments().first { it.id == doc }.pdfFile
        val before = pdf.readText(Charsets.ISO_8859_1)
        assertFalse("/Encrypt" in before)

        val dir = java.io.File(app.cacheDir, "prot_${System.nanoTime()}").apply { mkdirs() }
        val dst = java.io.File(dir, "locked.pdf")
        com.scanwhirl.app.util.PdfEncryptor.encrypt(pdf, dst, "student123")

        val after = dst.readText(Charsets.ISO_8859_1)
        assertTrue("/Encrypt" in after)
        assertTrue("/Filter /Standard" in after)
        assertTrue("/V 2" in after && "/R 3" in after && "/Length 128" in after)
        assertTrue("file id present", "/ID [" in after)
        // RC4 preserves lengths, so the honest page count must survive.
        assertEquals("/Count 1", Regex("/Count (\\d+)").find(after)!!.value)
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `protect pdf stores the password in the document`() = runBlocking {
        val doc = seedDoc("Vault", pages = 1)
        val protected = repo.protectPdf(doc, "hunter2")
        // Pages are untouched by the wrapper change.
        assertEquals(1, protected.pageCount)
        assertTrue(protected.pdfFile.exists())
        assertTrue(
            "/Encrypt" in repo.listDocuments().first { it.id == doc }
                .pdfFile.readText(Charsets.ISO_8859_1)
        )
        Unit
    }

    @Test
    fun `crop view honours a locked aspect ratio`() {
        val view = CropView(app)
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1000, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(1000, android.view.View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, 1000, 1000)
        val bmp = android.graphics.Bitmap.createBitmap(600, 600, android.graphics.Bitmap.Config.ARGB_8888)
        view.setImage(bmp)
        view.aspectRatio = 1f // square: passport-style lock

        view.onTouchEvent(android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, 1000f, 1000f, 0))
        view.onTouchEvent(android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_MOVE, 1000f, 250f, 0))
        view.onTouchEvent(android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_UP, 1000f, 250f, 0))

        val crop = view.getCropRect()
        assertEquals(crop.width(), crop.height(), 6f)
        bmp.recycle()
    }

    @Test
    fun `long image stitches pages into one tall jpeg`() {
        val dir = java.io.File(app.cacheDir, "long_${System.nanoTime()}").apply { mkdirs() }
        val pages = (0 until 2).map { i ->
            val f = java.io.File(dir, "p$i.jpg")
            f.writeBytes(jpegBytes())
            f
        }
        val out = java.io.File(dir, "long.jpg")
        com.scanwhirl.app.util.LongImage.stitch(pages, out)
        assertTrue("stitch produced a file", out.exists() && out.length() > 0)
        val dims = com.scanwhirl.app.util.ImageTools.imageSize(out.readBytes())!!
        assertEquals(310, dims.first)
        assertEquals("taller than one page", 888, dims.second)
        dir.deleteRecursively()
    }

    @Test
    fun `zip exporter packs one entry per scan`() = runBlocking {
        val a = seedDoc("Zip A", pages = 1)
        val b = seedDoc("Zip B", pages = 2)
        val docs = repo.listDocuments().filter { it.id == a || it.id == b }
        val dir = java.io.File(app.cacheDir, "zip_${System.nanoTime()}").apply { mkdirs() }
        val out = java.io.File(dir, "bundle.zip")
        com.scanwhirl.app.util.ZipExporter.export(docs, out)
        assertTrue(out.exists() && out.length() > 0)
        val names = java.util.zip.ZipFile(out).use { z ->
            (0 until z.size()).map { z.entries().asSequence().toList().sortedBy { e -> e.name }[it].name }
        }
        assertTrue("contains Zip A.pdf", names.any { it.startsWith("Zip A") })
        assertTrue("contains Zip B.pdf", names.any { it.startsWith("Zip B") })
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun `page op after a full-rect crop keeps the page intact`() = runBlocking {
        val doc = seedDoc("Safe crop")
        // Reproduced crash: open crop, immediately hit Apply (full-page crop rect).
        val before = com.scanwhirl.app.util.ImageTools.imageSize(
            repo.listDocuments().first { it.id == doc }.pageFiles[0].readBytes())
        val page = repo.listDocuments().first { it.id == doc }.pageFiles[0]
        com.scanwhirl.app.util.ImageTools.cropTo(
            page,
            android.graphics.RectF(0f, 0f, before!!.first.toFloat(), before.second.toFloat())
        )
        val after = com.scanwhirl.app.util.ImageTools.imageSize(
            repo.listDocuments().first { it.id == doc }.pageFiles[0].readBytes())
        assertEquals("page dims must survive a whole-page crop", before, after)
    }
}
