package com.scanwhirl.app.store

import android.app.Application
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.util.PdfGenerator
import com.scanwhirl.app.util.PdfProtection
import com.scanwhirl.app.util.ScanFilter
import com.scanwhirl.app.util.Sha256
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
 * PM-3.1b: a locked document never silently becomes unprotected. Page edits and tools are refused
 * on locked documents, and Protect / Unlock / Change password swap files by rename.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class LockGuardTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)
    private val docsDir get() = File(app.filesDir, "documents").apply { mkdirs() }

    @After fun tearDown() = Faults.resetAll()

    private fun dirOf(id: String) = File(docsDir, id)
    private fun pdfOf(id: String) = File(dirOf(id), "scan.pdf")

    // Pins ScanRepository.requireEditable at the top of the five page mutators.
    @Test fun `page edits on a locked document are refused and leave every byte unchanged`() {
        val ops: List<Pair<String, suspend (String) -> Unit>> = listOf(
            "rotatePage" to { id -> repo.rotatePage(id, 0) },
            "cropPage" to { id -> repo.cropPage(id, 0, RectF(0f, 0f, 50f, 50f)) },
            "setPageFilter" to { id -> repo.setPageFilter(id, 0, ScanFilter.BLACKWHITE) },
            "deletePage" to { id -> repo.deletePage(id, 1) },
            "movePage" to { id -> repo.movePage(id, 0, 2) }
        )
        for ((op, run) in ops) {
            val id = DocFixtures.locked(repo, app)
            val digest = PdfInvariants.dirDigest(dirOf(id))
            val result = runCatching { runBlocking { run(id) } }
            assertTrue("$op on a locked document must be refused", result.isFailure)
            assertTrue("$op must throw DocumentLocked, got ${result.exceptionOrNull()}",
                result.exceptionOrNull() is PdfProtection.DocumentLocked)
            assertEquals("$op changed bytes of a locked document", digest, PdfInvariants.dirDigest(dirOf(id)))
            assertEquals(true, PdfInvariants.trailerEncrypted(pdfOf(id)))
        }
    }

    // Pins the PdfGenerator.imagesToPdf choke (the only plaintext PDF writer).
    @Test fun `the generator refuses to write a plain scan pdf beside locked meta`() {
        val id = DocFixtures.locked(repo, app)
        val dir = dirOf(id)
        val before = Sha256.of(pdfOf(id))
        val pages = repo.getDocument(id)!!.pageFiles
        val result = runCatching { PdfGenerator.imagesToPdf(pages, File(dir, "scan.pdf")) }
        assertTrue("the generator must refuse a plain scan.pdf beside locked.meta", result.isFailure)
        assertEquals(before, Sha256.of(pdfOf(id)))
        assertTrue(!File(dir, "scan.pdf.tmp").exists())
    }

    // Pins the locked-source refusal at the top of merge, extract and compress.
    @Test fun `merge extract and compress refuse a locked source and publish nothing`() {
        val plain = DocFixtures.saved(repo, app, pages = 2)
        val locked = DocFixtures.locked(repo, app, pages = 2)
        val count = repo.listDocuments(includeVaulted = true).size
        val ops: List<Pair<String, suspend () -> Unit>> = listOf(
            "mergeDocuments" to { repo.mergeDocuments(listOf(plain, locked), null); Unit },
            "extractPages" to { repo.extractPages(locked, listOf(0)); Unit },
            "compressScan" to { repo.compressScan(locked, 50, null); Unit }
        )
        for ((op, run) in ops) {
            val result = runCatching { runBlocking { run() } }
            assertTrue("$op with a locked source must be refused", result.isFailure)
            assertEquals("$op published a document", count, repo.listDocuments(includeVaulted = true).size)
        }
        PdfInvariants.assertNoOrphans(app, repo)
        assertTrue(dirOf(plain).isDirectory)
    }

    // Pins duplicateDocument (A6): a copy of a locked document keeps its lock and its encryption.
    @Test fun `duplicate of a locked document stays locked and encrypted`() {
        val locked = DocFixtures.locked(repo, app)
        val copy = runBlocking { repo.duplicateDocument(locked, "Copy") }
        assertTrue(copy.isLocked)
        PdfInvariants.assertI3(repo, copy.id)
    }

    // Pins unlockPdf(id, password): a wrong password changes nothing; the right one unlocks (I1, I3).
    @Test fun `unlock with a wrong password changes nothing and the right one unlocks`() {
        val id = DocFixtures.locked(repo, app, password = "1234")
        val digest = PdfInvariants.dirDigest(dirOf(id))
        val wrong = runCatching { runBlocking { repo.unlockPdf(id, "0000") } }
        assertTrue("wrong password must throw WrongPassword, got ${wrong.exceptionOrNull()}",
            wrong.exceptionOrNull() is PdfProtection.WrongPassword)
        assertEquals(digest, PdfInvariants.dirDigest(dirOf(id)))

        val doc = runBlocking { repo.unlockPdf(id, "1234") }
        assertTrue(!doc.isLocked)
        assertTrue(!repo.getDocument(id)!!.isLocked)
        assertEquals(false, PdfInvariants.trailerEncrypted(pdfOf(id)))
        PdfInvariants.assertI1(repo, id)
        PdfInvariants.assertI3(repo, id)
        PdfInvariants.assertNoOrphans(app, repo)
    }

    // Pins changePdfPassword (A3 point 4): the current password is required.
    @Test fun `change password with a wrong current password changes nothing`() {
        val id = DocFixtures.locked(repo, app, password = "1234")
        val digest = PdfInvariants.dirDigest(dirOf(id))
        val wrong = runCatching { runBlocking { repo.changePdfPassword(id, "0000", "5678") } }
        assertTrue("wrong current password must throw WrongPassword, got ${wrong.exceptionOrNull()}",
            wrong.exceptionOrNull() is PdfProtection.WrongPassword)
        assertEquals(digest, PdfInvariants.dirDigest(dirOf(id)))

        runBlocking { repo.changePdfPassword(id, "1234", "5678") }
        assertTrue(repo.verifyPdfPassword(id, "5678"))
        assertTrue(!repo.verifyPdfPassword(id, "1234"))
        PdfInvariants.assertI3(repo, id)
        PdfInvariants.assertNoOrphans(app, repo)
    }

    // Pins the lock-first order of encryptInto: death after the lock record leaves the previous PDF byte
    // identical (the designed NEEDS_REPAIR state of PM-3.1c); the next start sweeps the staging folder.
    @Test fun `death between lock record and pdf swap leaves the previous pdf byte identical`() {
        val id = DocFixtures.saved(repo, app)
        val before = Sha256.of(pdfOf(id))
        val probe = Faults.dieAtOp("protect:lock_written")
        runCatching { runBlocking { repo.protectPdf(id, "1234") } }
        assertTrue("the fault point protect:lock_written was never reached", probe.fired)
        assertEquals(before, Sha256.of(pdfOf(id)))
        assertTrue(File(dirOf(id), "locked.meta").exists())
        assertEquals(false, PdfInvariants.trailerEncrypted(pdfOf(id)))

        Faults.resetAll() // a new process: nothing is live any more
        assertTrue(repo.recoverOperations() >= 1)
        assertTrue(File(app.filesDir, "op_staging").list().orEmpty().isEmpty())
    }

    // Pins protectPdf: the plaintext build happens in OpStaging, never in documents/<id>.
    @Test fun `protect keeps plaintext temporaries out of the document folder`() {
        val id = DocFixtures.saved(repo, app)
        Faults.dieAtFileSync("plain.pdf.tmp")
        runCatching { runBlocking { repo.protectPdf(id, "1234") } }
        val leaked = dirOf(id).list().orEmpty()
            .filter { it.startsWith("plain.pdf") || it.startsWith("protected.pdf") || it.startsWith("enc.pdf") }
        assertTrue("protect left plaintext temporaries in the document folder: $leaked", leaked.isEmpty())
    }

    // Pins PdfProtection.isEncodable (F15): the hash and the PDF password must agree.
    @Test fun `protect refuses a password the PDF cannot encode`() {
        val id = DocFixtures.saved(repo, app)
        val digest = PdfInvariants.dirDigest(dirOf(id))
        val result = runCatching { runBlocking { repo.protectPdf(id, "पासवर्ड1") } }
        assertTrue("protect must refuse a password outside Latin-1", result.isFailure)
        assertTrue(!File(dirOf(id), "locked.meta").exists())
        assertEquals(digest, PdfInvariants.dirDigest(dirOf(id)))
    }

    // A3 point 5: a review aid, not a sandbox. Only ScanRepository and PdfProtection may name scan.pdf.
    @Test fun `no production code writes scan pdf outside the repository`() {
        val main = File("src/main/java")
        assertTrue("src/main/java is reachable from the test working dir", main.isDirectory)
        val writes = Regex(
            "pdfFile\\.(writeBytes|writeText|appendBytes|outputStream|renameTo|delete)\\(" +
                "|(copyTo|renameTo)\\([^)]*pdfFile"
        )
        val offenders = main.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.invariantSeparatorsPath.let { p -> !p.endsWith("store/ScanRepository.kt") && !p.endsWith("util/PdfProtection.kt") } }
            .filter { val t = it.readText(); t.contains("\"scan.pdf\"") || writes.containsMatchIn(t) }
            .map { it.path }
            .toList()
        assertTrue("production code outside the repository touches scan.pdf: $offenders", offenders.isEmpty())
    }
}
