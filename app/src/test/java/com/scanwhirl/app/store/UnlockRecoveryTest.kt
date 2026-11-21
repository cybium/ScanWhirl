package com.scanwhirl.app.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.util.PdfProtection
import com.scanwhirl.app.util.Sha256
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * G1 item 3: a process death between "the unlocked PDF is durable" and "the lock record is gone"
 * left a plain scan.pdf beside locked.meta (NEEDS_REPAIR) with no way to finish the unlock. The
 * operation now records a recovery marker before the destructive swap; the next start finishes
 * the unlock or rolls back, without ever discarding the page sources.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class UnlockRecoveryTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)

    @After
    fun tearDown() = Faults.resetAll()

    private fun dirOf(id: String) = File(repo.docsDir, id)
    private fun pdfOf(id: String) = File(dirOf(id), PdfProtection.PDF_FILE)
    private fun lockOf(id: String) = File(dirOf(id), PdfProtection.LOCK_FILE)

    /** The reported window: the plain PDF is already swapped in, but the lock label is still there. */
    @Test
    fun `death after the unlock swap is finished at the next start`() {
        val id = DocFixtures.locked(repo, app, password = "1234")
        val pdf = pdfOf(id)
        val lock = lockOf(id)
        val encryptedDigest = Sha256.of(pdf)
        val pages = repo.getDocument(id)!!.pageFiles.map { it.name to Sha256.of(it) }
        val record = lock.readText()

        val probe = Faults.dieAtOp("unlock:swapped")
        val died = runCatching { runBlocking { repo.unlockPdf(id, "1234") } }
        assertTrue("the unlock must die at the swap", died.isFailure)
        assertTrue("the fault point unlock:swapped was never reached", probe.fired)

        // Half-unlocked state on disk: plain PDF, old verifier still present.
        assertNotEquals("the PDF was swapped to the plain copy", encryptedDigest, Sha256.of(pdf))
        assertEquals("the lock record has not been removed yet", record, lock.readText())
        assertEquals(PdfProtection.State.NEEDS_REPAIR, repo.protectionState(id))

        // Fresh process: no staging folder is live, so recovery runs exactly as at app start.
        Faults.resetAll()
        repo.recoverOperations()

        assertFalse("the lock record is gone after recovery", lock.exists())
        assertEquals(PdfProtection.State.NONE, repo.protectionState(id))
        assertFalse("the document is no longer locked", repo.getDocument(id)!!.isLocked)
        assertEquals("page sources are untouched", pages, repo.getDocument(id)!!.pageFiles.map { it.name to Sha256.of(it) })
        assertTrue("staging is swept", File(app.filesDir, "op_staging").list().orEmpty().isEmpty())
    }

    /** Died before the destructive swap: the encrypted original is still in place, so keep the lock. */
    @Test
    fun `death before the unlock swap leaves the document locked`() {
        val id = DocFixtures.locked(repo, app, password = "1234")
        val pdf = pdfOf(id)
        val lock = lockOf(id)
        val encryptedDigest = Sha256.of(pdf)
        val record = lock.readText()

        val probe = Faults.dieAtOp("unlock:built")
        runCatching { runBlocking { repo.unlockPdf(id, "1234") } }
        assertTrue("the fault point unlock:built was never reached", probe.fired)
        assertEquals("the encrypted PDF was not touched", encryptedDigest, Sha256.of(pdf))
        assertEquals("the verifier was not touched", record, lock.readText())

        Faults.resetAll()
        repo.recoverOperations()

        assertEquals(PdfProtection.State.LOCKED, repo.protectionState(id))
        assertTrue(repo.verifyPdfPassword(id, "1234"))
        assertFalse("the old password still opens the file", repo.verifyPdfPassword(id, "wrong"))
        assertTrue("staging is swept", File(app.filesDir, "op_staging").list().orEmpty().isEmpty())
    }

    /** An ordinary I/O failure after the swap must keep the marker for the next start, too. */
    @Test
    fun `a failure after the swap is finished by the next start`() {
        val id = DocFixtures.locked(repo, app, password = "1234")
        val pdf = pdfOf(id)
        val lock = lockOf(id)
        val encryptedDigest = Sha256.of(pdf)

        val probe = Faults.failAtOp("unlock:swapped")
        val failed = runCatching { runBlocking { repo.unlockPdf(id, "1234") } }
        assertTrue("the swap must fail", failed.isFailure)
        assertTrue("the injected failure fired", probe.fired)
        assertNotEquals("the PDF was swapped before the failure", encryptedDigest, Sha256.of(pdf))

        Faults.resetAll()
        repo.recoverOperations()

        assertFalse("the lock record is gone after recovery", lock.exists())
        assertEquals(PdfProtection.State.NONE, repo.protectionState(id))
        assertTrue("staging is swept", File(app.filesDir, "op_staging").list().orEmpty().isEmpty())
    }
}
