package com.scanwhirl.app.store

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import com.scanwhirl.app.util.DocxWriter
import com.scanwhirl.app.util.IdCard
import com.scanwhirl.app.util.ImportFixtures
import com.scanwhirl.app.util.LongImage
import com.scanwhirl.app.util.PageRanges
import com.scanwhirl.app.util.PaperSize
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs

/**
 * L4 tools engine: page ranges and split, PDF to images, the bounded long-image stitcher,
 * the Word writer and the metadata-free copy, plus the ID card composition.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ToolsEngineTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)

    @After fun tearDown() = Faults.resetAll()

    private fun work(name: String): File = File(app.cacheDir, name).apply { parentFile?.mkdirs() }

    private fun solidJpeg(color: Int, width: Int = 120, height: Int = 80): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).drawColor(color)
            return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun jpegSize(bytes: ByteArray): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        return opts.outWidth to opts.outHeight
    }

    // ---------------------------------------------------------------- PageRanges

    @Test
    fun `page ranges parse numbers open ranges and dashes`() {
        val spans = PageRanges.parse("1-3, 5, 8-", pageCount = 10)
        assertEquals(listOf(1 to 3, 5 to 5, 8 to 10), spans.map { it.from to it.to })
        assertEquals("pages 1-3", PageRanges.label(spans[0]))
        assertEquals("page 5", PageRanges.label(spans[1]))

        val loose = PageRanges.parse(" 1 \u2013 2 ,4 ", pageCount = 4)
        assertEquals(listOf(1 to 2, 4 to 4), loose.map { it.from to it.to })
    }

    @Test
    fun `page ranges refuse unusable input before anything is written`() {
        val bad = listOf("", "  ,  ", "0-2", "3-9", "3-1", "abc", "1-2-3")
        for (text in bad) {
            try {
                PageRanges.parse(text, pageCount = 5)
                fail("expected \"$text\" to be refused")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    // ---------------------------------------------------------------- Split

    @Test
    fun `split publishes one document per range and keeps the source`() {
        val id = DocFixtures.saved(repo, app, pages = 5, name = "Contract")
        val source = PdfInvariants.dirDigest(File(repo.docsDir, id))
        val ranges = PageRanges.parse("1-2, 4", pageCount = 5)

        val outputs = runBlocking { repo.splitByRange(id, ranges) }

        // fixui: tiles removed (owner F6/F7/F20) - split parts are "<name> part N" in page order.
        assertEquals(listOf("Contract part 1", "Contract part 2"), outputs.map { it.name })
        assertEquals(listOf(2, 1), outputs.map { it.pageCount })
        outputs.forEach {
            PdfInvariants.assertI1(repo, it.id)
            PdfInvariants.assertI10(repo, it.id)
        }
        PdfInvariants.assertI4(repo)
        assertEquals("split changed its source", source, PdfInvariants.dirDigest(File(repo.docsDir, id)))
    }

    @Test
    fun `split failure publishes nothing for the part that failed`() {
        val id = DocFixtures.saved(repo, app, pages = 3)
        val before = repo.listDocuments().map { it.id }.toSet()
        Faults.failAtFileSync("scan.pdf.tmp")
        val r = runCatching { runBlocking { repo.splitByRange(id, listOf(PageRanges.Span(1, 2))) } }
        assertTrue("split must fail when its PDF cannot be written", r.isFailure)
        assertEquals("a failed split must publish nothing", before, repo.listDocuments().map { it.id }.toSet())
    }

    @Test
    fun `split refuses a locked source`() {
        val id = DocFixtures.locked(repo, app, pages = 2)
        val before = repo.listDocuments().map { it.id }.toSet()
        val r = runCatching {
            runBlocking { repo.splitByRange(id, listOf(PageRanges.Span(1, 1))) }
        }
        assertTrue("a locked source must not be split", r.isFailure)
        assertEquals(before, repo.listDocuments().map { it.id }.toSet())
    }

    @Test
    fun `tool outputs never stack suffixes and stay unique`() {
        val id = DocFixtures.saved(repo, app, pages = 3, name = "Contract")

        val first = runBlocking { repo.compressScan(id, 60, null) }
        val second = runBlocking { repo.compressScan(id, 60, null) }
        assertEquals("Contract (2)", first.name)
        assertEquals("Contract (3)", second.name)

        val split = runBlocking { repo.splitByRange(first.id, listOf(PageRanges.Span(1, 2))) }
        // fixui: tiles removed (owner F6/F7/F20) - split parts are "<name> part N".
        assertEquals("Contract (2) part 1", split.first().name)

        val again = runBlocking { repo.splitByRange(first.id, listOf(PageRanges.Span(1, 2))) }
        assertEquals("Contract (2) part 1 (2)", again.first().name)
    }

    @Test
    fun `compress to a target size streams pages and publishes them`() {
        val id = DocFixtures.saved(repo, app, pages = 3, name = "Target")
        val before = requireNotNull(repo.getDocument(id))
        val copy = runBlocking { repo.compressScanToTarget(id, 120_000L) }
        assertEquals("Target (2)", copy.name)
        assertEquals(3, copy.pageCount)
        assertTrue("the published PDF exists", copy.pdfFile.isFile)
        assertTrue(
            "the target path re-encodes pages",
            copy.pageFiles.first().length() <= before.pageFiles.first().length()
        )
        assertEquals("the source is untouched", 3, repo.getDocument(id)!!.pageCount)
    }

    // ---------------------------------------------------------------- PDF to images

    @Test
    fun `pdf to images writes every page into one zip`() {
        val id = DocFixtures.saved(repo, app, pages = 3)
        val out = work("pages.zip")
        runBlocking { repo.pdfToImages(id, ScanRepository.ImageExportFormat.JPG, zip = true, outFile = out) }
        ZipFile(out).use { zip ->
            val names = zip.entries().toList().map { it.name }.sorted()
            assertEquals(listOf("page_001.jpg", "page_002.jpg", "page_003.jpg"), names)
            zip.getInputStream(zip.getEntry("page_001.jpg")).use { input ->
                val bytes = input.readBytes()
                assertEquals(0xFF, bytes[0].toInt() and 255)
                assertEquals(0xD8, bytes[1].toInt() and 255)
            }
        }
    }

    @Test
    fun `pdf to images writes a single png for one page and a zip for many`() {
        val one = DocFixtures.saved(repo, app, pages = 1)
        val single = work("one.png")
        runBlocking { repo.pdfToImages(one, ScanRepository.ImageExportFormat.PNG, zip = false, outFile = single) }
        val singleBytes = single.readBytes()
        assertEquals(0x89, singleBytes[0].toInt() and 255)
        assertEquals("PNG", String(singleBytes, 1, 3, Charsets.US_ASCII))

        val many = DocFixtures.saved(repo, app, pages = 2)
        val zipped = work("many.png.zip")
        runBlocking { repo.pdfToImages(many, ScanRepository.ImageExportFormat.PNG, zip = false, outFile = zipped) }
        ZipFile(zipped).use { zip ->
            assertEquals(2, zip.entries().toList().count { it.name.endsWith(".png") })
        }
    }

    @Test
    fun `pdf to images exports only the chosen pages and a lower quality`() {
        val id = DocFixtures.saved(repo, app, pages = 3)
        val out = work("selected.zip")
        runBlocking {
            repo.pdfToImages(
                id, ScanRepository.ImageExportFormat.JPG, zip = true, outFile = out,
                quality = 55, pageIndices = listOf(2, 0)
            )
        }
        ZipFile(out).use { zip ->
            assertEquals(listOf("page_001.jpg", "page_003.jpg"), zip.entries().toList().map { it.name }.sorted())
        }
    }

    @Test
    fun `pdf to images failure leaves no visible output file`() {
        val id = DocFixtures.saved(repo, app, pages = 2)
        // The second page cannot be decoded, so the PNG export fails after the first page
        // has been written into the temporary archive.
        repo.getDocument(id)!!.pageFiles[1].writeBytes("not an image".toByteArray())
        val out = work("broken.zip")
        val r = runCatching {
            runBlocking { repo.pdfToImages(id, ScanRepository.ImageExportFormat.PNG, zip = true, outFile = out) }
        }
        assertTrue("png export of an unreadable page must fail", r.isFailure)
        assertTrue("a broken ZIP must never be visible", !out.exists())
        assertTrue("no .part file may be left behind", !File(out.path + ".part").exists())
    }

    @Test
    fun `pdf to images refuses a locked source and writes nothing`() {
        val id = DocFixtures.locked(repo, app, pages = 1)
        val out = work("locked.zip")
        val r = runCatching {
            runBlocking { repo.pdfToImages(id, ScanRepository.ImageExportFormat.JPG, zip = true, outFile = out) }
        }
        assertTrue("a locked source must not be exported", r.isFailure)
        assertTrue("nothing may be written for a locked source", !out.exists())
    }

    // ---------------------------------------------------------------- Long image

    @Test
    fun `long image splits instead of squashing`() {
        // Two 7,000 px pages plus the gap fit one 16,000 px part; the third starts a new part.
        val parts = LongImage.plan(listOf(7_000, 7_000, 7_000))
        assertEquals(listOf(0..1, 2..2), parts)
        assertEquals(listOf(0..0, 1..1), LongImage.plan(listOf(100, 100), maxHeight = 150))
    }

    @Test
    fun `long image refuses a single page that cannot fit`() {
        try {
            LongImage.plan(listOf(25_000))
            fail("a page taller than the part limit must be refused, not squashed")
        } catch (e: IllegalArgumentException) {
            assertTrue("got ${e.message}", e.message!!.contains("Too large"))
        }
    }

    @Test
    fun `long image keeps every page aspect ratio at the target width`() {
        val a = work("long_a.jpg").apply { writeBytes(ImportFixtures.image(width = 100, height = 200)) }
        val b = work("long_b.jpg").apply { writeBytes(ImportFixtures.image(width = 200, height = 100)) }
        val parts = LongImage.stitch(listOf(a, b), width = 200, maxHeight = 5_000)
        assertEquals(1, parts.size)
        val (w, h) = jpegSize(parts[0])
        assertEquals(200, w)
        // 200-wide page 1 is 400 tall, page 2 is 100 tall, plus one 12 px gap.
        assertEquals(512, h)
        val bitmap = BitmapFactory.decodeByteArray(parts[0], 0, parts[0].size)
        try {
            assertTrue("top page must be drawn", bitmap.getPixel(20, 20) != Color.WHITE)
            assertTrue("bottom page must be drawn", bitmap.getPixel(20, 420) != Color.WHITE)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun `long image splits a document that is too tall`() {
        val pages = List(3) { i ->
            work("long_part_$i.jpg").apply { writeBytes(ImportFixtures.image(width = 100, height = 100)) }
        }
        val parts = LongImage.stitch(pages, width = 200, maxHeight = 450)
        assertEquals(2, parts.size)
        assertEquals(412, jpegSize(parts[0]).second)
        assertEquals(200, jpegSize(parts[1]).second)
    }

    @Test
    fun `long image export writes named parts at the bounded width`() {
        val id = DocFixtures.saved(repo, app, pages = 3)
        val source = PdfInvariants.dirDigest(File(repo.docsDir, id))
        val dir = File(app.cacheDir, "long_export").apply { mkdirs() }
        val files = runBlocking { repo.exportLongImage(id, dir) }

        assertEquals(1, files.size)
        assertTrue("unexpected name ${files[0].name}", files[0].name.endsWith(" - long.jpg"))
        val (w, h) = jpegSize(files[0].readBytes())
        assertEquals(LongImage.WIDTH, w)
        assertTrue("part is taller than the limit", h <= LongImage.MAX_PART_HEIGHT)
        assertEquals("long image changed its source", source, PdfInvariants.dirDigest(File(repo.docsDir, id)))
    }

    @Test
    fun `long image export failure deletes every partial output`() {
        val id = DocFixtures.saved(repo, app, pages = 25)
        val heights = LongImage.scaledHeights(repo.getDocument(id)!!.pageFiles)
        val partCount = LongImage.plan(heights).size
        assertTrue("fixture must need several parts, got $partCount", partCount >= 2)
        val dir = File(app.cacheDir, "long_failure").apply { mkdirs() }
        Faults.failAtFileSync("Doc - long 2 of $partCount.jpg.part")

        val r = runCatching { runBlocking { repo.exportLongImage(id, dir) } }

        assertTrue("a failing part must fail the export", r.isFailure)
        assertEquals("no partial long image may stay", emptyList<String>(), dir.list().orEmpty().toList())
    }

    // ---------------------------------------------------------------- Word

    @Test
    fun `docx is a zip with a well formed document and one paragraph per line`() {
        val out = work("text.docx")
        DocxWriter.write(out, listOf(listOf("Hello", "A & B"), listOf("Second <page>")))
        ZipFile(out).use { zip ->
            assertTrue(zip.getEntry("[Content_Types].xml") != null)
            assertTrue(zip.getEntry("_rels/.rels") != null)
            val entry = zip.getEntry("word/document.xml")
            assertTrue("no word/document.xml", entry != null)
            val xml = zip.getInputStream(entry!!).use { it.readBytes() }
            val text = String(xml, Charsets.UTF_8)
            assertTrue(text.contains("Hello"))
            assertTrue("XML must be escaped", text.contains("A &amp; B"))
            assertTrue(text.contains("Second &lt;page&gt;"))
            assertEquals("one page break between two pages", 1, Regex("<w:br w:type=\"page\"/>").findAll(text).count())
            // Parses as XML: a truncated or malformed document would throw here.
            DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(xml.inputStream())
        }
    }

    @Test
    fun `exportWord writes the recognised text of every page`() {
        val id = DocFixtures.saved(repo, app, pages = 2)
        DocFixtures.withOcr(repo, id)
        val out = work("doc.docx")
        runBlocking { repo.exportWord(id, out) }
        ZipFile(out).use { zip ->
            val text = zip.getInputStream(zip.getEntry("word/document.xml")).use {
                String(it.readBytes(), Charsets.UTF_8)
            }
            assertTrue("page 1 text missing: $text", text.contains("text page_000.jpg"))
            assertTrue("page 2 text missing: $text", text.contains("text page_001.jpg"))
        }

        val missing = runCatching { runBlocking { repo.exportWord(DocFixtures.saved(repo, app, 1), work("none.docx")) } }
        assertTrue("Word export without text must be refused", missing.isFailure)
    }

    @Test
    fun `exportWord failure leaves no half written docx`() {
        val out = work("partial.docx")
        val refused = runCatching {
            runBlocking {
                repo.exportWord(DocFixtures.saved(repo, app, pages = 1), out)
            }
        }
        assertTrue("a document without text must fail", refused.isFailure)
        assertTrue("no output may exist after a failed export", !out.exists())
        assertTrue("no .part file may be left behind", !File(out.path + ".part").exists())

        val directory = File(app.cacheDir, "docx_target").apply { mkdirs() }
        val r = runCatching { DocxWriter.write(directory, listOf(listOf("Hello"))) }
        assertTrue("writing over a directory must fail", r.isFailure)
        assertTrue("the directory must be untouched", directory.isDirectory)
        assertTrue("no .part file may be left behind", !File(directory.path + ".part").exists())
    }

    @Test
    fun `exportWord with pictures embeds every page image and keeps the text`() {
        val id = DocFixtures.saved(repo, app, pages = 2)
        DocFixtures.withOcr(repo, id)
        val out = work("pictures.docx")
        runBlocking { repo.exportWord(id, out, includePictures = true) }

        ZipFile(out).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertEquals(
                "one picture per page",
                2,
                names.count { it.startsWith("word/media/") && it.endsWith(".jpg") }
            )
            val document = zip.getInputStream(zip.getEntry("word/document.xml")).use {
                String(it.readBytes(), Charsets.UTF_8)
            }
            assertTrue("page 1 text missing", document.contains("text page_000.jpg"))
            assertTrue("page 2 text missing", document.contains("text page_001.jpg"))
            assertTrue("no inline drawing", document.contains("<w:drawing>"))
            assertTrue("picture does not embed its relationship", document.contains("r:embed=\"rId1\""))
            val rels = zip.getInputStream(zip.getEntry("word/_rels/document.xml.rels")).use {
                String(it.readBytes(), Charsets.UTF_8)
            }
            assertTrue("rels must point at the page picture: $rels", rels.contains("media/page_000.jpg"))
            val image = zip.getInputStream(zip.getEntry("word/media/page_000.jpg")).use { it.readBytes() }
            assertEquals("embedded picture is not a JPEG", 0xFF, image[0].toInt() and 255)
            assertEquals(0xD8, image[1].toInt() and 255)
        }

        val plain = work("textonly.docx")
        runBlocking { repo.exportWord(id, plain) }
        ZipFile(plain).use { zip ->
            assertTrue(
                "a text-only export must not carry media",
                zip.entries().toList().none { it.name.startsWith("word/media/") }
            )
        }
    }

    @Test
    fun `exportWord picture failure leaves no half written docx`() {
        val id = DocFixtures.saved(repo, app, pages = 1)
        DocFixtures.withOcr(repo, id)
        repo.getDocument(id)!!.pageFiles.first().writeBytes("not an image".toByteArray())
        val out = work("broken_pictures.docx")
        val r = runCatching { runBlocking { repo.exportWord(id, out, includePictures = true) } }
        assertTrue("an unreadable page must fail the picture export", r.isFailure)
        assertTrue("no output may exist after a failed export", !out.exists())
        assertTrue("no .part file may be left", !File(out.path + ".part").exists())
    }

    @Test
    fun `exportPowerPoint writes one slide per page with the OCR text in notes`() {
        val id = DocFixtures.saved(repo, app, pages = 2)
        DocFixtures.withOcr(repo, id)
        val out = work("deck.pptx")

        runBlocking { repo.exportPowerPoint(id, out) }

        ZipFile(out).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertEquals(
                "one slide per page",
                2,
                names.count { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
            )
            assertEquals(2, names.count { it.startsWith("ppt/media/") })
            assertTrue(names.contains("ppt/slideMasters/slideMaster1.xml"))
            assertTrue(names.contains("ppt/slideLayouts/slideLayout1.xml"))
            assertTrue(names.contains("ppt/theme/theme1.xml"))
            val notes = zip.getInputStream(zip.getEntry("ppt/notesSlides/notesSlide1.xml")).use {
                String(it.readBytes(), Charsets.UTF_8)
            }
            assertTrue("page 1 OCR text missing from notes: $notes", notes.contains("text page_000.jpg"))
            val presentation = zip.getInputStream(zip.getEntry("ppt/presentation.xml")).use {
                String(it.readBytes(), Charsets.UTF_8)
            }
            assertEquals(
                "two slide ids",
                2,
                Regex("<p:sldId ").findAll(presentation).count()
            )
        }
    }

    @Test
    fun `exportPowerPoint refuses a locked source and writes nothing`() {
        val id = DocFixtures.locked(repo, app, pages = 1)
        val out = work("locked.pptx")
        val r = runCatching { runBlocking { repo.exportPowerPoint(id, out) } }
        assertTrue("a locked source must not be exported", r.isFailure)
        assertTrue("nothing may be written", !out.exists())
    }

    @Test
    fun `merge names the output after the first source and stays unique`() {
        val first = DocFixtures.saved(repo, app, pages = 1, name = "isz14")
        val second = DocFixtures.saved(repo, app, pages = 1, name = "isz12")

        val merged = runBlocking { repo.mergeDocuments(listOf(first, second), null) }
        assertEquals("isz14 (2)", merged.name)

        val again = runBlocking { repo.mergeDocuments(listOf(first, second), null) }
        assertEquals("isz14 (3)", again.name)
    }

    // ---------------------------------------------------------------- Excel

    private fun ocrLine(text: String, left: Int, top: Int, width: Int = 120, height: Int = 40) =
        OcrLine(text, left, top, left + width, top + height)

    @Test
    fun `exportExcel writes one sheet per page with the detected table and numbers`() {
        val id = DocFixtures.saved(repo, app, pages = 2)
        repo.ocrStore.putPage(
            id, "page_000.jpg", "invoice",
            OcrPage(
                1200, 1600, listOf(
                    ocrLine("Item", 50, 100), ocrLine("Qty", 400, 100),
                    ocrLine("Rate", 650, 100), ocrLine("Amount", 900, 100),
                    ocrLine("Pen", 50, 170), ocrLine("2", 400, 170),
                    ocrLine("₹10", 650, 170), ocrLine("₹20", 900, 170),
                    ocrLine("Book", 50, 240), ocrLine("1", 400, 240),
                    ocrLine("₹250", 650, 240), ocrLine("₹250", 900, 240)
                )
            )
        )
        repo.ocrStore.putPage(
            id, "page_001.jpg", "letter",
            OcrPage(
                1200, 1600, listOf(
                    ocrLine("Dear sir", 50, 100, 400),
                    ocrLine("Thank you for your order.", 50, 170, 500),
                    ocrLine("Regards", 50, 240, 200)
                )
            )
        )
        val out = work("report.xlsx")
        runBlocking { repo.exportExcel(id, out, tablesOnly = false) }

        ZipFile(out).use { zip ->
            val workbook = zip.getInputStream(zip.getEntry("xl/workbook.xml")).use {
                String(it.readBytes(), Charsets.UTF_8)
            }
            assertEquals("two sheets", 2, Regex("<sheet ").findAll(workbook).count())
            assertTrue(workbook.contains("name=\"Page 1\""))
            assertTrue(workbook.contains("name=\"Page 2\""))
            val first = zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).use {
                String(it.readBytes(), Charsets.UTF_8)
            }
            assertTrue("invoice header", first.contains("Amount"))
            assertTrue("invoice row", first.contains("Pen"))
            assertTrue("a rupee cell is numeric", first.contains("<v>20</v>"))
            assertTrue("no table note on a table page", !first.contains("No table"))
            val second = zip.getInputStream(zip.getEntry("xl/worksheets/sheet2.xml")).use {
                String(it.readBytes(), Charsets.UTF_8)
            }
            assertTrue("plain text page", second.contains("Dear sir"))
        }

        val tablesOnly = work("tables.xlsx")
        val report = runBlocking {
            repo.exportExcel(id, tablesOnly, tablesOnly = true, noTableNote = "No table here")
        }
        assertEquals("one of two pages has a table", 1, report.tablePages)
        assertEquals(2, report.totalPages)
        assertEquals(tablesOnly, report.file)
        ZipFile(tablesOnly).use { zip ->
            val workbook = zip.getInputStream(zip.getEntry("xl/workbook.xml")).use {
                String(it.readBytes(), Charsets.UTF_8)
            }
            assertEquals("only the table page becomes a sheet", 1, Regex("<sheet ").findAll(workbook).count())
            val first = zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).use {
                String(it.readBytes(), Charsets.UTF_8)
            }
            assertTrue(first.contains("Pen"))
            assertTrue("no note sheet in tables-only", !first.contains("No table here"))
        }
    }

    @Test
    fun `exportExcel tables only stops before writing when no page has a table`() {
        val id = DocFixtures.saved(repo, app, pages = 2)
        repo.ocrStore.putPage(
            id, "page_000.jpg", "letter",
            OcrPage(1200, 1600, listOf(ocrLine("Dear sir", 50, 100, 400)))
        )
        repo.ocrStore.putPage(
            id, "page_001.jpg", "letter",
            OcrPage(1200, 1600, listOf(ocrLine("Regards", 50, 100, 200)))
        )
        val out = work("no-tables.xlsx")
        val report = runBlocking {
            repo.exportExcel(id, out, tablesOnly = true, noTableNote = "No table here")
        }
        assertEquals(0, report.tablePages)
        assertEquals(2, report.totalPages)
        assertNull("nothing is written without a table", report.file)
        assertTrue("no workbook file exists", !out.exists())
    }

    @Test
    fun `exportExcel refuses a locked source and writes nothing`() {
        val id = DocFixtures.locked(repo, app, pages = 1)
        val out = work("locked.xlsx")
        val r = runCatching { runBlocking { repo.exportExcel(id, out) } }
        assertTrue("a locked source must not be exported", r.isFailure)
        assertTrue("nothing may be written", !out.exists())
    }

    // ---------------------------------------------------------------- Remove metadata

    @Test
    fun `removeMetadata drops EXIF and keeps the document verifiable`() {
        // Build the source through the real save path so its scan.pdf embeds exactly the page
        // bytes that carry EXIF (editing a saved page in place would break its own I1).
        val exifFile = work("exif_page.jpg").apply { writeBytes(solidJpeg(Color.WHITE)) }
        ExifInterface(exifFile).apply {
            setAttribute(ExifInterface.TAG_MAKE, "TestCamera")
            setAttribute(ExifInterface.TAG_MODEL, "Secret Model")
            saveAttributes()
        }
        val exifBytes = exifFile.readBytes()
        assertTrue("fixture must carry EXIF first", String(exifBytes, Charsets.ISO_8859_1).contains("Exif"))
        val id = runBlocking { repo.createDocument("Camera scan", listOf(exifBytes)) }.id
        val source = PdfInvariants.dirDigest(File(repo.docsDir, id))

        val clean = runBlocking { repo.removeMetadata(id) }

        val cleanPage = clean.pageFiles.first()
        assertTrue(
            "clean page still carries EXIF",
            !String(cleanPage.readBytes(), Charsets.ISO_8859_1).contains("Exif")
        )
        assertTrue("clean page must be a JPEG", cleanPage.readBytes()[0] == 0xFF.toByte())
        assertEquals("removeMetadata changed its source", source, PdfInvariants.dirDigest(File(repo.docsDir, id)))
        PdfInvariants.assertI1(repo, clean.id)
        PdfInvariants.assertI4(repo)
    }

    @Test
    fun `removeMetadata keeps recognised text searchable in the clean copy`() {
        val id = DocFixtures.saved(repo, app, pages = 1)
        val pageFile = repo.getDocument(id)!!.pageFiles.single()
        val page = pageFile.name
        val (w, h) = requireNotNull(com.scanwhirl.app.util.PageReplace.bounds(pageFile))
        repo.ocrStore.putPage(
            id, page, "Marks: 85.50%",
            OcrPage(w, h, listOf(OcrLine("Marks: 85.50%", 10, 10, w - 10, 40)))
        )
        assertTrue("fixture must be searchable first", repo.makeSearchable(id))
        val sourceText = repo.ocrDoc(id)[page]
        assertTrue("fixture must carry text", !sourceText.isNullOrBlank())

        val clean = runBlocking { repo.removeMetadata(id) }

        assertEquals("recognised text must survive the clean copy", sourceText, repo.ocrDoc(clean.id)[page])
        assertTrue(
            "the clean copy must still be marked as searchable",
            File(repo.docsDir, clean.id + "/text_layer.flag").isFile
        )
        val embedded = org.apache.pdfbox.Loader.loadPDF(clean.pdfFile).use {
            org.apache.pdfbox.text.PDFTextStripper().getText(it)
        }
        assertTrue("selectable text lost from the clean PDF: $embedded", embedded.contains("85.50%"))
    }

    @Test
    fun `removeMetadata failure publishes nothing`() {
        val id = DocFixtures.saved(repo, app, pages = 1)
        val before = repo.listDocuments().map { it.id }.toSet()
        val source = PdfInvariants.dirDigest(File(repo.docsDir, id))
        Faults.failAtFileSync("scan.pdf.tmp")

        val r = runCatching { runBlocking { repo.removeMetadata(id) } }

        assertTrue("removeMetadata must fail when its PDF cannot be written", r.isFailure)
        assertEquals("a failed clean must publish nothing", before, repo.listDocuments().map { it.id }.toSet())
        assertEquals("removeMetadata changed its source", source, PdfInvariants.dirDigest(File(repo.docsDir, id)))
    }

    // ---------------------------------------------------------------- ID card

    @Test
    fun `id card puts the front on top and the back at the bottom of one A4 page`() {
        val front = solidJpeg(Color.RED)
        val back = solidJpeg(Color.BLUE)
        val bytes = IdCard.compose(front, back)
        val (w, h) = jpegSize(bytes)
        assertEquals(IdCard.WIDTH, w)
        assertEquals(IdCard.HEIGHT, h)
        assertTrue("A4 is taller than wide", h > w && abs(h.toFloat() / w - 1.414f) < 0.01f)

        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        try {
            assertColor(Color.RED, bitmap.getPixel(620, 470))
            assertColor(Color.BLUE, bitmap.getPixel(620, 1280))
            assertColor(Color.WHITE, bitmap.getPixel(620, 877))
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun `id card letter page keeps ID-1 proportions and print-safe margins`() {
        val front = solidJpeg(Color.RED)
        val back = solidJpeg(Color.BLUE)
        val bytes = IdCard.compose(front, back, com.scanwhirl.app.util.PaperSize.LETTER)
        val (w, h) = jpegSize(bytes)
        assertEquals(com.scanwhirl.app.util.PaperSize.LETTER.width, w)
        assertEquals(com.scanwhirl.app.util.PaperSize.LETTER.height, h)
        assertTrue("Letter is 8.5 x 11 in at 150 dpi", abs(h.toFloat() / w - 1.294f) < 0.01f)

        val card = IdCard.frontRect(com.scanwhirl.app.util.PaperSize.LETTER)
        assertEquals("ID-1 ratio", IdCard.ID1_RATIO, card.width() / card.height(), 0.01f)
        assertTrue("left margin", card.left >= w * 0.05f)
        assertTrue("right margin", w - card.right >= w * 0.05f)
        assertTrue("cards stay on the page", IdCard.backRect(com.scanwhirl.app.util.PaperSize.LETTER).bottom < h)
    }

    @Test
    fun `paper default follows the device country and converts to a compact id`() {
        assertEquals(PaperSize.LETTER, PaperSize.defaultForCountry("US"))
        assertEquals(PaperSize.LETTER, PaperSize.defaultForCountry("ca"))
        assertEquals(PaperSize.LETTER, PaperSize.defaultForCountry("PH"))
        assertEquals(PaperSize.A4, PaperSize.defaultForCountry("IN"))
        assertEquals(PaperSize.A4, PaperSize.defaultForCountry("DE"))
        assertEquals(PaperSize.A4, PaperSize.defaultForCountry(null))
        assertEquals(PaperSize.LETTER, PaperSize.fromId("letter"))
        assertEquals(PaperSize.A4, PaperSize.fromId("unknown"))
    }

    private fun assertColor(expected: Int, actual: Int, tolerance: Int = 45) {
        assertTrue(
            "expected ${Integer.toHexString(expected)}, got ${Integer.toHexString(actual)}",
            abs(Color.red(expected) - Color.red(actual)) <= tolerance &&
                abs(Color.green(expected) - Color.green(actual)) <= tolerance &&
                abs(Color.blue(expected) - Color.blue(actual)) <= tolerance
        )
    }

    @Test
    fun `id card refuses a missing or unreadable side without producing a page`() {
        val front = solidJpeg(Color.RED)
        val empty = runCatching { IdCard.compose(front, ByteArray(0)) }
        assertTrue("a missing back side must be refused", empty.isFailure)

        val garbage = runCatching { IdCard.compose("not an image".toByteArray(), solidJpeg(Color.BLUE)) }
        assertTrue("an unreadable side must be refused", garbage.isFailure)
    }
}
