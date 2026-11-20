package com.scanwhirl.app.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.util.PdfProtection
import com.scanwhirl.app.util.Sha256
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * S4B (independent re-review, MAJOR 3): a process death during Change password used to leave the
 * NEW verifier beside the OLD-password PDF, so the app accepted a password the file did not open
 * with. The change now records a recovery marker before the destructive step; the next start ends
 * in a consistent state — the original password still works, or the new one is fully applied.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PasswordSwapRecoveryTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)

    @After
    fun tearDown() = Faults.resetAll()

    private fun dirOf(id: String) = File(File(app.filesDir, "documents"), id)
    private fun pdfOf(id: String) = File(dirOf(id), PdfProtection.PDF_FILE)
    private fun lockOf(id: String) = File(dirOf(id), PdfProtection.LOCK_FILE)

    /**
     * The reported window: die right after the PDF swap, before the verifier write. On disk the
     * new PDF sits beside the old verifier. The next start must finish the change without the
     * password (the marker names the new verifier) and without touching the page sources.
     */
    @Test
    fun `death after the pdf swap is finished at the next start`() {
        val id = DocFixtures.locked(repo, app, password = "1234")
        val pdf = pdfOf(id)
        val lock = lockOf(id)
        val oldRecord = lock.readText()
        val oldPdf = Sha256.of(pdf)
        val pages = repo.getDocument(id)!!.pageFiles.map { it.name to Sha256.of(it) }

        val probe = Faults.dieAtOp("chpw:swapped")
        val died = runCatching { runBlocking { repo.changePdfPassword(id, "1234", "5678") } }
        assertTrue("the change must die at the swap", died.isFailure)
        assertTrue("the fault point chpw:swapped was never reached", probe.fired)

        // The state the review reported: new PDF, old verifier.
        val newPdf = Sha256.of(pdf)
        assertNotEquals("the PDF was swapped", oldPdf, newPdf)
        assertEquals("the old verifier was not replaced yet", oldRecord, lock.readText())

        // A new process: nothing is live any more, so recovery runs exactly as at app start.
        Faults.resetAll()
        repo.recoverOperations()

        assertTrue("the new password is fully applied", repo.verifyPdfPassword(id, "5678"))
        assertFalse("the old password no longer opens the document", repo.verifyPdfPassword(id, "1234"))
        assertEquals(PdfProtection.State.LOCKED, repo.protectionState(id))
        assertEquals("recovery did not rewrite the PDF", newPdf, Sha256.of(pdf))
        assertEquals("page sources are untouched", pages, repo.getDocument(id)!!.pageFiles.map { it.name to Sha256.of(it) })

        // An independent reader opens the file with the new password, and the app's own index knows it.
        Loader.loadPDF(pdf, "5678").use { assertTrue(it.numberOfPages >= 1) }
        assertNotNull("the lock index follows the new file", repo.findLockedDocumentByHash(newPdf))
        assertTrue("staging is swept", File(app.filesDir, "op_staging").list().orEmpty().isEmpty())
    }

    /**
     * Died before the destructive step: nothing was changed, so the original password is still the
     * only one that works and no marker survives the start.
     */
    @Test
    fun `death before the pdf swap leaves the original password working`() {
        val id = DocFixtures.locked(repo, app, password = "1234")
        val pdf = pdfOf(id)
        val lock = lockOf(id)
        val oldRecord = lock.readText()
        val oldPdf = Sha256.of(pdf)

        val probe = Faults.dieAtOp("chpw:marker")
        runCatching { runBlocking { repo.changePdfPassword(id, "1234", "5678") } }
        assertTrue("the fault point chpw:marker was never reached", probe.fired)
        assertEquals("the PDF must not be touched", oldPdf, Sha256.of(pdf))
        assertEquals("the verifier must not be touched", oldRecord, lock.readText())

        Faults.resetAll()
        repo.recoverOperations()

        assertTrue("the original password still works", repo.verifyPdfPassword(id, "1234"))
        assertFalse("the new password was never applied", repo.verifyPdfPassword(id, "5678"))
        assertEquals(PdfProtection.State.LOCKED, repo.protectionState(id))
        assertTrue("staging is swept", File(app.filesDir, "op_staging").list().orEmpty().isEmpty())
    }

    /** A completed change publishes nothing of the marker machinery. */
    @Test
    fun `a completed change leaves no marker or staging behind`() {
        val id = DocFixtures.locked(repo, app, password = "1234")
        val doc = runBlocking { repo.changePdfPassword(id, "1234", "5678") }

        assertTrue(doc.isLocked)
        assertTrue(repo.verifyPdfPassword(id, "5678"))
        assertFalse(repo.verifyPdfPassword(id, "1234"))
        assertTrue(
            "op_staging is empty after a completed change",
            File(app.filesDir, "op_staging").list().orEmpty().isEmpty()
        )
        assertEquals(PdfProtection.State.LOCKED, repo.protectionState(id))
    }

    /**
     * The same window opened by an I/O failure instead of a kill: the verifier write fails after the
     * PDF swap. The marker must survive for the next start (it is the only route back).
     */
    @Test
    fun `a failed verifier write keeps the marker and the next start finishes`() {
        val id = DocFixtures.locked(repo, app, password = "1234")
        val pdf = pdfOf(id)
        val oldPdf = Sha256.of(pdf)
        val oldRecord = lockOf(id).readText()

        val probe = Faults.failAtFileSync("locked.meta.tmp")
        val failed = runCatching { runBlocking { repo.changePdfPassword(id, "1234", "5678") } }
        assertTrue("the verifier write must fail, got ${failed.exceptionOrNull()}", failed.isFailure)
        assertTrue("the injected failure fired", probe.fired)

        val newPdf = Sha256.of(pdf)
        assertNotEquals("the PDF was swapped before the failure", oldPdf, newPdf)
        assertEquals("the verifier is still the old one", oldRecord, lockOf(id).readText())
        assertTrue(
            "the recovery marker survives the failure",
            File(app.filesDir, "op_staging").list().orEmpty().isNotEmpty()
        )

        Faults.resetAll()
        repo.recoverOperations()

        assertTrue("the new password is applied", repo.verifyPdfPassword(id, "5678"))
        assertFalse(repo.verifyPdfPassword(id, "1234"))
        assertEquals(PdfProtection.State.LOCKED, repo.protectionState(id))
        assertTrue("staging is swept", File(app.filesDir, "op_staging").list().orEmpty().isEmpty())
    }

    /**
     * A torn or unknown marker is never guessed at: recovery leaves the document alone and the
     * normal sweep removes the dead working folder.
     */
    @Test
    fun `an unreadable marker is ignored and swept`() {
        val id = DocFixtures.locked(repo, app, password = "1234")
        val before = Sha256.of(pdfOf(id))
        val record = lockOf(id).readText()
        val work = OpStaging.create(app.filesDir)
        File(work, ".password-swap").writeText("scanwhirl-password-swap-v1\nid=$id\nnew=broken\n")

        Faults.resetAll()
        repo.recoverOperations()

        assertEquals(before, Sha256.of(pdfOf(id)))
        assertEquals(record, lockOf(id).readText())
        assertTrue(repo.verifyPdfPassword(id, "1234"))
        assertTrue(File(app.filesDir, "op_staging").list().orEmpty().isEmpty())
    }
}
