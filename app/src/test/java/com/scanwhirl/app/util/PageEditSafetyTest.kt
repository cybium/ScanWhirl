package com.scanwhirl.app.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.store.ScanRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException

/**
 * M08 P0: Rotate / Crop / Filter must never destroy the only good copy of a page.
 *
 * Failpoints: the rename used to swap a page in ([Durable.renameSeam]), the fsync of
 * the staged copy ([Durable.beforeFileSync]) and an unreadable kept original. After
 * every failure the page, its `.orig` and the committed PDF must be byte-identical,
 * and no staging file may be left behind as a page.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PageEditSafetyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private var uriCounter = 0

    @After
    fun reset() = Durable.resetForTests()

    private fun pageJpeg(w: Int = 300, h: Int = 420): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val p = Paint().apply { color = Color.BLACK; textSize = 28f; isAntiAlias = true }
        for (i in 0 until 8) c.drawText("Line $i ZEPHYR", 20f, 50f + i * 44f, p)
        p.color = Color.rgb(200, 40, 40)
        c.drawRect(0f, 0f, w / 3f, 20f, p) // asymmetric mark: rotation really changes bytes
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun contentUri(bytes: ByteArray): Uri {
        val uri = Uri.parse("content://test/edit_${uriCounter++}.jpg")
        shadowOf(context.contentResolver).registerInputStream(uri, bytes.inputStream())
        return uri
    }

    private fun failingRename(match: (File) -> Boolean) {
        Durable.renameSeam = { from, to -> if (match(to)) false else from.renameTo(to) }
    }

    private fun strayFiles(dir: File): List<String> =
        dir.list()!!.filter { it.contains(".tmp") || it.contains(".swtmp") || it.contains(".swbak") }

    // ---- single page file (Review staging, viewer) ----------------------------------

    @Test
    fun `rotate whose swap rename fails keeps the only page byte-identical`() {
        val page = tmp.newFile("page_001.jpg").apply { writeBytes(pageJpeg()) }
        val before = page.readBytes()
        failingRename { it == page }

        try {
            ImageTools.rotate90(page, clockwise = true)
            fail("a failed swap must be reported")
        } catch (_: Exception) {
        }

        assertTrue("the page must still exist", page.exists())
        assertArrayEquals("the page must be untouched", before, page.readBytes())
        assertEquals(emptyList<String>(), strayFiles(page.parentFile!!))
    }

    @Test
    fun `crop whose swap rename fails keeps the only page byte-identical`() {
        val page = tmp.newFile("page_002.jpg").apply { writeBytes(pageJpeg()) }
        val before = page.readBytes()
        failingRename { it == page }

        try {
            ImageTools.cropTo(page, RectF(10f, 10f, 200f, 300f))
            fail("a failed swap must be reported")
        } catch (_: Exception) {
        }

        assertArrayEquals(before, page.readBytes())
        assertEquals(emptyList<String>(), strayFiles(page.parentFile!!))
    }

    @Test
    fun `fsync failure of the staged copy leaves the page untouched`() {
        val page = tmp.newFile("page_003.jpg").apply { writeBytes(pageJpeg()) }
        val before = page.readBytes()
        Durable.beforeFileSync = { throw IOException("No space left on device") }

        try {
            ImageTools.rotate90(page)
            fail("an unsynced copy must not replace the page")
        } catch (_: IOException) {
        }

        assertArrayEquals(before, page.readBytes())
        assertEquals(emptyList<String>(), strayFiles(page.parentFile!!))
    }

    @Test
    fun `successful rotate and crop leave no staging files and a decodable page`() {
        val page = tmp.newFile("page_004.jpg").apply { writeBytes(pageJpeg(300, 420)) }
        ImageTools.rotate90(page)
        assertEquals(420 to 300, ImageTools.imageSize(page.readBytes()))
        ImageTools.cropTo(page, RectF(20f, 30f, 220f, 230f))
        assertEquals(200 to 200, ImageTools.imageSize(page.readBytes()))
        assertEquals(emptyList<String>(), strayFiles(page.parentFile!!))
    }

    // ---- saved documents: page + kept original + PDF move together -------------------

    private fun docWithOriginal(repo: ScanRepository, name: String) = runBlocking {
        val doc = repo.saveScan(listOf(contentUri(pageJpeg())), null, name)
        // A filter creates the kept original page_000.orig next to the page.
        repo.setPageFilter(doc.id, 0, ScanFilter.ENHANCE)
        repo.listDocuments().first { it.id == doc.id }
    }

    @Test
    fun `document crop with an unreadable kept original changes nothing`() = runBlocking {
        val repo = ScanRepository(context)
        val doc = docWithOriginal(repo, "crop safety")
        val page = doc.pageFiles[0]
        val orig = File(page.parentFile, "page_000.orig")
        orig.writeBytes(byteArrayOf(1, 2, 3)) // damaged original
        val pageBefore = page.readBytes()
        val origBefore = orig.readBytes()
        val pdfBefore = doc.pdfFile.readBytes()

        try {
            repo.cropPage(doc.id, 0, RectF(10f, 10f, 200f, 300f))
            fail("crop must refuse when the original cannot follow")
        } catch (_: Exception) {
        }

        assertArrayEquals("page", pageBefore, page.readBytes())
        assertArrayEquals("kept original", origBefore, orig.readBytes())
        assertArrayEquals("committed PDF", pdfBefore, doc.pdfFile.readBytes())
    }

    @Test
    fun `document rotate whose original swap fails rolls the page back`() = runBlocking {
        val repo = ScanRepository(context)
        val doc = docWithOriginal(repo, "rotate safety")
        val page = doc.pageFiles[0]
        val orig = File(page.parentFile, "page_000.orig")
        val pageBefore = page.readBytes()
        val origBefore = orig.readBytes()
        val pdfBefore = doc.pdfFile.readBytes()
        failingRename { it.name == "page_000.orig" }

        try {
            repo.rotatePage(doc.id, 0)
            fail("a half-applied rotate must be reported")
        } catch (_: Exception) {
        }

        assertTrue("kept original must survive", orig.exists())
        assertArrayEquals("kept original", origBefore, orig.readBytes())
        assertArrayEquals("page rolled back", pageBefore, page.readBytes())
        assertArrayEquals("committed PDF", pdfBefore, doc.pdfFile.readBytes())
        assertEquals(emptyList<String>(), strayFiles(page.parentFile!!))
    }

    @Test
    fun `document filter whose page swap fails keeps page and original`() = runBlocking {
        val repo = ScanRepository(context)
        val doc = docWithOriginal(repo, "filter safety")
        val page = doc.pageFiles[0]
        val orig = File(page.parentFile, "page_000.orig")
        val pageBefore = page.readBytes()
        val origBefore = orig.readBytes()
        failingRename { it == page }

        try {
            repo.setPageFilter(doc.id, 0, ScanFilter.BLACKWHITE)
            fail("a failed filter swap must be reported")
        } catch (_: Exception) {
        }

        assertArrayEquals(pageBefore, page.readBytes())
        assertArrayEquals(origBefore, orig.readBytes())
    }

    @Test
    fun `document rotate and crop still succeed and keep page and original aligned`() = runBlocking {
        val repo = ScanRepository(context)
        val doc = docWithOriginal(repo, "happy path")
        val rotated = repo.rotatePage(doc.id, 0)
        val page = rotated.pageFiles[0]
        val orig = File(page.parentFile, "page_000.orig")
        assertEquals(420 to 300, ImageTools.imageSize(page.readBytes()))
        assertEquals(420 to 300, ImageTools.imageSize(orig.readBytes()))
        val cropped = repo.cropPage(doc.id, 0, RectF(0f, 0f, 400f, 250f))
        assertEquals(400 to 250, ImageTools.imageSize(cropped.pageFiles[0].readBytes()))
        assertEquals(400 to 250, ImageTools.imageSize(orig.readBytes()))
        assertTrue("/Count 1" in cropped.pdfFile.readText(Charsets.ISO_8859_1))
        assertEquals(emptyList<String>(), strayFiles(page.parentFile!!))
    }
}
