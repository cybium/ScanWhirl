package com.scanwhirl.app.store

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import com.scanwhirl.app.store.backup.FullBackup
import com.scanwhirl.app.util.ImportFixtures
import com.scanwhirl.app.util.PageRanges
import com.scanwhirl.app.util.PageReplace
import com.scanwhirl.app.util.PdfGenerator
import com.scanwhirl.app.util.Durable
import com.scanwhirl.app.util.ImageTools
import com.scanwhirl.app.util.ScanFilter
import org.junit.After
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
    @After
    fun resetFaults() {
        Durable.resetForTests()
        OpFaults.reset()
    }

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

    private fun pixelBox(page: File): Pair<Int, Int> {
        val (w, h) = requireNotNull(PageReplace.bounds(page))
        return PdfGenerator.sizeInPoints(w, h)
    }

    @Test(timeout = 120_000)
    fun `death after renumbering cannot attach stale paper sizes to different pages`() = runBlocking {
        val source = repo.compressScan(save(1240 to 1754, 1275 to 1650), 60, 600)
        // A killed old writer left the renamed pixels and the previous sidecar.
        val first = source.pageFiles[0].readBytes()
        source.pageFiles[0].writeBytes(source.pageFiles[1].readBytes())
        source.pageFiles[1].writeBytes(first)
        val expected = source.pageFiles.map(::pixelBox)
        val reopened = ScanRepository(app)
        val rebuilt = reopened.compressScan(source.id, 50, 400)
        assertEquals(expected, boxes(rebuilt.pdfFile))
        // Repair must be durable: a second reopen/recompress still uses the safe boxes.
        val again = ScanRepository(app).compressScan(source.id, 40, 300)
        assertEquals(expected, boxes(again.pdfFile))
    }

    @Test(timeout = 120_000)
    fun `stale rotation is rejected even when repair sidecar rename fails`() = runBlocking {
        val source = repo.compressScan(save(1240 to 1754, 1275 to 1650), 60, 600)
        PageReplace.commit(ImageTools.stageRotate90(source.pageFiles[0], true))
        val expected = listOf(pixelBox(source.pageFiles[0]), 612 to 792)
        source.pageFiles.forEach { page ->
            val (w, h) = requireNotNull(PageReplace.bounds(page))
            repo.ocrStore.putPage(source.id, page.name, "Token",
                OcrPage(w, h, listOf(OcrLine("Token", 5, 5, 50, 30))))
        }
        Durable.renameSeam = { from, to ->
            if (to.name == "page_sizes.txt") false else from.renameTo(to)
        }
        assertTrue(ScanRepository(app).makeSearchable(source.id))
        assertEquals(expected, boxes(source.pdfFile))
        Durable.resetForTests()
        val rebuilt = ScanRepository(app).compressScan(source.id, 50, 400)
        assertEquals(expected, boxes(rebuilt.pdfFile))
    }

    @Test(timeout = 120_000)
    fun `failed metadata publish never mutates the published page files`() = runBlocking {
        for (operation in listOf("delete", "move", "rotate")) {
            val source = repo.compressScan(save(1240 to 1754, 1275 to 1650), 60, 600)
            val before = source.pageFiles.map { it.readBytes() }
            Durable.renameSeam = { from, to ->
                if (to.name == "page_sizes.txt") false else from.renameTo(to)
            }
            try {
                when (operation) {
                    "delete" -> repo.deletePage(source.id, 0)
                    "move" -> repo.movePage(source.id, 0, 1)
                    else -> repo.rotatePage(source.id, 0)
                }
                org.junit.Assert.fail("metadata rename failure must abort $operation")
            } catch (_: java.io.IOException) {
                source.pageFiles.forEachIndexed { i, page ->
                    org.junit.Assert.assertArrayEquals(before[i], page.readBytes())
                }
            } finally {
                Durable.resetForTests()
            }
        }
    }

    @Test(timeout = 120_000)
    fun `death after metadata prepare or page mutation never trusts mismatched orientation`() = runBlocking {
        class Death : Error()
        for (step in listOf("geometry:prepared", "geometry:mutated")) {
            val source = repo.compressScan(save(1240 to 1754, 1275 to 1650), 60, 600)
            val oldPixelBox = pixelBox(source.pageFiles[0])
            OpFaults.hook = { if (it == step) throw Death() }
            try {
                repo.rotatePage(source.id, 0)
                org.junit.Assert.fail("Expected simulated death")
            } catch (_: Death) {
                // A real process kill would not unwind; reopen uses only the durable disk pair.
            } finally {
                OpFaults.reset()
            }
            val expected = if (step == "geometry:mutated") 842 to 595 else oldPixelBox
            val rebuilt = ScanRepository(app).compressScan(source.id, 50, 400)
            assertEquals(listOf(expected, 612 to 792), boxes(rebuilt.pdfFile))
        }
    }

    @Test(timeout = 120_000)
    fun `death after renumbering uses the prepublished content bound sizes`() = runBlocking {
        class Death : Error()
        for (delete in listOf(false, true)) {
            val source = repo.compressScan(save(1240 to 1754, 1275 to 1650), 60, 600)
            OpFaults.hook = { if (it == "geometry:mutated") throw Death() }
            try {
                if (delete) repo.deletePage(source.id, 0) else repo.movePage(source.id, 0, 1)
                org.junit.Assert.fail("Expected simulated death")
            } catch (_: Death) {
            } finally {
                OpFaults.reset()
            }
            val rebuilt = ScanRepository(app).compressScan(source.id, 50, 400)
            val expected = if (delete) listOf(612 to 792) else listOf(612 to 792, 595 to 842)
            assertEquals(expected, boxes(rebuilt.pdfFile))
        }
    }

    @Test(timeout = 120_000)
    fun `intentional refilter updates the fingerprint without shrinking the paper`() = runBlocking {
        val source = repo.compressScan(save(1240 to 1754, 1275 to 1650), 60, 600)
        val filtered = repo.setPageFilter(source.id, 0, ScanFilter.GREY)
        assertEquals(listOf(595 to 842, 612 to 792), boxes(filtered.pdfFile))
        val original = repo.setPageFilter(source.id, 0, ScanFilter.ORIGINAL)
        assertEquals(listOf(595 to 842, 612 to 792), boxes(original.pdfFile))
    }

    @Test(timeout = 120_000)
    fun `unchanged bounds alone cannot validate stale pixels or a half turn`() = runBlocking {
        val source = repo.compressScan(save(1240 to 1754), 60, 600)
        val page = source.pageFiles.single()
        val originalBounds = PageReplace.bounds(page)
        repeat(2) { PageReplace.commit(ImageTools.stageRotate90(page, true)) }
        assertEquals(originalBounds, PageReplace.bounds(page))
        val rebuilt = ScanRepository(app).compressScan(source.id, 50, 400)
        assertEquals(listOf(pixelBox(page)), boxes(rebuilt.pdfFile))
    }

    @Test(timeout = 120_000)
    fun `repeated compress and page operations preserve mixed paper identities`() = runBlocking {
        val first = repo.compressScan(save(1240 to 1754, 1275 to 1650), 60, 1000)
        val second = repo.compressScan(first.id, 50, 600)
        assertEquals(listOf(595 to 842, 612 to 792), boxes(second.pdfFile))
        val targeted = repo.compressScanToTarget(second.id, 10_000L)
        assertEquals(listOf(595 to 842, 612 to 792), boxes(targeted.pdfFile))
        val moved = repo.movePage(second.id, 0, 1)
        assertEquals(listOf(612 to 792, 595 to 842), boxes(moved.pdfFile))
        val rotated = repo.rotatePage(second.id, 0)
        assertEquals(listOf(792 to 612, 595 to 842), boxes(rotated.pdfFile))
        val deleted = repo.deletePage(second.id, 1)
        assertEquals(listOf(792 to 612), boxes(deleted.pdfFile))
    }

    @Test(timeout = 120_000)
    fun `copies extraction split and merge carry compressed geometry`() = runBlocking {
        val source = repo.compressScan(save(1240 to 1754, 1275 to 1650), 60, 600)
        val duplicate = repo.duplicateDocument(source.id, "Duplicate")
        assertEquals(listOf(595 to 842, 612 to 792), boxes(duplicate.pdfFile))
        val duplicateRebuilt = repo.compressScan(duplicate.id, 50, 400)
        assertEquals(listOf(595 to 842, 612 to 792), boxes(duplicateRebuilt.pdfFile))
        val extracted = repo.extractPages(source.id, listOf(1))
        assertEquals(listOf(612 to 792), boxes(extracted.pdfFile))
        val split = repo.splitByRange(source.id, listOf(PageRanges.Span(1, 1), PageRanges.Span(2, 2)))
        assertEquals(listOf(listOf(595 to 842), listOf(612 to 792)), split.map { boxes(it.pdfFile) })
        val merged = repo.mergeDocuments(listOf(extracted.id, duplicate.id), "Merged")
        assertEquals(listOf(612 to 792, 595 to 842, 612 to 792), boxes(merged.pdfFile))
    }

    @Test(timeout = 120_000)
    fun `draft geometry survives rotate insert move delete and both publish modes`() = runBlocking {
        for (replace in listOf(false, true)) {
            val source = repo.compressScan(save(1240 to 1754, 1275 to 1650), 60, 600)
            val insert = File(app.cacheDir, "insert_${UUID.randomUUID()}.jpg").apply {
                writeBytes(ImportFixtures.image(width = 1000, height = 500))
            }
            repo.draftRotate(source.id, 0)
            repo.draftInsertPages(source.id, listOf(insert, insert), 1)
            repo.draftMove(source.id, 3, 0)
            repo.draftDelete(source.id, 3)
            // Reload through a new repository: metadata must be durable, not in-memory.
            val reloaded = ScanRepository(app)
            val result = if (replace) reloaded.replaceWithDraft(source.id).document
                else reloaded.saveDraftAsNew(source.id, "Edited")
            assertEquals(listOf(612 to 792, 842 to 595, 480 to 240), boxes(result.pdfFile))
        }
    }

    @Test(timeout = 120_000)
    fun `corrupt geometry safely falls back to actual pixels`() = runBlocking {
        val source = repo.compressScan(save(1240 to 1754), 60, 600)
        val info = PdfGenerator.readJpegInfo(source.pageFiles.first().readBytes())
        for (bad in listOf(
            "page_000.jpg=2147483647x-1",
            "page_000.jpg=999999x842",
            "page_000.jpg=no-size",
            "page_000.jpg=595x842\npage_000.jpg=612x792",
            "595x842\n612x792", // legacy count mismatch must not be trusted
            "page_999.jpg=595x842" // a valid size for a different identity is not this page's size
        )) {
            File(source.pageFiles.first().parentFile, "page_sizes.txt").writeText(bad)
            val result = repo.compressScan(source.id, 50, 400)
            assertEquals(bad, listOf(PdfGenerator.sizeInPoints(info.width, info.height)), boxes(result.pdfFile))
        }
    }

    @Test(timeout = 120_000)
    fun `backup restore retains sidecar and a subsequent rebuild keeps paper sizes`() = runBlocking {
        val source = repo.compressScan(save(1240 to 1754, 1275 to 1650), 60, 600)
        val dir = requireNotNull(source.pageFiles.first().parentFile)
        val geometry = File(dir, "page_sizes.txt").readText()
        val backup = FullBackup(app)
        val archive = File(app.cacheDir, "geometry.swbackup")
        backup.write(archive, "fictional-password", FullBackup.Wrap.PASSWORD)
        dir.deleteRecursively()
        backup.restore(archive, "fictional-password")
        assertEquals(geometry, File(dir, "page_sizes.txt").readText())
        val rebuilt = repo.compressScan(source.id, 50, 400)
        assertEquals(listOf(595 to 842, 612 to 792), boxes(rebuilt.pdfFile))
    }

    @Test(timeout = 120_000)
    fun `unbound legacy geometry falls back rather than trusting an unverifiable identity`() = runBlocking {
        val source = repo.compressScan(save(1240 to 1754, 1275 to 1650), 60, 600)
        val expected = source.pageFiles.reversed().map(::pixelBox)
        File(source.pageFiles.first().parentFile, "page_sizes.txt").writeText("595x842\n612x792")
        val moved = repo.movePage(source.id, 0, 1)
        assertEquals(expected, boxes(moved.pdfFile))
    }

    @Test(timeout = 120_000)
    fun `inserting compressed pages and extracting a draft keep their own paper geometry`() = runBlocking {
        val source = repo.compressScan(save(1240 to 1754, 1275 to 1650), 60, 600)
        repo.draftInsertPages(source.id, source.pageFiles, 0)
        val extracted = repo.saveDraftAsNew(source.id, "Draft extract", listOf(1, 2))
        assertEquals(listOf(612 to 792, 595 to 842), boxes(extracted.pdfFile))
        val all = repo.saveDraftAsNew(source.id, "Draft all")
        assertEquals(listOf(595 to 842, 612 to 792, 595 to 842, 612 to 792), boxes(all.pdfFile))
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
