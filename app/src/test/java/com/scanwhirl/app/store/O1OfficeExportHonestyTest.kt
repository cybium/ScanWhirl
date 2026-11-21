package com.scanwhirl.app.store

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import com.scanwhirl.app.util.OcrBox
import com.scanwhirl.app.util.OcrEngine
import com.scanwhirl.app.util.OcrLanguage
import com.scanwhirl.app.util.OcrRawLine
import com.scanwhirl.app.util.OcrRawPage
import com.scanwhirl.app.util.OcrScript
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

/**
 * O1 office honesty, data-safe core. Every case uses synthetic pages and fictional text.
 * Most cases were RED on the original base commit. The two tests named "regression guard"
 * already passed on their base and are kept only to stop that data or wording being lost.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class O1OfficeExportHonestyTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository
    private lateinit var work: File

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        work = File(app.cacheDir, "o1_${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        OcrEngine.rawReader = null
        work.deleteRecursively()
    }

    // ---- Defect 1: an unreadable Word page is marked, not silently empty ----

    @Test
    fun `word marks the page it could not read and reports its count`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Half readable")
        val pages = repo.getDocument(id)!!.pageFiles
        repo.ocrStore.putPage(id, pages[0].name, "kept line", OcrPage(200, 300, listOf(OcrLine("kept line", 1, 1, 80, 20))))
        val unread = pages[1]
        OcrEngine.rawReader = { file, _ ->
            if (file.name == unread.name) throw IllegalStateException("text model is missing")
            OcrRawPage(200, 300, listOf(OcrRawLine("fresh line", OcrBox(10, 10, 100, 30), OcrScript.LATIN)))
        }

        val out = File(work, "half.docx")
        var reportedUnread: List<Int> = emptyList()
        runBlocking { repo.exportWord(id, out) { reportedUnread = it } }
        val document = ZipFile(out).use { zip ->
            zip.getInputStream(zip.getEntry("word/document.xml")).use { String(it.readBytes(), Charsets.UTF_8) }
        }
        assertTrue("the docx must name the unread page: $document", document.contains("Page 2"))
        assertTrue("the docx must say it could not be read: $document", document.contains("could not be read"))
        assertEquals(listOf(1), reportedUnread)
    }

    // ---- Defects 4 & 6 & 7: Excel honesty ----

    /** Regression guard, not RED on base: the report already carried these indices. */
    @Test
    fun `regression guard tables only all unread keeps the unread indices in the report`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Unreadable")
        OcrEngine.rawReader = { _, _ -> throw IllegalStateException("text model is missing") }

        val report = runBlocking {
            repo.exportExcel(id, File(work, "none.xlsx"), tablesOnly = true, noTableNote = "No table here")
        }

        assertEquals("nothing is written", null, report.file)
        assertEquals(listOf(0, 1), report.unreadPages)
        assertEquals(2, report.totalPages)
    }

    /**
     * Regression guard, not RED on fa2c27c7: that commit already made the default a resource.
     * Pins that a text-only page with no table uses the resource note, never an injected one.
     */
    @Test
    fun `regression guard excel default no table note is the resource note`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Plain page")
        val page = repo.getDocument(id)!!.pageFiles.single()
        // Stored-but-empty text without geometry: no unread page and no lines, so the default
        // "no table" note is the only cell.
        repo.ocrStore.putTexts(id, mapOf(page.name to ""))

        val out = File(work, "default.xlsx")
        runBlocking { repo.exportExcel(id, out) }
        val sheet = ZipFile(out).use { zip ->
            zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).use {
                String(it.readBytes(), Charsets.UTF_8)
            }
        }
        assertTrue(
            "the default note must be the resource text: $sheet",
            sheet.contains(app.getString(R.string.tools_excel_no_table))
        )
    }

    // ---- Defect 8: makeSearchable must not rewrite a partially layered PDF ----

    @Test
    fun `make searchable leaves scan pdf untouched when only some pages have a layer`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Partial")
        val pages = repo.getDocument(id)!!.pageFiles
        repo.ocrStore.putPage(id, pages[0].name, "layer one", OcrPage(200, 300, listOf(OcrLine("layer one", 1, 1, 80, 20))))
        val pdf = File(File(repo.docsDir, id), "scan.pdf")
        val sentinel = "PARTIAL LAYER MUST NOT OVERWRITE".toByteArray()
        pdf.writeBytes(sentinel)

        val available = runBlocking { repo.makeSearchable(id) }

        assertFalse("a partial layer is not a searchable PDF", available)
        assertTrue(
            "makeSearchable must not rewrite scan.pdf with partial layers",
            sentinel.contentEquals(pdf.readBytes())
        )
    }

    // ---- Defect 3: re-recognition must not lose text on a failing page ----

    @Test
    fun `a failing page keeps its old text and does not stop later pages`() {
        val id = DocFixtures.saved(repo, app, pages = 3, name = "Re-read")
        val pages = repo.getDocument(id)!!.pageFiles
        pages.forEachIndexed { i, page ->
            repo.ocrStore.putPage(id, page.name, "old $i", OcrPage(200, 300, listOf(OcrLine("old $i", 1, 1, 80, 20))))
        }
        val attempted = mutableListOf<String>()
        OcrEngine.rawReader = { file, _ ->
            synchronized(attempted) { attempted += file.name }
            if (file.name == pages[1].name) throw IllegalStateException("text model is missing")
            OcrRawPage(200, 300, listOf(OcrRawLine("fresh", OcrBox(10, 10, 100, 30), OcrScript.LATIN)))
        }

        val stored = runBlocking { OcrReindex(repo).reRecognise(id, OcrLanguage.ENGLISH) }

        val texts = repo.ocrDoc(id)
        assertEquals("old 1", texts[pages[1].name])
        assertTrue("the later page must be re-read too: $attempted", pages[2].name in attempted)
        assertTrue("the later page must have fresh text: $texts", texts[pages[2].name]?.contains("fresh") == true)
        assertTrue("the successful page must have fresh text: $texts", texts[pages[0].name]?.contains("fresh") == true)
        assertEquals("two pages were recognised", 2, stored)
    }

    // ---- New strings exist in both languages (no hard-coded English) ----

    @Test
    fun `all new office honesty strings exist in english and hindi`() {
        val names = mapOf(
            "tools_word_page_unread" to "string",
            "tools_unread_pages" to "plurals",
            "tools_excel_unread_all_title" to "string",
            "tools_excel_unread_all_msg" to "string",
            "tools_result_pptx_no_text_note" to "string"
        )
        for ((name, type) in names) {
            val en = locale("en")
            val hi = locale("hi")
            val enId = en.resources.getIdentifier(name, type, en.packageName)
            val hiId = hi.resources.getIdentifier(name, type, hi.packageName)
            assertNotNull("missing English $name", enId.takeIf { it != 0 })
            assertNotNull("missing Hindi $name", hiId.takeIf { it != 0 })
        }
    }

    private fun locale(language: String): Context {
        val config = Configuration(app.resources.configuration)
        config.setLocale(Locale(language))
        return app.createConfigurationContext(config)
    }

}
