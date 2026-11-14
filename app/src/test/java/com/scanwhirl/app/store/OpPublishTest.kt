package com.scanwhirl.app.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.util.Sha256
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * PM-2.1b: every merge output is built in private staging and published with one rename. A merge
 * whose PDF cannot be written fails and leaves nothing behind; a death at any stage leaves no
 * visible or orphan directory after startup recovery; the new document is queued for text
 * recognition.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class OpPublishTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)
    private val stagingRoot get() = File(app.filesDir, "op_staging")

    @After fun tearDown() = Faults.resetAll()

    private fun newProcess() {
        Faults.resetAll()
        repo.recoverOperations()
    }

    private fun stagingEntries(): List<String> = stagingRoot.list().orEmpty().sorted()

    private class Fixture(val a: String, val b: String, val digestA: String, val digestB: String)

    private fun fixture(): Fixture {
        val a = DocFixtures.saved(repo, app, pages = 2, name = "First")
        val b = DocFixtures.saved(repo, app, pages = 3, name = "Second")
        return Fixture(a, b, PdfInvariants.dirDigest(File(repo.docsDir, a)), PdfInvariants.dirDigest(File(repo.docsDir, b)))
    }

    private fun assertSourcesUnchanged(f: Fixture) {
        assertEquals("merge changed source A", f.digestA, PdfInvariants.dirDigest(File(repo.docsDir, f.a)))
        assertEquals("merge changed source B", f.digestB, PdfInvariants.dirDigest(File(repo.docsDir, f.b)))
    }

    @Test
    fun `merge publishes nothing when its PDF cannot be written`() {
        val f = fixture()
        Faults.failAtFileSync("scan.pdf.tmp")
        val r = runCatching { runBlocking { repo.mergeDocuments(listOf(f.a, f.b), "Merge") } }
        assertTrue("merge must fail when its PDF cannot be written", r.isFailure)
        assertEquals(2, repo.listDocuments().size)
        assertSourcesUnchanged(f)
        newProcess()
        PdfInvariants.assertClean(app, repo)
    }

    @Test
    fun `merge death at every stage leaves no visible or orphan directory after recovery`() {
        // Stage injectors: each must die exactly at its step. op:after_rename is the one stage
        // after which the document is legally visible.
        val injectors: List<Pair<String, (String) -> Faults.Probe>> = listOf(
            "meta.txt.tmp" to { name -> Faults.dieAtFileSync(name) },
            "scan.pdf.tmp" to { name -> Faults.dieAtFileSync(name) },
            "op:staged" to { name -> Faults.dieAtOp(name) },
            "op:page:1" to { name -> Faults.dieAtOp(name) },
            "op:pdf" to { name -> Faults.dieAtOp(name) },
            "op:meta" to { name -> Faults.dieAtOp(name) },
            "op:verified" to { name -> Faults.dieAtOp(name) },
            "op:before_rename" to { name -> Faults.dieAtOp(name) }
        )
        for ((step, inject) in injectors) {
            Faults.resetAll()
            val f = fixture()
            val before = repo.listDocuments().map { it.id }.toSet()
            val probe = inject(step)
            val r = runCatching { runBlocking { repo.mergeDocuments(listOf(f.a, f.b), "Merge") } }
            assertTrue("fault $step never fired", probe.fired)
            assertTrue("merge must fail when it dies at $step", r.isFailure)
            newProcess()
            PdfInvariants.assertClean(app, repo)
            assertEquals(
                "death at $step published a document",
                before, repo.listDocuments().map { it.id }.toSet()
            )
            assertSourcesUnchanged(f)
        }

        // The last stage completes the rename: the document IS published and satisfies I1 + I4.
        Faults.resetAll()
        val f = fixture()
        val probe = Faults.dieAtOp("op:after_rename")
        val before = repo.listDocuments().map { it.id }.toSet()
        runCatching { runBlocking { repo.mergeDocuments(listOf(f.a, f.b), "Merge") } }
        assertTrue("fault op:after_rename never fired", probe.fired)
        newProcess()
        val after = repo.listDocuments().map { it.id }.toSet()
        assertEquals("a death after the rename must publish exactly one document", 1, (after - before).size)
        assertEquals("a death after the rename must not lose another document", before, after - (after - before))
        val newId = (after - before).single()
        PdfInvariants.assertI1(repo, newId)
        PdfInvariants.assertClean(app, repo)
    }

    @Test
    fun `merged document is queued for text recognition`() {
        val f = fixture()
        val merged = runBlocking { repo.mergeDocuments(listOf(f.a, f.b), "Merge") }
        PdfInvariants.assertI10(repo, merged.id)
    }

    @Test
    fun `merge keeps sources byte identical and satisfies I1 I4`() {
        val f = fixture()
        val aPages = repo.getDocument(f.a)!!.pageFiles.map { Sha256.of(it) }
        val bPages = repo.getDocument(f.b)!!.pageFiles.map { Sha256.of(it) }
        val merged = runBlocking { repo.mergeDocuments(listOf(f.a, f.b), "Merge") }
        assertSourcesUnchanged(f)
        PdfInvariants.assertI1(repo, merged.id)
        PdfInvariants.assertI4(repo)
        assertEquals("merged page order must be A then B", aPages + bPages, merged.pageFiles.map { Sha256.of(it) })
    }

    @Test
    fun `recovery removes dead staging and keeps a live one`() {
        val dead = File(stagingRoot, "dead").apply { mkdirs() }
        File(dead, "x").writeText("x")
        val live = OpStaging.create(app.filesDir)
        repo.recoverOperations()
        assertTrue("dead staging must be swept", !dead.exists())
        assertTrue("live staging must survive recovery", live.isDirectory)
        OpStaging.release(live)
    }

    @Test
    fun `recovery never touches documents trash drafts or import staging`() {
        val doc = DocFixtures.saved(repo, app, pages = 1)
        val trashed = DocFixtures.saved(repo, app, pages = 1)
        repo.moveToTrash(trashed)
        val drafts = File(app.filesDir, "drafts/x").apply { mkdirs() }
        File(drafts, "draft.txt").writeText("draft")
        val imports = File(app.filesDir, "import_staging/y").apply { mkdirs() }
        File(imports, "f").writeText("f")

        val digests = listOf(
            File(app.filesDir, "documents"),
            File(app.filesDir, "trash"),
            File(app.filesDir, "drafts"),
            File(app.filesDir, "import_staging")
        ).map { PdfInvariants.dirDigest(it) }

        repo.recoverOperations()

        assertEquals(digests, listOf(
            File(app.filesDir, "documents"),
            File(app.filesDir, "trash"),
            File(app.filesDir, "drafts"),
            File(app.filesDir, "import_staging")
        ).map { PdfInvariants.dirDigest(it) })
    }

    @Test
    fun `publish rename failure leaves no visible document and cleans staging`() {
        val f = fixture()
        Faults.failAtOp("op:before_rename")
        val r = runCatching { runBlocking { repo.mergeDocuments(listOf(f.a, f.b), "Merge") } }
        assertTrue("merge must fail when publishing fails", r.isFailure)
        assertEquals(2, repo.listDocuments().size)
        assertTrue("staging must be released without recovery, found ${stagingEntries()}", stagingEntries().isEmpty())
    }

    @Test
    fun `low free space refuses before writing anything`() {
        val f = fixture()
        ScanRepository.usableSpace = { 0L }
        val r = runCatching { runBlocking { repo.mergeDocuments(listOf(f.a, f.b), "Merge") } }
        assertTrue("merge must refuse when storage is low", r.isFailure)
        assertEquals(app.getString(R.string.op_no_space), r.exceptionOrNull()?.message)
        assertTrue("nothing may be staged when storage is low", stagingEntries().isEmpty())
        assertEquals(2, repo.listDocuments().size)
    }

    @Test
    fun `cancel during merge leaves nothing behind`() {
        val f = fixture()
        var job: Job? = null
        OpFaults.hook = { step -> if (step == "op:page:0") job?.cancel() }
        runBlocking {
            job = async(Dispatchers.IO) { repo.mergeDocuments(listOf(f.a, f.b), "Merge") }
            runCatching { job!!.await() }
        }
        assertEquals(2, repo.listDocuments().size)
        assertTrue("cancel must leave staging empty, found ${stagingEntries()}", stagingEntries().isEmpty())
        PdfInvariants.assertClean(app, repo)
    }
}
