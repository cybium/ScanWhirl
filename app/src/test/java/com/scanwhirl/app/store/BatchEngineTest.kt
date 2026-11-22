package com.scanwhirl.app.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.util.OutputNames
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.zip.ZipFile

/**
 * v2 UI: 03_tools_hub — the batch engine: one action over many documents, one at a time through
 * the publish path; one failure is recorded and the rest continue; cancel stops after the current
 * document with completed outputs kept and no partial one.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class BatchEngineTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)

    @After
    fun tearDown() = Faults.resetAll()

    private fun docs(count: Int) = (0 until count).map { i ->
        DocFixtures.saved(repo, app, pages = 1, name = "Batch $i")
    }

    @Test
    fun `compress runs over three documents and publishes three outputs`() {
        val ids = docs(3)
        val results = runBlocking {
            repo.runBatch(ids, ScanRepository.BatchAction.Compress(quality = 60, maxDim = null))
        }
        assertEquals(3, results.count { it.ok })
        assertTrue(results.all { it.outputId != null })
        assertEquals("three sources plus three copies", 6, repo.listDocuments().size)
        val outputs = repo.listDocuments().filter { it.name.endsWith(" (2)") }
        assertEquals(3, outputs.size)
        assertEquals("3 unique output names", 3, outputs.map { it.name }.toSet().size)
    }

    @Test
    fun `one failing document is recorded and the batch continues`() {
        val first = DocFixtures.saved(repo, app, pages = 1, name = "Fine")
        val locked = DocFixtures.locked(repo, app, pages = 1)
        val last = DocFixtures.saved(repo, app, pages = 1, name = "Also fine")

        val results = runBlocking {
            repo.runBatch(
                listOf(first, locked, last),
                ScanRepository.BatchAction.Compress(quality = 60, maxDim = null)
            )
        }

        assertEquals(listOf(true, false, true), results.map { it.ok })
        assertTrue("a reason is recorded", !results[1].reason.isNullOrBlank())
        assertEquals("two copies published", 2, repo.listDocuments().count { it.name.endsWith(" (2)") })
    }

    @Test
    fun `cancel after the first document keeps one output and no partial`() {
        val ids = docs(3)
        var processed = 0
        val results = runBlocking {
            repo.runBatch(
                ids,
                ScanRepository.BatchAction.Compress(quality = 60, maxDim = null),
                onProgress = { _, _, _ -> processed++ },
                cancelled = { processed >= 1 }
            )
        }
        assertEquals("only the first document ran", 1, results.size)
        assertTrue(results.single().ok)
        assertEquals("one output, no partial copy", 4, repo.listDocuments().size)
        assertEquals(1, repo.listDocuments().count { it.name.endsWith(" (2)") })
    }

    @Test
    fun `protect applies one password to every document and verifies it`() {
        val ids = docs(2)
        val results = runBlocking {
            repo.runBatch(ids, ScanRepository.BatchAction.Protect("1234"))
        }
        assertEquals(2, results.count { it.ok })
        for (id in ids) {
            val doc = repo.getDocument(id)!!
            assertTrue("$id is locked", doc.isLocked)
            assertTrue("$id opens with the batch password", repo.verifyPdfPassword(id, "1234"))
        }
    }

    @Test
    fun `pdf to images writes one zip per document`() {
        val ids = docs(2)
        val results = runBlocking {
            repo.runBatch(
                ids,
                ScanRepository.BatchAction.PdfToImages(ScanRepository.ImageExportFormat.JPG, oneZip = false)
            )
        }
        assertEquals(2, results.count { it.ok })
        results.forEach { item ->
            assertEquals(1, item.files.size)
            assertTrue(item.files.first().isFile)
            ZipFile(item.files.first()).use { zip ->
                assertTrue(zip.entries().toList().isNotEmpty())
            }
        }
    }

    @Test
    fun `one zip for all holds every document`() {
        val ids = docs(2)
        val results = runBlocking {
            repo.runBatch(
                ids,
                ScanRepository.BatchAction.PdfToImages(ScanRepository.ImageExportFormat.JPG, oneZip = true)
            )
        }
        assertEquals(2, results.count { it.ok })
        val zip = results.first().files.single()
        assertNotNull(zip)
        ZipFile(zip).use { archive ->
            val names = archive.entries().toList().map { it.name }
            assertTrue(names.any { it.startsWith("Batch 0/") })
            assertTrue(names.any { it.startsWith("Batch 1/") })
        }
    }

    @Test
    fun `remove metadata creates clean copies with unique names`() {
        val ids = docs(2)
        val results = runBlocking {
            repo.runBatch(ids, ScanRepository.BatchAction.RemoveMetadata)
        }
        assertEquals(2, results.count { it.ok })
        val clean = repo.listDocuments().filter { it.name.endsWith(" (2)") }
        assertEquals(2, clean.size)
        assertEquals(2, clean.map { it.name }.toSet().size)
    }
}
