package com.scanwhirl.app.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import com.scanwhirl.app.util.PageReplace
import com.scanwhirl.app.util.Prefs
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.After
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
import java.util.Locale

/**
 * H4-1/H4-3: "Make searchable" claimed a Hindi document was searchable while the PDF carried no
 * Devanagari at all, because PdfTextLayer skipped every line whose *sanitised* fallback was blank.
 *
 * These tests read the produced PDF with PDFBox, an independent extractor (PDFBox is a test-only
 * oracle and is never shipped in the APK), so the claim is checked the way a user's PDF app would.
 * The Marathi-locale test covers the page-name arithmetic: with Devanagari digits no geometry key
 * matched any page file, so the layer was silently skipped and the flag was never written.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SearchablePdfHindiTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository
    private lateinit var work: File
    private lateinit var originalLocale: Locale

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        work = File(app.cacheDir, "f9text_searchable_${System.nanoTime()}").apply { mkdirs() }
        originalLocale = Locale.getDefault()
        Prefs.setMakeSearchable(app, true)
    }

    @After
    fun tearDown() {
        Locale.setDefault(originalLocale)
        Prefs.setMakeSearchable(app, true)
        work.deleteRecursively()
    }

    private fun hindiDoc(pages: Int, name: String): String {
        val id = DocFixtures.saved(repo, app, pages = pages, name = name)
        repo.getDocument(id)!!.pageFiles.forEachIndexed { index, page ->
            val (w, h) = requireNotNull(PageReplace.bounds(page)) { "unreadable page ${page.name}" }
            repo.ocrStore.putPage(
                id,
                page.name,
                "कुल राशि\nबकाया शून्य",
                OcrPage(
                    w, h,
                    listOf(
                        OcrLine("कुल राशि", 20, 40, 560, 70),
                        OcrLine("बकाया शून्य", 20, 120, 560, 160)
                    )
                )
            )
        }
        return id
    }

    private fun pdfText(id: String): String = Loader.loadPDF(File(File(repo.docsDir, id), "scan.pdf")).use {
        PDFTextStripper().getText(it)
    }

    @Test
    fun `a pure hindi document really is searchable after make searchable`() {
        val id = hindiDoc(pages = 1, name = "Hindi bill")

        assertTrue("the document must report itself searchable", repo.makeSearchable(id))

        val text = pdfText(id)
        assertTrue("H4-1: the extracted PDF text must contain the Devanagari line: $text", text.contains("कुल राशि"))
        assertTrue("H4-1: every Hindi line must survive: $text", text.contains("बकाया शून्य"))
        assertTrue(
            "the flag is honest: the PDF carries real text",
            File(File(repo.docsDir, id), "text_layer.flag").exists()
        )
    }

    @Test
    fun `hindi text layer is written with a devanagari digit device locale`() {
        // H4-3: mr-IN formats %03d with Devanagari digits, so "page_%03d.jpg".format(index) no
        // longer matched any stored geometry key and the whole layer was silently skipped.
        Locale.setDefault(Locale.forLanguageTag("mr-IN"))
        val id = hindiDoc(pages = 1, name = "Marathi phone")

        assertTrue("the layer must not depend on the device locale", repo.makeSearchable(id))
        assertTrue("the Hindi text must be in the PDF", pdfText(id).contains("कुल राशि"))
    }

    @Test
    fun `a page whose lines emit nothing does not claim a text layer`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Blank read")
        val page = repo.getDocument(id)!!.pageFiles.single()
        val (w, h) = requireNotNull(PageReplace.bounds(page))
        repo.ocrStore.putPage(id, page.name, "   ", OcrPage(w, h, listOf(OcrLine("   ", 0, 0, 10, 10))))

        runBlocking { repo.refreshPdfAfterIndex(id) }

        assertFalse(
            "a page that emitted no text must not be flagged searchable",
            File(File(repo.docsDir, id), "text_layer.flag").exists()
        )
        assertEquals("no text was written into the PDF", "", pdfText(id).trim())
    }
}
