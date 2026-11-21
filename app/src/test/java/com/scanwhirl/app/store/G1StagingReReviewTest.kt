package com.scanwhirl.app.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.util.Durable
import com.scanwhirl.app.util.PdfProtection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Sol re-review: a parseable recovery marker must not pin an ENTIRE staging folder. Only the small
 * metadata needed to finish belongs on disk; plaintext scratch is deleted, and a marker naming a
 * missing document or an unrelated PDF is stale and removes the whole folder.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class G1StagingReReviewTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)

    @After
    fun tearDown() = Faults.resetAll()

    private fun stagingDirs(): List<File> =
        File(app.filesDir, "op_staging").listFiles { it -> it.isDirectory }.orEmpty().toList()

    private fun marker(id: String, oldPdf: String, newPdf: String): String =
        "scanwhirl-unlock-v1\nid=$id\nold=oldverifier\npdf=$oldPdf\nnewPdf=$newPdf\n"

    @Test
    fun `a marker naming a missing document removes the whole dir including plaintext`() {
        val work = OpStaging.create(app.filesDir)
        File(work, "plain.pdf").writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        File(work, ".unlock").writeText(marker("missing0001", "0".repeat(64), "1".repeat(64)))
        OpStaging.resetForTests() // a new process owns no live staging folders

        repo.recoverOperations()

        assertFalse("a stale marker must not keep plaintext forever", work.exists())
    }

    @Test
    fun `a valid unresolved marker keeps only its metadata and drops plaintext scratch`() {
        val id = DocFixtures.locked(repo, app, password = "1234")
        val death = Faults.dieAtOp("unlock:swapped")
        runCatching { runBlocking { repo.unlockPdf(id, "1234") } }
        assertTrue("the unlock must die after the swap", death.fired)
        val work = stagingDirs().single()
        File(work, "plain.pdf").writeBytes(byteArrayOf(9, 9, 9, 9))
        File(work, "scratch.jpg").writeBytes(byteArrayOf(8, 8, 8, 8))
        Faults.resetAll()
        // Keep this marker UNRESOLVED so recovery cannot finish and delete it itself.
        ScanRepository.removeLockFile = { false }

        repo.recoverOperations()

        assertTrue("the unresolved marker folder is kept", work.exists())
        assertTrue("the marker itself stays", File(work, ".unlock").isFile)
        assertFalse("plaintext scratch is deleted", File(work, "plain.pdf").exists())
        assertFalse("other scratch is deleted", File(work, "scratch.jpg").exists())
        assertTrue("the document is still recoverable", File(repo.docsDir, id).exists())
    }

    @Test
    fun `a marker whose digests match no PDF is stale and its dir is removed`() {
        val id = DocFixtures.locked(repo, app, password = "1234")
        val work = OpStaging.create(app.filesDir)
        File(work, "plain.pdf").writeBytes(byteArrayOf(7, 7, 7, 7))
        File(work, ".unlock").writeText(marker(id, "2".repeat(64), "3".repeat(64)))
        OpStaging.resetForTests()

        repo.recoverOperations()

        assertFalse("stale digests must not pin the folder", work.exists())
        assertTrue("the document is untouched", File(repo.docsDir, id).exists())
        assertTrue("still locked", File(File(repo.docsDir, id), PdfProtection.LOCK_FILE).exists())
    }

    @Test
    fun `cancellation thrown inside a recovery step propagates to the caller`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Folder doc")
        val meta = File(File(repo.docsDir, id), "meta.txt")
        val lines = meta.readLines().toMutableList()
        LibraryMeta.write(lines, "Receipts", emptyList())
        meta.writeText(lines.joinToString("\n") + "\n")

        var fired = false
        Durable.beforeFileSync = { f ->
            if (f.name == "library_folders.txt.tmp") {
                fired = true
                throw CancellationException("startup cancelled")
            }
        }

        val result = runCatching { repo.recoverOperations() }

        assertTrue("the folders.txt write was attempted", fired)
        assertTrue("CancellationException must not be swallowed: ${result.exceptionOrNull()}",
            result.exceptionOrNull() is CancellationException)
    }

    @Test
    fun `a failed lock-label removal in recovery exposes incomplete`() {
        val id = DocFixtures.locked(repo, app, password = "1234")
        val death = Faults.dieAtOp("unlock:swapped")
        runCatching { runBlocking { repo.unlockPdf(id, "1234") } }
        assertTrue("the unlock must die after the swap", death.fired)
        Faults.resetAll()

        ScanRepository.removeLockFile = { false }
        ScanRepository.recoveryIncomplete = false
        repo.recoverOperations()

        assertTrue("a failed lock removal must mark recovery incomplete", ScanRepository.recoveryIncomplete)
    }
}
