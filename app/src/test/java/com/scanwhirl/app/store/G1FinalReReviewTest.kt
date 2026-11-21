package com.scanwhirl.app.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
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
import java.io.IOException

/** Sol final re-review: UNKNOWN must still prune scratch, and an earlier failure must stay visible. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class G1FinalReReviewTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)

    @After
    fun tearDown() = Faults.resetAll()

    private fun stagingDirs(): List<File> =
        File(app.filesDir, "op_staging").listFiles { it -> it.isDirectory }.orEmpty().toList()

    private fun unlockMarker(): File? = stagingDirs()
        .firstNotNullOfOrNull { File(it, ".unlock").takeIf { f -> f.isFile } }

    @Test
    fun `an unreadable document PDF keeps only the marker and drops nested plaintext every start`() {
        val id = DocFixtures.locked(repo, app, password = "1234")
        val death = Faults.dieAtOp("unlock:swapped")
        runCatching { runBlocking { repo.unlockPdf(id, "1234") } }
        assertTrue(death.fired)
        val work = stagingDirs().single()
        val nested = File(File(work, "scratch"), "deep").apply { mkdirs() }
        File(nested, "plain.pdf").writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        Faults.resetAll()
        ScanRepository.recoveryHash = { throw IOException("permanently unreadable") }
        ScanRepository.recoveryIncomplete = false

        repo.recoverOperations()

        assertTrue("the marker survives an UNKNOWN digest", unlockMarker() != null)
        assertFalse("nested plaintext is not kept forever", File(work, "scratch").exists())
        assertTrue("UNKNOWN is exposed", ScanRepository.recoveryIncomplete)

        // A second startup must not regrow any scratch and must still keep the marker.
        repo.recoverOperations()
        assertFalse("repeated startup does not regrow scratch", File(work, "scratch").exists())
        assertNotNull("the marker is still there", unlockMarker())

        // When the PDF becomes readable again, resolution completes and the marker is removed.
        Faults.resetAll()
        repo.recoverOperations()
        assertNull("the marker is resolved once hashing works", unlockMarker())
        assertTrue("staging is swept", stagingDirs().isEmpty())
    }

    @Test
    fun `an earlier startup failure is not reset by a later clean recovery pass`() {
        // Simulate a step that failed earlier in this startup pass (e.g. import staging).
        ScanRepository.recoveryIncomplete = true

        repo.recoverOperations() // this pass itself has no failures

        assertTrue("an earlier failure must remain visible", ScanRepository.recoveryIncomplete)
    }

    @Test
    fun `a new startup pass clears the accumulated flag once`() {
        ScanRepository.recoveryIncomplete = true
        ScanRepository.beginStartupRecoveryPass()
        assertFalse("only the start of a pass resets the flag", ScanRepository.recoveryIncomplete)
    }
}
