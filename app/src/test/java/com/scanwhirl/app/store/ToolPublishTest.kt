package com.scanwhirl.app.store

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.util.ImportFixtures
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * PM-2.1c: extract, compress, create, duplicate and device-PDF merge all build their output in
 * private staging and publish it with one rename, on top of PM-2.1b's [ScanRepository] protocol.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ToolPublishTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)
    private val stagingRoot get() = File(app.filesDir, "op_staging")

    @After fun tearDown() = Faults.resetAll()

    private fun newProcess() {
        Faults.resetAll()
        repo.recoverOperations()
    }

    private fun docDir(id: String) = File(repo.docsDir, id)

    private fun stagingEntries(): List<String> = stagingRoot.list().orEmpty().sorted()

    @Test
    fun `extract publishes nothing when its PDF cannot be written`() {
        val id = DocFixtures.saved(repo, app, pages = 3)
        val before = PdfInvariants.dirDigest(docDir(id))
        Faults.failAtFileSync("scan.pdf.tmp")
        val r = runCatching { runBlocking { repo.extractPages(id, listOf(0, 2)) } }
        assertTrue("extract must fail when its PDF cannot be written", r.isFailure)
        assertEquals(1, repo.listDocuments().size)
        assertEquals("extract changed its source", before, PdfInvariants.dirDigest(docDir(id)))
        newProcess()
        PdfInvariants.assertClean(app, repo)
    }

    @Test
    fun `compress publishes nothing when its PDF cannot be written`() {
        val id = DocFixtures.saved(repo, app, pages = 3)
        val before = PdfInvariants.dirDigest(docDir(id))
        Faults.failAtFileSync("scan.pdf.tmp")
        val r = runCatching { runBlocking { repo.compressScan(id, 50, null) } }
        assertTrue("compress must fail when its PDF cannot be written", r.isFailure)
        assertEquals(1, repo.listDocuments().size)
        assertEquals("compress changed its source", before, PdfInvariants.dirDigest(docDir(id)))
        newProcess()
        PdfInvariants.assertClean(app, repo)
    }

    @Test
    fun `duplicate death before metadata leaves no visible copy`() {
        val id = DocFixtures.saved(repo, app, pages = 2)
        val probe = Faults.dieAtFileSync("meta.txt.tmp")
        runCatching { runBlocking { repo.duplicateDocument(id, "Copy") } }
        assertTrue("fault meta.txt.tmp never fired", probe.fired)
        newProcess()
        assertEquals(1, repo.listDocuments().size)
        PdfInvariants.assertClean(app, repo)
    }

    @Test
    fun `createDocument death leaves no visible or orphan directory`() {
        Faults.dieAtFileSync("scan.pdf.tmp")
        val r = runCatching {
            runBlocking { repo.createDocument("X", listOf(ImportFixtures.image())) }
        }
        assertTrue("createDocument must fail when its PDF cannot be written", r.isFailure)
        newProcess()
        PdfInvariants.assertClean(app, repo)
        assertEquals(0, repo.listDocuments().size)
    }

    @Test
    fun `extract compress and duplicate outputs satisfy I1 I4 I5 I10`() {
        val id = DocFixtures.saved(repo, app, pages = 3)
        DocFixtures.withOcr(repo, id)
        val sourceDigest = PdfInvariants.dirDigest(docDir(id))
        val sourceOcr = File(docDir(id), "ocr.txt").readBytes()

        val extracted = runBlocking { repo.extractPages(id, listOf(0, 2)) }
        val compressed = runBlocking { repo.compressScan(id, 50, null) }
        val copy = runBlocking { repo.duplicateDocument(id, "Copy") }

        for (out in listOf(extracted, compressed, copy)) {
            PdfInvariants.assertI1(repo, out.id)
            PdfInvariants.assertI4(repo)
            PdfInvariants.assertI10(repo, out.id)
        }
        assertEquals("tools changed their source", sourceDigest, PdfInvariants.dirDigest(docDir(id)))
        assertTrue(
            "duplicate's ocr.txt must be byte-identical to the source's",
            sourceOcr.contentEquals(File(docDir(copy.id), "ocr.txt").readBytes())
        )
    }

    @Test
    fun `three hundred page compress completes`() {
        val pages = List(300) { ImportFixtures.image(width = 40, height = 60) }
        val source = runBlocking { repo.createDocument("Big", pages) }
        val out = runBlocking { repo.compressScan(source.id, 50, null) }
        PdfInvariants.assertI1(repo, out.id)
        assertEquals(300, out.pageCount)
    }

    @Test
    fun `duplicate never copies hidden scratch files`() {
        val id = DocFixtures.saved(repo, app, pages = 1)
        File(docDir(id), ".page_000.jpg.abc.swtmp").writeText("scratch")
        val copy = runBlocking { repo.duplicateDocument(id, "Copy") }
        val hidden = docDir(copy.id).listFiles().orEmpty().filter { it.name.startsWith(".") }.map { it.name }
        assertTrue("duplicate copied scratch files: $hidden", hidden.isEmpty())
    }

    @Test
    fun `mergePdfUris failure leaves no directory in documents`() {
        // An unreadable input is a failing merge: the build throws an Exception, staging is
        // released and `documents/` is untouched.
        val before = PdfInvariants.dirDigest(File(app.filesDir, "documents"))
        val uris = (0 until 2).map { n ->
            val uri = Uri.parse("content://test/broken_$n.pdf")
            shadowOf(app.contentResolver).registerInputStream(uri, object : InputStream() {
                override fun read(): Int = throw IOException("unreadable PDF")
            })
            uri
        }
        val r = runCatching { runBlocking { repo.mergePdfUris(uris, null) } }
        assertTrue("mergePdfUris of unreadable input must fail", r.isFailure)
        assertEquals(before, PdfInvariants.dirDigest(File(app.filesDir, "documents")))
        assertTrue("staging must be empty, found ${stagingEntries()}", stagingEntries().isEmpty())
    }
}
