package com.scanwhirl.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.TransitionDrawable
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.OcrIndexer
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.home.DocThumbBinder
import com.scanwhirl.app.ui.home.HomeDocInfoReader
import com.scanwhirl.app.ui.home.TextState
import com.scanwhirl.app.util.CategoryRules
import com.scanwhirl.app.util.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.io.File

/**
 * Home slice 2 — the Index: real first-page thumbnails with privacy-safe
 * placeholders, honest text status, library sizes 0/1/2/40/100, the recycled-view
 * stale-image guard, and every pre-existing Home entry point still reachable.
 *
 * Fixtures are synthetic document directories in Robolectric's isolated app
 * storage (meta.txt + one coloured JPEG page + a stand-in scan.pdf), so 100
 * documents cost milliseconds and never touch a real library.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class HomeSlice2Test {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        com.scanwhirl.app.util.ProGate.setPro(app, false)
        com.scanwhirl.app.util.ProGate.setTestOverride(app, null)
        // The once-per-install backfill would mark every fixture "pending".
        Prefs.setIndexBackfillDone(app, true)
        Prefs.setShowGreeting(app, false)
        Prefs.setSortMode(app, Prefs.SORT_DATE)
    }

    // ---- fixtures ----------------------------------------------------------

    private fun jpeg(color: Int, w: Int = 120, h: Int = 170): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(color)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private var seq = 0

    /** A committed document directory, written the way the repository reads it. */
    private fun fixture(
        name: String,
        pages: Int = 1,
        color: Int = Color.WHITE,
        category: String = CategoryRules.FORMS,
        pdf: Boolean = true,
        pageBytes: ByteArray? = null,
        createdAt: Long = 1_700_000_000_000L + (seq++) * 60_000L
    ): File {
        val dir = File(repo.docsDir, "fx_${System.nanoTime()}_${seq}")
        dir.mkdirs()
        val bytes = pageBytes ?: jpeg(color)
        for (i in 1..pages) File(dir, "page_%03d.jpg".format(i)).writeBytes(bytes)
        if (pdf) File(dir, "scan.pdf").writeBytes(ByteArray(2048) { 1 })
        File(dir, "meta.txt").writeText("$name\n$createdAt\n$category\n")
        return dir
    }

    private fun doc(dir: File): ScanDocument = repo.getDocument(dir.name)!!

    private fun pollUntil(timeoutMs: Long = 8000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(20)
        }
        shadowOf(android.os.Looper.getMainLooper()).idle()
        return condition()
    }

    private fun layout(rv: RecyclerView, w: Int = 1080, h: Int = 1920) {
        rv.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )
        rv.layout(0, 0, w, h)
    }

    private fun list(activity: MainActivity) = activity.findViewById<RecyclerView>(R.id.scanList)

    private fun rowView(activity: MainActivity, pos: Int): View {
        val rv = list(activity)
        layout(rv)
        return rv.findViewHolderForAdapterPosition(pos)!!.itemView
    }

    /** The bitmap a thumbnail ImageView currently shows (the end layer of a cross-fade). */
    private fun shownBitmap(image: ImageView): Bitmap? = when (val d = image.drawable) {
        is BitmapDrawable -> d.bitmap
        is TransitionDrawable -> (d.getDrawable(d.numberOfLayers - 1) as? BitmapDrawable)?.bitmap
        else -> null
    }

    private fun centreColor(bmp: Bitmap): Int = bmp.getPixel(bmp.width / 2, bmp.height / 2)

    private fun isBlueish(c: Int) = Color.blue(c) > 180 && Color.red(c) < 80
    private fun isReddish(c: Int) = Color.red(c) > 180 && Color.blue(c) < 80

    // ---- library sizes ------------------------------------------------------

    @Test
    fun `zero documents show the honest empty state and both dock actions`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(pollUntil { activity.findViewById<View>(R.id.emptyState).isVisible })
                assertTrue(activity.findViewById<View>(R.id.emptyNote).isVisible)
                assertFalse("no list header on an empty library",
                    activity.findViewById<View>(R.id.listHeader).isVisible)
                for (id in intArrayOf(R.id.btnScan, R.id.btnImport, R.id.btnTools)) {
                    assertTrue("dock action $id visible", activity.findViewById<View>(id).isShown)
                }
                // One primary: the legacy duplicate empty buttons stay hidden.
                assertFalse(activity.findViewById<View>(R.id.btnEmptyScan).isVisible)
            }
        }
    }

    @Test
    fun `one and two documents render rows with real first pages`() {
        fixture("Electricity bill", color = Color.BLUE, category = CategoryRules.RECEIPTS)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(pollUntil { list(activity).adapter!!.itemCount == 1 })
                assertEquals("1 document",
                    activity.findViewById<TextView>(R.id.headerCount).text.toString())
            }
        }
        fixture("Scholarship application", pages = 5, color = Color.RED, category = CategoryRules.FORMS)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(pollUntil { list(activity).adapter!!.itemCount == 2 })
                // Newest first (date sort): the 5-page form, then the bill.
                val top = rowView(activity, 0)
                assertEquals("Scholarship application",
                    top.findViewById<TextView>(R.id.scanName).text.toString())
                assertTrue("multi-page shows the second sheet", top.findViewById<View>(R.id.thumbBack).isVisible)
                assertEquals("5", top.findViewById<TextView>(R.id.pageBadge).text.toString())
                val bill = rowView(activity, 1)
                assertFalse("single page: no stacked sheet", bill.findViewById<View>(R.id.thumbBack).isVisible)
                assertFalse("single page: no page badge", bill.findViewById<View>(R.id.pageBadge).isVisible)

                // Each row shows ITS OWN first page (not one generic glyph for all).
                val topImage = top.findViewById<ImageView>(R.id.thumbImage)
                val billImage = bill.findViewById<ImageView>(R.id.thumbImage)
                assertTrue("form thumb decodes", pollUntil { shownBitmap(topImage) != null })
                assertTrue("bill thumb decodes", pollUntil { shownBitmap(billImage) != null })
                assertTrue(isReddish(centreColor(shownBitmap(topImage)!!)))
                assertTrue(isBlueish(centreColor(shownBitmap(billImage)!!)))
                assertFalse(top.findViewById<View>(R.id.thumbGlyph).isVisible)
                // Bounded: never a full-resolution page in the list.
                val bmp = shownBitmap(topImage)!!
                assertTrue("thumb ≤ ${DocThumbBinder.MAX_THUMB_PX}px",
                    maxOf(bmp.width, bmp.height) <= DocThumbBinder.MAX_THUMB_PX)
            }
        }
    }

    @Test
    fun `forty and one hundred documents scroll, filter and search without losing rows`() {
        repeat(100) { i ->
            fixture(
                "Doc %03d".format(i),
                color = if (i % 2 == 0) Color.RED else Color.BLUE,
                category = if (i % 4 == 0) CategoryRules.STUDY else CategoryRules.RECEIPTS
            )
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val rv = list(activity)
                assertTrue(pollUntil { rv.adapter!!.itemCount == 100 })
                layout(rv, 1080, 1920)
                // Fling through the whole library: rows recycle, nothing crashes and
                // every attached row ends up showing its own colour.
                for (step in 0 until 30) {
                    rv.scrollBy(0, 900)
                    layout(rv, 1080, 1920)
                    shadowOf(android.os.Looper.getMainLooper()).idle()
                }
                assertFalse("reached the end", rv.canScrollVertically(1))
                val attached = (0 until rv.childCount).map { rv.getChildViewHolder(rv.getChildAt(it)) }
                assertTrue(attached.isNotEmpty())
                assertTrue("attached rows decode", pollUntil {
                    attached.all { shownBitmap(it.itemView.findViewById(R.id.thumbImage)) != null }
                })
                for (vh in attached) {
                    val name = vh.itemView.findViewById<TextView>(R.id.scanName).text.toString()
                    val i = name.removePrefix("Doc ").toInt()
                    val c = centreColor(shownBitmap(vh.itemView.findViewById(R.id.thumbImage))!!)
                    assertTrue("row $name shows its own page", if (i % 2 == 0) isReddish(c) else isBlueish(c))
                }

                // Filter to Study (25) and search inside it.
                val group = activity.findViewById<com.google.android.material.chip.ChipGroup>(R.id.chipGroup)
                (0 until group.childCount).map { group.getChildAt(it) }
                    .first { it.tag == CategoryRules.STUDY }.performClick()
                assertTrue(pollUntil { rv.adapter!!.itemCount == 25 })
                activity.searchDebounceMs = 0
                activity.findViewById<android.widget.EditText>(R.id.searchInput).setText("Doc 048")
                assertTrue("search inside the filter", pollUntil { rv.adapter!!.itemCount == 1 })
                activity.findViewById<View>(R.id.btnClearSearch).performClick()
                assertTrue(pollUntil { rv.adapter!!.itemCount == 25 })
            }
        }
    }

    @Test
    fun `forty documents fit the default phone with header and dock clear`() {
        repeat(40) { fixture("Receipt %02d".format(it), category = CategoryRules.RECEIPTS) }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(pollUntil { list(activity).adapter!!.itemCount == 40 })
                assertEquals("40 documents",
                    activity.findViewById<TextView>(R.id.headerCount).text.toString())
                // The dock never overlaps the list: no negative margin, no protruding disc.
                val dock = activity.findViewById<View>(R.id.dockRoot)
                assertEquals(0, (dock.layoutParams as android.view.ViewGroup.MarginLayoutParams).topMargin)
            }
        }
    }

    // ---- thumbnails: privacy and placeholders --------------------------------

    @Test
    fun `locked and private documents never decode a page image`() {
        val locked = fixture("Bank statement", color = Color.RED)
        File(locked, "locked.meta").writeText("x")
        val vaulted = fixture("Private letter", color = Color.RED)
        File(vaulted, "vault.flag").writeText("")
        assertEquals(DocThumbBinder.Kind.LOCKED, DocThumbBinder.kindFor(doc(locked)))
        assertEquals(DocThumbBinder.Kind.PRIVATE, DocThumbBinder.kindFor(doc(vaulted)))

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // Normal Home lists only the locked one: the repository filter hides Private.
                assertTrue(pollUntil { list(activity).adapter!!.itemCount == 1 })
                val row = rowView(activity, 0)
                assertEquals("Bank statement", row.findViewById<TextView>(R.id.scanName).text.toString())
                assertTrue(row.findViewById<View>(R.id.lockBadge).isVisible)
                assertTrue(row.findViewById<View>(R.id.thumbGlyph).isVisible)
                val image = row.findViewById<ImageView>(R.id.thumbImage)
                assertNull("no request is pending for a locked page", image.tag)
                Thread.sleep(150)
                shadowOf(android.os.Looper.getMainLooper()).idle()
                assertNull("no locked page pixels on Home", shownBitmap(image))

                // Searching a Private document's name finds nothing in normal Home.
                activity.searchDebounceMs = 0
                activity.findViewById<android.widget.EditText>(R.id.searchInput).setText("Private letter")
                val gone = pollUntil { list(activity).adapter!!.itemCount == 0 }
                assertTrue("private name finds nothing (count ${list(activity).adapter!!.itemCount}: " +
                    (list(activity).adapter as ScanListAdapter).currentList.map { it.doc.name } + ")", gone)
                assertTrue("empty state speaks", activity.findViewById<View>(R.id.emptyState).isVisible)
            }
        }
    }

    @Test
    fun `pdf-only, zero-page and unreadable documents get purposeful placeholders`() {
        val pdfOnly = fixture("Imported contract", pages = 0)
        val broken = fixture("Damaged scan", pageBytes = "not a jpeg at all".toByteArray())
        assertEquals(DocThumbBinder.Kind.PDF_ONLY, DocThumbBinder.kindFor(doc(pdfOnly)))
        assertEquals(DocThumbBinder.Kind.PAGE, DocThumbBinder.kindFor(doc(broken)))

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(pollUntil { list(activity).adapter!!.itemCount == 2 })
                val rows = (0..1).map { rowView(activity, it) }
                val pdfRow = rows.first {
                    it.findViewById<TextView>(R.id.scanName).text.toString() == "Imported contract"
                }
                val brokenRow = rows.first { it !== pdfRow }
                assertTrue("PDF-only shows the PDF glyph", pdfRow.findViewById<View>(R.id.thumbGlyph).isVisible)
                assertTrue("0 pages reads honestly",
                    pdfRow.findViewById<TextView>(R.id.scanMeta).text.toString().startsWith("0 pages"))
                val brokenImage = brokenRow.findViewById<ImageView>(R.id.thumbImage)
                // The failed decode is reported back and the paper placeholder stays.
                assertTrue(pollUntil { brokenImage.tag == null })
                assertNull(shownBitmap(brokenImage))
                assertFalse(brokenRow.findViewById<View>(R.id.thumbGlyph).isVisible)
            }
        }
    }

    @Test
    fun `a recycled view never shows the previous document's page`() {
        val red = doc(fixture("Red", color = Color.RED, pages = 1))
        val blue = doc(fixture("Blue", color = Color.BLUE, pages = 1))
        val image = ImageView(app)
        val glyph = ImageView(app)
        // Bind A, immediately re-bind the same view to B (a fast fling).
        DocThumbBinder.bind(image, glyph, red)
        DocThumbBinder.bind(image, glyph, blue)
        assertTrue(pollUntil { shownBitmap(image) != null })
        Thread.sleep(200) // let the stale red job finish too
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue("the view shows B, never A", isBlueish(centreColor(shownBitmap(image)!!)))

        // Recycling drops the request: a late result never lands on the recycled view.
        val green = doc(fixture("Green", color = Color.GREEN, pages = 1))
        DocThumbBinder.bind(image, glyph, green)
        DocThumbBinder.unbind(image, glyph)
        Thread.sleep(200)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNull("unbound view keeps only the placeholder", shownBitmap(image))

        // An edited first page (new mtime/length) is a new request, not the stale thumb.
        val redFile = red.pageFiles.first()
        redFile.writeBytes(jpeg(Color.BLUE, 130, 180))
        redFile.setLastModified(System.currentTimeMillis() + 5_000)
        DocThumbBinder.bind(image, glyph, repo.getDocument(red.id)!!)
        assertTrue(pollUntil { shownBitmap(image)?.let { isBlueish(centreColor(it)) } == true })
    }

    // ---- honest text status ---------------------------------------------------

    @Test
    fun `text status comes from the pending marker and stored page text only`() {
        val waiting = fixture("Waiting", pages = 2)
        File(waiting, "ocr_pending").writeText("")
        val ready = fixture("Ready", pages = 2)
        File(ready, "ocr.txt").writeText("#scanwhirl-ocr-v2\npage_001.jpg\taGVsbG8=\npage_002.jpg\td29ybGQ=\n")
        val partial = fixture("Partial", pages = 2)
        File(partial, "ocr.txt").writeText("#scanwhirl-ocr-v2\npage_001.jpg\taGVsbG8=\n")
        val flagOnly = fixture("Flag only", pages = 1)
        File(flagOnly, "text_layer.flag").writeText("")
        val malformed = fixture("Malformed", pages = 1)
        File(malformed, "ocr.txt").writeText("#scanwhirl-ocr-v2\nbroken line without tab\n")

        val dir = repo.docsDir
        assertEquals(TextState.WAITING, HomeDocInfoReader.read(dir, doc(waiting)).text)
        assertEquals(TextState.READY, HomeDocInfoReader.read(dir, doc(ready)).text)
        HomeDocInfoReader.read(dir, doc(partial)).let {
            assertEquals(TextState.PARTIAL, it.text)
            assertEquals(1, it.textPages)
        }
        assertEquals("text_layer.flag alone claims nothing",
            TextState.NONE, HomeDocInfoReader.read(dir, doc(flagOnly)).text)
        assertEquals("unreadable ocr.txt is not search-ready",
            TextState.NONE, HomeDocInfoReader.read(dir, doc(malformed)).text)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(pollUntil { list(activity).adapter!!.itemCount == 5 })
                val byName = (0 until 5).associate { pos ->
                    val row = rowView(activity, pos)
                    row.findViewById<TextView>(R.id.scanName).text.toString() to row
                }
                fun status(name: String): String? = byName.getValue(name)
                    .findViewById<TextView>(R.id.scanTextStatus).takeIf { it.isVisible }?.text?.toString()
                assertEquals("Waiting to read text", status("Waiting"))
                // M08: "Search ready" is the normal state, so it is spoken, not
                // printed on the card (ScanListAdapter.bindStatus).
                assertNull("ready rows print no chip", status("Ready"))
                assertEquals("Text on 1 of 2 pages", status("Partial"))
                assertNull(status("Flag only"))
                assertNull(status("Malformed"))
                // TalkBack hears the same status in the row description.
                assertTrue(byName.getValue("Ready").contentDescription.toString().endsWith("Search ready"))

                // Live indexer progress replaces "Waiting" on that row only.
                val adapter = list(activity).adapter as ScanListAdapter
                adapter.indexState = OcrIndexer.IndexState(waiting.name, 1, 2, 0)
                shadowOf(android.os.Looper.getMainLooper()).idle()
                val live = rowView(activity, adapter.currentList.indexOfFirst { it.id == waiting.name })
                assertEquals("Reading text 1/2",
                    live.findViewById<TextView>(R.id.scanTextStatus).text.toString())
            }
        }
    }

    @Test
    fun `the indexer finishing a document re-reads its status`() {
        val waiting = fixture("Lecture notes", pages = 1)
        File(waiting, "ocr_pending").writeText("")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(pollUntil { list(activity).adapter!!.itemCount == 1 })
                val adapter = list(activity).adapter as ScanListAdapter
                assertEquals(TextState.WAITING, adapter.currentList[0].info.text)
                // The indexer stores text and clears the marker, then goes idle.
                File(waiting, "ocr.txt").writeText("#scanwhirl-ocr-v2\npage_001.jpg\taGVsbG8=\n")
                File(waiting, "ocr_pending").delete()
                adapter.indexState = OcrIndexer.IndexState(waiting.name, 0, 1, 0)
                val indexer = ScanWhirlApp.from(activity).indexer
                kotlinx.coroutines.runBlocking { indexer.indexNow(waiting.name) }
                assertTrue("status refreshes to Search ready",
                    pollUntil { adapter.currentList.firstOrNull()?.info?.text == TextState.READY })
            }
        }
    }

    // ---- entry points that must survive the redesign ---------------------------

    @Test
    fun `scan import tools settings pro private select more and long-press all still work`() {
        fixture("Hall ticket", category = CategoryRules.UNSORTED)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(pollUntil { list(activity).adapter!!.itemCount == 1 })
                assertEquals(app.getString(R.string.scan),
                    activity.findViewById<View>(R.id.btnScan).contentDescription)
                assertEquals("Scan", activity.findViewById<TextView>(R.id.scanLabel).text.toString())
                assertTrue("Import names photos or a PDF for TalkBack",
                    activity.findViewById<View>(R.id.btnImport).contentDescription.toString().contains("Import"))

                // Import → the existing sheet (Photos / PDF as pages), not redesigned.
                activity.findViewById<View>(R.id.btnImport).performClick()
                val importSheet = ShadowDialog.getLatestDialog()
                assertNotNull(importSheet)
                importSheet!!.dismiss()

                // Tools → still holds Quick sort and the Private (vault) path.
                activity.findViewById<View>(R.id.btnTools).performClick()
                val tools = ShadowDialog.getLatestDialog()!!
                // M08: the Vault tile only exists for a legacy Vault owner; this
                // device has no PIN, so Quick sort is the private-path-free tile.
                assertNull("no Vault tile without a legacy PIN", tools.findViewById<View>(R.id.tile_vault))
                assertNotNull(tools.findViewById<View>(R.id.tile_quick_sort))
                tools.dismiss()

                // Pro entry survives (demoted tag, not a crown).
                val pro = activity.findViewById<TextView>(R.id.proBadge)
                assertTrue(pro.isShown)
                assertNull("no crown drawable", pro.compoundDrawablesRelative[0])
                pro.performClick()
                val panel = ShadowDialog.getLatestDialog()!!
                assertNotNull(panel.findViewById<View>(R.id.proDossierStatus))
                panel.dismiss()

                // M08: the nudge row is retired; Quick sort stays reachable in Tools.
                val nudge = activity.findViewById<android.view.ViewGroup>(R.id.nudge)
                assertFalse("the retired nudge stays hidden", nudge.isVisible)

                // More and long-press both open quick actions.
                val row = rowView(activity, 0)
                row.findViewById<View>(R.id.btnMore).performClick()
                assertNotNull(ShadowDialog.getLatestDialog()!!.findViewById<View>(R.id.qaRename))
                ShadowDialog.getLatestDialog()!!.dismiss()
                row.performLongClick()
                assertNotNull(ShadowDialog.getLatestDialog()!!.findViewById<View>(R.id.qaShare))
                ShadowDialog.getLatestDialog()!!.dismiss()

                // Select: checkbox replaces More, the picked row is activated (accent wash).
                activity.findViewById<View>(R.id.btnSelect).performClick()
                shadowOf(android.os.Looper.getMainLooper()).idle()
                rowView(activity, 0).performClick()
                shadowOf(android.os.Looper.getMainLooper()).idle()
                val picked = rowView(activity, 0)
                assertTrue(picked.findViewById<View>(R.id.checkSelect).isVisible)
                assertFalse(picked.findViewById<View>(R.id.btnMore).isVisible)
                assertTrue(picked.isActivated)
                assertTrue(activity.findViewById<View>(R.id.selectionBar).isVisible)
                assertFalse(activity.findViewById<View>(R.id.dockRoot).isVisible)
                activity.onBackPressedDispatcher.onBackPressed()
                assertTrue(activity.findViewById<View>(R.id.dockRoot).isVisible)
            }
        }
    }

    @Test
    fun `settings stays in the app bar and no starfield remains`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val toolbar = activity.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
                assertNotNull(toolbar.menu.findItem(R.id.action_settings))
                shadowOf(android.os.Looper.getMainLooper()).idle()
                val bg = activity.findViewById<View>(R.id.homeRoot).background
                assertTrue("plain ink desk, not SpaceBackgroundDrawable: $bg",
                    bg is android.graphics.drawable.ColorDrawable)
                assertFalse("greeting/status line is opt-in",
                    activity.findViewById<View>(R.id.statusLine).isVisible)
            }
        }
    }

    // ---- copy, contrast and large text ---------------------------------------

    @Test
    fun `home copy never promises unbuilt features`() {
        val res = app.resources
        val homeCopy = listOf(
            R.string.search_hint, R.string.empty_home_title, R.string.empty_home_body,
            R.string.empty_home_note, R.string.text_status_ready, R.string.text_status_waiting
        ).map { res.getString(it).lowercase() } +
            res.getString(R.string.text_status_partial, 1, 2).lowercase() +
            res.getString(R.string.text_status_reading, 1, 2).lowercase()
        for (text in homeCopy) {
            for (banned in listOf("exact page", "favorite", "favourite", "backup", "nothing leaves", "searchable in")) {
                assertFalse("\"$text\" promises \"$banned\"", text.contains(banned))
            }
        }
        assertEquals("Search names and text", res.getString(R.string.search_hint))
    }

    private fun luminance(c: Int): Double {
        fun ch(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch(Color.red(c)) + 0.7152 * ch(Color.green(c)) + 0.0722 * ch(Color.blue(c))
    }

    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    @Test
    fun `home text tokens meet WCAG AA on the ink desk and surfaces`() {
        fun c(id: Int) = app.getColor(id)
        val backgrounds = listOf(R.color.rv_bg, R.color.rv_surface, R.color.rv_surface_2)
        val texts = listOf(
            R.color.rv_ink, R.color.rv_ink_2, R.color.rv_ink_3, R.color.rv_accent,
            R.color.sw_ok, R.color.rv_warn, R.color.rv_danger
        )
        for (bg in backgrounds) for (fg in texts) {
            val r = contrast(c(fg), c(bg))
            assertTrue("${app.resources.getResourceEntryName(fg)} on " +
                "${app.resources.getResourceEntryName(bg)} = $r", r >= 4.5)
        }
        for (cat in listOf(CategoryRules.STUDY, CategoryRules.RECEIPTS, CategoryRules.IDS,
            CategoryRules.FORMS, CategoryRules.OTHER)) {
            val r = contrast(com.scanwhirl.app.util.CategoryStyle.color(cat), c(R.color.rv_bg))
            assertTrue("$cat label contrast $r", r >= 4.5)
        }
        assertTrue("Scan label on the cyan pill", contrast(c(R.color.sw_on_scan), c(R.color.sw_scan)) >= 4.5)
        assertTrue("Add label on the ink pill", contrast(c(R.color.rv_on_commit), c(R.color.rv_commit)) >= 4.5)
    }
}

/** 200% text on the narrowest phone: rows, status and dock labels are never cut. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-xhdpi")
class HomeSlice2LargeTextTest {

    @Test
    fun `rows status and dock fit at 200 percent on 320dp`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val repo = ScanRepository(app)
        Prefs.setIndexBackfillDone(app, true)
        val dir = File(repo.docsDir, "fx_large").apply { mkdirs() }
        File(dir, "page_001.jpg").writeBytes(ByteArray(0).let {
            val bmp = Bitmap.createBitmap(60, 80, Bitmap.Config.ARGB_8888)
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.toByteArray()
        })
        File(dir, "page_002.jpg").writeBytes(File(dir, "page_001.jpg").readBytes())
        File(dir, "scan.pdf").writeBytes(ByteArray(4096))
        File(dir, "meta.txt").writeText("Scholarship application for the second semester\n1700000000000\n${CategoryRules.UNSORTED}\n")
        File(dir, "ocr_pending").writeText("")

        RuntimeEnvironment.setFontScale(2.0f)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val rv = activity.findViewById<RecyclerView>(R.id.scanList)
                    val deadline = System.currentTimeMillis() + 8000
                    while (rv.adapter!!.itemCount == 0 && System.currentTimeMillis() < deadline) {
                        shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(20)
                    }
                    val root = activity.findViewById<View>(R.id.homeRoot)
                    val dm = activity.resources.displayMetrics
                    root.measure(
                        View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY)
                    )
                    root.layout(0, 0, dm.widthPixels, dm.heightPixels)
                    val row = rv.findViewHolderForAdapterPosition(0)!!.itemView
                    val problems = mutableListOf<String>()
                    fun check(tv: TextView, what: String) {
                        val l = tv.layout ?: run { problems += "$what: no layout"; return }
                        val avail = tv.width - tv.totalPaddingLeft - tv.totalPaddingRight
                        for (i in 0 until l.lineCount) {
                            if (l.getEllipsisCount(i) > 0 && what != "scanName") problems += "$what ellipsized"
                            if (l.getLineMax(i) > avail + 0.5f) problems += "$what line $i overflows"
                        }
                        if (l.height + tv.totalPaddingTop + tv.totalPaddingBottom > tv.height + 0.5f) {
                            problems += "$what is cut vertically"
                        }
                    }
                    check(row.findViewById(R.id.scanMeta), "scanMeta")
                    // M08: an Unsorted document prints no category chip, so there
                    // is nothing to lay out; a categorised row still gets checked.
                    val cat = row.findViewById<TextView>(R.id.catChip)
                    assertFalse("Unsorted rows show no category chip", cat.isVisible)
                    if (cat.isVisible) check(cat, "catChip")
                    check(row.findViewById(R.id.scanTextStatus), "scanTextStatus")
                    check(row.findViewById(R.id.scanName), "scanName")
                    check(activity.findViewById(R.id.scanLabel), "scanLabel")
                    for (id in intArrayOf(R.id.btnTools, R.id.btnImport)) {
                        val g = activity.findViewById<android.view.ViewGroup>(id)
                        check(g.getChildAt(1) as TextView, "dock label $id")
                    }
                    val more = row.findViewById<View>(R.id.btnMore)
                    assertTrue("More stays a 48dp target", more.height >= (48 * dm.density - 1))
                    assertTrue(problems.joinToString("\n"), problems.isEmpty())
                }
            }
        } finally {
            RuntimeEnvironment.setFontScale(1.0f)
        }
    }
}
