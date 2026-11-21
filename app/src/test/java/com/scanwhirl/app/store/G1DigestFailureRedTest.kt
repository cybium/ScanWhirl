package com.scanwhirl.app.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.util.PdfProtection
import com.scanwhirl.app.util.Sha256
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.IOException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Sol re-review P1: an unreadable PDF is UNKNOWN, not a verified mismatch. Recovery must keep the
 * password-change marker (the only record of the new verifier) and report incomplete.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class G1DigestFailureRedTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)

    @After
    fun tearDown() = Faults.resetAll()

    @Test
    fun `an unreadable PDF after the swap keeps the password change marker`() {
        val id = DocFixtures.locked(repo, app, password = "1234")
        val dir = File(repo.docsDir, id)
        val lock = File(dir, PdfProtection.LOCK_FILE)
        val oldRecord = lock.readText()
        val pdf = File(dir, PdfProtection.PDF_FILE)

        // Die after the PDF swap, before the verifier write: the marker holds the new verifier.
        val death = Faults.dieAtOp("chpw:swapped")
        runCatching { runBlocking { repo.changePdfPassword(id, "1234", "5678") } }
        assertTrue(death.fired)
        Faults.resetAll()
        assertTrue("the swapped PDF is removed to make hashing fail", pdf.delete())

        ScanRepository.recoveryIncomplete = false
        repo.recoverOperations()

        val marker = File(app.filesDir, "op_staging").listFiles { it -> it.isDirectory }
            ?.firstNotNullOfOrNull { File(it, ".password-swap").takeIf { f -> f.isFile } }
        assertNotNull("the marker is the only record of the new verifier", marker)
        assertEquals("the old verifier is untouched", oldRecord, lock.readText())
        assertTrue("unreadable input is exposed", ScanRepository.recoveryIncomplete)
    }

    private fun swapMarker(): File? = File(app.filesDir, "op_staging").listFiles { it -> it.isDirectory }
        ?.firstNotNullOfOrNull { File(it, ".password-swap").takeIf { f -> f.isFile } }

    private fun deadPasswordSwap(): Pair<String, File> {
        val id = DocFixtures.locked(repo, app, password = "1234")
        val lock = File(File(repo.docsDir, id), PdfProtection.LOCK_FILE)
        val death = Faults.dieAtOp("chpw:swapped")
        runCatching { runBlocking { repo.changePdfPassword(id, "1234", "5678") } }
        assertTrue(death.fired)
        Faults.resetAll()
        return id to lock
    }

    private fun assertKeptAndRetried(id: String, lock: File, oldRecord: String) {
        assertNotNull("the marker is kept", swapMarker())
        assertEquals("the verifier is untouched", oldRecord, lock.readText())
        assertTrue("recovery is exposed as incomplete", ScanRepository.recoveryIncomplete)

        Faults.resetAll()
        repo.recoverOperations()
        assertNull("a successful retry resolves the marker", swapMarker())
        assertTrue("the new password now opens the document", repo.verifyPdfPassword(id, "5678"))
    }

    @Test
    fun `an IOException while hashing keeps the marker and is retried`() {
        val (id, lock) = deadPasswordSwap()
        val oldRecord = lock.readText()
        ScanRepository.recoveryHash = { throw IOException("injected hashing failure") }
        ScanRepository.recoveryIncomplete = false

        repo.recoverOperations()

        assertKeptAndRetried(id, lock, oldRecord)
    }

    @Test
    fun `an OutOfMemoryError while hashing keeps the marker and is retried`() {
        val (id, lock) = deadPasswordSwap()
        val oldRecord = lock.readText()
        ScanRepository.recoveryHash = { throw OutOfMemoryError("injected heap failure") }
        ScanRepository.recoveryIncomplete = false

        repo.recoverOperations()

        assertKeptAndRetried(id, lock, oldRecord)
    }

    @Test
    fun `a failed verifier write keeps the marker and is retried next start`() {
        val (id, lock) = deadPasswordSwap()
        val oldRecord = lock.readText()
        val probe = Faults.failAtFileSync("locked.meta.tmp")
        ScanRepository.recoveryIncomplete = false

        repo.recoverOperations()

        assertTrue("the verifier write was attempted", probe.fired)
        assertKeptAndRetried(id, lock, oldRecord)
    }
}
