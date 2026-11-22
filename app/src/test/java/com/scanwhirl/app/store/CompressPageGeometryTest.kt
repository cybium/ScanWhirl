package com.scanwhirl.app.store

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import com.scanwhirl.app.util.ImportFixtures
import com.scanwhirl.app.util.PageReplace
import com.scanwhirl.app.util.PdfGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import kotlin.math.abs

/**
 * FF-G-lite: a compression pass may change raster pixels, never the physical paper size.
 * Source A4 pages are 1240x1754 px (595.2 x 841.9 pt at the writer's fixed 150 DPI).
 * After a long-side-1000 compress the pixels are 707x1000, but the PDF page box must
 * stay 595 x 842 pt. Every assertion below reads the real MediaBox from the generated
 * PDF, so it fails RED on the unfixed writer (which would emit ~339 x 480 pt).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CompressPageGeometryTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)

    private fun save(vararg dims: Pair<Int, Int>): String {
        val files = dims.mapIndexed { i, (w, h) ->
            File(app.cacheDir, "ffg_${UUID.randomUUID()}_$i.jpg").apply {
                writeBytes(ImportFixtures.image(width = w, height = h))
            }
        }
        return runBlocking { repo.saveScan(files.map { Uri.fromFile(it) }, null, "Paper") }.id
    }

    private fun boxes(pdf: File): List<Pair<Int, Int>> =
        Regex("/MediaBox \\[0 0 (\\d+) (\\d+)]")
            .findAll(pdf.readText(Charsets.ISO_8859_1))
            .map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            .toList()

    private fun assertWithin(expected: Pair<Int, Int>, actual: Pair<Int, Int>, tolerance: Double = 0.5) {
        assertTrue(
            "expected ${expected.first}x${expected.second} pt, got ${actual.first}x${actual.second} pt",
            abs(actual.first - expected.first.toDouble()) <= tolerance &&
                abs(actual.second - expected.second.toDouble()) <= tolerance
        )
    }

    @Test
    fun `compress to long side 1000 keeps an A4 page box`() = runBlocking {
        val id = save(1240 to 1754)
        val out = repo.compressScan(id, 60, 1000)
        val info = PdfGenerator.readJpegInfo(out.pageFiles.single().readBytes())
        assertTrue(
            "long side is downsampled (${info.width}x${info.height})",
            maxOf(info.width, info.height) in 990..1000
        )
        assertEquals(1, boxes(out.pdfFile).size)
        assertWithin(595 to 842, boxes(out.pdfFile).single())
    }

    @Test
    fun `target KB compression keeps each source page box`() = runBlocking {
        // A4 (1240x1754) + US Letter at 150 DPI (1275x1650).
        val id = save(1240 to 1754, 1275 to 1650)
        val out = repo.compressScanToTarget(id, 40_000L)
        assertEquals("every page is kept", 2, out.pageFiles.size)
        val actual = boxes(out.pdfFile)
        assertEquals(2, actual.size)
        assertWithin(595 to 842, actual[0])
        assertWithin(612 to 792, actual[1])
    }

    @Test
    fun `mixed page sizes survive a long side compress per page`() = runBlocking {
        val id = save(1240 to 1754, 1275 to 1650)
        val out = repo.compressScan(id, 60, 1000)
        val actual = boxes(out.pdfFile)
        assertEquals(2, actual.size)
        assertWithin(595 to 842, actual[0])
        assertWithin(612 to 792, actual[1])
    }

    @Test
    fun `a later PDF regeneration still keeps the compressed paper size`() = runBlocking {
        val id = save(1240 to 1754)
        val out = repo.compressScan(id, 60, 1000)
        val page = out.pageFiles.single()
        val (w, h) = requireNotNull(PageReplace.bounds(page))
        repo.ocrStore.putPage(
            out.id, page.name, "Token",
            OcrPage(w, h, listOf(OcrLine("Token", 5, 5, 50, 30)))
        )
        assertTrue("precondition: make searchable succeeds", repo.makeSearchable(out.id))
        assertWithin(595 to 842, boxes(out.pdfFile).single())
    }
}
