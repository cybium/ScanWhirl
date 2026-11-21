package com.scanwhirl.app.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import com.scanwhirl.app.util.ImportFixtures
import com.scanwhirl.app.util.PdfGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

/**
 * X1 cross-lane integration: I1 made [ScanRepository.pageFilesIn] numeric but `loadDocument`
 * still sorted the page list lexicographically. One shared comparator must keep loadDocument,
 * the generated PDF, the compressed copy and Word text export in the same page order past 999.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PageOrderIntegrationTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val repo get() = ScanRepository(app)

    private fun pageName(index: Int) = String.format(Locale.ROOT, "page_%03d.jpg", index)

    @Test
    fun `loadDocument orders one thousand and one pages numerically`() {
        val dir = File(app.cacheDir, "x1_load_${System.nanoTime()}").apply { mkdirs() }
        try {
            File(dir, "meta.txt").writeText("X1 big scan\n${System.currentTimeMillis()}\nOther\n")
            (0..1000).forEach { File(dir, pageName(it)).writeBytes(byteArrayOf(1)) }
            // No .incomplete marker: this is a published document.

            val names = requireNotNull(repo.loadDocument(dir)).pageFiles.map { it.name }
            assertEquals(1001, names.size)
            assertEquals((0..1000).map(::pageName), names)
            // The five boundary indices the lexicographic sort hides.
            assertEquals(99, names.indexOf(pageName(99)))
            assertEquals(100, names.indexOf(pageName(100)))
            assertEquals(101, names.indexOf(pageName(101)))
            assertEquals(999, names.indexOf(pageName(999)))
            assertEquals(1000, names.indexOf(pageName(1000)))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the pending review reader uses the same numeric page order`() {
        val dir = File(app.cacheDir, "x1_pending_${System.nanoTime()}").apply { mkdirs() }
        try {
            val names = listOf("page_099.jpg", "page_100.jpg", "page_1000.jpg", "page_101.jpg", "page_999.jpg")
            names.forEach { File(dir, it).writeBytes(byteArrayOf(1)) }
            assertEquals(
                listOf("page_099.jpg", "page_100.jpg", "page_101.jpg", "page_999.jpg", "page_1000.jpg"),
                PendingScans.pages(dir).map { it.name }
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `loadDocument PDF compressed copy and Word export agree on page order`() = runBlocking {
        val dir = File(repo.docsDir, "x1agree${System.nanoTime().toString().takeLast(8)}").apply { mkdirs() }
        try {
            File(dir, "meta.txt").writeText("X1 agreement\n${System.currentTimeMillis()}\nOther\n")
            // Five distinguishable pages at the boundary indices. Each page's JPEG width is its identity.
            val specs = listOf(99 to 199, 100 to 200, 101 to 201, 999 to 999, 1000 to 1000)
            val ordered = specs.map { (index, width) ->
                File(dir, pageName(index)).apply { writeBytes(ImportFixtures.image(width = width, height = 60)) }
            }
            // A real save builds scan.pdf from the pages in numeric order, independent of any later re-read.
            PdfGenerator.imagesToPdf(ordered, File(dir, "scan.pdf"))
            val pdfWidths = pdfImageWidths(File(dir, "scan.pdf"))
            assertEquals("PDF ground truth is the numeric order", specs.map { it.second }, pdfWidths)

            val markers = listOf("Z099Z", "Z100Z", "Z101Z", "Z999Z", "Z1000Z")
            ordered.forEachIndexed { i, page ->
                repo.ocrStore.putPage(
                    dir.name, page.name, markers[i],
                    OcrPage(specs[i].second, 60, listOf(OcrLine(markers[i], 1, 1, 50, 20)))
                )
            }

            val doc = requireNotNull(repo.getDocument(dir.name))
            assertEquals("loadDocument uses the numeric order", ordered.map { it.name }, doc.pageFiles.map { it.name })
            assertEquals(
                "loadDocument order matches the generated PDF",
                pdfWidths,
                doc.pageFiles.map { PdfGenerator.readJpegInfo(it.readBytes()).width }
            )

            // The compressed copy consumes doc.pageFiles and renumbers; its carried OCR text keeps the order.
            val copy = repo.compressScan(doc.id, 80, null)
            assertEquals(
                "compressed copy agrees with the PDF order",
                markers,
                (0 until copy.pageCount).map { i -> repo.ocrStore.readText(copy.id)[pageName(i)] }
            )

            // Word text export walks doc.pageFiles in order.
            val out = File(app.cacheDir, "x1_agree_${System.nanoTime()}.docx")
            runBlocking { repo.exportWord(doc.id, out) }
            val xml = ZipFile(out).use { z ->
                z.getInputStream(z.getEntry("word/document.xml")).use { String(it.readBytes(), Charsets.UTF_8) }
            }
            val positions = markers.map { xml.indexOf(it) }
            assertTrue("every page marker must be exported: $xml", positions.all { it >= 0 })
            assertEquals("markers strictly follow the PDF page order", positions.sorted(), positions)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun pdfImageWidths(pdf: File): List<Int> =
        Regex("/Subtype /Image /Width (\\d+)").findAll(pdf.readBytes().toString(Charsets.ISO_8859_1))
            .map { it.groupValues[1].toInt() }.toList()
}
