package com.scanwhirl.app.store

import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.store.ScanRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
class OcrConsistencyTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val fakeJpeg = ByteArray(2048) { (it % 251).toByte() }

    private fun contentUri(bytes: ByteArray): android.net.Uri {
        val uri = android.net.Uri.parse("content://test/fake_${System.nanoTime()}.jpg")
        shadowOf(context.contentResolver).registerInputStream(uri, bytes.inputStream())
        return uri
    }

    private fun realJpegBytes(w: Int, h: Int, color: Int): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bmp).drawColor(color)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private suspend fun saveDoc(repo: ScanRepository, name: String, pageCount: Int = 1): String {
        val uris = (0 until pageCount).map {
            contentUri(realJpegBytes(310, 444, android.graphics.Color.rgb(it * 50 + 50, 200, 100)))
        }
        return repo.saveScan(uris, null, name).id
    }

    private val docsDir: File
        get() = File(context.filesDir, "documents")

    @Test
    fun `deletePage removes OCR for deleted page and shifts remaining`() = runBlocking {
        val repo = ScanRepository(context)
        val id = saveDoc(repo, "3 pages", pageCount = 3)
        repo.updateOcrPages(id, mapOf(
            "page_000.jpg" to "page one",
            "page_001.jpg" to "page two",
            "page_002.jpg" to "page three"
        ))

        repo.deletePage(id, 1) // delete middle page

        val text = repo.ocrDoc(id)
        assertEquals(2, text.size)
        assertEquals("page one", text["page_000.jpg"])
        assertEquals("page three", text["page_001.jpg"]) // shifted down
        assertNull(text["page_002.jpg"])
    }

    @Test
    fun `movePage reorders OCR text to match file order`() = runBlocking {
        val repo = ScanRepository(context)
        val id = saveDoc(repo, "2 pages", pageCount = 2)
        repo.updateOcrPages(id, mapOf(
            "page_000.jpg" to "first",
            "page_001.jpg" to "second"
        ))

        repo.movePage(id, 0, 1)

        val text = repo.ocrDoc(id)
        assertEquals("second", text["page_000.jpg"])
        assertEquals("first", text["page_001.jpg"])
    }

    @Test
    fun `rotatePage marks document pending (OCR invalidated)`() = runBlocking {
        val repo = ScanRepository(context)
        val id = saveDoc(repo, "1 page", pageCount = 1)
        repo.updateOcrPages(id, mapOf("page_000.jpg" to "text"))
        OcrStore(docsDir).clearPending(id)

        repo.rotatePage(id, 0)

        assertTrue("rotatePage should mark pending", OcrStore(docsDir).isPending(id))
    }

    @Test
    fun `cropPage marks document pending (OCR invalidated)`() = runBlocking {
        val repo = ScanRepository(context)
        val id = saveDoc(repo, "1 page", pageCount = 1)
        repo.updateOcrPages(id, mapOf("page_000.jpg" to "text"))
        OcrStore(docsDir).clearPending(id)

        repo.cropPage(id, 0, android.graphics.RectF(0f, 0f, 100f, 100f))

        assertTrue("cropPage should mark pending", OcrStore(docsDir).isPending(id))
    }

    @Test
    fun `saveScan marks document pending`() = runBlocking {
        val repo = ScanRepository(context)
        val id = saveDoc(repo, "new scan", pageCount = 1)
        assertTrue("saveScan should mark pending", OcrStore(docsDir).isPending(id))
    }
}
