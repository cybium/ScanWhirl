package com.scanwhirl.app.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import com.scanwhirl.app.util.OcrEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * C06: decoding a 6 MP page can raise OutOfMemoryError, which is an Error, not an Exception.
 * The indexer must count that page as unread and keep going instead of crashing the app.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class OcrIndexerOomTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
    }

    private fun indexer(recognize: suspend (java.io.File) -> OcrEngine.OcrResult): OcrIndexer =
        OcrIndexer(
            repo = repo,
            scope = CoroutineScope(Dispatchers.Unconfined),
            recognize = recognize,
            autoRun = false
        )

    @Test
    fun `an out-of-memory page is counted and never crashes the pass`() = runBlocking {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "OOM scan")
        var calls = 0
        val report = indexer {
            calls++
            throw OutOfMemoryError("could not allocate the page bitmap")
        }.index(id)

        assertEquals("both pages were attempted", 2, calls)
        assertEquals(2, report.totalPages)
        assertEquals("each OOM counts as an unread page", 2, report.failedPages)
        assertTrue("nothing is stored as empty text", repo.ocrDoc(id).isEmpty())
    }

    @Test
    fun `an OOM on one page does not stop the next page`() = runBlocking {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "OOM then ok")
        var calls = 0
        val page = OcrPage(100, 100, listOf(OcrLine("hello", 1, 1, 50, 20)))
        val report = indexer {
            if (calls++ == 0) throw OutOfMemoryError("first page")
            OcrEngine.OcrResult("hello", page)
        }.index(id)

        assertEquals("the second page was still read", 2, calls)
        assertEquals(1, report.failedPages)
        assertEquals("one page carries text", 1, report.pagesWithText)
    }
}
