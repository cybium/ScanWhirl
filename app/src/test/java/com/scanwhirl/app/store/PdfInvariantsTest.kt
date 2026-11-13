package com.scanwhirl.app.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** PM-0.2b: proves the shared invariant helpers detect violations (they are green on a healthy document). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PdfInvariantsTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)
    private val docsDir get() = File(app.filesDir, "documents").apply { mkdirs() }

    @After fun tearDown() = Faults.resetAll()

    // Pins ScanRepository.saveScan (publish after verify) and markPendingAfterPublish (ocr_pending).
    @Test fun `new scan satisfies I1 I3 I4 and I10`() {
        val id = DocFixtures.saved(repo, app, pages = 3)
        PdfInvariants.assertI1(repo, id)
        PdfInvariants.assertI3(repo, id)
        PdfInvariants.assertI4(repo)
        PdfInvariants.assertI10(repo, id)
        PdfInvariants.assertClean(app, repo)
        assertEquals(3, PdfInvariants.pageTreeCount(File(docsDir, "$id/scan.pdf")))
        assertEquals(false, PdfInvariants.trailerEncrypted(File(docsDir, "$id/scan.pdf")))
    }

    @Test fun `I4 check rejects a visible document without scan pdf`() {
        val id = DocFixtures.saved(repo, app, pages = 3)
        assertTrue(File(docsDir, "$id/scan.pdf").delete())
        // Still visible (ScanRepository.loadDocument: pages exist), so I4 is violated.
        assertTrue(repo.listDocuments(includeVaulted = true).any { it.id == id })
        assertThrows(AssertionError::class.java) { PdfInvariants.assertI4(repo) }
    }

    @Test fun `I1 check rejects a PDF whose Count differs from the page files`() {
        val id = DocFixtures.saved(repo, app, pages = 3)
        assertTrue(File(docsDir, "$id/page_002.jpg").delete())
        assertThrows(AssertionError::class.java) { PdfInvariants.assertI1(repo, id) }
    }

    @Test fun `I3 check rejects locked meta beside a plain PDF`() {
        val plain = DocFixtures.saved(repo, app, pages = 2)
        File(docsDir, "$plain/locked.meta").writeText("x")
        assertThrows(AssertionError::class.java) { PdfInvariants.assertI3(repo, plain) }

        val locked = DocFixtures.locked(repo, app, pages = 2)
        PdfInvariants.assertI3(repo, locked)
        assertEquals(true, PdfInvariants.trailerEncrypted(File(docsDir, "$locked/scan.pdf")))
    }

    @Test fun `dir digest changes on one flipped byte and on an added hidden file`() {
        val id = DocFixtures.saved(repo, app, pages = 2)
        val dir = File(docsDir, id)
        val page = File(dir, "page_000.jpg")
        val before = PdfInvariants.dirDigest(dir)

        val original = page.readBytes()
        val flipped = original.copyOf().also { it[100] = (it[100].toInt() xor 0xFF).toByte() }
        page.writeBytes(flipped)
        assertNotEquals(before, PdfInvariants.dirDigest(dir))
        page.writeBytes(original)
        assertEquals(before, PdfInvariants.dirDigest(dir))

        File(dir, ".x.swtmp").writeText("x")
        assertNotEquals(before, PdfInvariants.dirDigest(dir))
        assertEquals("absent", PdfInvariants.dirDigest(File(docsDir, "no-such-dir")))
    }

    @Test fun `orphan check flags a metadata-less directory under documents`() {
        val orphan = File(docsDir, "deadbeef").apply { mkdirs() }
        File(orphan, "page_000.jpg").writeBytes(byteArrayOf(1, 2, 3))
        assertThrows(AssertionError::class.java) { PdfInvariants.assertNoOrphans(app, repo) }

        orphan.deleteRecursively()
        val owned = File(docsDir, "cafebabe").apply { mkdirs() }
        SaveFormat.createMarker(owned, SaveFormat.newToken(), System.currentTimeMillis())
        PdfInvariants.assertNoOrphans(app, repo)
    }

    @Test fun `faults fire once and resetAll restores every seam`() {
        val id = DocFixtures.saved(repo, app, pages = 1)
        val probe = Faults.dieAtFileSync("meta.txt.tmp")
        assertThrows(SimulatedProcessDeath::class.java) { repo.renameDocument(id, "x") }
        assertTrue(probe.fired)

        Faults.dieAtOp("step-a")
        assertThrows(SimulatedProcessDeath::class.java) { OpFaults.at("step-a") }
        OpFaults.at("step-b") // only the exact step fires

        Faults.resetAll()
        repo.renameDocument(id, "x")
        assertEquals("x", repo.getDocument(id)!!.name)
        OpFaults.at("anything") // does not throw after a reset
        OpFaults.at("step-a")
    }
}
