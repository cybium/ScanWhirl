package com.scanwhirl.app.ux130.l3

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.Faults
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.store.SimulatedProcessDeath
import com.scanwhirl.app.store.ToolResultChoiceStore
import com.scanwhirl.app.util.Sha256
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * L3-01 — repository fault injection and durable-journal recovery.
 *
 * Every assertion about recovery is an assertion about BYTES: a source moved to
 * Trash and restored must be byte-for-byte what it was. A foreign document is
 * never touched. Faults simulate process death at all four Trash boundaries.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class L3_01_ToolResultChoiceStoreTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        Faults.resetAll()
    }

    @After
    fun tearDown() = Faults.resetAll()

    private fun doc(name: String): String = DocFixtures.saved(repo, app, pages = 1, name = name)

    /** Per-file SHA-256, so equality is byte equality (ByteArray.equals is referential). */
    private fun bytes(id: String): List<String> {
        val d = requireNotNull(repo.getDocument(id)) { "missing $id" }
        return d.pageFiles.sortedBy { it.name }.map { Sha256.of(it) } +
            listOfNotNull(d.pdfFile.takeIf { it.isFile }?.let { Sha256.of(it) })
    }

    private fun trashedBytes(key: String): List<String> {
        val d = requireNotNull(repo.listTrash().firstOrNull { it.key == key }) { "missing trash $key" }.document
        return d.pageFiles.sortedBy { it.name }.map { Sha256.of(it) } +
            listOfNotNull(d.pdfFile.takeIf { it.isFile }?.let { Sha256.of(it) })
    }

    private fun entry(
        op: String,
        source: String,
        output: String,
        fp: String,
        decision: String,
        key: String? = null
    ) = ToolResultChoiceStore.Entry(
        operationId = op, sourceId = source, outputId = output,
        operation = "compress", sourceFingerprint = fp,
        preferred = "replace", decision = decision, trashKey = key,
        updatedAt = System.currentTimeMillis()
    )

    @Test
    fun `replace is eligible only for a proven live one-in one-out transform`() {
        val source = doc("source")
        val output = doc("output")
        assertTrue(repo.isToolReplaceEligible(source, output, "compress"))
        assertFalse("unsupported operations never auto-replace", repo.isToolReplaceEligible(source, output, "merge"))
        assertFalse("same id is not two documents", repo.isToolReplaceEligible(source, source, "compress"))
        assertFalse("missing source", repo.isToolReplaceEligible("absent", output, "compress"))
        assertFalse("missing output", repo.isToolReplaceEligible(source, "absent", "compress"))
        val locked = DocFixtures.locked(repo, app)
        assertFalse("locked source", repo.isToolReplaceEligible(locked, output, "compress"))
    }

    @Test
    fun `replace moves only the proven source and restores it byte identically`() {
        val source = doc("original")
        val output = doc("compressed")
        val foreign = doc("unrelated")
        val before = bytes(source)
        val foreignBefore = bytes(foreign)
        val fp = requireNotNull(repo.toolSourceFingerprint(source))

        val key = repo.moveToolSourceToTrash(source, fp)
        assertNull("source is no longer in the library", repo.getDocument(source))
        assertNotNull("the output copy is untouched", repo.getDocument(output))
        assertEquals(before, trashedBytes(key))
        assertEquals(foreignBefore, bytes(foreign))

        val restored = repo.restoreToolSource(key, fp)
        assertEquals(before, bytes(restored.id))
        assertEquals(foreignBefore, bytes(foreign))
        assertNotNull("output still there after restore", repo.getDocument(output))
    }

    @Test
    fun `replace keep replace cycles never change the original bytes`() {
        val source = doc("cycle")
        val output = doc("cycle-out")
        val before = bytes(source)
        val fp = requireNotNull(repo.toolSourceFingerprint(source))
        repeat(3) {
            val key = repo.moveToolSourceToTrash(source, fp)
            assertEquals(before, trashedBytes(key))
            val restored = repo.restoreToolSource(key, fp)
            assertEquals(before, bytes(restored.id))
        }
        assertNotNull(repo.getDocument(source))
    }

    @Test
    fun `death before the Trash move is recoverable from the journal`() {
        val source = doc("die-before")
        val output = doc("die-before-out")
        val before = bytes(source)
        val fp = requireNotNull(repo.toolSourceFingerprint(source))
        ScanRepository.trashFault = {
            if (it == ScanRepository.Companion.TrashStage.BEFORE_MOVE) throw SimulatedProcessDeath("before-move")
        }
        assertThrows(SimulatedProcessDeath::class.java) { repo.moveToolSourceToTrash(source, fp) }
        assertNotNull("no half move: source still in the library", repo.getDocument(source))
        assertEquals(before, bytes(source))

        ScanRepository.trashFault = {}
        val store = ToolResultChoiceStore(app)
        store.save(entry("op-before", source, output, fp, "replace"))
        val done = requireNotNull(store.reconcile(repo, "op-before"))
        assertEquals(before, trashedBytes(requireNotNull(done.trashKey)))
        assertNull(repo.getDocument(source))
    }

    @Test
    fun `death after the Trash rename finds the entry by fingerprint`() {
        val source = doc("die-after")
        val output = doc("die-after-out")
        val before = bytes(source)
        val fp = requireNotNull(repo.toolSourceFingerprint(source))
        ScanRepository.trashFault = {
            if (it == ScanRepository.Companion.TrashStage.MOVED) throw SimulatedProcessDeath("moved")
        }
        assertThrows(SimulatedProcessDeath::class.java) { repo.moveToolSourceToTrash(source, fp) }
        assertNull("the rename already happened", repo.getDocument(source))

        ScanRepository.trashFault = {}
        val store = ToolResultChoiceStore(app)
        store.save(entry("op-after", source, output, fp, "replace"))
        val done = requireNotNull(store.reconcile(repo, "op-after"))
        assertEquals(before, trashedBytes(requireNotNull(done.trashKey)))
    }

    @Test
    fun `deaths on both restore boundaries are recovered and keep the bytes`() {
        val source = doc("restore-death")
        val output = doc("restore-death-out")
        val before = bytes(source)
        val fp = requireNotNull(repo.toolSourceFingerprint(source))
        val key = repo.moveToolSourceToTrash(source, fp)
        val store = ToolResultChoiceStore(app)

        // Before the rename: still in Trash, a later pass restores it.
        ScanRepository.trashFault = {
            if (it == ScanRepository.Companion.TrashStage.BEFORE_RESTORE) throw SimulatedProcessDeath("before-restore")
        }
        store.save(entry("op-restore", source, output, fp, "keep_both", key))
        try { store.reconcile(repo, "op-restore") } catch (_: SimulatedProcessDeath) { }
        assertNull("still in Trash after the failed restore", repo.getDocument(source))
        ScanRepository.trashFault = {}
        store.reconcile(repo, "op-restore")
        assertEquals(before, bytes(source))

        // After the rename: source is back in the library, so recovery only drops the stale key.
        val key2 = repo.moveToolSourceToTrash(source, fp)
        ScanRepository.trashFault = {
            if (it == ScanRepository.Companion.TrashStage.RESTORED) throw SimulatedProcessDeath("restored")
        }
        store.save(entry("op-restore2", source, output, fp, "keep_both", key2))
        try { store.reconcile(repo, "op-restore2") } catch (_: SimulatedProcessDeath) { }
        assertEquals(before, bytes(source))
        ScanRepository.trashFault = {}
        val done = requireNotNull(store.reconcile(repo, "op-restore2"))
        assertNull("stale Trash key cleared", done.trashKey)
        assertEquals(before, bytes(source))
    }

    @Test
    fun `a changed or locked source is never moved to Trash`() {
        val source = doc("changed")
        val output = doc("changed-out")
        val fp = requireNotNull(repo.toolSourceFingerprint(source))
        repo.getDocument(source)!!.pageFiles.first().writeBytes("tampered".toByteArray())
        assertThrows(IOException::class.java) { repo.moveToolSourceToTrash(source, fp) }
        assertNotNull("source kept in place", repo.getDocument(source))
        assertTrue("nothing entered Trash", repo.listTrash().isEmpty())
    }

    @Test
    fun `a changed Trash entry is not restored over the library`() {
        val source = doc("changed-trash")
        val output = doc("changed-trash-out")
        val fp = requireNotNull(repo.toolSourceFingerprint(source))
        val key = repo.moveToolSourceToTrash(source, fp)
        repo.listTrash().first { it.key == key }.document.pageFiles.first().writeBytes("tampered".toByteArray())
        assertThrows(IOException::class.java) { repo.restoreToolSource(key, fp) }
        assertNull("still in Trash, nothing overwritten", repo.getDocument(source))
    }

    @Test
    fun `two concurrent results keep independent journals`() {
        val a = doc("a"); val aOut = doc("a-out")
        val b = doc("b"); val bOut = doc("b-out")
        val aBefore = bytes(a); val bBefore = bytes(b)
        val aFp = requireNotNull(repo.toolSourceFingerprint(a))
        val bFp = requireNotNull(repo.toolSourceFingerprint(b))
        val store = ToolResultChoiceStore(app)
        store.save(entry("op-a", a, aOut, aFp, "replace"))
        store.save(entry("op-b", b, bOut, bFp, "keep_both"))
        store.reconcile(repo, "op-a")
        store.reconcile(repo, "op-b")
        assertNull(repo.getDocument(a))
        assertNotNull("second result's source is untouched", repo.getDocument(b))
        assertEquals(bBefore, bytes(b))
        assertEquals(aBefore, trashedBytes(requireNotNull(store.load("op-a")?.trashKey)))
        assertNull(store.load("op-b")?.trashKey)
    }
}
