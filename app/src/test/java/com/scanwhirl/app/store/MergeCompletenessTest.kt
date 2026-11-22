package com.scanwhirl.app.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import com.scanwhirl.app.util.ImportFixtures
import com.scanwhirl.app.util.PageReplace
import com.scanwhirl.app.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
import java.util.UUID

/**
 * FF-M — Merge completeness and OCR retention (P1).
 *
 * The audit found `mergeDocuments` filtered missing/empty source pages out (`filter { exists &&
 * length > 0 }`), published a shorter PDF without telling anyone, and dropped the source OCR map.
 * These tests pin the opposite: exact page count or nothing; ordered, source-mapped EN+HI text on
 * every output page; a changed source or a cancel aborts before publication; the sources never
 * change. A later FF-S lane owns the external object-preserving path, so device-PDF merging is
 * deliberately out of scope here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MergeCompletenessTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    private val enToken = "invoice"
    private val hiToken = "कुल"

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
        repo = ScanRepository(app)
        Prefs.setMakeSearchable(app, true)
    }

    @After
    fun tearDown() = Faults.resetAll()

    /** A saved document whose pages carry a visible fictional id and both scripts in OCR. */
    private fun source(name: String, labels: List<String>): String {
        val staging = File(app.cacheDir, "ffm_${UUID.randomUUID()}").apply { mkdirs() }
        val files = labels.mapIndexed { i, _ ->
            File(staging, "p$i.jpg").apply {
                writeBytes(ImportFixtures.image(width = 200 + 10 * i, height = 300))
            }
        }
        val id = runBlocking { repo.saveScan(files.map { android.net.Uri.fromFile(it) }, null, name) }.id
        val doc = requireNotNull(repo.getDocument(id)) { "fixture $id not saved" }
        doc.pageFiles.forEachIndexed { i, page ->
            val (w, h) = requireNotNull(PageReplace.bounds(page)) { "unreadable page ${page.name}" }
            val text = "${labels[i]} $enToken $hiToken"
            repo.ocrStore.putPage(
                id,
                page.name,
                text,
                OcrPage(w, h, listOf(OcrLine(text, 4, 4, maxOf(8, w - 4), maxOf(8, h / 8))))
            )
        }
        return id
    }

    private fun pdfText(file: File): String = Loader.loadPDF(file).use {
        PDFTextStripper().getText(it)
    }

    private fun labelOf(text: String): String = text.substringBefore(' ')

    @Test
    fun `two plus three pages merge into five with visible ids and both scripts on every page`() {
        val a = source("Class 10", listOf("A1", "A2"))
        val b = source("Income certificate", listOf("B1", "B2", "B3"))

        val merged = runBlocking { repo.mergeDocuments(listOf(a, b), "Evidence pack") }

        assertEquals("2 + 3 source pages must make 5", 5, merged.pageCount)
        assertEquals("the PDF agrees with the page files", 5, PdfInvariants.pageTreeCount(merged.pdfFile))

        val texts = repo.ocrDoc(merged.id)
        assertEquals(
            "output pages keep the user's order and their source ids",
            listOf("A1", "A2", "B1", "B2", "B3"),
            merged.pageFiles.map { labelOf(texts.getValue(it.name)) }
        )
        merged.pageFiles.forEach { page ->
            val text = texts.getValue(page.name)
            assertTrue("$page lost English: $text", text.contains(enToken))
            assertTrue("$page lost Hindi: $text", text.contains(hiToken))
        }
        val fromPdf = pdfText(merged.pdfFile)
        listOf("A1", "A2", "B1", "B2", "B3").forEach { id ->
            assertTrue("PDF lost $id: $fromPdf", fromPdf.contains(id))
        }
        assertTrue("PDF lost English text", fromPdf.contains(enToken))
        assertTrue("PDF lost Hindi text", fromPdf.contains(hiToken))
        assertTrue("the merged copy is searchable right away", repo.hasOcr(merged.id))
        assertFalse("no re-recognition was queued", repo.ocrStore.isPending(merged.id))
        assertTrue(
            "the text layer is still declared",
            File(File(repo.docsDir, merged.id), "text_layer.flag").isFile
        )
    }

    @Test
    fun `a missing source page fails with no four page success`() {
        val a = source("Class 10", listOf("A1", "A2"))
        val b = source("Income certificate", listOf("B1", "B2", "B3"))
        // B2 is gone from disk; a 5-page merge is impossible.
        assertTrue(File(File(repo.docsDir, b), "page_001.jpg").delete())
        val before = repo.listDocuments().size

        val result = runCatching { runBlocking { repo.mergeDocuments(listOf(a, b), "Evidence pack") } }

        assertTrue("a missing page must abort the merge", result.isFailure)
        assertEquals(
            "the failure names the real reason",
            app.getString(R.string.ffm_merge_missing_page),
            result.exceptionOrNull()?.message
        )
        assertEquals("nothing was published", before, repo.listDocuments().size)
        PdfInvariants.assertNoOrphans(app, repo)
    }

    @Test
    fun `user selected order wins over library order`() {
        val a = source("Class 10", listOf("A1", "A2"))
        val b = source("Income certificate", listOf("B1", "B2", "B3"))
        // Library order is A then B, but the person picked B first.
        val merged = runBlocking { repo.mergeDocuments(listOf(b, a), "B then A") }
        val texts = repo.ocrDoc(merged.id)
        assertEquals(
            "merge must never re-sort the chosen documents",
            listOf("B1", "B2", "B3", "A1", "A2"),
            merged.pageFiles.map { labelOf(texts.getValue(it.name)) }
        )
    }

    @Test
    fun `a source page changed mid run aborts and publishes nothing`() {
        val a = source("Class 10", listOf("A1", "A2"))
        val b = source("Income certificate", listOf("B1", "B2", "B3"))
        val before = repo.listDocuments().size
        try {
            OpFaults.hook = { step ->
                if (step == "op:page:1") {
                    File(File(repo.docsDir, b), "page_001.jpg").writeBytes(
                        ImportFixtures.image(width = 999, height = 999)
                    )
                }
            }
            val result = runCatching {
                runBlocking { repo.mergeDocuments(listOf(a, b), "Evidence pack") }
            }
            assertTrue("a source changing mid-run must abort", result.isFailure)
            assertEquals(
                "the failure names the real reason",
                app.getString(R.string.ffm_merge_source_changed),
                result.exceptionOrNull()?.message
            )
            assertEquals("no partial document was published", before, repo.listDocuments().size)
            PdfInvariants.assertNoOrphans(app, repo)
        } finally {
            OpFaults.reset()
        }
    }

    @Test
    fun `cancel mid merge publishes nothing and leaves no staging`() {
        val a = source("Class 10", listOf("A1", "A2"))
        val b = source("Income certificate", listOf("B1", "B2", "B3"))
        val before = repo.listDocuments().size
        try {
            var job: kotlinx.coroutines.Job? = null
            OpFaults.hook = { step -> if (step == "op:page:0") job?.cancel() }
            runBlocking {
                job = async(Dispatchers.IO) { repo.mergeDocuments(listOf(a, b), "Evidence pack") }
                runCatching { job!!.await() }
            }
            assertEquals("a cancelled merge publishes nothing", before, repo.listDocuments().size)
            assertTrue(
                "staging is released on cancel",
                File(app.filesDir, "op_staging").list().orEmpty().isEmpty()
            )
            PdfInvariants.assertClean(app, repo)
        } finally {
            OpFaults.reset()
        }
    }

    @Test
    fun `sources stay byte identical after a successful merge`() {
        val a = source("Class 10", listOf("A1", "A2"))
        val b = source("Income certificate", listOf("B1", "B2", "B3"))
        val digestA = PdfInvariants.dirDigest(File(repo.docsDir, a))
        val digestB = PdfInvariants.dirDigest(File(repo.docsDir, b))

        val merged = runBlocking { repo.mergeDocuments(listOf(a, b), "Evidence pack") }

        assertEquals("source A was modified", digestA, PdfInvariants.dirDigest(File(repo.docsDir, a)))
        assertEquals("source B was modified", digestB, PdfInvariants.dirDigest(File(repo.docsDir, b)))
        assertEquals(5, merged.pageCount)
        PdfInvariants.assertClean(app, repo)
    }
}
