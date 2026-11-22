package com.scanwhirl.app.cr

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.Faults
import com.scanwhirl.app.store.ReplaceAuthorizationStore
import com.scanwhirl.app.store.ReplaceCoordinator
import com.scanwhirl.app.store.ReplaceFaults
import com.scanwhirl.app.store.ReplaceJournal
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.store.SimulatedProcessDeath
import com.scanwhirl.app.util.ReplaceFeature
import com.scanwhirl.app.util.Sha256
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** GPT security rework acceptance for lane CR (a9e8d7cc NO-GO). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CR_SecurityReworkTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository
    private lateinit var coordinator: ReplaceCoordinator
    private lateinit var journal: ReplaceJournal
    private lateinit var auth: ReplaceAuthorizationStore

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        Faults.resetAll()
        ReplaceFaults.reset()
        ReplaceFeature.testOverride = true
        coordinator = ReplaceCoordinator(app, repo)
        journal = ReplaceJournal(app)
        auth = ReplaceAuthorizationStore(app)
    }

    @After
    fun tearDown() {
        ReplaceFaults.reset()
        Faults.resetAll()
        ReplaceFeature.resetForTests()
    }

    private fun doc(name: String): String = DocFixtures.saved(repo, app, pages = 1, name = name)

    private fun hashes(id: String): List<String> {
        val d = repo.getDocument(id) ?: error("missing $id")
        return d.pageFiles.sortedBy { it.name }.map { Sha256.of(it) } +
            listOfNotNull(d.pdfFile.takeIf { it.isFile }?.let { Sha256.of(it) })
    }

    private fun authorize(source: String, output: String, tool: String = "compress"): String {
        val id = "auth-$source-$output-${System.nanoTime()}"
        auth.issue(
            id, source, output, tool,
            requireNotNull(repo.replaceDocIdentity(source)),
            requireNotNull(repo.replaceDocIdentity(output))
        )
        return id
    }

    private fun replaceWith(source: String, output: String, tool: String = "compress"): String {
        val key = runBlocking {
            coordinator.replace(authorize(source, output, tool), source, output, tool, source).getOrThrow()
        }
        return key
    }

    @Test
    fun `a death after the Trash rename leaves an identifiable original that survives 31 days`() = runBlocking {
        val source = doc("crash-source"); val output = doc("crash-out")
        val before = hashes(source)
        val authId = authorize(source, output)
        ScanRepository.trashFault = { stage ->
            if (stage == ScanRepository.Companion.TrashStage.MOVED) throw SimulatedProcessDeath("moved")
        }
        val failed = coordinator.replace(authId, source, output, "compress", source)
        ScanRepository.trashFault = {}
        assertTrue(failed.isFailure)
        // No Trash screen opened: the repository's own expiry entry reconciles first.
        repo.recoverTrash(System.currentTimeMillis() + 31L * 24 * 60 * 60 * 1000)
        val entry = journal.all().firstOrNull { it.sourceId == source }
        assertNotNull("recovery found the operation", entry)
        val key = requireNotNull(entry!!.trashKey)
        val trashed = repo.listTrash().firstOrNull { it.key == key }
        assertNotNull("original survived the purge", trashed)
        val d = trashed!!.document
        assertEquals(before, d.pageFiles.sortedBy { it.name }.map { Sha256.of(it) } +
            listOfNotNull(d.pdfFile.takeIf { it.isFile }?.let { Sha256.of(it) }))
        assertTrue(repo.trashHasIdentity(key, entry.sourceIdentity, entry.opId))
    }

    @Test
    fun `a consumed authorization cannot be replayed after the original is restored`() = runBlocking {
        val source = doc("replay-source"); val output = doc("replay-out")
        val authId = authorize(source, output)
        val key = coordinator.replace(authId, source, output, "compress", source).getOrThrow()
        coordinator.undo(requireNotNull(journal.findByTrashKey(key)?.opId)).getOrThrow()
        assertNotNull(repo.getDocument(source))
        val replay = coordinator.replace(authId, source, output, "compress", source)
        assertTrue("single-use authorization refused", replay.isFailure)
        assertTrue(repo.listTrash().none { it.originalId == source })
    }

    @Test
    fun `id reuse and an unrelated output are refused at commit`() = runBlocking {
        val source = doc("reuse-source"); val output = doc("reuse-out")
        val authId = authorize(source, output)
        val foreign = doc("foreign")
        File(repo.docsDir, source).deleteRecursively()
        File(repo.docsDir, foreign).copyRecursively(File(repo.docsDir, source))
        assertTrue(coordinator.replace(authId, source, output, "compress", source).isFailure)

        val source2 = doc("bound-source"); val output2 = doc("bound-out")
        val other = doc("unrelated-out")
        val auth2 = authorize(source2, output2)
        assertTrue(coordinator.replace(auth2, source2, other, "compress", source2).isFailure)
        assertNotNull(repo.getDocument(source2))
    }

    @Test
    fun `a source or output changed during confirmation is refused`() = runBlocking {
        val source = doc("changed-source"); val output = doc("changed-out")
        val authId = authorize(source, output)
        repo.getDocument(output)!!.pdfFile.writeBytes("changed".toByteArray())
        assertTrue(coordinator.replace(authId, source, output, "compress", source).isFailure)
        assertNotNull(repo.getDocument(source))

        val s2 = doc("changed2-source"); val o2 = doc("changed2-out")
        val auth2 = authorize(s2, o2)
        repo.getDocument(s2)!!.pageFiles.first().writeBytes("changed".toByteArray())
        assertTrue(coordinator.replace(auth2, s2, o2, "compress", s2).isFailure)
        assertNotNull(repo.getDocument(s2))
    }

    @Test
    fun `undo swaps the original back and trashes the output`() = runBlocking {
        val source = doc("swap-source"); val output = doc("swap-out")
        val before = hashes(source)
        val key = replaceWith(source, output)
        val outcome = coordinator.undo(requireNotNull(journal.findByTrashKey(key)?.opId)).getOrThrow()
        assertNotNull(repo.getDocument(outcome.restoredId))
        assertEquals(before, hashes(outcome.restoredId))
        assertNull(repo.getDocument(output))
        assertTrue(outcome.outputTrashed)
        assertFalse(outcome.outputKeptChanged)
        assertEquals(ReplaceJournal.STATE_UNDONE, journal.findBySource(source)?.state)
    }

    @Test
    fun `undo keeps a changed output but still restores the original honestly`() = runBlocking {
        val source = doc("keep-source"); val output = doc("keep-out")
        val before = hashes(source)
        val key = replaceWith(source, output)
        repo.getDocument(output)!!.pdfFile.writeBytes("edited".toByteArray())
        val outcome = coordinator.undo(requireNotNull(journal.findByTrashKey(key)?.opId)).getOrThrow()
        assertEquals(before, hashes(outcome.restoredId))
        assertTrue("changed output kept", outcome.outputKeptChanged)
        assertNotNull(repo.getDocument(output))
        assertNull(outcome.outputTrashKey)
    }

    @Test
    fun `death during undo is recovered and never deletes a document`() = runBlocking {
        for (step in listOf("before-restore", "after-restore", "before-output-trash", "after-output-trash")) {
            val source = doc("undo-$step"); val output = doc("undo-out-$step")
            val before = hashes(source)
            val key = replaceWith(source, output)
            val op = requireNotNull(journal.findByTrashKey(key)?.opId)
            ReplaceFaults.hook = { at -> if (at == step) throw SimulatedProcessDeath(at) }
            coordinator.undo(op)
            ReplaceFaults.reset()
            coordinator.recoverBeforeExpiry()
            assertNotNull("original still recoverable after $step",
                repo.getDocument(source) ?: repo.listTrash().firstOrNull { it.originalId == source })
            assertNotNull("output still exists after $step", repo.getDocument(output)
                ?: repo.listTrash().firstOrNull { it.originalId == output })
            val actual = if (repo.getDocument(source) != null) hashes(source)
            else trashedHashesOf(source)
            assertEquals(before, actual)
        }
    }

    private fun trashedHashesOf(originalId: String): List<String> {
        val key = requireNotNull(repo.listTrash().firstOrNull { it.originalId == originalId }?.key)
        val d = repo.listTrash().first { it.key == key }.document
        return d.pageFiles.sortedBy { it.name }.map { Sha256.of(it) } +
            listOfNotNull(d.pdfFile.takeIf { it.isFile }?.let { Sha256.of(it) })
    }

    @Test
    fun `release config ignores the mutable test override`() {
        assertFalse(ReplaceFeature.resolve(allowOverride = false, override = true))
        assertTrue(ReplaceFeature.resolve(allowOverride = true, override = true))
    }

    @Test
    fun `journal is pruned after undo and after the Trash window`() = runBlocking {
        val source = doc("prune-source"); val output = doc("prune-out")
        val key = replaceWith(source, output)
        val op = requireNotNull(journal.findByTrashKey(key)?.opId)
        coordinator.undo(op).getOrThrow()
        coordinator.recoverBeforeExpiry()
        assertNull("undone journal pruned", journal.load(op))

        val s2 = doc("old-source"); val o2 = doc("old-out")
        val k2 = replaceWith(s2, o2)
        val op2 = requireNotNull(journal.findByTrashKey(k2)?.opId)
        journal.backdate(op2, System.currentTimeMillis() - 31L * 24 * 60 * 60 * 1000)
        coordinator.recoverBeforeExpiry()
        assertNull("expired journal pruned", journal.load(op2))
    }
}
