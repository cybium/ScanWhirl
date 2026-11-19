package com.scanwhirl.app.store

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import com.scanwhirl.app.util.ImportFixtures
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * T38: a two-column page (and a page with a narrow sidebar) exports to Word with the columns
 * read one after the other instead of interleaved line by line (QA 2026-11-19, case 5 FAIL and
 * case 8 PARTIAL).
 *
 * The Word export writes one paragraph per recognised line from the stored OCR text. The stored
 * text was assembled in the order the recogniser produced, which for a page with a full-width
 * title puts the two columns in the same rows (the historical `OcrLayout` cut cannot pass a
 * line that spans the gutter). Where the stored geometry still belongs to the stored text, the
 * export now orders those lines with [com.scanwhirl.app.util.ReadingOrder]. Nothing else about
 * the file changes: same paragraphs, same page breaks, same text.
 *
 * These tests use fictional geometry on a synthetic page image, never owner media.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class WordReadingOrderTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository
    private lateinit var work: File

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        work = File(app.cacheDir, "word_order_${UUID.randomUUID()}").apply { mkdirs() }
    }

    // ---------------------------------------------------------------- fixtures

    /** A saved one-page document whose page image is [width] x [height]. */
    private fun page(width: Int = 1000, height: Int = 1400): Pair<String, OcrPage> {
        val staging = File(work, "src_${UUID.randomUUID()}").apply { mkdirs() }
        val file = File(staging, "p0.jpg").apply { writeBytes(ImportFixtures.image(width = width, height = height)) }
        val id = runBlocking { repo.saveScan(listOf(Uri.fromFile(file)), null, "Two column memo") }.id
        return id to OcrPage(width, height, emptyList())
    }

    /** One recognised line box: [width] px wide, [height] px tall, at ([left], [top]). */
    private fun box(text: String, left: Int, top: Int, width: Int, height: Int = 40) =
        OcrLine(text, left, top, left + width, top + height)

    /**
     * Stores the text and geometry of [id]'s single page. [lines] is the order the recogniser
     * handed back, which is also the stored text order (that is how today's export reads it).
     */
    private fun store(id: String, page: OcrPage, lines: List<OcrLine>) {
        val name = repo.getDocument(id)!!.pageFiles.first().name
        repo.ocrStore.putPage(
            id,
            name,
            lines.joinToString("\n") { it.text },
            page.copy(lines = lines)
        )
    }

    /** The paragraph texts of the Word file, in document order. */
    private fun paragraphs(docx: File): List<String> {
        val xml = ZipFile(docx).use { zip ->
            val entry = zip.getEntry("word/document.xml") ?: error("no word/document.xml")
            zip.getInputStream(entry).use { it.readBytes() }
        }
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.inputStream())
        val paragraphs = document.getElementsByTagName("w:p")
        return (0 until paragraphs.length).map { index ->
            val element = paragraphs.item(index) as Element
            val texts = element.getElementsByTagName("w:t")
            (0 until texts.length).joinToString("") { texts.item(it).textContent }
        }
    }

    private fun ordered(paragraphs: List<String>, vararg texts: String): List<Int> =
        texts.map { wanted ->
            val index = paragraphs.indexOf(wanted)
            assertTrue("paragraph \"$wanted\" is missing from $paragraphs", index >= 0)
            index
        }

    /**
     * T47: the stored OCR text and geometry of the public CC0 FynePDF two-column sample, as the
     * phone produced them (`qa/export-quality-2026-11-19/device-ocr/`, copied with `run-as` from
     * a real import on 2026-11-19). The sample's own public text only.
     */
    private fun captured(page: String): Pair<String, OcrPage> {
        fun fixture(name: String): String =
            javaClass.getResourceAsStream("/t38/$name")!!.use { it.readBytes().toString(Charsets.UTF_8) }

        val textRecord = fixture("sample-two-column.ocr.txt").lineSequence().first { it.startsWith("$page\t") }
        val text = String(java.util.Base64.getDecoder().decode(textRecord.substringAfter('\t')), Charsets.UTF_8)

        val geoRecord = fixture("sample-two-column.ocr_geo.txt").lineSequence().first { it.startsWith("$page\t") }
        val payload = String(java.util.Base64.getDecoder().decode(geoRecord.substringAfter('\t')), Charsets.UTF_8)
        val rows = payload.lineSequence().toList()
        val (width, height) = rows.first().split(' ').map { it.toInt() }
        val lines = rows.drop(1)
            .filter { it.contains('\t') && !it.startsWith("#text-sha256") }
            .map { row ->
                val coordinates = row.substringBefore('\t').split(' ')
                OcrLine(
                    row.substringAfter('\t'),
                    coordinates[0].toInt(),
                    coordinates[1].toInt(),
                    coordinates[2].toInt(),
                    coordinates[3].toInt()
                )
            }
        return text to OcrPage(width, height, lines)
    }

    // ---------------------------------------------------------------- tests

    @Test
    fun `a two-column page keeps the title first and reads the left column before the right`() {
        val (id, page) = page()
        val title = box("A two-page sample PDF set in two columns", 60, 60, 880)
        val footer = box("Page 2 of 2", 60, 1300, 880)
        val left = (0 until 6).map { i -> box("left column line ${i + 1}", 60, 140 + 50 * i, 410) }
        val right = (0 until 6).map { i -> box("right column line ${i + 1}", 530, 140 + 50 * i, 410) }
        // The recogniser's order: the title, then the two columns row by row, then the footer.
        store(id, page, listOf(title) + left.zip(right).flatMap { (l, r) -> listOf(l, r) } + footer)

        val out = File(work, "two_column.docx")
        runBlocking { repo.exportWord(id, out) }

        val paragraphs = paragraphs(out)
        val at = ordered(
            paragraphs,
            title.text,
            left.first().text,
            left.last().text,
            right.first().text,
            right.last().text,
            footer.text
        )
        val titleAt = at[0]
        val left1 = at[1]
        val left6 = at[2]
        val right1 = at[3]
        val right6 = at[4]
        val footerAt = at[5]
        assertTrue("T38: title must stay first, got $paragraphs", titleAt < left1)
        assertTrue("T38: the left column must finish before the right starts: $paragraphs", left6 < right1)
        assertTrue("T38: the footer stays last: $paragraphs", right6 < footerAt)
        // Nothing is dropped or duplicated by the reorder.
        assertEquals("every recognised line is one paragraph", 14, paragraphs.count { it.isNotBlank() })
    }

    @Test
    fun `a narrow sidebar is read after the main column`() {
        val (id, page) = page()
        val title = box("Income in the United States: 2024", 60, 60, 880)
        val main = (0 until 5).map { i -> box("main text line ${i + 1}", 60, 140 + 50 * i, 650) }
        val sidebar = (0 until 3).map { i -> box("pull quote line ${i + 1}", 790, 140 + 50 * i, 150) }
        store(id, page, listOf(title) + main.zip(sidebar).flatMap { (m, s) -> listOf(m, s) } +
            main.drop(3) + sidebar.drop(3))

        val out = File(work, "sidebar.docx")
        runBlocking { repo.exportWord(id, out) }

        val paragraphs = paragraphs(out)
        val (_, main5, sidebar1) = ordered(paragraphs, title.text, main.last().text, sidebar.first().text)
        assertTrue("T38: the sidebar must not split the main column: $paragraphs", main5 < sidebar1)
    }

    @Test
    fun `a single-column page keeps exactly the stored order`() {
        val (id, page) = page()
        val lines = (0 until 8).map { i -> box("single column line ${i + 1}", 60, 60 + 60 * i, 880) }
        store(id, page, lines)

        val out = File(work, "single_column.docx")
        runBlocking { repo.exportWord(id, out) }

        assertEquals(lines.map { it.text }, paragraphs(out).filter { it.isNotBlank() })
    }

    @Test
    fun `a page without a confident gutter keeps the stored order`() {
        val (id, page) = page()
        // Two short blocks side by side: not enough body lines to be a column layout. Reordering
        // these would move a stamped header line to the end, which is worse than today.
        val lines = listOf(
            box("Date: 12 May 2026", 60, 60, 300),
            box("Invoice 0001", 700, 60, 240),
            box("Balance due", 60, 110, 300),
            box("Rs 4,200", 700, 110, 240)
        )
        store(id, page, lines)

        val out = File(work, "mixed.docx")
        runBlocking { repo.exportWord(id, out) }

        assertEquals(lines.map { it.text }, paragraphs(out).filter { it.isNotBlank() })
    }

    @Test
    fun `text the user corrected is never reordered from stale geometry`() {
        val (id, page) = page()
        val title = box("Quarterly report", 60, 60, 880)
        val left = (0 until 4).map { i -> box("left ${i + 1}", 60, 140 + 50 * i, 410) }
        val right = (0 until 4).map { i -> box("right ${i + 1}", 530, 140 + 50 * i, 410) }
        val lines = listOf(title) + left.zip(right).flatMap { (l, r) -> listOf(l, r) }
        val name = repo.getDocument(id)!!.pageFiles.first().name
        // The stored text no longer matches the geometry (an edit), so the geometry is stale and
        // must not be used to shuffle the corrected text.
        repo.ocrStore.putPage(id, name, "hand corrected text\nsecond line", page.copy(lines = lines))

        val out = File(work, "edited.docx")
        runBlocking { repo.exportWord(id, out) }

        assertEquals(
            listOf("hand corrected text", "second line"),
            paragraphs(out).filter { it.isNotBlank() }
        )
    }

    /**
     * T47: the phone FAIL of 2026-11-19 (DEVICE_BUILD6 F2). The public FynePDF sample stores
     * text and geometry that match line for line, but its footer starts in the left column and
     * runs a little past the gutter, so the export still fell back to the interleaved order.
     * This is the device's own stored data, not synthetic geometry.
     */
    @Test
    fun `the captured public two-column sample exports the left column before the right`() {
        val (id, page) = page(width = 1240, height = 1752)
        val (text, ocr) = captured("page_000.jpg")
        val name = repo.getDocument(id)!!.pageFiles.first().name
        repo.ocrStore.putPage(id, name, text, ocr)

        val out = File(work, "sample_two_column.docx")
        runBlocking { repo.exportWord(id, out) }

        val paragraphs = paragraphs(out).filter { it.isNotBlank() }
        assertEquals("every stored line is still one paragraph", 79, paragraphs.size)
        assertEquals("The PDF format in ten short parts", paragraphs.first())
        assertEquals(
            "Sample file from FynePDF. Free to use for testing. fynepdf.com/samples",
            paragraphs.last()
        )
        val left = paragraphs.indexOf("PDF stands for Portable Document Format. Adobe")
        val right = paragraphs.indexOf("and screenshots are usually kept in a lossless")
        assertTrue("the left column comes first: $paragraphs", left in 0 until right)
        // DEVICE_BUILD6 F2 quoted exactly this interleaving: heading, right column, left column.
        val heading = paragraphs.indexOf("1. What a PDF is")
        val afterHeading = paragraphs.indexOf("introduced it in 1993 so a document would look")
        assertTrue("the left column is not split by the right: $paragraphs", heading in 0 until afterHeading)
        assertTrue("the right column follows the whole left one: $paragraphs", afterHeading < right)
    }
}
