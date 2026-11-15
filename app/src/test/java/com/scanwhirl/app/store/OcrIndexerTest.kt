package com.scanwhirl.app.store

import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import com.scanwhirl.app.util.OcrEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class OcrIndexerTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val docsDir: File
        get() = File(context.filesDir, "documents")

    private fun contentUri(bytes: ByteArray): android.net.Uri {
        val uri = android.net.Uri.parse("content://test/fake_${System.nanoTime()}.jpg")
        shadowOf(context.contentResolver).registerInputStream(uri, bytes.inputStream())
        return uri
    }

    private fun realJpegBytes(w: Int, h: Int, color: Int): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bmp).drawColor(color)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private suspend fun saveDoc(repo: ScanRepository, name: String, pageCount: Int = 1): String {
        val uris = (0 until pageCount).map {
            contentUri(realJpegBytes(310, 444, android.graphics.Color.rgb(it * 50 + 50, 200, 100)))
        }
        return repo.saveScan(uris, null, name).id
    }

    /** Fake recognizer: page N -> "text of page_N", injectable failures per page key. */
    private class FakeRecognizer(
        val results: MutableMap<String, OcrEngine.OcrResult> = mutableMapOf(),
        val callCount: AtomicInteger = AtomicInteger(0),
        var shouldFail: (String) -> Boolean = { false }
    ) {
        suspend fun recognize(file: File): OcrEngine.OcrResult {
            callCount.incrementAndGet()
            val key = file.name
            if (shouldFail(key)) throw RuntimeException("recognizer boom for $key")
            return results.getOrPut(key) {
                OcrEngine.OcrResult(
                    "text of $key",
                    OcrPage(100, 100, listOf(OcrLine("line", 0, 0, 10, 10)))
                )
            }
        }
    }

    @Test
    fun `enqueue completes even when the caller scope is cancelled`() = runBlocking {
        val repo = ScanRepository(context)
        val id = saveDoc(repo, "3 pages", pageCount = 3)

        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Default)
        val fake = FakeRecognizer()
        val indexer = OcrIndexer(repo, scope, recognize = fake::recognize, autoRun = true)

        // Enqueue (non-suspending) then abandon the caller — the indexer's own
        // scope keeps the consumer alive.
        indexer.enqueue(id)

        // Wait for the consumer to finish indexing.
        withTimeout(5000) {
            while (OcrStore(docsDir).isPending(id)) {
                delay(10)
            }
        }

        val text = repo.ocrDoc(id)
        assertEquals(3, text.size)
        assertEquals("text of page_000.jpg", text["page_000.jpg"])
        assertEquals("text of page_001.jpg", text["page_001.jpg"])
        assertEquals("text of page_002.jpg", text["page_002.jpg"])
        assertFalse("ocr_pending should be cleared after indexing", OcrStore(docsDir).isPending(id))
        job.cancel()
    }

    @Test
    fun `resumePending recognizes only the missing page`() = runBlocking {
        val repo = ScanRepository(context)
        val id = saveDoc(repo, "3 pages", pageCount = 3)

        // Seed pages 0 and 1; leave page 2 absent. Leave an ocr_pending marker.
        repo.updateOcrPages(id, mapOf(
            "page_000.jpg" to "already indexed 0",
            "page_001.jpg" to "already indexed 1"
        ))
        val store = OcrStore(docsDir)
        store.markPending(id)
        assertTrue(store.isPending(id))

        val fake = FakeRecognizer()
        val indexer = OcrIndexer(repo, CoroutineScope(SupervisorJob() + Dispatchers.Default),
            recognize = fake::recognize, autoRun = false)
        indexer.resumePending()

        val failed = withTimeout(5000) { indexer.indexNow(id) }
        assertEquals(0, failed)
        // Only page 2 was recognized (call count == 1).
        assertEquals(1, fake.callCount.get())
        val text = repo.ocrDoc(id)
        assertEquals(3, text.size)
        assertEquals("already indexed 0", text["page_000.jpg"])
        assertEquals("already indexed 1", text["page_001.jpg"])
        assertEquals("text of page_002.jpg", text["page_002.jpg"])
        assertFalse(store.isPending(id))
    }

    @Test
    fun `recognizer failure leaves page absent, counts failure, clears marker`() = runBlocking {
        val repo = ScanRepository(context)
        val id = saveDoc(repo, "3 pages", pageCount = 3)

        val fake = FakeRecognizer(shouldFail = { it == "page_001.jpg" })
        val indexer = OcrIndexer(repo, CoroutineScope(SupervisorJob() + Dispatchers.Default),
            recognize = fake::recognize, autoRun = false)

        val failed = withTimeout(5000) { indexer.indexNow(id) }
        assertEquals(1, failed)

        val text = repo.ocrDoc(id)
        assertEquals(2, text.size) // page_001 absent
        assertEquals("text of page_000.jpg", text["page_000.jpg"])
        assertNull(text["page_001.jpg"])
        assertEquals("text of page_002.jpg", text["page_002.jpg"])
        assertFalse("ocr_pending should be cleared even with failures", OcrStore(docsDir).isPending(id))
    }

    @Test
    fun `enqueue twice for same id produces one pass`() = runBlocking {
        val repo = ScanRepository(context)
        val id = saveDoc(repo, "2 pages", pageCount = 2)

        val fake = FakeRecognizer()
        val indexer = OcrIndexer(repo, CoroutineScope(SupervisorJob() + Dispatchers.Default),
            recognize = fake::recognize, autoRun = false)

        indexer.enqueue(id)
        indexer.enqueue(id) // duplicate, should be ignored

        val failed = withTimeout(5000) { indexer.indexNow(id) }
        assertEquals(0, failed)
        // Both pages recognized exactly once each (call count == 2, not 4).
        assertEquals(2, fake.callCount.get())
    }

    @Test
    fun `autoRun false writes marker only, recognizes nothing`() = runBlocking {
        val repo = ScanRepository(context)
        val id = saveDoc(repo, "1 page", pageCount = 1)

        val fake = FakeRecognizer()
        val indexer = OcrIndexer(repo, CoroutineScope(SupervisorJob() + Dispatchers.Default),
            recognize = fake::recognize, autoRun = false)

        indexer.enqueue(id)
        assertTrue("ocr_pending should be written by enqueue", OcrStore(docsDir).isPending(id))
        assertEquals(0, fake.callCount.get())
        assertEquals(0, repo.ocrDoc(id).size)
    }

    @Test
    fun `afterIndexed runs once per finished pass`() = runBlocking {
        val repo = ScanRepository(context)
        val id = saveDoc(repo, "1 page", pageCount = 1)

        val indexed = mutableListOf<String>()
        val fake = FakeRecognizer()
        val indexer = OcrIndexer(repo, CoroutineScope(SupervisorJob() + Dispatchers.Default),
            recognize = fake::recognize, autoRun = false,
            afterIndexed = { indexed.add(it) })

        val failed = withTimeout(5000) { indexer.indexNow(id) }
        assertEquals(0, failed)
        assertEquals(listOf(id), indexed)
    }

    /** A document built directly on disk (real JPEGs are not needed with a fake recognizer). */
    private fun syntheticDoc(id: String, pages: Int): String {
        val dir = File(docsDir, id).apply { mkdirs() }
        File(dir, "meta.txt").writeText("Bulk $id\n${System.currentTimeMillis()}\nOther\n")
        File(dir, "scan.pdf").writeBytes(byteArrayOf(1))
        repeat(pages) { p ->
            File(dir, "page_%03d.jpg".format(p)).writeBytes(byteArrayOf(p.toByte(), 2, 3))
        }
        File(dir, "ocr_pending").writeText("")
        return id
    }

    @Test
    fun `a long index writes a bounded number of files, not one per page`() = runBlocking {
        val repo = ScanRepository(context)
        val pages = 120
        val id = syntheticDoc("bulk_batch", pages)
        val fake = FakeRecognizer()
        val indexer = OcrIndexer(
            repo, CoroutineScope(SupervisorJob() + Dispatchers.Default),
            recognize = fake::recognize, autoRun = false, batchPages = 8
        )

        OcrStore.resetWriteCountForTests()
        val report = indexer.index(id)
        val writes = OcrStore.writeCountForTests()

        assertEquals(pages, report.pagesWithText)
        assertEquals(0, report.failedPages)
        assertEquals(pages, fake.callCount.get())
        // Two writes per batch (geometry, then text) plus at most one partial batch. A per-page
        // implementation would write 2 * pages times.
        val batches = (pages + 8 - 1) / 8
        assertTrue("writes=$writes for $pages pages (batches=$batches)", writes <= batches * 2 + 2)
        assertTrue("writes=$writes looks per-page", writes < pages)
        assertEquals(pages, repo.ocrStore.readText(id).size)
        assertEquals(pages, repo.ocrStore.readGeo(id).size)
    }

    @Test
    fun `the report counts only this document's pages`() = runBlocking {
        val repo = ScanRepository(context)
        val id = syntheticDoc("bulk_report", 6)
        val fake = FakeRecognizer()
        val indexer = OcrIndexer(repo, CoroutineScope(SupervisorJob() + Dispatchers.Default),
            recognize = fake::recognize, autoRun = false)
        indexer.indexNow(id)
        // A leftover key from a page that no longer exists must not inflate the report.
        repo.ocrStore.putPage(id, "page_999.jpg", "stale", null)

        val report = indexer.index(id)
        assertEquals(6, report.totalPages)
        assertEquals(6, report.pagesWithText)
    }

    @Test
    fun `a pass that dies mid-batch leaves a consistent store and resumes`() = runBlocking {
        val repo = ScanRepository(context)
        val pages = 10
        val id = syntheticDoc("bulk_crash", pages)
        val fake = FakeRecognizer()
        // Die (like a process kill) on the sixth page, after the first batch of four was committed.
        val dying = object {
            suspend fun recognize(file: File): OcrEngine.OcrResult {
                if (file.name == "page_005.jpg") throw kotlinx.coroutines.CancellationException("process died")
                return fake.recognize(file)
            }
        }
        val indexer = OcrIndexer(
            repo, CoroutineScope(SupervisorJob() + Dispatchers.Default),
            recognize = dying::recognize, autoRun = false, batchPages = 4
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val outcome = runCatching { runBlocking { kotlinx.coroutines.withTimeout(5_000) { indexer.indexNow(id) } } }

        assertTrue("the pass was interrupted", outcome.isFailure)
        val store = repo.ocrStore
        val text = store.readText(id)
        val geo = store.readGeo(id)
        assertEquals("the committed batches survive, the in-flight page too", 5, text.size)
        assertEquals("text and geometry agree after an interrupted batch", text.keys, geo.keys)
        assertTrue("the document stays pending for the next launch", store.isPending(id))

        val resumeFake = FakeRecognizer()
        val resumed = OcrIndexer(repo, scope, recognize = { file -> resumeFake.recognize(file) },
            autoRun = false, batchPages = 4)
        assertEquals(0, resumed.indexNow(id))
        assertEquals(pages, store.readText(id).size)
        assertFalse(store.isPending(id))
    }

    @Test
    fun `the pending queue replaces the directory scan and drops dead entries`() = runBlocking {
        val repo = ScanRepository(context)
        val a = saveDoc(repo, "Queued A", pageCount = 1)
        val b = saveDoc(repo, "Queued B", pageCount = 1)
        val store = repo.ocrStore
        store.markPending(a)
        store.markPending(b)

        assertEquals(setOf(a, b), store.pendingQueue().toSet())
        assertTrue("one small queue file", File(context.filesDir, "ocr_pending_queue.txt").isFile)

        store.clearPending(a)
        assertEquals(listOf(b), store.pendingQueue())
    }

    @Test
    fun `a missing queue file is rebuilt from the markers`() = runBlocking {
        val repo = ScanRepository(context)
        val a = saveDoc(repo, "Upgrade A", pageCount = 1)
        val b = saveDoc(repo, "Upgrade B", pageCount = 1)
        val store = repo.ocrStore
        store.markPending(a)
        store.markPending(b)
        // An install upgraded from a build without the queue file: only markers exist.
        File(context.filesDir, "ocr_pending_queue.txt").delete()

        assertEquals(setOf(a, b), store.pendingQueue().toSet())
        assertTrue("the queue is rebuilt", File(context.filesDir, "ocr_pending_queue.txt").isFile)
    }
}
