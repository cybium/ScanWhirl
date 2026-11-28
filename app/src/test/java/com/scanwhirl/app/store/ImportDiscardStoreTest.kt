package com.scanwhirl.app.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.util.Durable
import com.scanwhirl.app.util.Sha256
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ImportDiscardStoreTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val repo get() = ScanRepository(app)
    private var now = 1_000L
    private var boot = 7
    private var wall = 1_700_000_000_000L
    private fun store() = ImportDiscardStore(app, repo, { now }, { boot }, { wall })
    private fun digest(id: String) = PdfInvariants.dirDigest(File(repo.docsDir, id))

    @After fun reset() {
        ImportDiscardStore.fault = {}
        Durable.resetForTests()
    }

    @Test fun `escrow removes all active files and caches without entering Trash and Undo is exact`() {
        val id = DocFixtures.saved(repo, app, pages = 3, name = "काल्पनिक लंबा दस्तावेज़ - FAKE")
        val other = DocFixtures.saved(repo, app, pages = 1)
        repo.ocrStore.putPage(id, "page_000.jpg", "Fictional text", null)
        val search = OcrTextCache(repo)
        val searchable = search.normalized(id)
        assertTrue(searchable.isNotEmpty())
        val dir = File(repo.docsDir, id)
        File(dir, "nested").mkdirs()
        File(dir, "nested/thumb.jpg").writeBytes(byteArrayOf(7, 8, 9))
        File(dir, "nested/extra.bin").writeBytes(byteArrayOf(3, 2, 1))
        val before = digest(id)
        val doc = requireNotNull(repo.getDocument(id))
        repo.drafts.save(DraftStore.Draft(id, SaveFormat.pagesDigest(doc.pageFiles), 1, 1, 0, 0,
            doc.pageFiles.map { DraftStore.Entry(it.name, Sha256.of(it)) }))
        val draftDir = repo.drafts.folderOf(id)
        File(draftDir, "fixture.bin").writeText("Fictional draft companion")
        File(draftDir, "r_999.jpg").writeText("Fictional orphan must not be pruned during escrow")
        val draftBefore = PdfInvariants.dirDigest(draftDir)
        val unrelated = digest(other)
        val caches = listOf(File(app.cacheDir, "preview_$id.pdf"),
            File(app.cacheDir, "preview_$id.fp"), File(app.cacheDir, "documents/preview_$id.pdf"))
        caches.forEach { it.parentFile!!.mkdirs(); it.writeText("FAKE preview") }
        val token = store().discard(id, 8_000)
        assertNull(repo.getDocument(id))
        assertFalse(dir.exists())
        assertFalse("no active draft remains", draftDir.exists())
        assertTrue(repo.listTrash().isEmpty())
        assertTrue(caches.none { it.exists() })
        assertTrue("search cannot serve a discarded copy", search.normalized(id).isEmpty())
        assertEquals(listOf(token), store().pending())
        assertEquals(unrelated, digest(other))
        assertEquals(id, store().restore(token.key))
        assertEquals(before, digest(id))
        assertEquals("all zero-change draft bytes are restored", draftBefore, PdfInvariants.dirDigest(draftDir))
        assertEquals(searchable, search.normalized(id))
        assertEquals(3, requireNotNull(repo.getDocument(id)).pageCount)
        assertTrue(store().pending().isEmpty())
        assertNull("Undo is single use", store().restore(token.key))
        assertEquals(unrelated, digest(other))
    }

    @Test fun `expired escrow is fully deleted on next process startup`() {
        val id = DocFixtures.saved(repo, app, pages = 2)
        val token = store().discard(id, 8_000)
        now += 8_001
        assertTrue(store().recover().isEmpty())
        assertFalse(File(app.filesDir, "import_discard/${token.key}").exists())
        assertNull(repo.getDocument(id))
        assertTrue(repo.listTrash().isEmpty())
        assertNull(store().restore(token.key))
    }

    @Test fun `process death at each staging point restores a complete byte identical copy`() {
        for (stage in listOf(ImportDiscardStore.Stage.MANIFEST_WRITTEN,
            ImportDiscardStore.Stage.DOCUMENT_MOVED, ImportDiscardStore.Stage.DRAFT_MOVED)) {
            val id = DocFixtures.saved(repo, app, pages = 2)
            val before = digest(id)
            ImportDiscardStore.fault = { if (it == stage) throw SimulatedDeath() }
            try { store().discard(id, 8_000); fail("death was not injected: $stage") }
            catch (_: SimulatedDeath) {}
            ImportDiscardStore.fault = {}
            assertTrue(store().recover().isEmpty())
            assertEquals("$stage restored all bytes", before, digest(id))
        }
    }

    @Test fun `a post commit fault cannot revoke the durable token or its remaining window`() {
        val id = DocFixtures.saved(repo, app, pages = 2)
        val before = digest(id)
        // PENDING is already durable: a later fault is a success receipt, not a discard failure.
        val faultFired = java.util.concurrent.atomic.AtomicBoolean(false)
        ImportDiscardStore.fault = {
            if (it == ImportDiscardStore.Stage.COMMITTED) {
                faultFired.set(true)
                throw SimulatedDeath()
            }
        }
        val token = store().discard(id, 8_000)
        ImportDiscardStore.fault = {}
        assertTrue("the post-commit fault hook must still have fired", faultFired.get())
        now += 3_000
        assertEquals(listOf(token), store().recover())
        assertEquals(5_000L, store().remainingMs(token))
        assertEquals(id, store().restore(token.key))
        assertEquals(before, digest(id))
    }

    @Test fun `death during Undo finishes restoration rather than purging restored bytes`() {
        for (stage in listOf(ImportDiscardStore.Stage.RESTORE_STARTED,
            ImportDiscardStore.Stage.DOCUMENT_RESTORED, ImportDiscardStore.Stage.DRAFT_RESTORED)) {
            val id = DocFixtures.saved(repo, app, pages = 2)
            val before = digest(id)
            val token = store().discard(id, 8_000)
            ImportDiscardStore.fault = { if (it == stage) throw SimulatedDeath() }
            try { store().restore(token.key); fail("death not injected") } catch (_: SimulatedDeath) {}
            ImportDiscardStore.fault = {}
            now += 20_000
            store().recover()
            assertEquals("$stage must finish accepted Undo", before, digest(id))
        }
    }

    @Test fun `death during purge completes deletion on restart without touching another item`() {
        val id = DocFixtures.saved(repo, app, pages = 2)
        val other = DocFixtures.saved(repo, app, pages = 1)
        val before = digest(other)
        val token = store().discard(id, 8_000)
        now += 9_000
        ImportDiscardStore.fault = { if (it == ImportDiscardStore.Stage.PURGE_STARTED) throw SimulatedDeath() }
        try { store().recover(); fail("death not injected") } catch (_: SimulatedDeath) {}
        ImportDiscardStore.fault = {}
        store().recover()
        assertFalse(File(app.filesDir, "import_discard/${token.key}").exists())
        assertEquals(before, digest(other))
    }

    @Test fun `failed staging rolls back without a false discard receipt`() {
        val id = DocFixtures.saved(repo, app, pages = 2)
        val before = digest(id)
        ImportDiscardStore.fault = {
            if (it == ImportDiscardStore.Stage.DOCUMENT_MOVED) throw IOException("Fictional failure")
        }
        try { store().discard(id, 8_000); fail("failure not injected") } catch (_: IOException) {}
        ImportDiscardStore.fault = {}
        assertEquals(before, digest(id))
        assertTrue(store().pending().isEmpty())
    }

    @Test fun `a corrupt manifest restores while an unexpired reboot keeps the token`() {
        run {
            val id = DocFixtures.saved(repo, app, pages = 2)
            val before = digest(id)
            val token = store().discard(id, 8_000)
            File(app.filesDir, "import_discard/${token.key}/manifest").writeText("../../outside")
            assertTrue(store().recover().isEmpty())
            assertEquals(before, digest(id))
        }
        run {
            val id = DocFixtures.saved(repo, app, pages = 2)
            val before = digest(id)
            val token = store().discard(id, 8_000)
            boot++
            val rebooted: ImportDiscardStore.Token = store().recover().singleOrNull()
                ?: throw AssertionError("an unexpired escrow must stay restorable across reboot")
            assertEquals(token.key, rebooted.key)
            assertEquals(id, store().restore(token.key))
            assertEquals(before, digest(id))
        }
    }

    @Test fun `untrusted ids and transaction tokens cannot target source or unrelated files`() {
        val external = File(app.cacheDir, "fictional-source.pdf").apply { writeText("read-only source") }
        val before = external.readBytes()
        assertNull(store().restore("../../cache/fictional-source.pdf"))
        try { store().discard("../../cache/fictional-source.pdf", 8_000); fail("unsafe id accepted") }
        catch (_: IllegalArgumentException) {}
        assertArrayEquals(before, external.readBytes())
    }

    @Test fun `edited imports are refused by the storage boundary`() = runBlocking {
        val id = DocFixtures.saved(repo, app, pages = 2)
        repo.draftDelete(id, 0)
        val before = digest(id)
        try { store().discard(id, 8_000); fail("edited import was discarded") }
        catch (_: IllegalStateException) {}
        assertEquals(before, digest(id))
        assertEquals(1, repo.draftPages(id).size)
    }

    @Test fun `imports with saved export records are refused by the storage boundary`() {
        val id = DocFixtures.saved(repo, app, pages = 2)
        repo.addExport(id, android.net.Uri.parse("content://fictional-exports/kept.pdf"))
        val before = digest(id)
        try { store().discard(id, 8_000); fail("a kept import was discarded") }
        catch (_: IllegalStateException) {}
        assertEquals(before, digest(id))
        assertTrue(store().pending().isEmpty())
    }

    @Test fun `a manifest fsync failure leaves the complete active copy and no Undo token`() {
        val id = DocFixtures.saved(repo, app, pages = 2)
        val before = digest(id)
        Durable.beforeFileSync = {
            if (it.name == "manifest.tmp") throw IOException("Fictional ENOSPC")
        }
        try { store().discard(id, 8_000); fail("write should have failed") }
        catch (_: IOException) {}
        Durable.resetForTests()
        assertEquals(before, digest(id))
        assertTrue(store().pending().isEmpty())
    }

    @Test fun `Undo racing expiry has only a complete restore or a complete discard outcome`() {
        val id = DocFixtures.saved(repo, app, pages = 2)
        val before = digest(id)
        val token = store().discard(id, 8_000)
        val start = java.util.concurrent.CountDownLatch(1)
        val error = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val undo = Thread {
            try { start.await(); store().restore(token.key) } catch (t: Throwable) { error.set(t) }
        }
        val expire = Thread {
            try {
                start.await()
                repo.withImportDiscardLock { now += 9_000; store().recover() }
            } catch (t: Throwable) { error.set(t) }
        }
        undo.start()
        expire.start()
        start.countDown()
        undo.join()
        expire.join()
        assertNull("both sides of the race must complete honestly", error.get())
        store().recover()
        if (repo.getDocument(id) != null) assertEquals(before, digest(id))
        assertFalse(File(app.filesDir, "import_discard/${token.key}").exists())
        assertTrue(repo.listTrash().isEmpty())
    }

    @Test fun `restored import is invisible until marker cleanup and preserves exact legacy metadata bytes`() {
        val id = DocFixtures.saved(repo, app, pages = 2)
        val dir = File(repo.docsDir, id)
        val meta = File(dir, "meta.txt")
        // A no-conflict restore must not normalize even the line endings.
        meta.writeText(meta.readLines().joinToString("\r\n", postfix = "\r\n"))
        val before = digest(id)
        val token = store().discard(id, 8_000)
        var checked = 0
        ImportDiscardStore.fault = { stage ->
            if (stage == ImportDiscardStore.Stage.DOCUMENT_RESTORED ||
                stage == ImportDiscardStore.Stage.DRAFT_RESTORED) {
                assertTrue("the directory has moved but is not yet published", dir.isDirectory)
                assertTrue(File(dir, ImportDiscardStore.MARKER).isFile)
                assertNull("getDocument must not reveal a partial Undo", repo.getDocument(id))
                assertFalse("listing must not reveal a partial Undo", repo.listDocuments().any { it.id == id })
                assertFalse("OCR queue must not claim an unpublished import", repo.ocrStore.pendingQueue().contains(id))
                checked++
            }
        }
        assertEquals(id, store().restore(token.key))
        assertEquals(2, checked)
        assertNotNull(repo.getDocument(id))
        assertEquals(before, digest(id))
    }

    private class SimulatedDeath : Error()
}
