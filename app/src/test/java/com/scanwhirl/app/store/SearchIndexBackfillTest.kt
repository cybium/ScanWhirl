package com.scanwhirl.app.store

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.LibraryIndexBackfill
import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import com.scanwhirl.app.util.OcrEngine
import com.scanwhirl.app.util.PdfProtection
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.ProGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * T41-SEARCH-INDEX · "find it later": a document whose text was recognised by an
 * older build (or by any save) must be found by the Home word search after an
 * upgrade, and must not need its OCR re-run to get there.
 *
 * The device finding (DEVICE_BUILD4 issue 2, `a5f3ca02` base) was: 3 of 15
 * library documents answered a word search; the rest sat in
 * `ocr_pending_queue.txt` because the shipped Home never asked the indexer to
 * resume. Fixtures here are fictional documents built through the real
 * repository and OCR store; no owner media or production app data is used.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SearchIndexBackfillTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository
    private lateinit var scope: CoroutineScope

    private val docsDir: File
        get() = File(app.filesDir, "documents")

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null)
        Prefs.setIndexBackfillDone(app, false)
        if (docsDir.exists()) docsDir.deleteRecursively()
    }

    @After
    fun tearDown() {
        scope.cancel()
        ProGate.setTestOverride(app, null)
    }

    // ---- fixtures ----------------------------------------------------------

    private fun saved(name: String, pages: Int = 3): String =
        DocFixtures.saved(repo, app, pages = pages, name = name).also {
            // saveScan marks the new document pending; an older install had published
            // documents with no marker at all. Tests start from that older state unless
            // they mark a document themselves.
            repo.ocrStore.clearPending(it)
        }

    /** Writes recognised text the way the indexer does (real ocr.txt, real store). */
    private fun withText(id: String, text: String) {
        val doc = requireNotNull(repo.getDocument(id)) { "fixture $id not found" }
        repo.ocrStore.putTexts(id, doc.pageFiles.associate { it.name to text })
    }

    /** The indexer with a fake recognizer, like the device's ML Kit would run it. */
    private fun indexer(
        autoRun: Boolean = false,
        text: String = "Line 1: The quick brown fox jumps over the lazy dog. 12345"
    ): OcrIndexer {
        val recognize: suspend (File) -> OcrEngine.OcrResult = {
            OcrEngine.OcrResult(
                text,
                OcrPage(200, 300, listOf(OcrLine("line", 0, 0, 10, 10)))
            )
        }
        return OcrIndexer(repo, scope, recognize = recognize, autoRun = autoRun)
    }

    // ---- 1. the stated hypothesis: text stored before any cache exists ----

    @Test
    fun `stored OCR text is searchable before the cache ever saw the document`() {
        val id = saved("SW_TEST_3pages Copy")
        withText(id, "Line 1: The quick brown fox jumps over the lazy dog. 12345")
        val empty = saved("Passport photo")

        // Same call LibraryScreen's computeVisible() makes, on a cold cache object.
        val cache = OcrTextCache(repo)
        val docs = repo.listDocuments()
        val visible = docs.filter { cache.normalized(it.id).contains("brown") }

        assertEquals(listOf(id), visible.map { it.id })
        assertEquals("the text is read again unchanged", cache.normalized(id), cache.normalized(id))
        assertTrue("page-level hits have stored page text", cache.pages(id).isNotEmpty())
        assertTrue("a document without text is not matched", cache.normalized(empty).isBlank())
    }

    @Test
    fun `a headerless ocr file written by an old build is still searchable`() {
        // Pre-test21 files have one plain `key<TAB>text` line per page and no header.
        val id = saved("Old receipt")
        File(File(docsDir, id), "ocr.txt")
            .writeText("page_000.jpg\tLine 1: The quick brown fox\n")

        val cache = OcrTextCache(repo)
        assertTrue(
            "an old install's stored text still answers a word search",
            cache.normalized(id).contains("brown")
        )
    }

    // ---- 2. the root cause: documents left pending are resumed at startup ----

    @Test
    fun `a document left pending by an older build is recognised at startup and becomes searchable`() =
        runBlocking {
            val id = saved("Old scan", pages = 2)
            // Exactly what an older build leaves behind: a marker and no ocr.txt.
            repo.ocrStore.markPending(id)
            assertFalse("fixture starts without stored text", repo.hasOcr(id))

            val idx = indexer(autoRun = true)
            val marked = LibraryIndexBackfill.run(
                app, repo, idx, powerSave = { false }
            )
            assertEquals("the pending document is still queued", 0, marked)
            withTimeout(10_000) {
                while (repo.ocrStore.isPending(id)) delay(10)
            }

            assertTrue("startup recognition stored text", repo.hasOcr(id))
            val cache = OcrTextCache(repo)
            assertTrue(
                "the document an upgrade left behind is findable by a word inside it",
                cache.normalized(id).contains("brown")
            )
            assertFalse("the marker is cleared once text exists", repo.ocrStore.isPending(id))
        }

    // ---- 3. copies must keep (or rebuild) the searchable text -------------

    @Test
    fun `a duplicated document keeps its recognised text so search finds the copy`() =
        runBlocking {
            val id = saved("Receipt")
            withText(id, "Line 1: The quick brown fox")

            val copy = repo.duplicateDocument(id, "Receipt Copy")

            assertTrue("the copy carries ocr.txt", repo.hasOcr(copy.id))
            assertFalse(
                "the copy is not queued for another recognition pass",
                repo.ocrStore.isPending(copy.id)
            )
            val cache = OcrTextCache(repo)
            assertTrue("search reads the copy's own stored text", cache.normalized(copy.id).contains("brown"))
        }

    // ---- 4. Vault / locked documents are never indexed or searched -------

    @Test
    fun `startup backfill never queues a Vault or password-locked document`() = runBlocking {
        val vaulted = saved("Private passbook")
        File(File(docsDir, vaulted), "vault.flag").writeText("1")
        val locked = DocFixtures.locked(repo, app)
        repo.ocrStore.clearPending(locked)
        val alreadyRead = saved("Already read")
        withText(alreadyRead, "Line 1: The quick brown fox")
        val plain = saved("Plain note")

        val marked = LibraryIndexBackfill.run(
            app, repo, indexer(), powerSave = { false }
        )

        assertEquals("only the ordinary unread document is queued", 1, marked)
        assertTrue(repo.ocrStore.isPending(plain))
        assertFalse("a Vault document is never queued", repo.ocrStore.isPending(vaulted))
        assertFalse("a locked document is never queued", repo.ocrStore.isPending(locked))
        assertFalse("a document with text is not read again", repo.ocrStore.isPending(alreadyRead))
        assertFalse("no text is produced for the Vault document", repo.hasOcr(vaulted))
        assertFalse("no text is produced for the locked document", repo.hasOcr(locked))
        assertTrue(
            "the Vault document stays out of the library list search reads",
            repo.listDocuments().none { it.id == vaulted }
        )

        // A screen that asks for it directly is refused too (fail closed, not only the sweep).
        repo.ocrStore.markPending(vaulted)
        repo.ocrStore.markPending(locked)
        assertFalse("a Vault document is never marked", repo.ocrStore.isPending(vaulted))
        assertFalse("a locked document is never marked", repo.ocrStore.isPending(locked))
        assertTrue(
            "neither can reach the indexer through the pending queue",
            repo.ocrStore.pendingQueue().none { it == vaulted || it == locked }
        )
    }

    @Test
    fun `a marker written before the document had an id is still resumed`() = runBlocking {
        val queued = saved("Queued scan")
        repo.ocrStore.markPending(queued) // the queue file now exists and lists only this id
        val output = saved("Tool output")
        // Exactly what buildAndPublish does: the marker moves into the library with the
        // folder, after the id exists, so the queue file cannot know about it.
        File(File(docsDir, output), "ocr_pending").writeText("")

        assertTrue(
            "the marker alone reaches the indexer",
            repo.ocrStore.pendingQueue().contains(output)
        )

        val idx = indexer(autoRun = true)
        LibraryIndexBackfill.run(app, repo, idx, powerSave = { false })
        withTimeout(10_000) {
            while (repo.ocrStore.isPending(output)) delay(10)
        }
        assertTrue("the unqueued output is recognised", repo.hasOcr(output))
    }

    // ---- 5. bounded, off the main thread, resumable across launches ------

    @Test
    fun `startup backfill is bounded per launch and runs off the main thread`() = runBlocking {
        val ids = (1..5).map { saved("Note $it", pages = 1) }
        var probeThread: String? = null
        val idx = indexer()

        val first = LibraryIndexBackfill.run(
            app, repo, idx,
            powerSave = { false },
            maxNewMarks = 3,
            threadProbe = { probeThread = it }
        )
        assertEquals("one launch marks at most its cap", 3, first)
        assertNotEquals(
            "the backfill never runs on the main thread",
            Looper.getMainLooper().thread.name,
            probeThread
        )
        assertEquals(3, ids.count { repo.ocrStore.isPending(it) })
        assertFalse(
            "a capped pass never claims the whole library was swept",
            Prefs.indexBackfillDone(app)
        )

        val second = LibraryIndexBackfill.run(
            app, repo, idx, powerSave = { false }, maxNewMarks = 3
        )
        assertEquals("the next launch marks the rest", 2, second)
        assertEquals(5, ids.count { repo.ocrStore.isPending(it) })
        assertTrue("the sweep is complete only after every document was seen", Prefs.indexBackfillDone(app))

        val third = LibraryIndexBackfill.run(app, repo, idx, powerSave = { false }, maxNewMarks = 3)
        assertEquals("a finished sweep does no work again", 0, third)
    }

    @Test
    fun `a battery-saver launch marks nothing and keeps the sweep due`() = runBlocking {
        val id = saved("Note", pages = 1)

        val marked = LibraryIndexBackfill.run(app, repo, indexer(), powerSave = { true })

        assertEquals(0, marked)
        assertFalse("no document was queued on a battery-saver launch", repo.ocrStore.isPending(id))
        assertFalse("the sweep stays due for a later launch", Prefs.indexBackfillDone(app))
    }

    @Test
    fun `the startup pass also recognises a document that carries no text`() = runBlocking {
        // H3-5: a Compress/Split/Extract output now carries its source text, so a fresh copy is
        // no longer the example of a text-less document. A library restored from an older build's
        // backup can still hold one: text gone, marker present. The startup pass must pick it up.
        val id = saved("Booklet", pages = 2)
        withText(id, "Line 1: The quick brown fox")
        val compressed = repo.compressScan(id, quality = 70, maxDim = null)
        assertTrue("H3-5: the compressed copy carries its text", repo.hasOcr(compressed.id))
        assertFalse(
            "H3-5: and it is not queued for a pointless re-recognition",
            repo.ocrStore.isPending(compressed.id)
        )

        // Exactly what an older build left behind: no ocr.txt and a pending marker.
        File(File(docsDir, compressed.id), "ocr.txt").delete()
        repo.ocrStore.markPending(compressed.id)
        assertFalse("fixture starts without stored text", repo.hasOcr(compressed.id))

        val idx = indexer(autoRun = true)
        LibraryIndexBackfill.run(app, repo, idx, powerSave = { false })
        withTimeout(10_000) {
            while (repo.ocrStore.isPending(compressed.id)) delay(10)
        }

        assertNotNull(repo.getDocument(compressed.id))
        assertTrue(
            "the compressed output becomes searchable without the user doing anything",
            OcrTextCache(repo).normalized(compressed.id).contains("brown")
        )
    }
}
