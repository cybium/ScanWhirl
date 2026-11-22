package com.scanwhirl.app.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import com.scanwhirl.app.util.PageReplace
import com.scanwhirl.app.util.Prefs
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

/**
 * FF-O3 (P1): the explicit "OCR PDF / Make searchable" action must produce a real, searchable
 * copy independently of the global Settings preference. That preference governs only automatic
 * behaviour (save / refreshPdfAfterIndex), never the user's explicit action.
 *
 * Text is read back with PDFBox, an independent extractor that is test-only and never shipped.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class FfO3SearchablePrefIndependenceTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository
    private lateinit var work: File

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        work = File(app.cacheDir, "ffo3_${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        Prefs.setMakeSearchable(app, true)
        work.deleteRecursively()
    }

    /** A saved 2-page document, every page carrying EN + Devanagari OCR geometry. */
    private fun bilingualDoc(name: String): String {
        val id = DocFixtures.saved(repo, app, pages = 2, name = name)
        repo.getDocument(id)!!.pageFiles.forEach { page ->
            val (w, h) = requireNotNull(PageReplace.bounds(page)) { "unreadable page ${page.name}" }
            repo.ocrStore.putPage(
                id,
                page.name,
                "Ravi Kumar\nरवि कुमार ₹1250",
                OcrPage(
                    w, h,
                    listOf(
                        OcrLine("Ravi Kumar", 20, 40, 560, 70),
                        OcrLine("रवि कुमार ₹1250", 20, 120, 560, 160)
                    )
                )
            )
        }
        return id
    }

    private fun pdfPageText(id: String, page: Int): String {
        val file = File(File(repo.docsDir, id), "scan.pdf")
        return Loader.loadPDF(file).use { doc ->
            PDFTextStripper().apply {
                startPage = page
                endPage = page
            }.getText(doc)
        }
    }

    private fun pdfText(id: String): String = Loader.loadPDF(File(File(repo.docsDir, id), "scan.pdf")).use {
        PDFTextStripper().getText(it)
    }

    @Test
    fun `explicit OCR PDF writes an EN plus Devanagari text layer on every page with preference OFF`() {
        Prefs.setMakeSearchable(app, false)
        val id = bilingualDoc("FF-O3 pref off")

        assertTrue("the explicit action must succeed with the global preference off", repo.makeSearchable(id))

        for (page in 1..2) {
            val text = pdfPageText(id, page)
            assertTrue("page $page must contain the Latin name: $text", text.contains("Ravi Kumar"))
            assertTrue("page $page must contain the Devanagari line: $text", text.contains("रवि कुमार ₹1250"))
        }
        assertTrue(
            "flag must be honest: real text is in the PDF",
            File(File(repo.docsDir, id), "text_layer.flag").exists()
        )
    }

    @Test
    fun `the explicit tool result does not depend on the global preference`() {
        Prefs.setMakeSearchable(app, false)
        val offId = bilingualDoc("FF-O3 off")
        assertTrue("explicit action with preference off", repo.makeSearchable(offId))
        val offText = pdfText(offId)

        Prefs.setMakeSearchable(app, true)
        val onId = bilingualDoc("FF-O3 on")
        assertTrue("explicit action with preference on", repo.makeSearchable(onId))
        val onText = pdfText(onId)

        assertEquals("preference must not change the text-layer result", offText, onText)
    }

    @Test
    fun `automatic refresh still respects the global preference`() {
        // The preference must keep governing automatic behaviour: with it off, an index refresh
        // must NOT add a layer, while the explicit action in the tests above still does.
        Prefs.setMakeSearchable(app, false)
        val id = bilingualDoc("FF-O3 automatic")
        repo.refreshPdfAfterIndex(id)

        assertFalse(
            "automatic refresh must stay a no-op when the preference is off",
            File(File(repo.docsDir, id), "text_layer.flag").exists()
        )
        assertEquals("automatic refresh must not add text with the preference off", "", pdfText(id).trim())
    }
}
