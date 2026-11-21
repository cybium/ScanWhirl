package com.scanwhirl.app.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.mlkit.common.MlKitException
import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import com.scanwhirl.app.util.OcrBox
import com.scanwhirl.app.util.OcrEngine
import com.scanwhirl.app.util.OcrLanguage
import com.scanwhirl.app.util.OcrRawLine
import com.scanwhirl.app.util.OcrRawPage
import com.scanwhirl.app.util.OcrScript
import com.scanwhirl.app.util.PageReplace
import com.scanwhirl.app.util.Prefs
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Independent review of fa2c27c7. RED on that commit:
 *  - a re-read refreshed the stored OCR but never rebuilt the searchable PDF, so the old text
 *    layer stayed in scan.pdf;
 *  - a mixed run (some pages read, one page's text model unavailable) returned a partial success
 *    and lost the "unavailable" reason the Text screen needs.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class O1ReviewFixTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository
    private lateinit var work: File

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        work = File(app.cacheDir, "o1_review_${System.nanoTime()}").apply { mkdirs() }
        Prefs.setMakeSearchable(app, true)
    }

    @After
    fun tearDown() {
        OcrEngine.rawReader = null
        Prefs.setMakeSearchable(app, true)
        work.deleteRecursively()
    }

    private fun pdfText(id: String): String =
        Loader.loadPDF(File(File(repo.docsDir, id), "scan.pdf")).use { PDFTextStripper().getText(it) }

    private fun seedOldLayer(id: String) {
        val page = repo.getDocument(id)!!.pageFiles.single()
        val (w, h) = requireNotNull(PageReplace.bounds(page))
        repo.ocrStore.putPage(
            id, page.name, "OLD LINE",
            OcrPage(w, h, listOf(OcrLine("OLD LINE", 20, 40, 200, 70)))
        )
        runBlocking { repo.refreshPdfAfterIndex(id) }
        assertTrue("the fixture must start with the old text in the PDF", pdfText(id).contains("OLD LINE"))
    }

    private fun freshReader() {
        OcrEngine.rawReader = { _, _ ->
            OcrRawPage(
                200, 300,
                listOf(OcrRawLine("NEW LINE", OcrBox(20, 40, 200, 70), OcrScript.LATIN))
            )
        }
    }

    @Test
    fun `confirmed read again rebuilds the searchable pdf with the new text`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Confirmed re-read")
        seedOldLayer(id)
        freshReader()

        runBlocking { OcrReindex(repo).reRecognise(id, OcrLanguage.ENGLISH, replaceCorrections = true) }

        val text = pdfText(id)
        assertTrue("the PDF text layer must hold the new text: $text", text.contains("NEW LINE"))
        assertFalse("the old text layer must be gone: $text", text.contains("OLD LINE"))
    }

    @Test
    fun `one shot hindi read rebuilds the searchable pdf with the new text`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "One-shot read")
        seedOldLayer(id)
        freshReader()

        runBlocking { OcrReindex(repo).reRecognise(id, OcrLanguage.ENGLISH, replaceCorrections = false) }

        val text = pdfText(id)
        assertTrue("the PDF text layer must hold the new text: $text", text.contains("NEW LINE"))
        assertFalse("the old text layer must be gone: $text", text.contains("OLD LINE"))
    }

    @Test
    fun `a mixed run with an unavailable model is reported as unavailable, not partial success`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Mixed availability")
        OcrEngine.rawReader = { file, _ ->
            if (file.name == "page_000.jpg") {
                OcrRawPage(
                    200, 300,
                    listOf(OcrRawLine("FRESH LINE", OcrBox(20, 40, 200, 70), OcrScript.LATIN))
                )
            } else {
                throw MlKitException(
                    "Waiting for the text recognition model to be downloaded.",
                    MlKitException.UNAVAILABLE
                )
            }
        }

        val result = runCatching {
            runBlocking { OcrReindex(repo).reRecognise(id, OcrLanguage.ENGLISH) }
        }

        assertTrue("a page with an unavailable model must not be reported as a clean partial read", result.isFailure)
        assertTrue(
            "the caller must receive the retryable unavailable reason",
            OcrEngine.isUnavailable(result.exceptionOrNull() ?: error("no exception"))
        )
    }
}
