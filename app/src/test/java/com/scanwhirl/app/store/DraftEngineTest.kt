package com.scanwhirl.app.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.util.ImageTools
import com.scanwhirl.app.util.ImportFixtures
import com.scanwhirl.app.util.PdfProtection
import kotlinx.coroutines.runBlocking
import org.junit.After
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
 * PM-2.2b: the draft is a manifest over an immutable base. Rotate/delete/move never touch the
 * document (I7); Save as new and Replace publish through the sealed staging (I1/I4/I8/I10); a
 * Replace that dies between its two renames is rolled forward; a changed base page is stale.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DraftEngineTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)

    @After fun tearDown() = Faults.resetAll()

    private fun docDir(id: String) = File(repo.docsDir, id)
    private fun digest(id: String) = PdfInvariants.dirDigest(docDir(id))
    private fun draftDir(id: String) = File(app.filesDir, "drafts/$id")
    private fun staging() = File(app.filesDir, "op_staging").list().orEmpty().sorted()

    private fun newProcess(): ScanRepository {
        Faults.resetAll()
        return ScanRepository(app)
    }

    @Test
    fun `draft edits never change the base document`() = runBlocking {
        val id = DocFixtures.saved(repo, app, pages = 4)
        DocFixtures.withOcr(repo, id)
        val d0 = digest(id)

        repo.draftRotate(id, 0, true)
        repo.draftDelete(id, 1)
        repo.draftMove(id, 0, 2)
        repo.draftRotate(id, 1, false)

        assertEquals("the base changed before Save", d0, digest(id))
        assertEquals(3, repo.draftPages(id).size)
        assertEquals(4, repo.draftOf(id)!!.changes)
    }

    @Test
    fun `a draft survives process death with an equal manifest`() = runBlocking {
        val id = DocFixtures.saved(repo, app, pages = 3)
        repo.draftRotate(id, 0, true)

        val death = Faults.dieAtOp("draft:saved")
        runCatching { runBlocking { repo.draftRotate(id, 0, true) } }
        assertTrue("the second rotate must die at draft:saved", death.fired)

        val afterSavedDeath = newProcess()
        assertEquals(2, runBlocking { afterSavedDeath.draftOf(id) }!!.changes)

        val halfWritten = Faults.dieAtOp("draft:replacement_written")
        runCatching { runBlocking { afterSavedDeath.draftRotate(id, 0, true) } }
        assertTrue("the third rotate must die before the manifest", halfWritten.fired)

        val reloaded = newProcess()
        val draft = runBlocking { reloaded.draftOf(id) }!!
        assertEquals("the manifest still holds the last committed edit", 2, draft.changes)
        val referenced = draft.entries.flatMap {
            listOfNotNull(it.replacement, it.replacementOrig)
        }.toSet()
        val leftovers = draftDir(id).listFiles()
            .orEmpty().filter { it.name.startsWith("r_") && it.name !in referenced }
        assertTrue("unreferenced replacements must be pruned, found $leftovers", leftovers.isEmpty())
    }

    @Test
    fun `discard deletes only the draft`() = runBlocking {
        val a = DocFixtures.saved(repo, app, pages = 2)
        val b = DocFixtures.saved(repo, app, pages = 2)
        repo.draftRotate(a, 0, true)
        repo.draftRotate(b, 0, true)
        val digestA = digest(a)
        val digestB = digest(b)
        val draftB = PdfInvariants.dirDigest(draftDir(b))

        repo.discardDraft(a)

        assertFalse(File(app.filesDir, "drafts/$a").exists())
        assertEquals("discard must not touch the other draft", draftB, PdfInvariants.dirDigest(draftDir(b)))
        assertEquals("discard must not touch the base", digestA, digest(a))
        assertEquals(digestB, digest(b))
    }

    @Test
    fun `save as new builds the draft pages and keeps the base`() = runBlocking {
        val id = DocFixtures.saved(repo, app, pages = 4)
        DocFixtures.withOcr(repo, id)
        val base = repo.getDocument(id)!!
        val baseHeight = requireNotNull(ImageTools.imageSize(base.pageFiles[0].readBytes())).second
        val baseText = repo.ocrDoc(id)
        val d0 = digest(id)

        repo.draftRotate(id, 0, true)
        repo.draftDelete(id, 3)
        val copy = repo.saveDraftAsNew(id, null)

        assertEquals(app.getString(R.string.draft_edited_name, base.name), copy.name)
        assertEquals(3, copy.pageFiles.size)
        PdfInvariants.assertI1(repo, copy.id)
        PdfInvariants.assertI4(repo)
        PdfInvariants.assertI10(repo, copy.id)
        assertEquals(
            "the rotated page's width is the base page's height",
            baseHeight,
            requireNotNull(ImageTools.imageSize(copy.pageFiles[0].readBytes())).first
        )
        assertEquals(
            "unchanged pages carry their OCR text",
            baseText["page_001.jpg"],
            repo.ocrDoc(copy.id)["page_001.jpg"]
        )
        assertEquals("Save changed the base", d0, digest(id))
        assertFalse("the draft must be gone after Save as new", draftDir(id).exists())
    }

    @Test
    fun `replace moves the base to Trash as previous version and publishes the draft under the same id`() = runBlocking {
        val id = DocFixtures.saved(repo, app, pages = 4)
        DocFixtures.withOcr(repo, id)
        val before = digest(id)
        val oldMeta = File(docDir(id), "meta.txt").readBytes()

        repo.draftDelete(id, 0)
        val result = repo.replaceWithDraft(id)

        assertEquals(id, result.document.id)
        assertEquals(3, result.document.pageFiles.size)
        assertArrayEquals(oldMeta, File(docDir(id), "meta.txt").readBytes())
        val previous = repo.listTrash().filter { it.previousToken != null && it.originalId == id }
        assertEquals("exactly one previous version in Trash", 1, previous.size)
        val restored = repo.restoreFromTrash(result.trashKey)
        assertEquals("the previous version restores byte-identically", before, digest(restored.id))
        assertFalse("the draft must be gone after Replace", draftDir(id).exists())
        assertFalse(
            "no seal may stay in a published document",
            File(docDir(id), ".publish").exists()
        )
        PdfInvariants.assertI1(repo, id)
    }

    @Test
    fun `death between base to Trash and publish is rolled forward by recovery`() = runBlocking {
        // (a) Replace dies after the base moved to Trash.
        val id = DocFixtures.saved(repo, app, pages = 4)
        repo.draftDelete(id, 0)
        val death = Faults.dieAtOp("replace:base_trashed")
        runCatching { runBlocking { repo.replaceWithDraft(id) } }
        assertTrue("Replace must die after the base moved", death.fired)
        assertFalse(File(repo.docsDir, id).exists())

        val recovered = newProcess()
        assertEquals(1, recovered.recoverOperations())
        val after = requireNotNull(recovered.getDocument(id))
        assertEquals(4 - 1, after.pageFiles.size)
        PdfInvariants.assertI1(recovered, id)
        PdfInvariants.assertI4(recovered)
        assertEquals(1, recovered.listTrash().count { it.previousToken != null })
        assertFalse(
            "recovery must finish the draft cleanup",
            File(app.filesDir, "drafts/$id").exists()
        )
        assertTrue("op_staging must be empty, found ${staging()}", staging().isEmpty())
        val settled = digest(id)
        assertEquals("a second recovery does nothing", 0, recovered.recoverOperations())
        assertEquals(settled, digest(id))

        // (b) Save as new dies after sealing: the base is unchanged and the draft survives.
        val other = DocFixtures.saved(repo, app, pages = 2)
        repo.draftRotate(other, 0, true)
        val baseDigest = digest(other)
        val sealed = Faults.dieAtOp("op:sealed")
        runCatching { runBlocking { repo.saveDraftAsNew(other, null) } }
        assertTrue("Save as new must die after sealing", sealed.fired)
        assertEquals("a sealed death must not change the base", baseDigest, digest(other))

        val next = newProcess()
        next.recoverOperations()
        assertNotNull("the draft survives recovery", runBlocking { next.draftOf(other) })
        assertTrue("staging must be swept, found ${staging()}", staging().isEmpty())
        assertEquals(baseDigest, digest(other))
    }

    @Test
    fun `in-place page edits and protect are refused while a draft exists`() = runBlocking {
        val id = DocFixtures.saved(repo, app, pages = 3)
        val d0 = digest(id)
        repo.draftRotate(id, 0, true)

        assertTrue(
            "an in-place rotate must be refused",
            runCatching { runBlocking { repo.rotatePage(id, 1) } }
                .exceptionOrNull() is DraftStore.DraftOpen
        )
        assertTrue(
            "protect must be refused",
            runCatching { runBlocking { repo.protectPdf(id, "1234") } }
                .exceptionOrNull() is DraftStore.DraftOpen
        )
        assertEquals(d0, digest(id))

        repo.discardDraft(id)
        assertEquals(3, repo.rotatePage(id, 1).pageFiles.size)
    }

    @Test
    fun `a changed base page makes save as new and replace fail and keep the draft`() = runBlocking {
        val id = DocFixtures.saved(repo, app, pages = 4)
        repo.draftRotate(id, 0, true)
        // A legacy in-place change bypasses the lease.
        File(docDir(id), "page_002.jpg")
            .writeBytes(ImportFixtures.image(width = 210, height = 310))
        val library = repo.listDocuments().size

        assertTrue(
            "Save as new must report stale",
            runCatching { runBlocking { repo.saveDraftAsNew(id, null) } }
                .exceptionOrNull() is DraftStore.DraftStale
        )
        assertTrue(
            "Replace must report stale",
            runCatching { runBlocking { repo.replaceWithDraft(id) } }
                .exceptionOrNull() is DraftStore.DraftStale
        )
        assertTrue("the draft must survive", draftDir(id).isDirectory)
        assertTrue("no staging may be left, found ${staging()}", staging().isEmpty())
        assertEquals(library, repo.listDocuments().size)
    }

    @Test
    fun `replace keeps OCR text for unchanged pages and marks the rest pending`() = runBlocking {
        val id = DocFixtures.saved(repo, app, pages = 4)
        DocFixtures.withOcr(repo, id)
        val old = repo.ocrDoc(id)

        repo.draftRotate(id, 0, true)
        repo.replaceWithDraft(id)

        val now = repo.ocrDoc(id)
        assertEquals(setOf("page_001.jpg", "page_002.jpg", "page_003.jpg"), now.keys)
        assertEquals(old["page_001.jpg"], now["page_001.jpg"])
        assertTrue(
            "a replaced page has no text and must be re-queued",
            File(docDir(id), "ocr_pending").exists()
        )
    }

    @Test
    fun `recovery removes a draft whose base is permanently deleted and keeps one whose base is in Trash`() = runBlocking {
        val a = DocFixtures.saved(repo, app, pages = 2)
        val b = DocFixtures.saved(repo, app, pages = 2)
        repo.draftRotate(a, 0, true)
        repo.draftRotate(b, 0, true)

        repo.moveToTrash(a)
        val keyB = requireNotNull(repo.moveToTrash(b))
        repo.deleteFromTrash(keyB)

        repo.recoverOperations()

        assertTrue("a base in Trash keeps its draft", draftDir(a).isDirectory)
        assertFalse("a permanently deleted base loses its draft", draftDir(b).exists())
    }

    @Test
    fun `a locked document cannot start a draft`() = runBlocking {
        val id = DocFixtures.locked(repo, app)
        assertTrue(
            "a locked document must refuse a draft",
            runCatching { runBlocking { repo.draftRotate(id, 0) } }
                .exceptionOrNull() is PdfProtection.DocumentLocked
        )
        assertFalse(draftDir(id).exists())
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        org.junit.Assert.assertArrayEquals(expected, actual)
    }
}
