package com.scanwhirl.app.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.util.Durable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/**
 * L1lib2 P1: folder rename must be consistent at EVERY step, not just on the happy path.
 *
 * The rename writes folders.txt twice: first with BOTH old and new names (so a document
 * rewritten to the new name is never orphaned), then the final list with the old name
 * removed. A failure rolls documents back; a process dying between the two writes leaves
 * the intermediate (both-names) list, which startup/next-load reconciliation tolerates.
 *
 * Fictional fixtures only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RenameCrashSafeL1Lib2Test {

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
    }

    private class ProcessDeath : Error("simulated process death between steps")

    private fun seededFolder(name: String = "Old"): List<String> {
        repo.createFolder(name)
        return listOf(
            DocFixtures.saved(repo, app, pages = 1, name = "A"),
            DocFixtures.saved(repo, app, pages = 1, name = "B")
        ).also { repo.setFolder(it, name) }
    }

    private fun onFolderSync(action: (Int) -> Unit) {
        var syncs = 0
        Durable.beforeFileSync = { file ->
            if (file.name == "library_folders.txt.tmp") action(++syncs)
        }
    }

    /** @throws IOException when the intermediate folders.txt write fails. */
    @Test(expected = IOException::class)
    fun `failure at the intermediate folders write changes nothing`() {
        val ids = seededFolder()
        onFolderSync { n -> if (n == 1) throw IOException("fictional full disk") }
        repo.renameFolder("Old", "New")
    }

    @Test
    fun `after the intermediate write fails the old folder and its documents survive`() {
        val ids = seededFolder()
        onFolderSync { n -> if (n == 1) throw IOException("fictional full disk") }
        runCatching { repo.renameFolder("Old", "New") }
        Durable.resetForTests()

        assertEquals(listOf("Old"), repo.listFolders())
        ids.forEach { assertEquals("Old", repo.getDocument(it)?.folder) }
    }

    @Test
    fun `failure at the final folders write rolls documents back`() {
        val ids = seededFolder()
        onFolderSync { n -> if (n == 2) throw IOException("fictional full disk") }
        try {
            repo.renameFolder("Old", "New")
            fail("the final folders.txt write must fail the rename")
        } catch (_: IOException) {
            // expected
        }
        Durable.resetForTests()

        assertEquals(listOf("Old"), repo.listFolders())
        ids.forEach { assertEquals("Old", repo.getDocument(it)?.folder) }
    }

    @Test
    fun `death between the two folder writes leaves both names and no orphaned document`() {
        val ids = seededFolder()
        onFolderSync { n -> if (n == 2) throw ProcessDeath() }
        try {
            repo.renameFolder("Old", "New")
            fail("the simulated death must propagate")
        } catch (_: ProcessDeath) {
            // expected: an Error is deliberately not rolled back
        }
        Durable.resetForTests()

        // The intermediate list names BOTH folders, so documents rewritten to "New" are
        // not silently dropped into "All only" when the app restarts.
        val names = repo.listFolders()
        assertTrue("intermediate list keeps the old name: $names", names.contains("Old"))
        assertTrue("intermediate list has the new name: $names", names.contains("New"))
        ids.forEach { assertEquals("New", repo.getDocument(it)?.folder) }

        // Startup / next-load reconciliation keeps the new name even if only the old
        // name had survived in folders.txt (reflection keeps this RED on the old build).
        val file = File(File(repo.docsDir, ids.first()), "meta.txt")
        file.writeText(file.readLines().toMutableList().also { lines ->
            val meta = LibraryMeta.read(lines)
            LibraryMeta.write(lines, "Ghost folder", meta.tags)
        }.joinToString("\n") + "\n")
        val reconcile = repo.javaClass.getDeclaredMethod("reconcileFolders").apply { isAccessible = true }
        reconcile.invoke(repo)
        assertTrue("reconcile adds a folder Name present only in documents", repo.listFolders().contains("Ghost folder"))
        ids.forEach { assertFalse(it, repo.getDocument(it)?.folder == null) }
    }

    @Test
    fun `reconciliation on next library load adds a folder name missing from folders txt`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Receipt")
        val file = File(File(repo.docsDir, id), "meta.txt")
        val lines = file.readLines().toMutableList()
        LibraryMeta.write(lines, "Travelling file", emptyList())
        file.writeText(lines.joinToString("\n") + "\n")
        assertFalse("the folder is not in folders.txt yet", repo.listFolders().contains("Travelling file"))

        assertEquals(1, repo.listDocuments().size)
        assertTrue("next load reconciles the folder name", repo.listFolders().contains("Travelling file"))
    }
}
