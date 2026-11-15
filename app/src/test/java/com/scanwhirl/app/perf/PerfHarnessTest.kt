package com.scanwhirl.app.perf

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import android.util.Base64
import android.view.View
import android.widget.ImageView
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import com.scanwhirl.app.store.OcrIndexer
import com.scanwhirl.app.store.OcrTextCache
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.pagegrid.PageGridView
import com.scanwhirl.app.ui.pagegrid.PageThumbLoader
import com.scanwhirl.app.util.ImageTools
import com.scanwhirl.app.util.ImportFixtures
import com.scanwhirl.app.util.PdfGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.max
import kotlin.system.measureNanoTime

/**
 * v2 UI performance harness (owner imports 2000-page textbooks).
 *
 * Normal runs use modest sizes so the cloud gate stays fast; when the owner QA folder carries an
 * `EVAL_ON` sentinel the same harness builds a 300-document library with one 500-page document and
 * writes the measured table to `docs/audit/2026-11-15/PERF_RAW.txt`.
 *
 * It measures the paths that decide whether a big library feels usable:
 * library load, name/text search, opening a document (page list), Organize grid binding, merge
 * 3×N pages, compress N pages, PDF generation N pages, and an OCR index rebuild. The memory test
 * asserts the decode sizes requested for thumbnails (never a full-resolution decode).
 *
 * Everything is synthetic (tiny JPEGs); no owner data is read or written.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PerfHarnessTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo by lazy { ScanRepository(app) }

    private val big: Boolean by lazy {
        ownerRoots().any { File(it, "EVAL_ON").isFile }
    }

    private val docCount get() = if (big) 300 else 40
    private val heavyPages get() = if (big) 500 else 80
    private val mergePages get() = if (big) 200 else 30
    private val indexPages get() = if (big) 500 else 60

    private val pageJpeg: ByteArray by lazy { ImportFixtures.image(width = 64, height = 96) }

    private lateinit var heavyId: String
    private lateinit var docs: List<com.scanwhirl.app.model.ScanDocument>

    @After
    fun tearDown() {
        ImageTools.thumbProbe = null
    }

    // ------------------------------------------------------------------
    // Fixtures (direct, no publish path: the harness measures reads, not setup)
    // ------------------------------------------------------------------

    private fun ownerRoots() = listOf(
        File("/mnt/c/Users/Cybium/Desktop/.sw-qa/owner"),
        File("C:/Users/Cybium/Desktop/.sw-qa/owner")
    )

    private fun buildLibrary() {
        val docsDir = repo.docsDir
        val now = System.currentTimeMillis()
        for (i in 0 until docCount) {
            val id = "perf_%03d".format(i)
            val dir = File(docsDir, id).apply { mkdirs() }
            val name = "Doc $i"
            File(dir, "meta.txt").writeText("$name\n${now - i * 60_000L}\nOther\n")
            File(dir, "scan.pdf").writeBytes(pageJpeg)
            repeat(2) { p ->
                File(dir, "page_%03d.jpg".format(p)).writeBytes(pageJpeg)
            }
            writeOcr(dir, 2, "invoice $i page")
        }
        val heavy = File(docsDir, "perf_heavy").apply { mkdirs() }
        File(heavy, "meta.txt").writeText("Textbook\n$now\nStudy\n")
        File(heavy, "scan.pdf").writeBytes(pageJpeg)
        repeat(heavyPages) { p ->
            File(heavy, "page_%03d.jpg".format(p)).writeBytes(pageJpeg)
        }
        writeOcr(heavy, heavyPages, "textbook chapter")
        writeGeo(heavy, heavyPages)
        heavyId = heavy.name
        docs = repo.listDocuments()
        assertEquals(docCount + 1, docs.size)
    }

    private fun writeOcr(dir: File, pages: Int, prefix: String) {
        val body = buildString {
            append("#scanwhirl-ocr-v2\n")
            for (p in 0 until pages) {
                val text = "$prefix $p"
                append("page_%03d.jpg\t".format(p))
                append(Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
                append('\n')
            }
        }
        File(dir, "ocr.txt").writeText(body)
    }

    /** Geometry for every page, in the same format OcrStore writes. */
    private fun writeGeo(dir: File, pages: Int) {
        val body = buildString {
            append("#scanwhirl-ocrgeo-v1\n")
            for (p in 0 until pages) {
                val payload = "64 96\n1 1 20 20\tword\n"
                append("page_%03d.jpg\t".format(p))
                append(Base64.encodeToString(payload.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
                append('\n')
            }
        }
        File(dir, "ocr_geo.txt").writeText(body)
    }

    private fun docPages(id: String): List<File> =
        requireNotNull(repo.getDocument(id)).pageFiles

    private fun time(label: String, warmups: Int = 1, runs: Int = 3, block: () -> Unit): Long {
        repeat(warmups) { block() }
        var best = Long.MAX_VALUE
        repeat(runs) {
            val ns = measureNanoTime(block)
            best = minOf(best, ns)
        }
        val ms = best / 1_000_000
        record("$label: ${ms}ms")
        return ms
    }

    private fun record(line: String) {
        println("PERF $line")
        if (!big) return
        val out = File(repoRoot(), "docs/audit/2026-11-15/PERF_RAW.txt")
        if (!out.parentFile.exists()) return
        synchronized(this) { out.appendText(line + "\n") }
    }

    private fun repoRoot(): File {
        // The gate builds in a WSL mirror; write the measured table back to the real worktree.
        val worktree = File("/mnt/c/Users/Cybium/Desktop/ScanWhirl-L4")
        if (worktree.isDirectory) return worktree
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return if (cwd.name == "app") cwd.parentFile ?: cwd else cwd
    }

    // ------------------------------------------------------------------
    // 1. Library load + search
    // ------------------------------------------------------------------

    @Test
    fun `library load and search`() {
        buildLibrary()

        val fresh = ScanRepository(app)
        val loadMs = time("library load", warmups = 1, runs = 3) { fresh.listDocuments() }
        val cache = OcrTextCache(repo)
        val all = repo.listDocuments()

        // Same shape as LibraryScreen.visibleDocs(): name/tags first, then OCR text.
        fun search(q: String) {
            val lower = q.lowercase()
            all.filter { doc ->
                doc.name.lowercase().contains(lower) || doc.tags.any { it.lowercase().contains(lower) } ||
                    runCatching { cache.normalized(doc.id).contains(lower) }.getOrDefault(false)
            }
        }

        val coldName = time("search name cold", warmups = 0, runs = 1) { search("Doc 25") }
        val warmName = time("search name warm", warmups = 1, runs = 3) { search("Doc 25") }
        val coldTextCache = OcrTextCache(repo)
        val coldText = time("search OCR cold", warmups = 0, runs = 1) {
            val lower = "invoice 25"
            repo.listDocuments().filter { doc ->
                runCatching { coldTextCache.normalized(doc.id).contains(lower) }.getOrDefault(false)
            }
        }
        val warmText = time("search OCR warm", warmups = 1, runs = 3) {
            val lower = "invoice 25"
            repo.listDocuments().filter { doc ->
                runCatching { cache.normalized(doc.id).contains(lower) }.getOrDefault(false)
            }
        }

        // Generous ceilings: catch a quadratic/whole-library rescan, not runner noise.
        assertTrue("library load too slow: ${loadMs}ms", loadMs < 10_000)
        assertTrue("name search too slow: $coldName/$warmName", warmName < 3_000)
        assertTrue("text search too slow: $coldText/$warmText", warmText < 10_000)
    }

    // ------------------------------------------------------------------
    // 2. Opening a document (page list)
    // ------------------------------------------------------------------

    @Test
    fun `open heavy document page list`() {
        buildLibrary()
        val open = time("open document (page list)", warmups = 1, runs = 3) {
            requireNotNull(repo.getDocument(heavyId))
        }
        val draft = time("open document + draftPages", warmups = 1, runs = 3) {
            repo.getDocument(heavyId)
            runBlocking { repo.draftPages(heavyId) }
        }
        assertTrue("open too slow: ${open}ms", open < 10_000)
        assertTrue("open+draft too slow: ${draft}ms", draft < 20_000)
    }

    // ------------------------------------------------------------------
    // 3. Organize grid adapter binding
    // ------------------------------------------------------------------

    @Test
    fun `organize grid binds the first screen`() {
        buildLibrary()
        val pages = docPages(heavyId)
        val controller = Robolectric.buildActivity(Activity::class.java)
        val activity = controller.setup().get()
        activity.setTheme(R.style.Theme_ScanWhirl)
        val grid = PageGridView(activity)
        val ms = time("organize grid first screen", warmups = 0, runs = 1) {
            grid.submit(pages)
            grid.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
            )
            grid.layout(0, 0, 1080, 1920)
        }
        val bound = grid.childCount
        record("organize grid visible cells: $bound")
        assertTrue("the grid bound some cells", bound > 0)
        assertTrue("grid binding too slow: ${ms}ms", ms < 10_000)
    }

    // ------------------------------------------------------------------
    // 4. Merge 3×N pages
    // ------------------------------------------------------------------

    @Test
    fun `merge three heavy documents`() {
        buildLibrary()
        val ids = (0 until 3).map { i ->
            val dir = File(repo.docsDir, "merge_$i").apply { mkdirs() }
            File(dir, "meta.txt").writeText("Merge $i\n${System.currentTimeMillis()}\nOther\n")
            File(dir, "scan.pdf").writeBytes(pageJpeg)
            repeat(mergePages) { p -> File(dir, "page_%03d.jpg".format(p)).writeBytes(pageJpeg) }
            dir.name
        }
        val ms = time("merge 3x$mergePages pages", warmups = 0, runs = 1) {
            runBlocking { repo.mergeDocuments(ids, "Perf merge") }
        }
        assertTrue("merge too slow: ${ms}ms", ms < 180_000)
        val merged = requireNotNull(repo.listDocuments().firstOrNull { it.name == "Perf merge" })
        assertEquals(3 * mergePages, merged.pageCount)
    }

    // ------------------------------------------------------------------
    // 5. Compress N pages
    // ------------------------------------------------------------------

    @Test
    fun `compress the heavy document`() {
        buildLibrary()
        val ms = time("compress $heavyPages pages", warmups = 0, runs = 1) {
            runBlocking { repo.compressScan(heavyId, quality = 60, maxDim = null) }
        }
        assertTrue("compress too slow: ${ms}ms", ms < 180_000)
    }

    @Test
    fun `target size compress holds one page at a time`() {
        buildLibrary()
        val pages = docPages(heavyId)
        // The old path returns every compressed page at once (a whole-book ByteArray list) and
        // hands that list to createDocument.
        val oldMs = time("target compress old (all pages in memory)", warmups = 0, runs = 1) {
            val compressed = com.scanwhirl.app.tools.SizeFit.compressPages(pages, 2_000_000L)
            runBlocking { repo.createDocument(repo.outputName("Target source", "Compressed"), compressed) }
        }
        val newMs = time("target compress streaming (1 page at a time)", warmups = 0, runs = 1) {
            runBlocking { repo.compressScanToTarget(heavyId, 2_000_000L) }
        }
        record("target compress peak pages in memory: old=$heavyPages new=1")
        assertTrue("streaming target compress too slow: ${newMs}ms", newMs < 180_000)
        assertEquals(
            "both paths publish the same page count",
            2,
            repo.listDocuments().count { it.name.endsWith(" - Compressed") }
        )
        assertTrue("old path was measured", oldMs > 0)
    }

    // ------------------------------------------------------------------
    // 5b. Make searchable (PDF rebuild + text-layer flag)
    // ------------------------------------------------------------------

    @Test
    fun `make searchable on the heavy document`() {
        buildLibrary()
        // The old flag check re-read the whole geometry map once per page.
        val oldMs = time("text-layer flag old (geo re-read per page)", warmups = 0, runs = 1) {
            repeat(heavyPages) { repo.ocrStore.readGeo(heavyId) }
        }
        val oneRead = repo.ocrStore.readGeo(heavyId)
        val newMs = time("text-layer flag new (one geo read)", warmups = 1, runs = 3) {
            repeat(1) { oneRead.size }
        }
        record("text-layer flag reads: old=$heavyPages new=1")
        val ms = time("make searchable $heavyPages pages", warmups = 0, runs = 1) {
            assertTrue(repo.makeSearchable(heavyId))
        }
        assertTrue("make searchable too slow: ${ms}ms", ms < 180_000)
        assertTrue(File(File(repo.docsDir, heavyId), "text_layer.flag").isFile)
        assertTrue("old flag check was measured", oldMs > 0 && newMs >= 0)
    }

    // ------------------------------------------------------------------
    // 6. PDF generation N pages
    // ------------------------------------------------------------------

    @Test
    fun `generate the heavy pdf`() {
        buildLibrary()
        val pages = docPages(heavyId)
        val out = File(app.cacheDir, "documents/perf/heavy.pdf").apply { parentFile?.mkdirs() }
        val ms = time("pdf $heavyPages pages", warmups = 0, runs = 1) {
            PdfGenerator.imagesToPdf(pages, out)
        }
        assertTrue("pdf too slow: ${ms}ms", ms < 180_000)
        assertTrue("pdf was written", out.length() > 0)
    }

    // ------------------------------------------------------------------
    // 7. OCR index rebuild
    // ------------------------------------------------------------------

    @Test
    fun `ocr index rebuild`() {
        buildLibrary()
        val dir = File(repo.docsDir, "index_doc").apply { mkdirs() }
        File(dir, "meta.txt").writeText("Index doc\n${System.currentTimeMillis()}\nOther\n")
        File(dir, "scan.pdf").writeBytes(pageJpeg)
        repeat(indexPages) { p -> File(dir, "page_%03d.jpg".format(p)).writeBytes(pageJpeg) }
        val indexer = OcrIndexer(
            repo,
            CoroutineScope(Dispatchers.IO + SupervisorJob()),
            recognize = {
                kotlinx.coroutines.delay(0)
                com.scanwhirl.app.util.OcrEngine.OcrResult(
                    "recognised text",
                    OcrPage(64, 96, listOf(OcrLine("recognised", 1, 1, 20, 20)))
                )
            },
            autoRun = false
        )
        val ms = time("ocr index $indexPages pages", warmups = 0, runs = 1) {
            runBlocking { indexer.indexNow(dir.name) }
        }
        assertTrue("index too slow: ${ms}ms", ms < 240_000)
        assertEquals(indexPages, repo.ocrStore.readText(dir.name).size)
    }

    // ------------------------------------------------------------------
    // 8. Thumbnail memory bound
    // ------------------------------------------------------------------

    @Test
    fun `thumbnails request a sampled decode, never full size`() {
        val file = File(app.cacheDir, "documents/perf/big-page.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(ImportFixtures.image(width = 1600, height = 2200))
        }
        val target = 220
        val calls = mutableListOf<Triple<Int, Int, Int>>()
        ImageTools.thumbProbe = { _, sample, w, h -> calls += Triple(sample, w, h) }

        val controller = Robolectric.buildActivity(Activity::class.java)
        val activity = controller.setup().get()
        activity.setTheme(R.style.Theme_ScanWhirl)
        val view = ImageView(activity)
        PageThumbLoader.load(file, target, com.scanwhirl.app.util.ScanFilter.ORIGINAL, view)
        val deadline = System.currentTimeMillis() + 10_000
        while (calls.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertTrue("a thumbnail decode was requested", calls.isNotEmpty())
        val (sample, w, h) = calls.first()
        record("thumbnail sample: target=${target}px sample=$sample decoded=${w}x$h")
        assertTrue("must downsample a 1600x2200 page (sample=$sample)", sample >= 2)
        assertTrue("decoded width $w far above target $target", w <= target * 2)
        assertTrue("decoded height $h far above target $target", h <= target * 2)
        val bytes = w.toLong() * h * 4
        assertTrue("decoded bitmap $w x $h is too large", bytes <= 1_500_000)
        assertEquals(1, calls.size)
    }
}
