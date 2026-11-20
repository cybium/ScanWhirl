package com.scanwhirl.app.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.util.PageRanges
import com.scanwhirl.app.util.Prefs
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

/**
 * H3-5 (bug hunt 2026-11-20): Compress, Split and Extract published copies of the pages but
 * dropped the document's recognised text and its invisible PDF text layer â€” the copy only became
 * searchable after a restart and a background re-recognition, and a part shared right away was
 * image-only. A duplicate kept them.
 *
 * The rule these tests pin: compression changes pixels, not words; splitting/extracting only
 * renumbers pages, so the words (and their line boxes, when the pixels kept their size) travel
 * with them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ToolOutputKeepsTextTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
        repo = ScanRepository(app)
        // The honest default: searchable PDFs on. A copy must then carry the layer immediately.
        Prefs.setMakeSearchable(app, true)
    }

    /** The PDF's selectable text (what a portal or a search reads out of the file). */
    private fun pdfText(file: File): String =
        org.apache.pdfbox.Loader.loadPDF(file).use {
            org.apache.pdfbox.text.PDFTextStripper().getText(it)
        }

    /**
     * Recognised text for every page, the way the indexer writes it: the page text (what search
     * reads) and one line with its own words and box (what the PDF text layer embeds).
     */
    private fun withRecognisedText(id: String) {
        val doc = requireNotNull(repo.getDocument(id)) { "fixture $id not found" }
        doc.pageFiles.forEach { file ->
            val (w, h) = requireNotNull(
                com.scanwhirl.app.util.PageReplace.bounds(file)
            ) { "unreadable page ${file.name}" }
            repo.ocrStore.putPage(
                id,
                file.name,
                "text ${file.name}",
                com.scanwhirl.app.model.OcrPage(
                    w, h,
                    listOf(
                        com.scanwhirl.app.model.OcrLine(
                            "line of ${file.name}", 2, 2, maxOf(3, w - 2), maxOf(4, h / 8)
                        )
                    )
                )
            )
        }
    }

    @Test
    fun `split parts keep the recognised text and the text layer`() {
        val id = DocFixtures.saved(repo, app, pages = 4, name = "Searchable book")
        withRecognisedText(id)

        val parts = runBlocking {
            repo.splitByRange(id, listOf(PageRanges.Span(1, 2), PageRanges.Span(3, 4)))
        }

        assertEquals(2, parts.size)
        assertEquals(
            "part 1 keeps its own two pages' text",
            mapOf("page_000.jpg" to "text page_000.jpg", "page_001.jpg" to "text page_001.jpg"),
            repo.ocrDoc(parts[0].id)
        )
        assertEquals(
            "part 2 keeps the text of the pages it holds, under its own page numbers",
            mapOf("page_000.jpg" to "text page_002.jpg", "page_001.jpg" to "text page_003.jpg"),
            repo.ocrDoc(parts[1].id)
        )

        parts.forEachIndexed { index, part ->
            val label = "part ${index + 1}"
            assertTrue("$label is searchable immediately", repo.hasOcr(part.id))
            assertFalse(
                "$label must not wait for a restart to be recognised",
                repo.ocrStore.isPending(part.id)
            )
            assertTrue(
                "$label still declares its text layer",
                File(File(repo.docsDir, part.id), "text_layer.flag").isFile
            )
        }
        val first = pdfText(parts[0].pdfFile)
        assertTrue("part 1 lost its text layer: $first", first.contains("line of page_000.jpg"))
        assertFalse("part 1 must not carry pages it does not hold", first.contains("line of page_003.jpg"))
        val second = pdfText(parts[1].pdfFile)
        assertTrue("part 2 lost its text layer: $second", second.contains("line of page_002.jpg"))
        assertFalse("part 2 must not carry pages it does not hold", second.contains("line of page_000.jpg"))
    }

    @Test
    fun `extract keeps the text of exactly the chosen pages`() {
        val id = DocFixtures.saved(repo, app, pages = 4, name = "Extract text")
        withRecognisedText(id)

        val part = runBlocking { repo.extractPages(id, listOf(0, 2)) }

        assertEquals(
            "the extracted pages are renumbered and keep their words",
            mapOf(
                "page_000.jpg" to "text page_000.jpg",
                "page_001.jpg" to "text page_002.jpg"
            ),
            repo.ocrDoc(part.id)
        )
        assertTrue("the extract is searchable immediately", repo.hasOcr(part.id))
        assertFalse("and not queued for a re-read", repo.ocrStore.isPending(part.id))
        val text = pdfText(part.pdfFile)
        assertTrue("the extract lost its text layer: $text", text.contains("line of page_000.jpg"))
        assertTrue("the third page's text is missing: $text", text.contains("line of page_002.jpg"))
        assertFalse("an unselected page's text leaked: $text", text.contains("line of page_001.jpg"))
    }

    @Test
    fun `compress keeps the words and the line boxes when the pixels keep their size`() {
        val id = DocFixtures.saved(repo, app, pages = 3, name = "Compress text")
        withRecognisedText(id)

        val copy = runBlocking { repo.compressScan(id, quality = 60, maxDim = null) }

        assertEquals(
            "every page's text survives compression",
            mapOf(
                "page_000.jpg" to "text page_000.jpg",
                "page_001.jpg" to "text page_001.jpg",
                "page_002.jpg" to "text page_002.jpg"
            ),
            repo.ocrDoc(copy.id)
        )
        assertTrue("the copy is searchable immediately", repo.hasOcr(copy.id))
        assertFalse("and not queued for a re-read", repo.ocrStore.isPending(copy.id))
        assertTrue(
            "a re-encode at the same pixel size keeps valid line boxes",
            repo.ocrGeometry(copy.id).isNotEmpty()
        )
        assertTrue(
            "and the copy still declares its text layer",
            File(File(repo.docsDir, copy.id), "text_layer.flag").isFile
        )
        val text = pdfText(copy.pdfFile)
        assertTrue(
            "the compressed copy lost its text layer: $text",
            text.contains("line of page_001.jpg")
        )
    }

    @Test
    fun `a downscaled compress keeps the words but never the stale line boxes`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Downscale text")
        withRecognisedText(id)

        // maxDim 100 halves the 200..220 px pages: the words stay, the coordinates cannot.
        val copy = runBlocking { repo.compressScan(id, quality = 60, maxDim = 100) }

        assertEquals(
            "the words survive a downscale",
            mapOf(
                "page_000.jpg" to "text page_000.jpg",
                "page_001.jpg" to "text page_001.jpg"
            ),
            repo.ocrDoc(copy.id)
        )
        assertTrue(
            "boxes measured on the old pixels must not be carried",
            repo.ocrGeometry(copy.id).isEmpty()
        )
        assertFalse(
            "no text layer may be claimed for the resized pages",
            File(File(repo.docsDir, copy.id), "text_layer.flag").exists()
        )
        assertFalse(
            "the text is already known, so no re-read is queued",
            repo.ocrStore.isPending(copy.id)
        )
    }
}
