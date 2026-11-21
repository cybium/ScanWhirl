package com.scanwhirl.app.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * G1 integration-audit extra item: folder repair used to run FIRST, so a disk-full folders.txt
 * write aborted startup recovery before an interrupted Replace was rolled forward or dead staging
 * was swept, while startup still declared recovery complete. Core steps now run first, each step
 * is isolated, folder repair is last, and failure is exposed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class G1RecoveryIsolationTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)

    @After
    fun tearDown() = Faults.resetAll()

    @Test
    fun `a folder write failure cannot abort Replace roll forward or the staging sweep`() {
        // An interrupted Replace: base is in Trash, prepared version is sealed in staging.
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Interrupted Replace")
        val retainedPage = requireNotNull(repo.getDocument(id)).pageFiles.last().readBytes()
        runBlocking { repo.draftDelete(id, 0) }
        val death = Faults.dieAtOp("replace:base_trashed")
        runCatching { runBlocking { repo.replaceWithDraft(id) } }
        assertTrue("the Replace must have been interrupted", death.fired)
        Faults.resetAll()
        assertFalse(File(repo.docsDir, id).exists())

        // Abandoned plaintext staging that the sweep must still remove.
        val deadStaging = OpStaging.create(app.filesDir)
        File(deadStaging, "plain.pdf").writeBytes(byteArrayOf(1, 2, 3, 4))

        // Folder work that will fail: a tombstone for an interrupted delete and a document whose
        // folder name is missing from folders.txt. Both writes fail at the folders.txt sync.
        val other = DocFixtures.saved(repo, app, pages = 1, name = "Folder doc")
        val meta = File(File(repo.docsDir, other), "meta.txt")
        val lines = meta.readLines().toMutableList()
        LibraryMeta.write(lines, "Receipts", emptyList())
        meta.writeText(lines.joinToString("\n") + "\n")
        val tombstone = File(app.filesDir, "library_folders_deleting.txt")
        tombstone.writeText("Old folder\n")

        val folderFailure = Faults.failAtFileSync("library_folders.txt.tmp")
        // Simulate a fresh process: staging created above must no longer count as live.
        OpStaging.resetForTests()

        ScanRepository.recoveryIncomplete = false
        repo.recoverOperations()

        assertTrue("the folders.txt write was attempted", folderFailure.fired)
        assertNotNull("the interrupted Replace still rolled forward", repo.getDocument(id))
        assertArrayEquals("the retained page is intact", retainedPage,
            requireNotNull(repo.getDocument(id)).pageFiles.single().readBytes())
        assertFalse("abandoned plaintext staging was still swept", deadStaging.exists())
        assertTrue("incomplete recovery is exposed", ScanRepository.recoveryIncomplete)
        assertTrue("failed folder work is kept for retry", tombstone.exists())
    }

    @Test
    fun `a clean recovery pass is not marked incomplete`() {
        repo.recoverOperations()
        assertFalse("a clean pass must not expose incomplete", ScanRepository.recoveryIncomplete)
    }
}
