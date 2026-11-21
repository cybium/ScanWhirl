package com.scanwhirl.app.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import com.scanwhirl.app.util.PageReplace
import com.scanwhirl.app.util.PdfGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * CP1 defects 4 and 1: a compressed copy of a searchable document must stay searchable even when
 * the page pixels changed, and a Cancel during target fit must publish nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CompressSearchablePdfTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)

    @Test
    fun `a compressed copy of a searchable scan keeps its scaled text layer`() = runBlocking {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Searchable")
        val source = requireNotNull(repo.getDocument(id))
        val page = source.pageFiles.single()
        val (w, h) = requireNotNull(PageReplace.bounds(page))
        val line = "Fictional receipt line"
        repo.ocrStore.putPage(
            id, page.name, line, OcrPage(w, h, listOf(OcrLine(line, 5, 5, 195, 30)))
        )
        assertTrue("precondition: the source has a real text layer", repo.makeSearchable(id))
        assertTrue(
            "precondition: the source PDF carries invisible text",
            source.pdfFile.readBytes().toString(Charsets.ISO_8859_1).contains("/ActualText")
        )

        // Force a pixel-size change: 200x300 -> long side 120.
        val out = repo.compressScan(id, 20, 120)
        assertNotEquals("a new document is published", id, out.id)
        assertTrue(
            "CP1: the copy is still marked searchable",
            File(repo.docsDir, out.id + "/text_layer.flag").isFile
        )
        assertTrue(
            "CP1: the copy's PDF carries PdfTextLayer content",
            out.pdfFile.readBytes().toString(Charsets.ISO_8859_1).contains("/ActualText")
        )

        val copyPage = out.pageFiles.single()
        val info = requireNotNull(runCatching { PdfGenerator.readJpegInfo(copyPage.readBytes()) }.getOrNull())
        val geo = repo.ocrStore.readGeo(out.id)[copyPage.name]
        assertEquals("geometry follows the new pixel width", info.width, geo?.width)
        assertEquals("geometry follows the new pixel height", info.height, geo?.height)
        assertEquals("the recognised words survive", line, repo.ocrStore.readText(out.id)[copyPage.name])
        assertTrue(
            "line boxes stay inside the smaller page",
            geo!!.lines.all { it.right <= info.width && it.bottom <= info.height }
        )
        assertTrue("the original is untouched", requireNotNull(repo.getDocument(id)).pdfFile.isFile)
    }

    @Test
    fun `cancel during target fit publishes no partial document`() = runBlocking {
        val id = DocFixtures.saved(repo, app, pages = 3, name = "Cancel me")
        val sourcePdf = requireNotNull(repo.getDocument(id)).pdfFile.readBytes()
        val before = repo.listDocuments().map { it.id }.toSet()
        val job = Job()
        val fit = async(Dispatchers.IO + job) {
            repo.compressScanToTarget(id, 1_000L, onPage = { _, _ -> job.cancel() })
        }
        val failure = runCatching { fit.await() }.exceptionOrNull()
        assertTrue("the cooperative cancel is observed: $failure", failure is CancellationException)
        assertEquals(
            "cancel leaves no partial document in the library",
            before,
            repo.listDocuments().map { it.id }.toSet()
        )
        assertArrayEquals(
            "cancel leaves the source PDF byte-for-byte unchanged",
            sourcePdf,
            requireNotNull(repo.getDocument(id)).pdfFile.readBytes()
        )
    }
}
