package com.scanwhirl.app.store

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import com.scanwhirl.app.util.CategoryRules
import com.scanwhirl.app.util.Durable
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * M07 P1: Move to Trash → Restore → explicit permanent delete.
 *
 * A trashed document keeps its complete identity (every file byte-identical:
 * pages, `.orig`, scan.pdf, meta with category and export records, OCR text and
 * geometry, flags), leaves every library view, and comes back whole under the same
 * id, also after a restart and across repeated cycles. Interruptions at every step
 * leave the document fully in one place, never split or duplicated.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class TrashTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
    }

    @After
    fun tearDown() {
        ScanRepository.resetTestSeams()
        Durable.resetForTests()
    }

    @Test
    fun `trash and restore keep every file byte-identical and the same id`() {
        val id = richDoc("Passport renewal")
        val before = snapshot(File(repo.docsDir, id))

        val key = repo.moveToTrash(id)!!
        assertNull("gone from the library", repo.getDocument(id))
        assertTrue(repo.listDocuments().none { it.id == id })
        assertTrue(repo.listDocuments(includeVaulted = true).none { it.id == id })
        assertFalse("no library folder left behind", File(repo.docsDir, id).exists())
        val item = repo.listTrash().single()
        assertEquals(id, item.originalId)
        assertEquals("Passport renewal", item.document.name)
        assertEquals(3, item.document.pageCount)

        val restored = repo.restoreFromTrash(key)
        assertEquals("same id: page links and records still resolve", id, restored.id)
        assertEquals(before, snapshot(File(repo.docsDir, id)))
        assertTrue(repo.listTrash().isEmpty())
        assertEquals(CategoryRules.RECEIPTS, repo.getDocument(id)!!.category)
        assertEquals("page 2 text", repo.ocrDoc(id)["page_001.jpg"])
        assertEquals(1, repo.ocrStore.readGeo(id).size)
    }

    @Test
    fun `repeated cycles and a restart never lose or duplicate anything`() {
        val id = richDoc("Lease")
        val before = snapshot(File(repo.docsDir, id))
        repeat(5) {
            val key = repo.moveToTrash(id)!!
            // A "restart": a brand-new repository instance reads Trash from disk.
            val fresh = ScanRepository(app)
            assertEquals(1, fresh.listTrash().size)
            assertEquals(0, fresh.listDocuments().size)
            fresh.recoverTrash()
            assertEquals("recovery never touches a visible Trash entry", 1, fresh.listTrash().size)
            fresh.restoreFromTrash(key)
        }
        assertEquals(before, snapshot(File(repo.docsDir, id)))
        assertEquals(1, repo.listDocuments().size)
        assertTrue(repo.listTrash().isEmpty())
    }

    @Test
    fun `a trashed document is invisible to OCR resume and its pending marker survives`() {
        val id = richDoc("Pending")
        repo.ocrStore.markPending(id)
        repo.moveToTrash(id)
        val pendingInLibrary = repo.docsDir.listFiles()!!.filter { File(it, "ocr_pending").exists() }
        assertTrue("the indexer only scans the library", pendingInLibrary.isEmpty())
        repo.restoreFromTrash(repo.listTrash().single().key)
        assertTrue("still waiting for recognition after restore", repo.ocrStore.isPending(id))
    }

    @Test
    fun `restore never overwrites an item that took the id meanwhile`() {
        val id = richDoc("Original")
        val key = repo.moveToTrash(id)!!
        // Something now lives at the old id (non-empty).
        val squatter = File(repo.docsDir, id).apply { mkdirs() }
        File(squatter, "meta.txt").writeText("Squatter\n1\nOther\n")
        File(squatter, "page_000.jpg").writeBytes(jpeg())
        val squatterBefore = snapshot(squatter)

        val restored = repo.restoreFromTrash(key)
        assertNotEquals("restored under a new id", id, restored.id)
        assertEquals("Original", restored.name)
        assertEquals("the existing item is untouched", squatterBefore, snapshot(squatter))
        assertEquals(2, repo.listDocuments().size)
        assertEquals(2, repo.listDocuments().map { it.id }.toSet().size)
    }

    @Test
    fun `two trash entries from the same id stay separate`() {
        val id = richDoc("First")
        val k1 = repo.moveToTrash(id)!!
        val again = File(repo.docsDir, id).apply { mkdirs() }
        File(again, "meta.txt").writeText("Second\n1\nOther\n")
        File(again, "page_000.jpg").writeBytes(jpeg())
        val k2 = repo.moveToTrash(id)!!
        assertNotEquals(k1, k2)
        assertEquals(setOf("First", "Second"), repo.listTrash().map { it.document.name }.toSet())
    }

    @Test
    fun `crash right after the move leaves a complete restorable Trash entry`() {
        val id = richDoc("Crash")
        val before = snapshot(File(repo.docsDir, id))
        ScanRepository.trashFault = { if (it == ScanRepository.Companion.TrashStage.MOVED) throw IOException("process died") }
        try {
            repo.moveToTrash(id)
            fail("fault expected")
        } catch (_: IOException) {
        }
        ScanRepository.resetTestSeams()
        val fresh = ScanRepository(app)
        fresh.recoverTrash()
        assertNull(fresh.getDocument(id))
        val item = fresh.listTrash().single()
        assertEquals("id recovered without the marker", id, item.originalId)
        fresh.restoreFromTrash(item.key)
        assertEquals(before, snapshot(File(repo.docsDir, id)))
    }

    @Test
    fun `crash before a move or a restore changes nothing`() {
        val id = richDoc("Stay")
        ScanRepository.trashFault = { if (it == ScanRepository.Companion.TrashStage.BEFORE_MOVE) throw IOException("died") }
        runCatching { repo.moveToTrash(id) }
        assertNotNull("still in the library", repo.getDocument(id))
        assertTrue(repo.listTrash().isEmpty())

        ScanRepository.resetTestSeams()
        val key = repo.moveToTrash(id)!!
        ScanRepository.trashFault = { if (it == ScanRepository.Companion.TrashStage.BEFORE_RESTORE) throw IOException("died") }
        runCatching { repo.restoreFromTrash(key) }
        assertNull(repo.getDocument(id))
        assertEquals("still whole in Trash", 1, repo.listTrash().size)
    }

    @Test
    fun `crash just after moving an old document does not make it expire`() {
        val id = richDoc("Old but just deleted")
        val live = File(repo.docsDir, id)
        val original = snapshot(live)
        val beforeMove = System.currentTimeMillis()
        assertTrue(live.setLastModified(beforeMove - 2 * ScanRepository.TRASH_RETENTION_MS))
        ScanRepository.trashFault = {
            if (it == ScanRepository.Companion.TrashStage.MOVED) throw IOException("process died")
        }
        assertTrue(runCatching { repo.moveToTrash(id) }.exceptionOrNull() is IOException)
        ScanRepository.resetTestSeams()

        val fresh = ScanRepository(app)
        assertEquals(0, fresh.recoverTrash())
        val item = fresh.listTrash().single()
        assertTrue("the restore window starts at the move", item.trashedAt >= beforeMove)
        fresh.restoreFromTrash(item.key)
        assertEquals(original, snapshot(live))
    }

    @Test
    fun `a legacy entry with no trustworthy marker starts its restore window at first sight`() {
        val now = System.currentTimeMillis()
        val dirs = listOf("Missing", "Corrupt").map { name ->
            val id = richDoc(name)
            File(repo.trashDir, repo.moveToTrash(id)!!).apply {
                val marker = File(this, ScanRepository.TRASH_MARKER)
                if (name == "Missing") {
                    assertTrue(marker.delete())
                } else {
                    marker.writeText("invalid\n$id\n${now - 2 * ScanRepository.TRASH_RETENTION_MS}\n")
                }
                assertTrue(setLastModified(now - 2 * ScanRepository.TRASH_RETENTION_MS))
            }
        }

        assertEquals(0, repo.recoverTrash(now))
        assertTrue(dirs.all { File(it, ScanRepository.TRASH_MARKER).isFile })
        val firstSeen = ScanRepository(app).listTrash().map { it.trashedAt }
        assertEquals(listOf(now, now), firstSeen)
        assertEquals(0, repo.recoverTrash(now + ScanRepository.TRASH_RETENTION_MS - 1))
        assertEquals(2, repo.recoverTrash(now + ScanRepository.TRASH_RETENTION_MS))
    }

    @Test
    fun `a legacy entry whose marker cannot be written never expires`() {
        val id = richDoc("Cannot record the date")
        val entry = File(repo.trashDir, repo.moveToTrash(id)!!)
        assertTrue(File(entry, ScanRepository.TRASH_MARKER).delete())
        assertTrue(entry.setLastModified(1L))
        Durable.beforeFileSync = {
            if (it.name == "${ScanRepository.TRASH_MARKER}.tmp") throw IOException("disk full")
        }

        assertEquals(0, repo.recoverTrash())
        assertEquals(0, repo.recoverTrash(System.currentTimeMillis() + 2 * ScanRepository.TRASH_RETENTION_MS))
        assertEquals(id, repo.listTrash().single().originalId)
    }

    @Test
    fun `crash after restore move leaves no stale marker after recovery`() {
        val id = richDoc("Marker")
        val key = repo.moveToTrash(id)!!
        ScanRepository.trashFault = { if (it == ScanRepository.Companion.TrashStage.RESTORED) throw IOException("died") }
        runCatching { repo.restoreFromTrash(key) }
        ScanRepository.resetTestSeams()
        assertNotNull("the document is back", repo.getDocument(id))
        // The marker may be left behind by the crash; recovery removes it.
        repo.recoverTrash()
        assertFalse(File(File(repo.docsDir, id), ScanRepository.TRASH_MARKER).exists())
        assertTrue(repo.listTrash().isEmpty())
    }

    @Test
    fun `an interrupted permanent delete is never shown and is finished on restart`() {
        val id = richDoc("Purge")
        val key = repo.moveToTrash(id)!!
        ScanRepository.trashFault = { if (it == ScanRepository.Companion.TrashStage.PURGE_HIDDEN) throw IOException("died") }
        runCatching { repo.deleteFromTrash(key) }
        ScanRepository.resetTestSeams()
        assertTrue("a half-deleted item never appears", repo.listTrash().isEmpty())
        assertNull(repo.getDocument(id))
        assertEquals(1, repo.recoverTrash())
        assertTrue(repo.trashDir.listFiles()!!.isEmpty())
    }

    @Test
    fun `low space for the Trash marker still moves the document whole`() {
        val id = richDoc("Full disk")
        val before = snapshot(File(repo.docsDir, id))
        Durable.beforeFileSync = { f ->
            if (f.name.startsWith(ScanRepository.TRASH_MARKER)) throw IOException("ENOSPC (No space left on device)")
        }
        val key = repo.moveToTrash(id)
        assertNotNull(key)
        Durable.resetForTests()
        assertEquals(1, repo.listTrash().size)
        repo.restoreFromTrash(key!!)
        assertEquals(before, snapshot(File(repo.docsDir, id)))
    }

    @Test
    fun `legacy Vault items never enter Trash`() {
        val id = richDoc("Private")
        repo.setVaulted(id, true)
        try {
            repo.moveToTrash(id)
            fail("vault items must be refused")
        } catch (_: ScanRepository.VaultItemNotTrashable) {
        }
        assertNotNull(repo.getDocument(id))
        assertTrue(repo.listTrash().isEmpty())
        assertEquals(1, repo.listVaultDocuments().size)
    }

    @Test
    fun `a vault-flagged folder in Trash is never listed`() {
        val id = richDoc("Hidden")
        repo.moveToTrash(id)
        File(repo.trashDir.listFiles()!!.single(), "vault.flag").writeText("1")
        assertTrue(repo.listTrash().isEmpty())
        assertEquals(0, repo.trashCount())
    }

    @Test
    fun `unlocked previews do not outlive a trashed document`() {
        val id = richDoc("Locked preview")
        val preview = File(app.cacheDir, "preview_$id.pdf").apply { writeText("plain") }
        val preview2 = File(File(app.cacheDir, "documents").apply { mkdirs() }, "preview_$id.pdf").apply { writeText("plain") }
        repo.moveToTrash(id)
        assertFalse(preview.exists())
        assertFalse(preview2.exists())
    }

    @Test
    fun `permanent delete removes only that entry`() {
        val a = richDoc("A")
        val b = richDoc("B")
        val ka = repo.moveToTrash(a)!!
        repo.moveToTrash(b)
        repo.deleteFromTrash(ka)
        assertEquals(listOf("B"), repo.listTrash().map { it.document.name })
        assertTrue(repo.trashDir.listFiles()!!.none { it.name.startsWith(ScanRepository.PURGE_PREFIX) })
    }

    @Test
    fun `a Trash entry expires after the 30-day restore window`() {
        val id = richDoc("Old receipt")
        val key = repo.moveToTrash(id)!!
        val entry = File(repo.trashDir, key)
        val inside = System.currentTimeMillis() + ScanRepository.TRASH_RETENTION_MS - 60_000

        assertEquals("still restorable a minute before the window ends", 0, repo.recoverTrash(now = inside))
        assertTrue(entry.exists())

        assertEquals(1, repo.recoverTrash(now = inside + 120_000))
        assertFalse("the entry is gone for good", entry.exists())
        assertTrue(repo.listTrash().isEmpty())
        assertFalse("its unlocked preview does not outlive it", File(app.cacheDir, "preview_$id.pdf").exists())
    }

    @Test
    fun `an entry an interrupted Replace moved to Trash is never expired`() {
        val id = richDoc("Replace target")
        val key = repo.moveToTrash(id)!!
        File(File(repo.trashDir, key), ScanRepository.TRASH_MARKER).appendText("previous=tok123\n")

        val later = System.currentTimeMillis() + 2 * ScanRepository.TRASH_RETENTION_MS
        assertEquals("a recovery entry is not the user's deletion", 0, repo.recoverTrash(now = later))
        assertTrue(File(repo.trashDir, key).exists())
    }

    @Test
    fun `default names count Trash so a restore never duplicates one`() {
        val id = runBlocking { repo.saveScan(listOf(img()), null, null).id }
        assertEquals("Document 1", repo.getDocument(id)!!.name)
        repo.moveToTrash(id)
        assertEquals("Document 2", repo.nextDocumentName())
    }

    // ------------------------------------------------------------------

    /** A 3-page document with a filtered page's `.orig`, OCR text + geometry, category and an export record. */
    private fun richDoc(name: String): String {
        val id = runBlocking { repo.saveScan(listOf(img(), img(), img()), null, name).id }
        val dir = File(repo.docsDir, id)
        File(dir, "page_001.orig").writeBytes(jpeg(7))
        repo.setCategory(id, CategoryRules.RECEIPTS)
        repo.ocrStore.putTexts(id, mapOf("page_000.jpg" to "page 1 text", "page_001.jpg" to "page 2 text"))
        repo.ocrStore.putPage(id, "page_002.jpg", "page 3 text", OcrPage(100, 100, listOf(OcrLine("page 3 text", 0, 0, 10, 10))))
        repo.addExport(id, android.net.Uri.parse("content://media/external/file/77"))
        File(dir, "text_layer.flag").writeText("")
        return id
    }

    private fun snapshot(dir: File): Map<String, String> =
        dir.walkTopDown().filter { it.isFile }.associate { f ->
            f.relativeTo(dir).path to MessageDigest.getInstance("SHA-256").digest(f.readBytes())
                .joinToString("") { "%02x".format(it) }
        }

    private fun img(): android.net.Uri {
        val uri = android.net.Uri.parse("content://test/trash_${System.nanoTime()}.jpg")
        shadowOf(app.contentResolver).registerInputStream(uri, jpeg().inputStream())
        return uri
    }

    private fun jpeg(shade: Int = 0): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(80, 100, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bmp).drawColor(android.graphics.Color.rgb(250 - shade, 250, 250))
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
        bmp.recycle()
        return out.toByteArray()
    }
}
