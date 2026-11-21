package com.scanwhirl.app.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.util.Durable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * L1lib3: every folders.txt write has a safe order, and reconciliation is startup-only.
 *
 * Operations that write folders.txt and the proof for each order:
 *  - create: list first (name exists before any document can point at it). A death before
 *    the write leaves nothing; after it leaves a harmless empty folder.
 *  - rename: list with BOTH names, rewrite documents, final list without the old name.
 *    Every intermediate document name resolves to a folder (tested in RenameCrashSafe...).
 *  - move/setFolder: create the target folder first, then rewrite documents. A death
 *    mid-move leaves only documents referencing a folder that exists.
 *  - delete: rewrite documents FIRST (clear folder), then remove the name from the list,
 *    with a tombstone so a death mid-delete is completed on the next startup. Otherwise
 *    reconciliation would resurrect a folder the user deleted.
 *
 * Fictional fixtures only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FolderWriteOrderL1Lib3Test {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        Durable.resetForTests()
    }

    @After
    fun tearDown() {
        Durable.resetForTests()
        File(app.filesDir, "library_folders_deleting.txt").delete()
    }

    private class ProcessDeath : Error("simulated process death")

    private fun docsIn(folder: String): List<String> {
        repo.createFolder(folder)
        return listOf(
            DocFixtures.saved(repo, app, pages = 1, name = "A"),
            DocFixtures.saved(repo, app, pages = 1, name = "B")
        ).also { repo.setFolder(it, folder) }
    }

    // ---- P1: delete must not be resurrected by startup reconciliation ----

    @Test
    fun `a delete that dies midway stays deleted on startup and strands no document`() {
        val ids = docsIn("Old")
        // On the buggy order the folders.txt write already succeeded when this fires;
        // on the fixed order it fires while documents are being cleared (list still has Old).
        Durable.beforeFileSync = { file ->
            if (file.name == "meta.txt.tmp") throw ProcessDeath()
        }
        try {
            repo.deleteFolder("Old")
            fail("the simulated death must propagate")
        } catch (_: ProcessDeath) {
            // expected
        }
        Durable.resetForTests()

        // Next process start runs storage recovery before the UI reads folders.
        repo.recoverOperations()

        assertFalse("the deleted folder must not come back", repo.listFolders().contains("Old"))
        ids.forEach { id ->
            assertNull("no document stranded in a deleted folder", repo.getDocument(id)?.folder)
        }
    }

    @Test
    fun `a normal delete still removes the folder and keeps its documents`() {
        val ids = docsIn("Temp")
        repo.deleteFolder("Temp")
        assertFalse(repo.listFolders().contains("Temp"))
        ids.forEach { assertEquals(1, repo.getDocument(it)!!.pageCount) }
        ids.forEach { assertNull(repo.getDocument(it)?.folder) }
    }

    // ---- P2: listing documents is read-only for folders.txt ---------------

    @Test
    fun `listDocuments never writes folders txt even for a stale document folder`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Receipt")
        val file = File(File(repo.docsDir, id), "meta.txt")
        val lines = file.readLines().toMutableList()
        LibraryMeta.write(lines, "Ghost folder", emptyList())
        file.writeText(lines.joinToString("\n") + "\n")
        assertFalse(repo.listFolders().contains("Ghost folder"))

        var writes = 0
        Durable.beforeFileSync = { f -> if (f.name == "library_folders.txt.tmp") writes++ }
        assertEquals(1, repo.listDocuments().size)
        Durable.resetForTests()

        assertEquals("listing documents must be read-only for folders.txt", 0, writes)
        assertFalse("no reconciliation from a main-thread caller", repo.listFolders().contains("Ghost folder"))
    }

    // ---- P2: reconciled names follow the same rules as user-created names -

    @Test
    fun `reconciliation ignores invalid and denormalized folder names`() {
        // Reflection keeps this RED on builds where reconcileFrom is private/absent.
        val reconcile = repo.javaClass.getDeclaredMethod("reconcileFrom", Collection::class.java)
            .apply { isAccessible = true }
        val invalid = listOf("  padded  ", "x".repeat(41), " ", "line\nbreak", "tab\there", "")
        reconcile.invoke(repo, invalid + "Valid folder")

        val folders = repo.listFolders()
        assertEquals(listOf("Valid folder"), folders)
    }

    @Test
    fun `startup reconciliation still adds a valid folder name missing from folders txt`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Paper")
        val file = File(File(repo.docsDir, id), "meta.txt")
        val lines = file.readLines().toMutableList()
        LibraryMeta.write(lines, "Receipts", emptyList())
        file.writeText(lines.joinToString("\n") + "\n")

        repo.recoverOperations()
        assertTrue("startup reconciliation adds the name", repo.listFolders().contains("Receipts"))
        assertEquals("Receipts", repo.getDocument(id)?.folder)
    }

    // ---- proofs for the other two folder-writing operations ---------------

    @Test
    fun `create dies at the folders write and leaves no folder and no moved document`() {
        var syncs = 0
        Durable.beforeFileSync = { f ->
            if (f.name == "library_folders.txt.tmp") {
                syncs++
                throw ProcessDeath()
            }
        }
        try {
            repo.createFolder("Fresh")
            fail("the simulated death must propagate")
        } catch (_: ProcessDeath) {
            // expected
        }
        Durable.resetForTests()

        assertTrue("the folders.txt write was attempted", syncs > 0)
        assertFalse(repo.listFolders().contains("Fresh"))
        assertTrue(repo.listDocuments().all { it.folder == null })
    }

    @Test
    fun `move keeps its target folder even if a document write dies`() {
        val ids = listOf(
            DocFixtures.saved(repo, app, pages = 1, name = "M1"),
            DocFixtures.saved(repo, app, pages = 1, name = "M2")
        )
        repo.createFolder("Target")
        Durable.beforeFileSync = { f -> if (f.name == "meta.txt.tmp") throw ProcessDeath() }
        try {
            repo.setFolder(ids, "Target")
            fail("the simulated death must propagate")
        } catch (_: ProcessDeath) {
            // expected
        }
        Durable.resetForTests()

        assertTrue("the target folder was created before any document moved", repo.listFolders().contains("Target"))
        repo.listDocuments().forEach { doc ->
            if (doc.folder != null) assertEquals("Target", doc.folder)
        }
    }
}
