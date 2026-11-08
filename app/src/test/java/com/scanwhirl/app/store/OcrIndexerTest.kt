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
}
