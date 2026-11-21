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
    fun `cancel on the final page publishes no partial document`() = runBlocking {
        // CP1 review P1: cancelling from the LAST onPage callback must still stop the publish.
        val id = DocFixtures.saved(repo, app, pages = 3, name = "Cancel me")
        val sourcePdf = requireNotNull(repo.getDocument(id)).pdfFile.readBytes()
        val before = repo.listDocuments().map { it.id }.toSet()
        val job = Job()
        val fit = async(Dispatchers.IO + job) {
            repo.compressScanToTarget(id, 1_000L, onPage = { done, total ->
                if (done == total) job.cancel()
            })
        }
        val failure = runCatching { fit.await() }.exceptionOrNull()
        assertTrue("the cooperative cancel is observed: $failure", failure is CancellationException)
        assertEquals(
            "cancel leaves no partial document in the library",
            before,
            repo.listDocuments().map { it.id }.toSet()
        )
        assertTrue(
            "staging was released, not left for recovery",
            File(app.filesDir, OpStaging.DIR).listFiles().isNullOrEmpty()
        )
        assertArrayEquals(
            "cancel leaves the source PDF byte-for-byte unchanged",
            sourcePdf,
            requireNotNull(repo.getDocument(id)).pdfFile.readBytes()
        )
    }

    @Test
    fun `the staged fit checks cancellation after its last page too`(): Unit = runBlocking {
        // RED on b53b2701: the loop checked per page, so a cancel signalled from the final page
        // returned normally instead of throwing before carryOcr/publish.
        val staging = File(app.filesDir, "cp1_fit_cancel_${java.util.UUID.randomUUID()}").apply { mkdirs() }
        repeat(2) { i ->
            File(staging, String.format(java.util.Locale.ROOT, "page_%03d.jpg", i))
                .writeBytes(com.scanwhirl.app.util.ImportFixtures.image(width = 200, height = 300))
        }
        val job = Job()
        val fit = async(Dispatchers.IO + job) {
            repo.fitStagedPagesToTarget(staging, 1_000L, onPage = { done, total ->
                if (done == total) job.cancel()
            })
        }
        val failure = runCatching { fit.await() }.exceptionOrNull()
        assertTrue("the last-page cancel must be observed: $failure", failure is CancellationException)
        staging.deleteRecursively()
    }

    /**
     * CP1 review P2: a saved page is always upright, so scaling ASCII/OCR boxes by old-pixel to
     * new-pixel dimensions cannot be upset by an EXIF rotation. Import bakes the rotation into the
     * pixels: ImageTools.normalizePage (util/ImageTools.kt:174-228) applies all eight EXIF transforms
     * and returns the input untouched only when the matrix is identity (ImageTools.kt:216-218);
     * gallery photos go through normalizeGalleryPhoto before staging (DocumentViewerActivity.kt:
     * 1149-1150), and device-PDF pages are rendered upright. OcrEngine.exifRotation
     * (util/OcrEngine.kt:286-303) still tolerates legacy sideways files, but those already have
     * their boxes in display coordinates; our scaling keeps that same coordinate relationship.
     */
    @Test
    fun `a saved EXIF-rotated page is upright and its compressed copy keeps scaled boxes`() = runBlocking {
        val oriented = File(app.cacheDir, "cp1_exif_${java.util.UUID.randomUUID()}.jpg")
        com.scanwhirl.app.util.ImportFixtures.oriented(
            oriented, androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90,
            width = 120, height = 200
        )
        val id = runBlocking { repo.saveScan(listOf(android.net.Uri.fromFile(oriented)), null, "Rotated") }.id
        val page = requireNotNull(repo.getDocument(id)).pageFiles.single()
        val rotated = com.scanwhirl.app.util.PdfGenerator.readJpegInfo(page.readBytes())
        assertEquals("import normalized the 120x200 page to 200x120", 200, rotated.width)
        assertEquals(120, rotated.height)

        val line = "Upright line"
        repo.ocrStore.putPage(
            id, page.name, line,
            com.scanwhirl.app.model.OcrPage(
                rotated.width, rotated.height,
                listOf(com.scanwhirl.app.model.OcrLine(line, 5, 5, 195, 30))
            )
        )
        assertTrue(repo.makeSearchable(id))
        val copy = repo.compressScan(id, 30, 100)
        val copyInfo = requireNotNull(
            runCatching { com.scanwhirl.app.util.PdfGenerator.readJpegInfo(copy.pageFiles.single().readBytes()) }.getOrNull()
        )
        val geo = repo.ocrStore.readGeo(copy.id)[copy.pageFiles.single().name]
        assertEquals("geometry rescaled on the upright page", copyInfo.width, geo?.width)
        assertEquals(copyInfo.height, geo?.height)
        assertTrue("the copy is still marked searchable", File(repo.docsDir, copy.id + "/text_layer.flag").isFile)
    }
}
