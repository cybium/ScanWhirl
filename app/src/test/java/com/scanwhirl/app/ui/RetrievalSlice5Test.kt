package com.scanwhirl.app.ui

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.home.HomeDocInfo
import com.scanwhirl.app.ui.home.HomePageMatches
import com.scanwhirl.app.ui.home.HomeRow
import com.scanwhirl.app.util.CategoryRules
import com.scanwhirl.app.util.Prefs
import org.junit.After
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
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.security.MessageDigest

/**
 * Retrieval slice 5 — Home search → the page that truly matches → that page in
 * the ScanWhirl reader → Find over the stored OCR text.
 *
 * Fixtures are synthetic document directories (meta + JPEG pages + a stand-in
 * scan.pdf + ocr.txt through OcrStore). Robolectric cannot run PdfRenderer on
 * this host JDK, so the reader's page source is a fake with a chosen page count;
 * everything else (repository, OCR store, matcher, views, intents) is real.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RetrievalSlice5Test {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    /** Pages the fake renderer reports; null = the opened document's page count. */
    private var fakePdfPages: Int? = null
    private val defaultFactory = PdfViewerActivity.pagesFactory

    private class FakePages(override val pageCount: Int) : PdfViewerActivity.PdfPages {
        override fun render(index: Int, maxDim: Int): Bitmap =
            Bitmap.createBitmap(100, 141, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        override fun close() = Unit
    }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        com.scanwhirl.app.util.ProGate.setPro(app, false)
        com.scanwhirl.app.util.ProGate.setTestOverride(app, null)
        Prefs.setIndexBackfillDone(app, true)
        Prefs.setShowGreeting(app, false)
        Prefs.setSortMode(app, Prefs.SORT_DATE)
        Prefs.setDefaultViewer(app, Prefs.VIEWER_IN_APP)
        // FileProvider caches path roots statically; each Robolectric test has a new data dir.
        androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
            .apply { isAccessible = true }.let { (it.get(null) as MutableMap<*, *>).clear() }
        fakePdfPages = null
        PdfViewerActivity.pagesFactory = { fd ->
            fd.close()
            FakePages(fakePdfPages ?: openedPages)
        }
    }

    @After
    fun tearDown() {
        PdfViewerActivity.pagesFactory = defaultFactory
        RuntimeEnvironment.setFontScale(1.0f)
    }

    // ---- fixtures ----------------------------------------------------------

    private var seq = 0
    private var openedPages = 1

    private fun jpeg(): ByteArray {
        val bmp = Bitmap.createBitmap(60, 85, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.LTGRAY)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        return out.toByteArray()
    }

    /** A committed document: [pages] JPEG pages page_000…, stand-in PDF, optional OCR. */
    private fun fixture(
        name: String,
        pages: Int,
        ocr: Map<Int, String>? = null,
        locked: Boolean = false,
        vaulted: Boolean = false,
        pending: Boolean = false
    ): ScanDocument {
        val dir = File(repo.docsDir, "rx_${System.nanoTime()}_${seq}")
        dir.mkdirs()
        val bytes = jpeg()
        for (i in 0 until pages) File(dir, "page_%03d.jpg".format(i)).writeBytes(bytes)
        File(dir, "scan.pdf").writeBytes(ByteArray(2048) { 1 })
        File(dir, "meta.txt").writeText("$name\n${1_700_000_000_000L + (seq++) * 60_000L}\n${CategoryRules.FORMS}\n")
        if (locked) File(dir, "locked.meta").writeText("x")
        if (vaulted) File(dir, "vault.flag").writeText("1")
        if (ocr != null) repo.ocrStore.putTexts(dir.name, ocr.mapKeys { "page_%03d.jpg".format(it.key) })
        if (pending) File(dir, "ocr_pending").writeText("")
        openedPages = pages
        return repo.getDocument(dir.name)!!
    }

    /** Filler OCR for every page, plus [extra] on chosen pages. */
    private fun text(pages: Int, extra: Map<Int, String> = emptyMap()) =
        (0 until pages).associateWith { "Filler sheet ${it + 1} of the sample.\n${extra[it].orEmpty()}" }

    private fun pollUntil(timeoutMs: Long = 8000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            // Advance the paused main looper's clock too, so debounced work runs.
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(40))
            if (safely(condition)) return true
            Thread.sleep(15)
        }
        shadowOf(android.os.Looper.getMainLooper()).idle()
        return safely(condition)
    }

    /** A row may not exist yet while a search is still running. */
    private fun safely(condition: () -> Boolean) = try { condition() } catch (_: NullPointerException) { false }

    private fun layout(v: View, w: Int = 1080, h: Int = 1920) {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )
        v.layout(0, 0, w, h)
    }

    private fun list(activity: MainActivity) = activity.findViewById<RecyclerView>(R.id.scanList)

    private fun row(activity: MainActivity, pos: Int): View {
        val rv = list(activity)
        layout(rv)
        return rv.findViewHolderForAdapterPosition(pos)!!.itemView
    }

    private fun search(activity: MainActivity, q: String) {
        activity.searchDebounceMs = 0
        activity.findViewById<EditText>(R.id.searchInput).setText(q)
    }

    /** Visible page-link strips of a row. */
    private fun strips(row: View): List<View> {
        val box = row.findViewById<LinearLayout>(R.id.pageHits)
        if (!box.isVisible) return emptyList()
        return (0 until box.childCount).map { box.getChildAt(it) }.filter { it.isVisible }
    }

    private fun label(strip: View) = strip.findViewById<TextView>(R.id.hitLabel).text.toString()

    private fun snapshotLibrary(): Map<String, String> =
        repo.docsDir.walkTopDown().filter { it.isFile }.associate { f ->
            f.relativeTo(repo.docsDir).path to
                MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) } +
                "@" + f.lastModified()
        }

    // ---- Home: page evidence ---------------------------------------------------

    @Test
    fun `unique word on page 2 of 5 shows a Page 2 link with its real excerpt and opens that page`() {
        val doc = fixture("Scholarship application", 5, text(5, mapOf(1 to "Reference ZEPHYRQUILL 77")))
        val before = snapshotLibrary()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(pollUntil { list(activity).adapter!!.itemCount == 1 })
                assertTrue("no page links on blank Home", strips(row(activity, 0)).isEmpty())
                search(activity, "zephyrquill")
                assertTrue(pollUntil { strips(row(activity, 0)).isNotEmpty() })
                val r = row(activity, 0)
                val strip = strips(r).single()
                assertEquals("Page 2 · 1 match", label(strip))
                val excerpt = strip.findViewById<TextView>(R.id.hitExcerpt)
                assertTrue(excerpt.isVisible)
                assertTrue(excerpt.text.toString().contains("ZEPHYRQUILL"))
                assertTrue(strip.contentDescription.startsWith("Open Scholarship application, page 2"))
                assertTrue("tap target ≥ 48dp", strip.minimumHeight >= (48 * app.resources.displayMetrics.density).toInt())
                assertFalse("page excerpt replaces the document snippet", r.findViewById<View>(R.id.scanSnippet).isVisible)
                assertFalse(r.findViewById<View>(R.id.matchScope).isVisible)

                strip.performClick()
                val started = shadowOf(activity).nextStartedActivity
                assertEquals(PdfViewerActivity::class.java.name, started.component?.className)
                assertEquals(doc.id, started.getStringExtra("doc_id"))
                assertEquals(1, started.getIntExtra("start_page", -1))
                assertEquals("page_001.jpg", started.getStringExtra("start_page_key"))
                assertEquals("zephyrquill", started.getStringExtra("find_query"))
            }
        }
        assertEquals("searching and tapping change no file", before, snapshotLibrary())
        assertEquals(1, repo.listDocuments().size)
    }

    @Test
    fun `terms spread across pages say document match and offer no page`() {
        fixture("Rent file", 4, text(4, mapOf(0 to "annual", 2 to "income")))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(pollUntil { list(activity).adapter!!.itemCount == 1 })
                search(activity, "annual income")
                assertTrue(pollUntil { row(activity, 0).findViewById<View>(R.id.matchScope).isVisible })
                val r = row(activity, 0)
                assertTrue(strips(r).isEmpty())
                assertTrue("document snippet still explains the match", r.findViewById<View>(R.id.scanSnippet).isVisible)
            }
        }
    }

    @Test
    fun `name-only match shows neither pages nor a scope line`() {
        fixture("Zephyrquill notes", 3, text(3))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(pollUntil { list(activity).adapter!!.itemCount == 1 })
                search(activity, "zephyrquill")
                assertTrue(pollUntil {
                    list(activity).adapter!!.itemCount == 1 &&
                        (list(activity).adapter as ScanListAdapter).currentList.single().match == null
                })
                val r = row(activity, 0)
                assertTrue(strips(r).isEmpty())
                assertFalse(r.findViewById<View>(R.id.matchScope).isVisible)
            }
        }
    }

    @Test
    fun `many matching pages list three and a See all link into Find`() {
        fixture("Lab book", 8, text(8, (0 until 8).associateWith { if (it % 2 == 1) "titration" else "" }))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(pollUntil { list(activity).adapter!!.itemCount == 1 })
                search(activity, "titration")
                assertTrue(pollUntil { strips(row(activity, 0)).size == 4 })
                val s = strips(row(activity, 0))
                assertEquals(listOf("Page 2 · 1 match", "Page 4 · 1 match", "Page 6 · 1 match"), s.take(3).map(::label))
                assertEquals("See all 4 matching pages in Find", label(s[3]))
                s[3].performClick()
                val started = shadowOf(activity).nextStartedActivity
                assertEquals(1, started.getIntExtra("start_page", -1))
            }
        }
    }

    @Test
    fun `external viewer preference keeps ordinary opens external and page links say they open here`() {
        Prefs.setDefaultViewer(app, Prefs.VIEWER_SYSTEM)
        fixture("Bill", 3, text(3, mapOf(2 to "kilowatt")))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(pollUntil { list(activity).adapter!!.itemCount == 1 })
                search(activity, "kilowatt")
                assertTrue(pollUntil { strips(row(activity, 0)).isNotEmpty() })
                val r = row(activity, 0)
                assertTrue(r.findViewById<View>(R.id.pageHitNote).isVisible)
                r.performClick()
                assertEquals(Intent.ACTION_VIEW, shadowOf(activity).nextStartedActivity.action)
                strips(r).single().performClick()
                assertEquals(PdfViewerActivity::class.java.name,
                    shadowOf(activity).nextStartedActivity.component?.className)
            }
        }
        assertEquals("no silent preference change", Prefs.VIEWER_SYSTEM, Prefs.defaultViewer(app))
    }

    @Test
    fun `locked document is not found by its content and shows no page count or link`() {
        fixture("Locked pay slip", 3, text(3, mapOf(1 to "gratuity")), locked = true)
        fixture("Open memo", 2, text(2, mapOf(0 to "gratuity")))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(pollUntil { list(activity).adapter!!.itemCount == 2 })
                search(activity, "gratuity")
                // Only the ordinary document is a hit; the locked one is not even listed.
                assertTrue(pollUntil {
                    (list(activity).adapter as ScanListAdapter).currentList.map { it.doc.name } == listOf("Open memo")
                })
                val rows = (list(activity).adapter as ScanListAdapter).currentList
                assertTrue(rows.none { it.doc.isLocked })
                assertTrue(pollUntil { strips(row(activity, 0)).isNotEmpty() })
            }
        }
    }

    @Test
    fun `locked document found by name carries no OCR hint, count, link or snippet and still needs the password`() {
        fixture("Locked pay slip", 3, text(3, mapOf(1 to "gratuity")), locked = true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(pollUntil { list(activity).adapter!!.itemCount == 1 })
                // name term + a content-only word: no oracle
                search(activity, "locked gratuity")
                assertTrue(pollUntil { list(activity).adapter!!.itemCount == 0 })
                search(activity, "pay slip")
                assertTrue(pollUntil {
                    list(activity).adapter!!.itemCount == 1 &&
                        (list(activity).adapter as ScanListAdapter).currentList.single().doc.isLocked
                })
                val r = row(activity, 0)
                assertTrue(strips(r).isEmpty())
                assertFalse(r.findViewById<View>(R.id.matchScope).isVisible)
                assertFalse(r.findViewById<View>(R.id.scanSnippet).isVisible)
                assertNull((list(activity).adapter as ScanListAdapter).currentList.single().match)
                r.performClick()
                assertNull("never routed around the unlock", shadowOf(activity).nextStartedActivity)
                val dialog = ShadowDialog.getLatestDialog()
                assertNotNull(dialog)
                assertTrue(dialog!!.isShowing)
            }
        }
    }

    @Test
    fun `a recycled or stale row never shows OCR hints for a document that is locked now`() {
        val open = fixture("Payroll", 3, text(3, mapOf(1 to "gratuity")))
        val lockedNow = open.copy(isLocked = true)
        val match = HomePageMatches.build("gratuity", listOf(open), setOf(open.id)) { id ->
            repo.ocrDoc(id)
        }.getValue(open.id)
        assertTrue(match.isPageEvidence)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val adapter = ScanListAdapter({}, {}, {}, {})
                val holder = adapter.onCreateViewHolder(FrameLayout(activity), 0)
                adapter.snippets = mapOf(open.id to "…secret gratuity…")
                // 1) bound while unlocked: page evidence appears
                adapter.submitList(listOf(HomeRow(open, HomeDocInfo.UNKNOWN, match)))
                assertTrue(pollUntil { adapter.currentList.size == 1 })
                adapter.onBindViewHolder(holder, 0)
                assertTrue(strips(holder.itemView).isNotEmpty())
                // 2) the SAME holder is re-bound to the now-locked document, with the stale
                //    match AND the stale snippet map still in place
                adapter.submitList(listOf(HomeRow(lockedNow, HomeDocInfo.UNKNOWN, match)))
                assertTrue(pollUntil { adapter.currentList.singleOrNull()?.doc?.isLocked == true })
                adapter.onBindViewHolder(holder, 0)
                val v = holder.itemView
                assertTrue(strips(v).isEmpty())
                assertFalse(v.findViewById<View>(R.id.pageHits).isVisible)
                assertFalse(v.findViewById<View>(R.id.matchScope).isVisible)
                assertFalse(v.findViewById<View>(R.id.scanSnippet).isVisible)
                assertEquals("", v.findViewById<TextView>(R.id.scanSnippet).text.toString())
                val box = v.findViewById<LinearLayout>(R.id.pageHits)
                for (i in 0 until box.childCount) {
                    val c = box.getChildAt(i)
                    assertFalse(c.hasOnClickListeners())
                    assertEquals("", c.findViewById<TextView>(R.id.hitLabel).text.toString())
                    assertEquals("", c.findViewById<TextView>(R.id.hitExcerpt).text.toString())
                }
            }
        }
    }

    @Test
    fun `changing the viewer preference while Home shows page links updates the note without a new search`() {
        // Device-found (API 37): after Settings → System PDF app → Back, the same rows were
        // kept by DiffUtil and the "opens in the ScanWhirl reader" note never appeared.
        fixture("Bill", 3, text(3, mapOf(2 to "kilowatt")))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(pollUntil { list(activity).adapter!!.itemCount == 1 })
                search(activity, "kilowatt")
                assertTrue(pollUntil { strips(row(activity, 0)).isNotEmpty() })
                assertFalse(row(activity, 0).findViewById<View>(R.id.pageHitNote).isVisible)
            }
            Prefs.setDefaultViewer(app, Prefs.VIEWER_SYSTEM)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                assertTrue(pollUntil { row(activity, 0).findViewById<View>(R.id.pageHitNote).isVisible })
                assertEquals("kilowatt", activity.findViewById<EditText>(R.id.searchInput).text.toString())
            }
            Prefs.setDefaultViewer(app, Prefs.VIEWER_IN_APP)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                assertTrue(pollUntil { !row(activity, 0).findViewById<View>(R.id.pageHitNote).isVisible })
                assertTrue(strips(row(activity, 0)).isNotEmpty())
            }
        }
    }

    @Test
    fun `a query typed inside Private is not restored after recreation, an ordinary one is`() {
        fixture("Visible", 2, text(2, mapOf(0 to "orchid")))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(pollUntil { list(activity).adapter!!.itemCount == 1 })
                search(activity, "orchid")
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals("ordinary query survives rotation", "orchid",
                    activity.findViewById<EditText>(R.id.searchInput).text.toString())
                activity.findViewById<EditText>(R.id.searchInput).setText("")
                activity.setVaultMode(true)
                search(activity, "secretterm")
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals("private query is not carried into a recreated Home", "",
                    activity.findViewById<EditText>(R.id.searchInput).text.toString())
            }
        }
    }

    @Test
    fun `vaulted documents leak no row, count or page outside Private`() {
        fixture("Visible", 2, text(2, mapOf(0 to "orchid")))
        fixture("Hidden", 2, text(2, mapOf(1 to "orchid zephyrquill")), vaulted = true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(pollUntil { list(activity).adapter!!.itemCount == 1 })
                search(activity, "zephyrquill")
                assertTrue(pollUntil { list(activity).adapter!!.itemCount == 0 })
                search(activity, "orchid")
                assertTrue(pollUntil { list(activity).adapter!!.itemCount == 1 && strips(row(activity, 0)).isNotEmpty() })
                val rows = (list(activity).adapter as ScanListAdapter).currentList
                assertEquals(listOf("Visible"), rows.map { it.doc.name })
                assertTrue(rows.all { it.match?.docId == it.doc.id })
            }
        }
    }

    @Test
    fun `a changed search rebinds rows and each link opens its own document`() {
        val a = fixture("Alpha doc", 3, text(3, mapOf(1 to "aardvark")))
        val b = fixture("Beta doc", 4, text(4, mapOf(3 to "aardvark bison")))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(pollUntil { list(activity).adapter!!.itemCount == 2 })
                search(activity, "aardvark")
                assertTrue(pollUntil { list(activity).adapter!!.itemCount == 2 && strips(row(activity, 1)).isNotEmpty() })
                search(activity, "bison")
                assertTrue(pollUntil {
                    list(activity).adapter!!.itemCount == 1 && strips(row(activity, 0)).isNotEmpty() &&
                        label(strips(row(activity, 0)).single()) == "Page 4 · 1 match"
                })
                strips(row(activity, 0)).single().performClick()
                val started = shadowOf(activity).nextStartedActivity
                assertEquals(b.id, started.getStringExtra("doc_id"))
                assertEquals(3, started.getIntExtra("start_page", -1))
                assertFalse(started.getStringExtra("doc_id") == a.id)
            }
        }
    }

    @Test
    fun `a row never shows a match computed for another document or revision`() {
        val a = fixture("A", 2, text(2, mapOf(1 to "walrus")))
        val b = fixture("B", 2, text(2, mapOf(1 to "walrus")))
        val matches = HomePageMatches.build("walrus", listOf(a, b), setOf(a.id, b.id)) { repo.ocrDoc(it) }
        val parent = FrameLayout(android.view.ContextThemeWrapper(app, R.style.Theme_ScanWhirl))
        // A fresh adapter per case: ListAdapter only applies the first list synchronously.
        fun bound(row: HomeRow, selecting: Boolean = false): View {
            val adapter = ScanListAdapter({}, {}, {}, {})
            adapter.selectionMode = selecting
            val holder = adapter.onCreateViewHolder(parent, 0)
            adapter.submitList(listOf(row))
            adapter.onBindViewHolder(holder, 0)
            return holder.itemView
        }
        assertTrue("A's pages on B's row are dropped",
            strips(bound(HomeRow(b, HomeDocInfo.UNKNOWN, matches[a.id]))).isEmpty())
        val stale = matches.getValue(b.id).copy(revision = b.revision - 1)
        assertTrue("an edited document's old pages are dropped",
            strips(bound(HomeRow(b, HomeDocInfo.UNKNOWN, stale))).isEmpty())
        assertEquals(1, strips(bound(HomeRow(b, HomeDocInfo.UNKNOWN, matches[b.id]))).size)
        assertTrue("no page links while selecting",
            strips(bound(HomeRow(b, HomeDocInfo.UNKNOWN, matches[b.id]), selecting = true)).isEmpty())
    }

    @Test
    fun `page matches follow the current order after a real page move`() {
        val doc = fixture("Moved", 4, text(4, mapOf(1 to "quokka")))
        kotlinx.coroutines.runBlocking { repo.movePage(doc.id, 1, 3) }
        val moved = repo.getDocument(doc.id)!!
        val m = HomePageMatches.build("quokka", listOf(moved), setOf(moved.id)) { repo.ocrDoc(it) }
        assertEquals(listOf(3), m.getValue(moved.id).pages.map { it.pageIndex })
        // After a delete of that page the word is gone: no page, no guessed index.
        kotlinx.coroutines.runBlocking { repo.deletePage(doc.id, 3) }
        val cut = repo.getDocument(doc.id)!!
        val m2 = HomePageMatches.build("quokka", listOf(cut), setOf(cut.id)) { repo.ocrDoc(it) }
        assertTrue(m2.getValue(cut.id).pages.isEmpty())
    }

    // ---- Reader: start page -------------------------------------------------------

    private fun pager(a: PdfViewerActivity) = a.findViewById<RecyclerView>(R.id.pdfPager)
    private fun shownPage(a: PdfViewerActivity): Int {
        layout(pager(a), 1080, 1600)
        return (pager(a).layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
    }
    private fun indicator(a: PdfViewerActivity) = a.findViewById<TextView>(R.id.pdfPageIndicator).text.toString()
    private fun status(a: PdfViewerActivity) = a.findViewById<TextView>(R.id.findStatus).text.toString()

    @Test
    fun `page link lands on page 2 of 5 and Find shows the genuine match`() {
        val doc = fixture("Scholarship application", 5, text(5, mapOf(1 to "Reference ZEPHYRQUILL 77")))
        val intent = PdfViewerActivity.pageIntent(app, doc.id, doc.name, 1, "page_001.jpg", "zephyrquill")
        ActivityScenario.launch<PdfViewerActivity>(intent).use { scenario ->
            scenario.onActivity { a ->
                assertEquals(1, shownPage(a))
                assertEquals("2 / 5", indicator(a))
                assertTrue(a.findViewById<View>(R.id.findPanel).isVisible)
                assertTrue(pollUntil { status(a).startsWith("Match 1 of 1") })
                assertEquals("Match 1 of 1 · Page 2", status(a))
                val excerpt = a.findViewById<TextView>(R.id.findExcerpt)
                assertTrue(excerpt.text.toString().startsWith("Page 2"))
                assertTrue(excerpt.text.toString().contains("ZEPHYRQUILL"))
                assertTrue(a.findViewById<View>(R.id.findNote).isVisible)
                // Page-shaped placeholders: only the pages in view are bound, not the document.
                assertTrue(pager(a).childCount <= 3)
            }
        }
    }

    @Test
    fun `invalid start pages open at page 1 with an honest message`() {
        val doc = fixture("Five", 5, text(5, mapOf(1 to "zephyrquill")))
        for ((page, key) in listOf(9 to "page_009.jpg", -1 to "page_001.jpg", 2 to "page_001.jpg")) {
            ShadowToast.reset()
            val intent = PdfViewerActivity.pageIntent(app, doc.id, doc.name, page, key, null)
            ActivityScenario.launch<PdfViewerActivity>(intent).use { scenario ->
                scenario.onActivity { a ->
                    assertEquals("start $page/$key", 0, shownPage(a))
                    if (page >= 0) {
                        assertEquals(app.getString(R.string.reader_start_changed), ShadowToast.getTextOfLatestToast())
                    }
                    assertFalse(a.findViewById<View>(R.id.findPanel).isVisible)
                }
            }
        }
        // A moved page: the link's key is now elsewhere. Find still lists the real match
        // but the reader stays on page 1, as its message says.
        val moved = PdfViewerActivity.pageIntent(app, doc.id, doc.name, 3, "page_003.jpg", "zephyrquill")
            .putExtra("start_page_key", "page_001.jpg")
        ActivityScenario.launch<PdfViewerActivity>(moved).use { scenario ->
            scenario.onActivity { a ->
                assertTrue(pollUntil { status(a) == "Match 1 of 1 · Page 2" })
                assertEquals(0, shownPage(a))
            }
        }
        // The renderer disagrees with the page order (e.g. stale PDF): no jump either.
        fakePdfPages = 4
        val intent = PdfViewerActivity.pageIntent(app, doc.id, doc.name, 1, "page_001.jpg", "zephyrquill")
        ActivityScenario.launch<PdfViewerActivity>(intent).use { scenario ->
            scenario.onActivity { a ->
                assertEquals(0, shownPage(a))
                assertTrue(pollUntil { status(a) == app.getString(R.string.reader_find_out_of_step) })
            }
        }
        assertNull(PdfViewerActivity.startPage(1, "page_001.jpg", 5, null))
        assertNull(PdfViewerActivity.startPage(1, null, 5, listOf("a", "b", "c", "d", "e")))
        assertEquals(1, PdfViewerActivity.startPage(1, "b", 5, listOf("a", "b", "c", "d", "e")))
    }

    @Test
    fun `ordinary open still starts at page 1 with Find closed`() {
        val doc = fixture("Plain", 3, text(3))
        ActivityScenario.launch<PdfViewerActivity>(PdfViewerActivity.intent(app, doc.id, doc.name)).use { scenario ->
            scenario.onActivity { a ->
                assertEquals(0, shownPage(a))
                assertTrue(pollUntil { indicator(a) == "1 / 3" })
                assertFalse(a.findViewById<View>(R.id.findPanel).isVisible)
            }
        }
    }

    // ---- Reader: Find --------------------------------------------------------------

    @Test
    fun `Find on 30 pages steps Next and Previous across pages and wraps`() {
        val doc = fixture("Thirty", 30, text(30, mapOf(2 to "alpha", 9 to "alpha and alpha", 24 to "ALPHA")))
        ActivityScenario.launch<PdfViewerActivity>(PdfViewerActivity.intent(app, doc.id, doc.name)).use { scenario ->
            scenario.onActivity { a ->
                a.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).menu
                    .performIdentifierAction(R.id.action_find, 0)
                assertTrue(a.findViewById<View>(R.id.findPanel).isVisible)
                assertTrue(pollUntil { status(a).isNotEmpty() })
                assertEquals(app.getString(R.string.reader_find_type), status(a))
                a.findViewById<EditText>(R.id.findInput).setText("alpha")
                assertTrue(pollUntil { status(a) == "Match 1 of 4 · Page 3" })
                assertEquals(2, shownPage(a))
                val next = a.findViewById<View>(R.id.btnFindNext)
                val prev = a.findViewById<View>(R.id.btnFindPrev)
                assertTrue(next.isEnabled)
                next.performClick()
                assertEquals("Match 2 of 4 · Page 10", status(a))
                assertEquals(9, shownPage(a))
                next.performClick()
                assertEquals("Match 3 of 4 · Page 10", status(a))
                next.performClick()
                assertEquals("Match 4 of 4 · Page 25", status(a))
                assertEquals(24, shownPage(a))
                next.performClick()
                assertEquals("wraps to the first", "Match 1 of 4 · Page 3", status(a))
                prev.performClick()
                assertEquals("Match 4 of 4 · Page 25", status(a))
                // Tapping the excerpt returns to the match's page after scrolling away.
                (pager(a).layoutManager as LinearLayoutManager).scrollToPositionWithOffset(0, 0)
                assertEquals(0, shownPage(a))
                a.findViewById<View>(R.id.findExcerpt).performClick()
                assertEquals(24, shownPage(a))
            }
        }
    }

    @Test
    fun `no match, partial text and pending text are said plainly`() {
        val doc = fixture("Partial", 5, mapOf(0 to "invoice total", 3 to "invoice"), pending = true)
        ActivityScenario.launch<PdfViewerActivity>(PdfViewerActivity.intent(app, doc.id, doc.name)).use { scenario ->
            scenario.onActivity { a ->
                a.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).menu
                    .performIdentifierAction(R.id.action_find, 0)
                a.findViewById<EditText>(R.id.findInput).setText("platypus")
                assertTrue(pollUntil { status(a).startsWith("No match") })
                assertTrue(status(a).contains(app.getString(R.string.reader_find_pending)))
                assertFalse(a.findViewById<View>(R.id.findNav).isVisible)
                assertFalse(a.findViewById<View>(R.id.findExcerpt).isVisible)
            }
        }
        File(File(repo.docsDir, doc.id), "ocr_pending").delete()
        ActivityScenario.launch<PdfViewerActivity>(PdfViewerActivity.intent(app, doc.id, doc.name)).use { scenario ->
            scenario.onActivity { a ->
                a.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).menu
                    .performIdentifierAction(R.id.action_find, 0)
                a.findViewById<EditText>(R.id.findInput).setText("invoice")
                assertTrue(pollUntil { status(a).startsWith("Match 1 of 2") })
                assertTrue(status(a).contains(app.getString(R.string.reader_find_partial, 2, 5)))
            }
        }
    }

    @Test
    fun `a scan with no text and an external PDF explain instead of pretending`() {
        val doc = fixture("Unread", 2, ocr = null)
        ActivityScenario.launch<PdfViewerActivity>(PdfViewerActivity.intent(app, doc.id, doc.name)).use { scenario ->
            scenario.onActivity { a ->
                a.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).menu
                    .performIdentifierAction(R.id.action_find, 0)
                assertTrue(pollUntil { status(a) == app.getString(R.string.reader_find_no_text) })
            }
        }
        val external = File(app.cacheDir, "outside.pdf").apply { writeBytes(ByteArray(1024) { 2 }) }
        openedPages = 3
        ActivityScenario.launch<PdfViewerActivity>(PdfViewerActivity.fileIntent(app, external.path, "Outside")).use { scenario ->
            scenario.onActivity { a ->
                a.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).menu
                    .performIdentifierAction(R.id.action_find, 0)
                assertEquals(app.getString(R.string.reader_find_external), status(a))
                assertFalse(a.findViewById<EditText>(R.id.findInput).isEnabled)
                assertFalse(a.findViewById<View>(R.id.findNav).isVisible)
            }
        }
    }

    @Test
    fun `Find re-reads edited text and never shows stale results`() {
        val doc = fixture("Edited", 3, text(3, mapOf(1 to "Recieved")))
        ActivityScenario.launch<PdfViewerActivity>(PdfViewerActivity.intent(app, doc.id, doc.name)).use { scenario ->
            scenario.onActivity { a ->
                a.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).menu
                    .performIdentifierAction(R.id.action_find, 0)
                a.findViewById<EditText>(R.id.findInput).setText("received")
                assertTrue(pollUntil { status(a).startsWith("No match") })
                repo.updateOcrPage(doc.id, "page_002.jpg", "Received in full")
                a.findViewById<EditText>(R.id.findInput).setText("received ")
                assertTrue(pollUntil { status(a) == "Match 1 of 1 · Page 3" })
            }
        }
    }

    @Test
    fun `recreation keeps an ordinary query but never a private one`() {
        val doc = fixture("Ordinary", 4, text(4, mapOf(3 to "heron")))
        ActivityScenario.launch<PdfViewerActivity>(PdfViewerActivity.intent(app, doc.id, doc.name)).use { scenario ->
            scenario.onActivity { a ->
                a.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).menu
                    .performIdentifierAction(R.id.action_find, 0)
                a.findViewById<EditText>(R.id.findInput).setText("heron")
                assertTrue(pollUntil { status(a) == "Match 1 of 1 · Page 4" })
            }
            scenario.recreate()
            scenario.onActivity { a ->
                assertTrue(a.findViewById<View>(R.id.findPanel).isVisible)
                assertEquals("heron", a.findViewById<EditText>(R.id.findInput).text.toString())
                assertTrue(pollUntil { status(a) == "Match 1 of 1 · Page 4" })
            }
        }
        val secret = fixture("Private", 2, text(2, mapOf(1 to "heron")), vaulted = true)
        val intent = PdfViewerActivity.pageIntent(app, secret.id, secret.name, 1, "page_001.jpg", "heron")
        ActivityScenario.launch<PdfViewerActivity>(intent).use { scenario ->
            scenario.onActivity { a -> assertTrue(pollUntil { status(a) == "Match 1 of 1 · Page 2" }) }
            scenario.recreate()
            scenario.onActivity { a ->
                assertEquals("", a.findViewById<EditText>(R.id.findInput).text.toString())
            }
        }
    }

    @Test
    fun `back closes Find before the reader, and zoom hides then restores the panel`() {
        val doc = fixture("Backable", 2, text(2, mapOf(0 to "egret")))
        ActivityScenario.launch<PdfViewerActivity>(PdfViewerActivity.intent(app, doc.id, doc.name)).use { scenario ->
            scenario.onActivity { a ->
                a.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).menu
                    .performIdentifierAction(R.id.action_find, 0)
                a.findViewById<EditText>(R.id.findInput).setText("egret")
                assertTrue(pollUntil { status(a).startsWith("Match 1") })
                layout(pager(a), 1080, 1600)
                pager(a).findViewHolderForAdapterPosition(0)!!.itemView
                    .findViewById<View>(R.id.pageImage).performClick()
                assertTrue(pollUntil { a.findViewById<View>(R.id.zoomOverlay).isVisible })
                assertFalse(a.findViewById<View>(R.id.findPanel).isVisible)
                a.onBackPressedDispatcher.onBackPressed()
                assertFalse(a.findViewById<View>(R.id.zoomOverlay).isVisible)
                assertTrue(a.findViewById<View>(R.id.findPanel).isVisible)
                a.onBackPressedDispatcher.onBackPressed()
                assertFalse(a.findViewById<View>(R.id.findPanel).isVisible)
                assertFalse(a.isFinishing)
                a.onBackPressedDispatcher.onBackPressed()
                assertTrue(a.isFinishing)
            }
        }
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-xhdpi")
    fun `Find panel at 200 percent text on a 320dp phone stays capped and keeps 48dp controls`() {
        RuntimeEnvironment.setFontScale(2.0f)
        val doc = fixture("Large text", 3, text(3, mapOf(1 to "pelican pelican")))
        val intent = PdfViewerActivity.pageIntent(app, doc.id, doc.name, 1, "page_001.jpg", "pelican")
        ActivityScenario.launch<PdfViewerActivity>(intent).use { scenario ->
            scenario.onActivity { a ->
                assertTrue(pollUntil { status(a).startsWith("Match 1 of 2") })
                val dm = a.resources.displayMetrics
                val root = a.findViewById<View>(android.R.id.content)
                layout(root, dm.widthPixels, dm.heightPixels)
                val panel = a.findViewById<View>(R.id.findPanel)
                assertTrue("panel ≤ 45% of the screen", panel.height <= (dm.heightPixels * 0.45f).toInt() + 1)
                assertTrue("the pages keep room", pager(a).height > dm.heightPixels / 3)
                val min = (48 * dm.density).toInt()
                for (id in intArrayOf(R.id.btnFindPrev, R.id.btnFindNext, R.id.btnFindClose, R.id.findInput)) {
                    assertTrue("control $id ≥ 48dp", a.findViewById<View>(id).height >= min)
                }
                assertTrue(a.findViewById<View>(R.id.btnFindPrev).contentDescription.isNotBlank())
            }
        }
    }

    @Test
    fun `opening a page and using Find writes nothing to the library`() {
        val doc = fixture("Integrity", 4, text(4, mapOf(2 to "ibis")))
        val before = snapshotLibrary()
        val intent = PdfViewerActivity.pageIntent(app, doc.id, doc.name, 2, "page_002.jpg", "ibis")
        ActivityScenario.launch<PdfViewerActivity>(intent).use { scenario ->
            scenario.onActivity { a ->
                assertTrue(pollUntil { status(a).startsWith("Match 1 of 1") })
                a.findViewById<View>(R.id.btnFindNext).performClick()
            }
        }
        assertEquals(before, snapshotLibrary())
        assertEquals(1, repo.listDocuments().size)
    }
}
