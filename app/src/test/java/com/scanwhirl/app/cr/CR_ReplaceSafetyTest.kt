package com.scanwhirl.app.cr

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.Faults
import com.scanwhirl.app.store.ReplaceCoordinator
import com.scanwhirl.app.store.ReplaceFaults
import com.scanwhirl.app.store.ReplaceJournal
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.store.SimulatedProcessDeath
import com.scanwhirl.app.util.ReplaceFeature
import com.scanwhirl.app.util.Sha256
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
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
import java.io.IOException

/**
 * Lane CR acceptance: Replace original is safe at every journal boundary.
 * The feature flag ships OFF; these tests opt in explicitly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CR_ReplaceSafetyTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository
    private lateinit var coordinator: ReplaceCoordinator
    private lateinit var journal: ReplaceJournal

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        Faults.resetAll()
        ReplaceFaults.reset()
        ReplaceFeature.testOverride = true
        coordinator = ReplaceCoordinator(app, repo)
        journal = ReplaceJournal(app)
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

    private fun trashedHashes(key: String): List<String> {
        val d = repo.listTrash().first { it.key == key }.document
        return d.pageFiles.sortedBy { it.name }.map { Sha256.of(it) } +
            listOfNotNull(d.pdfFile.takeIf { it.isFile }?.let { Sha256.of(it) })
    }

    private fun sourceIdentity(id: String): String = requireNotNull(repo.replaceDocIdentity(id))

    @Test
    fun `the feature flag ships off and entry is dark by default`() {
        ReplaceFeature.resetForTests()
        assertFalse(ReplaceFeature.isEnabled())
        val result = runBlocking { coordinator.replace(doc("a"), doc("b"), "compress", "a") }
        assertTrue(result.isFailure)
    }

    @Test
    fun `only proven single input transforms are eligible`() {
        val a = doc("source"); val b = doc("output")
        assertTrue(repo.replaceIsEligible(a, b, "compress"))
        assertFalse(repo.replaceIsEligible(a, b, "merge"))
        assertFalse(repo.replaceIsEligible(a, a, "compress"))
        val locked = DocFixtures.locked(repo, app)
        assertFalse(repo.replaceIsEligible(locked, b, "compress"))
    }

    @Test
    fun `replace moves only the source to Trash and undo restores identical bytes`() = runTest {
        val source = doc("original"); val output = doc("compressed"); val foreign = doc("foreign")
        val before = hashes(source); val foreignBefore = hashes(foreign)
        val id = sourceIdentity(source)
        val key = coordinator.replace(source, output, "compress", "original").getOrThrow()
        assertEquals(ReplaceJournal.STATE_DONE, journal.findBySource(source)?.state)
        assertNull(repo.getDocument(source))
        assertNotNull(repo.getDocument(output))
        assertEquals(before, trashedHashes(key))
        assertEquals(foreignBefore, hashes(foreign))
        assertTrue("30-day marker protects the original",
            repo.trashHasIdentity(key, id, requireNotNull(journal.findBySource(source)?.opId)))

        val restored = coordinator.undoByTrashKey(key).getOrThrow()
        assertEquals(before, hashes(restored.restoredId))
        assertEquals(foreignBefore, hashes(foreign))
        assertEquals(ReplaceJournal.STATE_UNDONE, journal.findBySource(source)?.state)
    }

    @Test
    fun `a protected pending original survives a 31 day purge`() = runTest {
        val source = doc("pending"); val output = doc("pending-out")
        val key = coordinator.replace(source, output, "compress", "pending").getOrThrow()
        repo.recoverTrash(System.currentTimeMillis() + 31L * 24 * 60 * 60 * 1000)
        assertNotNull("pending original is not expired", repo.listTrash().firstOrNull { it.key == key })
    }

    @Test
    fun `death at every journal boundary is recovered without losing the original`() = runTest {
        for (step in listOf("before-move", "after-move", "after-committing", "after-done")) {
            val source = doc("death-$step"); val output = doc("out-$step")
            val before = hashes(source)
            val id = sourceIdentity(source)
            ReplaceFaults.hook = { at -> if (at == step) throw SimulatedProcessDeath(at) }
            coordinator.replace(source, output, "compress", source)
            ReplaceFaults.reset()
            // Simulated restart: recovery runs before Trash expiry.
            coordinator.recoverBeforeExpiry()
            val op = journal.all().firstOrNull { it.sourceId == source }
            if (op == null) {
                // No move was authorized: the source is still in the library.
                assertNotNull(repo.getDocument(source))
                assertEquals(before, hashes(source))
            } else {
                val key = requireNotNull(op.trashKey) { "recovery must find the moved entry: $step" }
                assertEquals(before, trashedHashes(key))
                assertTrue(repo.trashHasIdentity(key, id, op.opId))
            }
        }
    }

    @Test
    fun `a source changed after the intent is never moved`() = runTest {
        val source = doc("changed"); val output = doc("changed-out")
        var key: String? = null
        ReplaceFaults.hook = { step ->
            if (step == "before-move") {
                repo.getDocument(source)!!.pageFiles.first().writeBytes("tampered".toByteArray())
            }
        }
        val result = coordinator.replace(source, output, "compress", source)
        ReplaceFaults.reset()
        assertTrue("identity gate refused the changed source", result.isFailure)
        assertNull(key)
        assertNotNull(repo.getDocument(source))
        assertTrue(repo.listTrash().isEmpty())
    }

    @Test
    fun `a failed move leaves no journal that claims success`() = runTest {
        val source = doc("disk-full"); val output = doc("disk-full-out")
        ReplaceFaults.hook = { step -> if (step == "before-move") throw IOException("no space") }
        val result = coordinator.replace(source, output, "compress", source)
        ReplaceFaults.reset()
        assertTrue(result.isFailure)
        assertNotNull(repo.getDocument(source))
        assertTrue("no DONE journal", journal.all().none { it.state == ReplaceJournal.STATE_DONE })
    }

    @Test
    fun `a collision restore comes back under a new id with the same bytes`() = runTest {
        val source = doc("collide"); val output = doc("collide-out")
        val before = hashes(source)
        val key = coordinator.replace(source, output, "compress", source).getOrThrow()
        val squatter = doc("squatter")
        File(repo.docsDir, squatter).copyRecursively(File(repo.docsDir, source))
        assertNotNull(repo.getDocument(source))
        val restored = coordinator.undoByTrashKey(key).getOrThrow()
        assertFalse("no overwrite of the squatter", restored.restoredId == source)
        assertEquals(before, hashes(restored.restoredId))
        assertEquals("squatter", repo.getDocument(source)?.name)
    }

    @Test
    fun `two concurrent replaces of one source run single flight`() = runTest {
        val source = doc("race"); val a = doc("race-a"); val b = doc("race-b")
        val before = hashes(source)
        val first = async { coordinator.replace(source, a, "compress", source) }
        val second = async { coordinator.replace(source, b, "compress", source) }
        val results = listOf(first.await(), second.await())
        assertTrue("at least one succeeded", results.any { it.isSuccess })
        assertTrue("the other was refused", results.any { it.isFailure })
        assertEquals(1, repo.listTrash().count { it.originalId == source })
        val entry = journal.findBySource(source)
        assertNotNull(entry)
        val kept = repo.listTrash().first { it.originalId == source }
        assertEquals(before, trashedHashes(kept.key))
    }
}
