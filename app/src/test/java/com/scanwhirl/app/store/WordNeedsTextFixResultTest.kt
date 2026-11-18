package com.scanwhirl.app.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import com.scanwhirl.app.util.OcrBox
import com.scanwhirl.app.util.OcrEngine
import com.scanwhirl.app.util.OcrRawLine
import com.scanwhirl.app.util.OcrRawPage
import com.scanwhirl.app.util.OcrScript
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.zip.ZipFile

/**
 * N2 (found by the lead on the screen map): picking a document that has no recognised text yet
 * and tapping Continue in To Word failed with "Could not finish: Read the text first, then
 * convert it to Word". The tool must instead read the pages that have no text now (the app's own
 * on-device recognition, the same engine the Text screen and OcrIndexer use) and then convert.
 *
 * What these tests pin:
 *  - the Word export recognises only the pages that have no text, and converts them;
 *  - the recognised text is kept in the document's OCR store, so Text shows it too;
 *  - when nothing can be read the export still fails with the honest message and writes nothing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class WordNeedsTextFixResultTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository
    private lateinit var work: File
    private val readPages = mutableListOf<String>()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        work = File(app.cacheDir, "fixresult_word_${System.nanoTime()}").apply { mkdirs() }
        readPages.clear()
        // ML Kit cannot run in the JVM: this is the same seam the OCR tests use.
        OcrEngine.rawReader = { page, _ ->
            synchronized(readPages) { readPages += page.name }
            OcrRawPage(
                600, 800,
                listOf(
                    OcrRawLine(
                        "recognised ${page.name.take(8)}", OcrBox(20, 40, 560, 70), OcrScript.LATIN
                    )
                )
            )
        }
    }

    @After
    fun tearDown() {
        OcrEngine.rawReader = null
        work.deleteRecursively()
    }

    @Test
    fun `to word reads the pages that have no text and converts them`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Unread memo")
        assertTrue("the fixture has no text yet", repo.ocrDoc(id).isEmpty())

        val out = File(work, "Unread memo.docx")
        runBlocking { repo.exportWord(id, out) }

        assertTrue("N2: the .docx must exist", out.exists())
        val document = documentXml(out)
        assertTrue(
            "N2: the page recognised now must be in the document: $document",
            document.contains("recognised page_000")
        )
        assertTrue(
            "N2: every page is recognised: $document",
            document.contains("recognised page_001")
        )
        assertEquals("one recognition per page", 2, readPages.size)

        // The text stays in the store: the Text screen must show what the export read.
        val stored = repo.ocrDoc(id)
        assertTrue("page 1 text must be stored: ${stored.keys}", stored["page_000.jpg"]!!.contains("recognised page_000"))
        assertTrue("page 2 text must be stored", stored["page_001.jpg"]!!.contains("recognised page_001"))
    }

    @Test
    fun `to word never re-reads a page the user already has`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Half read")
        val pages = repo.getDocument(id)!!.pageFiles
        repo.ocrStore.putPage(
            id, pages[0].name, "hand corrected line",
            OcrPage(200, 300, listOf(OcrLine("hand corrected line", 1, 1, 10, 10)))
        )
        readPages.clear()

        val out = File(work, "Half read.docx")
        runBlocking { repo.exportWord(id, out) }

        assertEquals("only the page without text may be read", listOf(pages[1].name), readPages)
        val document = documentXml(out)
        assertTrue("the existing text is kept: $document", document.contains("hand corrected line"))
        assertTrue("the page read now is added: $document", document.contains("recognised page_001"))
        assertEquals("corrected text must not be overwritten", "hand corrected line", repo.ocrDoc(id)[pages[0].name])
    }

    @Test
    fun `to word still says so when nothing can be read`() {
        OcrEngine.rawReader = { _, _ -> throw IllegalStateException("text model is missing") }
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Unreadable")
        val out = File(work, "Unreadable.docx")

        val failure = runCatching { runBlocking { repo.exportWord(id, out) } }

        assertTrue("N2: an unreadable document must still fail", failure.isFailure)
        assertEquals(
            "the honest message stays",
            "Read the text first, then convert it to Word.",
            failure.exceptionOrNull()?.message
        )
        assertFalse("no half written .docx may be left", out.exists())
        assertNull("nothing is invented as text", repo.ocrDoc(id)["page_000.jpg"])
    }

    private fun documentXml(docx: File): String = ZipFile(docx).use { zip ->
        val entry = zip.getEntry("word/document.xml") ?: error("no word/document.xml in ${docx.name}")
        zip.getInputStream(entry).use { String(it.readBytes(), Charsets.UTF_8) }
    }
}
