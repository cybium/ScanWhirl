package com.scanwhirl.app.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.util.ImageTools
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class J5DurableUndoTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test fun `rotate undo survives process death without reusing replacement names`() = runBlocking {
        val repo = ScanRepository(app)
        val id = DocFixtures.saved(repo, app, pages = 3)
        val doc = repo.getDocument(id)!!
        val store = DraftStore(app.filesDir)
        val original = doc.pageFiles.first().readBytes()
        val before = store.prepareOrganize(doc, setOf(doc.pageFiles.first().path))
        repo.draftRotate(id, 0, true)
        store.finishOrganizeBatch(doc, before)
        val rotated = repo.draftPages(id).first()
        assertEquals(ImageTools.imageSize(original)!!.let { it.second to it.first },
            ImageTools.imageSize(rotated.readBytes()))

        val reloaded = DraftStore(app.filesDir)
        assertEquals(before.entries.first().pageId, reloaded.load(id)!!.entries.first().pageId)
        val restored = reloaded.undoOrganize(doc)!!
        assertArrayEquals(original, repo.draftPages(id).first().readBytes())
        assertEquals(before.selection, restored.selection)
        assertEquals(0, restored.changes)

        repo.draftRotate(id, 0, true)
        assertNotEquals("Undo cannot rewind the immutable replacement counter",
            rotated.path, repo.draftPages(id).first().path)
    }

    @Test fun `delete undo restores the same page bytes identity and selection`() = runBlocking {
        val repo = ScanRepository(app)
        val id = DocFixtures.saved(repo, app, pages = 3)
        val doc = repo.getDocument(id)!!
        val store = DraftStore(app.filesDir)
        val pages = doc.pageFiles
        val bytes = pages[1].readBytes()
        store.organizeOrder(doc, pages, listOf(pages[0], pages[2]), emptyList(),
            setOf(pages[1].path))
        assertEquals(2, repo.draftPages(id).size)
        val restored = DraftStore(app.filesDir).undoOrganize(doc)!!
        assertEquals(pages, repo.draftPages(id))
        assertArrayEquals(bytes, repo.draftPages(id)[1].readBytes())
        assertEquals(setOf(restored.entries[1].pageId), restored.selection)
        assertTrue(restored.history.isEmpty())
    }

    @Test fun `an older manifest cannot overwrite a newer page edit`() = runBlocking {
        val repo = ScanRepository(app)
        val id = DocFixtures.saved(repo, app, pages = 3)
        val doc = repo.getDocument(id)!!
        val store = DraftStore(app.filesDir)
        val old = store.prepareOrganize(doc, emptySet())
        val order = doc.pageFiles.reversed()
        store.organizeOrder(doc, doc.pageFiles, order, listOf(order.first()), emptySet())
        try {
            store.save(old.copy(changes = old.changes + 1))
            fail("stale manifests must fail closed")
        } catch (_: DraftStore.DraftStale) {
            assertEquals(order, repo.draftPages(id))
        }
    }
}
