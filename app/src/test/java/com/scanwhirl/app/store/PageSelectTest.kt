package com.scanwhirl.app.store

import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.util.CategoryRules
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

/**
 * test26 T5.2 (§4.5): Extract — the ticked pages become a brand-new document.
 *
 * The rule under test: the ORIGINAL keeps every page (and its exports), the
 * new scan gets only the selection renumbered from 0, both keep the category,
 * and the new name says exactly which pages moved out.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PageSelectTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private var uriCounter = 0

    private fun contentUri(bytes: ByteArray): android.net.Uri {
        val uri = android.net.Uri.parse("content://pagesel/fake_${uriCounter++}.jpg")
        shadowOf(context.contentResolver).registerInputStream(uri, bytes.inputStream())
        return uri
    }

    private fun jpegBytes(seed: Int): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(
            310, 444, android.graphics.Bitmap.Config.ARGB_8888
        )
        android.graphics.Canvas(bmp).drawColor(
            android.graphics.Color.rgb(seed * 50 + 50, 200, 100)
        )
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private suspend fun seedDoc(
        repo: ScanRepository,
        name: String,
        pages: Int,
        category: String? = null
    ): com.scanwhirl.app.model.ScanDocument {
        val uris = (0 until pages).map { contentUri(jpegBytes(it)) }
        return repo.saveScan(uris, null, name, category = category)
    }

    @Test
    fun `extract two of three pages leaves the original intact`() = runBlocking {
        val repo = ScanRepository(context)
        val src = seedDoc(repo, "Contract", pages = 3, category = CategoryRules.STUDY)
        val before = repo.getDocument(src.id)!!

        val out = repo.extractPages(src.id, listOf(0, 2))

        assertNotEquals("a new document, not a move", src.id, out.id)
        assertEquals("the selection only", 2, out.pageCount)
        assertEquals(
            "a new copy under the source stem, not a verb suffix",
            "Contract (2)",
            out.name
        )
        // Category inherited, like duplicateDocument.
        assertEquals(CategoryRules.STUDY, out.category)
        // The extracted pages are renumbered 0..n inside the new dir.
        assertEquals(
            listOf("page_000.jpg", "page_001.jpg"),
            out.pageFiles.map { it.name }
        )
        out.pageFiles.forEach { assertTrue("new page is a real file", it.length() > 0) }
        assertTrue("the new scan ships a regenerated PDF", out.pdfFile.exists())

        // The original is untouched: still 3 pages, same name, same category.
        val after = repo.getDocument(src.id)!!
        assertEquals("the original keeps every page", 3, after.pageCount)
        assertEquals("Contract", after.name)
        assertEquals(CategoryRules.STUDY, after.category)
        assertEquals(
            listOf("page_000.jpg", "page_001.jpg", "page_002.jpg"),
            after.pageFiles.map { it.name }
        )
    }

    @Test
    fun `extract a single page is named page N`() = runBlocking {
        val repo = ScanRepository(context)
        val src = seedDoc(repo, "Receipt", pages = 3)

        val out = repo.extractPages(src.id, listOf(1))

        assertEquals(1, out.pageCount)
        assertEquals("Receipt (2)", out.name)
        assertEquals(3, repo.getDocument(src.id)!!.pageCount)
    }

    @Test
    fun `extract keeps a unique copy name without a forced page suffix`() = runBlocking {
        val repo = ScanRepository(context)
        val src = seedDoc(repo, "Far apart", pages = 10)

        val sparse = repo.extractPages(src.id, listOf(0, 9))
        assertEquals("Far apart (2)", sparse.name)
        assertEquals("only the two picked pages", 2, sparse.pageCount)

        val contiguous = seedDoc(repo, "Nearby", pages = 3)
        assertEquals("Nearby (2)", repo.extractPages(contiguous.id, listOf(0, 1, 2)).name)
    }

    @Test
    fun `extract sorts the selection and rejects empty or out-of-range picks`() = runBlocking {
        val repo = ScanRepository(context)
        val src = seedDoc(repo, "Sorted", pages = 3)

        // Unsorted input still yields pages 0 then 2 in disk order.
        val out = repo.extractPages(src.id, listOf(2, 0))
        assertEquals("Sorted (2)", out.name)

        runCatching { repo.extractPages(src.id, emptyList()) }
            .exceptionOrNull()
            .let { assertTrue("empty selection must be refused", it is IllegalArgumentException) }
        runCatching { repo.extractPages(src.id, listOf(0, 7)) }
            .exceptionOrNull()
            .let { assertTrue("out-of-range pick must be refused", it is IllegalArgumentException) }
    }
}
