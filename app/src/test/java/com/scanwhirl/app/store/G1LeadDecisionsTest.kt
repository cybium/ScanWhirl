package com.scanwhirl.app.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.util.Sha256
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Lead decisions after the 548afe85 re-review: recursive plaintext cleanup with honest failure,
 * live-operation exclusion during pruning, bounded marker reads, and no Error escaping startup.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class G1LeadDecisionsTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)

    @After
    fun tearDown() = Faults.resetAll()

    private fun stagingDirs(): List<File> =
        File(app.filesDir, "op_staging").listFiles { it -> it.isDirectory }.orEmpty().toList()

    private fun unresolvedUnlockMarker(id: String): String {
        val dir = File(repo.docsDir, id)
        val record = File(dir, "locked.meta").readText()
        val encrypted = Sha256.of(File(dir, "scan.pdf"))
        return "scanwhirl-unlock-v1\nid=$id\nold=$record\npdf=$encrypted\nnewPdf=${"1".repeat(64)}\n"
    }

    @Test
    fun `nested plaintext scratch under an unresolved marker is removed recursively`() {
        val id = DocFixtures.locked(repo, app, password = "1234")
        val death = Faults.dieAtOp("unlock:swapped")
        runCatching { runBlocking { repo.unlockPdf(id, "1234") } }
        assertTrue(death.fired)
        val work = stagingDirs().single()
        val nested = File(File(work, "scratch"), "deep").apply { mkdirs() }
        File(nested, "plain.pdf").writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        Faults.resetAll()
        ScanRepository.removeLockFile = { false } // keep the marker unresolved

        repo.recoverOperations()

        assertTrue("the unresolved marker folder stays", work.exists())
        assertTrue("the marker stays", File(work, ".unlock").isFile)
        assertFalse("nested plaintext is removed recursively", File(work, "scratch").exists())
    }

    @Test
    fun `an oversized publish marker is classified stale and never kept`() {
        val work = OpStaging.create(app.filesDir)
        val hugeToken = "a".repeat(100 * 1024)
        File(work, OpStaging.SEAL).writeText(buildString {
            append("scanwhirl-publish-v1\n")
            append("token=$hugeToken\n")
            append("draft=d1\ndraftCreated=1\nreplaces=-\npages=p1.jpg\n")
            append("pagesSha256=${"0".repeat(64)}\npdfSha256=${"0".repeat(64)}\n")
        })
        OpStaging.resetForTests()

        assertNull("a 100 KB marker must not be accepted as a seal", OpStaging.readSeal(work))
        repo.recoverOperations() // no OOM, no crash: startup completes
        assertFalse("the folder is swept as stale", work.exists())
    }

    @Test
    fun `a live operation's staging folder is never pruned or swept`() {
        val id = DocFixtures.locked(repo, app, password = "1234")
        val work = OpStaging.create(app.filesDir) // registered live in this process
        File(work, ".unlock").writeText(unresolvedUnlockMarker(id))
        val scratch = File(work, "scratch").apply { mkdirs() }
        File(scratch, "plain.pdf").writeBytes(byteArrayOf(9, 9, 9))

        repo.recoverOperations()

        assertTrue("a live staging folder is untouched", work.exists())
        assertTrue("its plaintext scratch is untouched by pruning or sweep", File(scratch, "plain.pdf").exists())
        OpStaging.release(work)
    }

    @Test
    fun `an Error inside one recovery step does not abort the later steps`() {
        val orphan = File(repo.docsDir, "deadbeef20").apply { assertTrue(mkdirs()) }
        val dead = OpStaging.create(app.filesDir)
        File(dead, "plain.pdf").writeBytes(byteArrayOf(1, 2, 3))
        OpStaging.resetForTests()

        ScanRepository.recoveryStepFault = { step ->
            if (step == "replace") throw OutOfMemoryError("injected startup failure")
        }
        ScanRepository.recoveryIncomplete = false
        val result = runCatching { repo.recoverOperations() }

        assertTrue("no Error may escape startup recovery: ${result.exceptionOrNull()}", result.isSuccess)
        assertTrue("an Error is exposed as incomplete", ScanRepository.recoveryIncomplete)
        assertFalse("later orphan cleanup still ran", orphan.exists())
        assertFalse("later staging sweep still ran", dead.exists())
    }

    @Test
    fun `a failed scratch deletion is exposed and the scratch is kept for retry`() {
        val id = DocFixtures.locked(repo, app, password = "1234")
        val death = Faults.dieAtOp("unlock:swapped")
        runCatching { runBlocking { repo.unlockPdf(id, "1234") } }
        assertTrue(death.fired)
        val work = stagingDirs().single()
        val scratch = File(work, "scratch").apply { assertTrue(mkdirs()) }
        val plaintext = File(scratch, "plain.pdf").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        Faults.resetAll()

        ScanRepository.removeLockFile = { false }
        ScanRepository.recoveryFileDelete = { false }
        ScanRepository.recoveryIncomplete = false
        repo.recoverOperations()

        assertTrue("a failed delete must be exposed", ScanRepository.recoveryIncomplete)
        assertTrue("unremoved scratch stays for the next start", plaintext.exists())
    }
}
