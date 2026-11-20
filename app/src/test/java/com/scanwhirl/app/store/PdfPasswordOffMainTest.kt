package com.scanwhirl.app.store

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.util.PdfProtection
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.Sha256
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * S4B (independent re-review, MAJOR 1): the 1 300 000-iteration PBKDF2 lock check is a suspending
 * call that runs off the main thread, one attempt derives once, a dismissed check changes nothing,
 * and an authorization is never reused after a concurrent password change.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PdfPasswordOffMainTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)

    private var onMain = 0
    private var offMain = 0

    @After
    fun tearDown() {
        Faults.resetAll() // also clears ScanRepository.lockDerivationProbe
    }

    private fun lockFile(id: String) =
        File(File(File(app.filesDir, "documents"), id), PdfProtection.LOCK_FILE)

    private fun pdfFile(id: String) =
        File(File(File(app.filesDir, "documents"), id), PdfProtection.PDF_FILE)

    private fun countThreads() {
        onMain = 0
        offMain = 0
        ScanRepository.lockDerivationProbe = {
            if (Looper.myLooper() == Looper.getMainLooper()) onMain++ else offMain++
        }
    }

    @Test
    fun `the lock check derives on a background thread, not the caller's`() {
        val id = DocFixtures.locked(repo, app, password = "1234")
        countThreads()

        val grant = runBlocking { repo.checkPdfPassword(id, "1234") }

        assertNotNull("the right password produces a grant", grant)
        assertEquals("no PBKDF2 on the main thread", 0, onMain)
        assertTrue("the check derived off the main thread", offMain >= 1)
    }

    @Test
    fun `a wrong password derives off main and produces no grant`() {
        val id = DocFixtures.locked(repo, app, password = "1234")
        countThreads()

        assertNull(runBlocking { repo.checkPdfPassword(id, "0000") })
        assertEquals(0, onMain)
        assertTrue(offMain >= 1)
        assertTrue("the document is still locked", repo.getDocument(id)!!.isLocked)
    }

    /**
     * The device-unlock path used to derive on the main thread in the sheet and again in the action.
     * One grant must be enough: unlocking must not run the KDF a second time.
     */
    @Test
    fun `one verified attempt unlocks without a second derivation`() {
        val id = DocFixtures.locked(repo, app, password = "1234")
        val before = Sha256.of(pdfFile(id))
        var derivations = 0
        ScanRepository.lockDerivationProbe = { derivations++ }

        val grant = runBlocking { repo.checkPdfPassword(id, "1234") }
        assertNotNull(grant)
        val afterCheck = derivations
        assertEquals("the check derives exactly once", 1, afterCheck)

        runBlocking { repo.unlockPdf(id, grant!!) }

        assertFalse("the document is unlocked", repo.getDocument(id)!!.isLocked)
        assertEquals("unlock reused the grant instead of deriving again", afterCheck, derivations)
        assertTrue(before != Sha256.of(pdfFile(id)))
        assertEquals(PdfProtection.State.NONE, repo.protectionState(id))
    }

    /**
     * The legacy (SHA-256) record is migrated only after a successful check, and the migration is a
     * compare-and-swap: a record written by a concurrent password change must win, never be
     * overwritten by the migration.
     */
    @Test
    fun `a concurrent password change wins over a legacy migration`() {
        val id = DocFixtures.locked(repo, app, password = "1234")
        val lock = lockFile(id)
        lock.writeText(Prefs.sha256("pdf_lock:1234"))
        val concurrent = Prefs.sha256("pdf_lock:9999")
        // Runs on the deriving thread, after the legacy check and before the migration's CAS.
        ScanRepository.lockDerivationProbe = { lock.writeText(concurrent) }

        assertNull("the stale record no longer authorizes", runBlocking { repo.checkPdfPassword(id, "1234") })
        assertEquals("the migration overwrote the concurrent change", concurrent, lock.readText())

        ScanRepository.lockDerivationProbe = null
        assertTrue("the concurrent change is the password now", repo.verifyPdfPassword(id, "9999"))
        assertFalse("the old password is gone", repo.verifyPdfPassword(id, "1234"))
    }

    /** Dismissing the sheet cancels the check: a cancelled derivation must not migrate the record. */
    @Test
    fun `a cancelled check leaves the legacy record untouched`() {
        val id = DocFixtures.locked(repo, app, password = "1234")
        val lock = lockFile(id)
        val legacy = Prefs.sha256("pdf_lock:1234")
        lock.writeText(legacy)
        val deriving = CountDownLatch(1)
        ScanRepository.lockDerivationProbe = { deriving.countDown() }

        val scope = CoroutineScope(Dispatchers.Default)
        val job = scope.launch { repo.checkPdfPassword(id, "1234") }
        assertTrue("the derivation started", deriving.await(5, TimeUnit.SECONDS))
        job.cancel()
        runBlocking { job.join() }

        assertEquals("a dismissed check changed the record", legacy, lock.readText())
        assertFalse(File(lock.parentFile, "locked.meta.tmp").exists())

        ScanRepository.lockDerivationProbe = null
        assertTrue("the owner can still open the document", repo.verifyPdfPassword(id, "1234"))
        assertFalse(repo.verifyPdfPassword(id, "9999"))
    }

    /** An authorization is for the record it checked; after a password change it must not act. */
    @Test
    fun `a grant is refused after the password changed`() {
        val id = DocFixtures.locked(repo, app, password = "1234")
        val grant = runBlocking { repo.checkPdfPassword(id, "1234") }!!
        runBlocking { repo.changePdfPassword(id, "1234", "5678") }

        val refused = runCatching { runBlocking { repo.unlockPdf(id, grant) } }
        assertTrue(
            "a stale grant must be refused, got ${refused.exceptionOrNull()}",
            refused.exceptionOrNull() is PdfProtection.WrongPassword
        )
        assertTrue("the document stays locked", repo.getDocument(id)!!.isLocked)
        assertTrue(repo.verifyPdfPassword(id, "5678"))
    }

    /**
     * The measured cost the fix moves off the UI thread: one 1 300 000-iteration PBKDF2 check on
     * this JVM. Recorded in the report; the bound is deliberately loose (a slow CI box must not
     * flake) — the point is that the same number no longer runs on the main thread.
     */
    @Test
    fun `one lock check is measured in the jvm`() {
        val id = DocFixtures.locked(repo, app, password = "1234")
        val start = System.nanoTime()
        val grant = runBlocking { repo.checkPdfPassword(id, "1234") }
        val millis = (System.nanoTime() - start) / 1_000_000
        println("S4B lock check (PBKDF2-HMAC-SHA1 1 300 000, JVM): $millis ms")
        assertNotNull("the check succeeded", grant)
        assertTrue("one check finishes well inside the ANR budget ($millis ms)", millis in 1..30_000)
    }
}
