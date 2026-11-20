package com.scanwhirl.app.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import com.scanwhirl.app.util.ImportFixtures
import com.scanwhirl.app.util.OcrEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * F-02: a page whose recognition throws (disk error, corrupted JPEG, OutOfMemoryError) used to
 * lose its `ocr_pending` marker, so neither the next launch nor `resumePending` ever retried it.
 * The document now stays queued while retries remain and the automatic retries are bounded, so a
 * page that cannot be decoded is never attempted forever (an OOM loop at every cold start).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class OcrIndexerRetryTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
    }

    private fun indexer(recognize: suspend (File) -> OcrEngine.OcrResult): OcrIndexer =
        OcrIndexer(
            repo = repo,
            scope = CoroutineScope(Dispatchers.Unconfined),
            recognize = recognize,
            autoRun = false
        )

    private fun pageText(file: File) = "text of ${file.name}"

    private fun ok(file: File): OcrEngine.OcrResult {
        val text = pageText(file)
        return OcrEngine.OcrResult(text, OcrPage(100, 100, listOf(OcrLine(text, 1, 1, 50, 20))))
    }

    @Test
    fun `a failed page keeps the document queued for a later retry`() = runBlocking {
        val id = DocFixtures.saved(repo, app, pages = 3, name = "Failing page")
        val report = indexer { file ->
            if (file.name == "page_001.jpg") throw IllegalStateException("page could not be decoded")
            ok(file)
        }.index(id)

        assertEquals(1, report.failedPages)
        assertTrue(
            "a page that could not be read stays queued for the next launch",
            repo.ocrStore.isPending(id)
        )
        assertTrue("the pages that were read keep their text", "page_000.jpg" in repo.ocrDoc(id))
    }

    @Test
    fun `automatic retries stop after three attempts for one page`() = runBlocking {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Never readable")
        val calls = AtomicInteger()
        val indexer = indexer {
            calls.incrementAndGet()
            throw IllegalStateException("page could not be decoded")
        }

        repeat(3) { assertEquals(1, indexer.index(id).failedPages) }
        assertEquals("the page was attempted three times", 3, calls.get())
        assertFalse(
            "the queue marker is cleared once automatic retries are used up",
            repo.ocrStore.isPending(id)
        )

        assertEquals(1, indexer.index(id).failedPages)
        assertEquals("no automatic fourth attempt", 3, calls.get())
    }

    @Test
    fun `an out-of-memory page is never retried forever`() = runBlocking {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Huge page")
        val calls = AtomicInteger()
        val indexer = indexer {
            calls.incrementAndGet()
            throw OutOfMemoryError("could not allocate the page bitmap")
        }

        repeat(3) { indexer.index(id) }
        assertEquals(3, calls.get())
        assertFalse(repo.ocrStore.isPending(id))
        indexer.index(id)
        assertEquals("an OOM loop does not survive the retry budget", 3, calls.get())
    }

    @Test
    fun `a retry that succeeds stores the text and leaves nothing queued`() = runBlocking {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Read on the second try")
        var fail = true
        val indexer = indexer { file ->
            if (fail) throw IllegalStateException("camera was busy")
            ok(file)
        }

        assertEquals(1, indexer.index(id).failedPages)
        assertTrue(repo.ocrStore.isPending(id))

        fail = false
        assertEquals(0, indexer.index(id).failedPages)
        assertEquals(pageText(File("page_000.jpg")), repo.ocrDoc(id)["page_000.jpg"])
        assertFalse("the retry cleared the queue marker", repo.ocrStore.isPending(id))
    }

    @Test
    fun `a replaced page image gets its own retry budget`() = runBlocking {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Page swapped")
        val calls = AtomicInteger()
        val indexer = indexer {
            calls.incrementAndGet()
            throw IllegalStateException("page could not be decoded")
        }

        repeat(3) { indexer.index(id) }
        assertEquals(3, calls.get())

        val page = requireNotNull(repo.getDocument(id)).pageFiles.first()
        page.writeBytes(ImportFixtures.image(width = 320, height = 300))

        assertEquals(1, indexer.index(id).failedPages)
        assertEquals("a new page image is read again", 4, calls.get())
    }
}
