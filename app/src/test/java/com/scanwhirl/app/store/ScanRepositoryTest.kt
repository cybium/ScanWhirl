package com.scanwhirl.app.store

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.model.ScanDocument
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ScanRepositoryTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private var uriCounter = 0

    private fun contentUri(bytes: ByteArray): Uri {
        val uri = Uri.parse("content://test/fake_${uriCounter++}.jpg")
        shadowOf(context.contentResolver).registerInputStream(uri, bytes.inputStream())
        return uri
    }

    private val fakeJpeg = ByteArray(2048) { (it % 251).toByte() }
    private val fakePdf = ByteArray(4096) { (it % 199).toByte() }

    @Test
    fun `save then list returns the document`() = runBlocking {
        val repo = ScanRepository(context)
        val name = "My receipt"

        val saved = repo.saveScan(listOf(contentUri(fakeJpeg)), contentUri(fakePdf), name)

        val listed = repo.listDocuments()
        assertEquals(1, listed.size)
        val doc = listed.first()
        assertEquals(saved.id, doc.id)
        assertEquals(name, doc.name)
        assertEquals(1, doc.pageCount)
        assertTrue("page jpg should exist", doc.pageFiles.first().exists())
        assertTrue("page jpg should be non-empty", doc.pageFiles.first().length() > 0)
        assertTrue("pdf should exist", doc.pdfFile.exists())
        assertTrue("pdf should be non-empty", doc.pdfFile.length() > 0)
    }

    @Test
    fun `save multi-page scan lists all pages in order`() = runBlocking {
        val repo = ScanRepository(context)
        val pages = listOf(
            contentUri(realJpegBytes(320, 240, android.graphics.Color.rgb(250, 100, 100))),
            contentUri(realJpegBytes(320, 240, android.graphics.Color.rgb(100, 250, 100))),
            contentUri(realJpegBytes(320, 240, android.graphics.Color.rgb(100, 100, 250)))
        )

        val saved = repo.saveScan(pages, null, "3 pages")

        val doc = repo.listDocuments().first { it.id == saved.id }
        assertTrue("pdf generated even without scanner pdf", doc.pdfFile.exists())
        assertEquals(3, doc.pageCount)
        assertEquals(listOf("page_000.jpg", "page_001.jpg", "page_002.jpg"), doc.pageFiles.map { it.name })
    }

    @Test
    fun `blank name falls back to next Document N name`() = runBlocking {
        val repo = ScanRepository(context)
        val saved = repo.saveScan(listOf(contentUri(fakeJpeg)), null, "   ")
        val doc = repo.listDocuments().first { it.id == saved.id }
        assertTrue("got ${doc.name}", doc.name.startsWith("Document "))
    }

    @Test
    fun `newlines in name are stripped so meta stays parseable`() = runBlocking {
        val repo = ScanRepository(context)
        val saved = repo.saveScan(listOf(contentUri(fakeJpeg)), null, "line1\nline2")
        val doc = repo.listDocuments().first { it.id == saved.id }
        assertEquals("line1 line2", doc.name)
        assertEquals(1, doc.pageCount)
    }

    @Test
    fun `delete removes document from list`() = runBlocking {
        val repo = ScanRepository(context)
        val saved = repo.saveScan(listOf(contentUri(fakeJpeg)), null, "to delete")
        repo.deleteDocument(saved.id)
        assertTrue(repo.listDocuments().none { it.id == saved.id })
        val dir = File(File(context.filesDir, "documents"), saved.id)
        assertTrue(!dir.exists())
    }

    @Test
    fun `rename updates name in list`() = runBlocking {
        val repo = ScanRepository(context)
        val saved = repo.saveScan(listOf(contentUri(fakeJpeg)), null, "old")
        repo.renameDocument(saved.id, "new name")
        val doc = repo.listDocuments().first { it.id == saved.id }
        assertEquals("new name", doc.name)
    }

    @Test
    fun `unopenable uri fails loudly instead of saving broken doc`() {
        val repo = ScanRepository(context)
        val badUri = Uri.parse("content://test/does-not-exist")
        var thrown = false
        try {
            runBlocking { repo.saveScan(listOf(badUri), null, "broken") }
        } catch (e: Exception) {
            thrown = true
        }
        assertTrue("expected exception for unreadable uri", thrown)
        assertEquals(0, repo.listDocuments().size)
    }

    private fun realJpegBytes(w: Int, h: Int, color: Int): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bmp).drawColor(color)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private suspend fun saveDoc(repo: ScanRepository, name: String, pageCount: Int = 1): ScanDocument {
        val uris = (0 until pageCount).map {
            contentUri(realJpegBytes(310, 444, android.graphics.Color.rgb(it * 50 + 50, 200, 100)))
        }
        return repo.saveScan(uris, null, name)
    }

    @Test
    fun `merge combines pages in order and keeps sources intact`() = runBlocking {
        val repo = ScanRepository(context)
        val a = saveDoc(repo, "Doc A", pageCount = 2)
        val b = saveDoc(repo, "Doc B", pageCount = 3)

        val merged = repo.mergeDocuments(listOf(a.id, b.id), "Contract bundle")

        assertEquals("Contract bundle", merged.name)
        assertEquals(5, merged.pageCount)
        assertEquals(
            listOf("page_000.jpg", "page_001.jpg", "page_002.jpg", "page_003.jpg", "page_004.jpg"),
            merged.pageFiles.map { it.name }
        )
        assertTrue("merged pdf should exist", merged.pdfFile.exists())
        val pdfText = merged.pdfFile.readText(Charsets.ISO_8859_1)
        assertTrue("merged pdf should have 5 pages", "/Count 5" in pdfText)

        // sources untouched
        val srcA = repo.listDocuments().first { it.id == a.id }
        val srcB = repo.listDocuments().first { it.id == b.id }
        assertEquals(2, srcA.pageCount)
        assertEquals(3, srcB.pageCount)
    }

    @Test
    fun `merge default name is timestamped`() = runBlocking {
        val repo = ScanRepository(context)
        val a = saveDoc(repo, "A")
        val b = saveDoc(repo, "B")
        val merged = repo.mergeDocuments(listOf(a.id, b.id), null)
        assertTrue(merged.name.startsWith("Merged scan "))
    }

    @Test
    fun `merge refuses single source`() = runBlocking {
        val repo = ScanRepository(context)
        val a = saveDoc(repo, "Only")
        val thrown = try {
            repo.mergeDocuments(listOf(a.id), null); false
        } catch (e: IllegalArgumentException) {
            true
        }
        assertTrue(thrown)
    }

    @Test
    fun `delete page reduces count and renumbers`() = runBlocking {
        val repo = ScanRepository(context)
        val doc = saveDoc(repo, "3 pages", pageCount = 3)

        val after = repo.deletePage(doc.id, 1)

        assertEquals(2, after.pageCount)
        assertEquals(listOf("page_000.jpg", "page_001.jpg"), after.pageFiles.map { it.name })
        assertTrue("/Count 2" in after.pdfFile.readText(Charsets.ISO_8859_1))
    }

    @Test
    fun `delete last page is refused`() = runBlocking {
        val repo = ScanRepository(context)
        val doc = saveDoc(repo, "1 page")
        val thrown = try {
            repo.deletePage(doc.id, 0); false
        } catch (e: IllegalArgumentException) {
            true
        }
        assertTrue(thrown)
        assertEquals(1, repo.listDocuments().first { it.id == doc.id }.pageCount)
    }

    @Test
    fun `rotate page swaps dimensions and keeps page count`() = runBlocking {
        val repo = ScanRepository(context)
        val doc = saveDoc(repo, "rotate me") // 310x444 portrait

        val after = repo.rotatePage(doc.id, 0)

        assertEquals(1, after.pageCount)
        val dims = com.scanwhirl.app.util.PdfGenerator.readJpegInfo(after.pageFiles[0].readBytes())
        assertEquals(444, dims.width)
        assertEquals(310, dims.height)
        assertTrue("/Count 1" in after.pdfFile.readText(Charsets.ISO_8859_1))
    }

    @Test
    fun `move page reorders and rebuilds pdf`() = runBlocking {
        val repo = ScanRepository(context)
        val doc = saveDoc(repo, "reorder", pageCount = 3)
        val firstBytes = doc.pageFiles[0].readBytes()
        val middleBytes = doc.pageFiles[1].readBytes()
        val lastBytes = doc.pageFiles[2].readBytes()

        val after = repo.movePage(doc.id, 0, 2)

        assertEquals(3, after.pageCount)
        // old first moved to the end; middle and last shifted left
        assertTrue("middle shifted left", middleBytes.contentEquals(after.pageFiles[0].readBytes()))
        assertTrue("last shifted left", lastBytes.contentEquals(after.pageFiles[1].readBytes()))
        assertTrue("first moved to end", firstBytes.contentEquals(after.pageFiles[2].readBytes()))
        assertNotEquals(firstBytes, lastBytes)
        assertTrue("/Count 3" in after.pdfFile.readText(Charsets.ISO_8859_1))
    }

    @Test
    fun `move page no-op when index equal`() = runBlocking {
        val repo = ScanRepository(context)
        val doc = saveDoc(repo, "same", pageCount = 2)
        val before = doc.pageFiles.map { it.readBytes().toList() }

        val after = repo.movePage(doc.id, 1, 1)

        assertEquals(2, after.pageCount)
        assertEquals(before, after.pageFiles.map { it.readBytes().toList() })
    }

    @Test
    fun `createDocument builds new scan with pdf and sequential fallback name`() = runBlocking {
        val repo = ScanRepository(context)
        val bytes = (0 until 2).map { realJpegBytes(310, 444, android.graphics.Color.WHITE) }

        val doc = repo.createDocument(null, bytes)

        assertEquals(2, doc.pageCount)
        assertTrue("got ${doc.name}", doc.name.startsWith("Document "))
        assertTrue(doc.pdfFile.exists())
        assertTrue("/Count 2" in doc.pdfFile.readText(Charsets.ISO_8859_1))
        assertEquals(doc.id, repo.listDocuments().first { it.id == doc.id }.id)
    }


    @Test
    fun `compressScan creates smaller copy and leaves source intact`() = runBlocking {
        val repo = ScanRepository(context)
        // high-quality pages so recompression at q=20 shrinks them
        val doc = saveDoc(repo, "big scan", pageCount = 3)

        val compressed = repo.compressScan(doc.id, quality = 20, maxDim = 400)

        assertEquals("${doc.name} (compressed)", compressed.name)
        assertEquals(3, compressed.pageCount)
        assertTrue(compressed.pdfFile.exists())
        assertTrue("/Count 3" in compressed.pdfFile.readText(Charsets.ISO_8859_1))
        // source untouched
        val source = repo.listDocuments().first { it.id == doc.id }
        assertEquals(3, source.pageCount)
        assertTrue(!source.name.contains("(compressed)"))
        // downscale applied
        for (page in compressed.pageFiles) {
            val (w, h) = com.scanwhirl.app.util.ImageTools.imageSize(page.readBytes())!!
            assertTrue("page should be <=400px: $w x $h", maxOf(w, h) <= 400)
        }
        // both docs listed
        assertEquals(2, repo.listDocuments().size)
    }

    @Test
    fun `compressScan without maxDim keeps page dimensions`() = runBlocking {
        val repo = ScanRepository(context)
        val doc = saveDoc(repo, "keep dims")

        val compressed = repo.compressScan(doc.id, quality = 75, maxDim = null)

        val before = com.scanwhirl.app.util.ImageTools.imageSize(doc.pageFiles[0].readBytes())
        val after = com.scanwhirl.app.util.ImageTools.imageSize(compressed.pageFiles[0].readBytes())
        assertEquals(before, after)
    }

    @Test
    fun `saveScan applies per-page filters without touching siblings`() = runBlocking {
        val repo = ScanRepository(context)
        val doc = repo.saveScan(
            listOf(
                contentUri(realJpegBytes(320, 240, android.graphics.Color.rgb(250, 100, 100))),
                contentUri(realJpegBytes(320, 240, android.graphics.Color.rgb(100, 250, 100)))
            ),
            null,
            "per page",
            pageFilters = mapOf(
                0 to com.scanwhirl.app.util.ScanFilter.BLACKWHITE,
                1 to com.scanwhirl.app.util.ScanFilter.ORIGINAL
            )
        )

        // Page 1 was binarized: its center pixel is pure gray.
        val bw = android.graphics.BitmapFactory.decodeFile(doc.pageFiles[0].path)
        val c0 = bw.getPixel(bw.width / 2, bw.height / 2)
        bw.recycle()
        assertEquals((c0 shr 16) and 0xFF, (c0 shr 8) and 0xFF)
        assertEquals((c0 shr 8) and 0xFF, c0 and 0xFF)

        // Page 2 was left alone: still colored.
        val kept = android.graphics.BitmapFactory.decodeFile(doc.pageFiles[1].path)
        val c1 = kept.getPixel(kept.width / 2, kept.height / 2)
        kept.recycle()
        assertNotEquals((c1 shr 16) and 0xFF, (c1 shr 8) and 0xFF)
    }

    @Test
    fun `saveScan copies the scanner pdf only when every page is original`() = runBlocking {
        val repo = ScanRepository(context)
        // A real generated PDF stands in for the scanner's output.
        val seed = repo.saveScan(
            listOf(contentUri(realJpegBytes(320, 240, android.graphics.Color.RED))),
            null,
            "seed"
        )
        val scannerPdf = seed.pdfFile.readBytes()

        // All-original pages: the scanner's PDF is copied as-is.
        val untouched = repo.saveScan(
            listOf(contentUri(realJpegBytes(320, 240, android.graphics.Color.BLUE))),
            contentUri(scannerPdf),
            "untouched",
            pageFilters = mapOf(0 to com.scanwhirl.app.util.ScanFilter.ORIGINAL)
        )
        assertTrue(
            "scanner pdf should be copied when nothing was filtered",
            scannerPdf.contentEquals(untouched.pdfFile.readBytes())
        )

        // A filtered page forces a regeneration instead of the stale copy.
        val filtered = repo.saveScan(
            listOf(contentUri(realJpegBytes(320, 240, android.graphics.Color.BLUE))),
            contentUri(scannerPdf),
            "filtered",
            pageFilters = mapOf(0 to com.scanwhirl.app.util.ScanFilter.ENHANCE)
        )
        assertTrue(
            "scanner pdf must not be copied when a page is filtered",
            !scannerPdf.contentEquals(filtered.pdfFile.readBytes())
        )
    }


}
