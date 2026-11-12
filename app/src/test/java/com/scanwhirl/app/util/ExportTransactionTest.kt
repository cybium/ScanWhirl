package com.scanwhirl.app.util

import android.app.Application
import android.content.pm.ProviderInfo
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.DeleteExports
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * M06 P0: the Android 10+ MediaStore export transaction under failure.
 *
 * Every test starts from an earlier export ("old bytes") where that matters and
 * asserts, after a failpoint fires anywhere in a later save, that:
 *  - the earlier export's bytes and checksum are unchanged,
 *  - no new *visible* row exists (no partial file in Files),
 *  - nothing pending is left behind when cleanup works,
 *  - the failure reaches the caller, so success is never reported.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ExportTransactionTest {

    private lateinit var app: Application
    private lateinit var media: FakeMediaStore
    private lateinit var repo: ScanRepository

    /** Bigger than one 64 KiB chunk, so a WRITING fault really lands mid-file. */
    private val oldBytes = ByteArray(200_000) { (it % 251).toByte() }
    private val newBytes = ByteArray(180_000) { (it % 13 + 7).toByte() }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        media = FakeMediaStore().also { provider ->
            provider.attachInfo(app, ProviderInfo().apply { authority = "media"; exported = true })
            ShadowContentResolver.registerProviderInternal("media", provider)
        }
    }

    @After
    fun tearDown() {
        DownloadSaver.resetForTests()
        media.close()
    }

    // ------------------------------------------------------------------
    // Happy paths: first save, identical re-save, changed re-save
    // ------------------------------------------------------------------

    @Test
    fun `first save publishes one complete verified row`() {
        val saved = DownloadSaver.savePdfFile(app, oldBytes, "Bill.pdf")
        assertTrue(saved.created)
        assertFalse(saved.keptEarlier)
        assertEquals("Bill.pdf", saved.name)
        val row = media.rowNamed("Bill.pdf")
        assertArrayEquals(oldBytes, row.bytes())
        assertFalse("published", row.pending)
        assertEquals(DownloadSaver.Fingerprint(oldBytes.size.toLong(), sha(oldBytes)), saved.fingerprint)
        assertTrue("no pending leftovers", media.pendingRows().isEmpty())
    }

    @Test
    fun `identical re-save writes nothing and reports already saved`() {
        val first = DownloadSaver.savePdfFile(app, oldBytes, "Bill.pdf")
        val writes = media.totalWrites()
        val second = DownloadSaver.savePdfFile(app, oldBytes, "Bill.pdf")
        assertEquals(DownloadSaver.Outcome.ALREADY_SAVED, second.outcome)
        assertFalse("nothing created → nothing to undo", second.created)
        assertEquals(first.uri, second.uri)
        assertEquals("no second write", writes, media.totalWrites())
        assertEquals(1, media.scanwhirlPdfs().size)
    }

    @Test
    fun `changed re-save creates a new copy and never touches the earlier file`() {
        val first = DownloadSaver.savePdfFile(app, oldBytes, "Bill.pdf")
        val second = DownloadSaver.savePdfFile(app, newBytes, "Bill.pdf")
        assertTrue(second.created)
        assertTrue("UI must say the earlier file was kept", second.keptEarlier)
        assertEquals("Bill (2).pdf", second.name)
        assertNotEquals(first.uri, second.uri)
        assertArrayEquals("earlier export untouched", oldBytes, media.rowNamed("Bill.pdf").bytes())
        assertEquals("earlier row written exactly once (its own save)", 1, media.rowNamed("Bill.pdf").writes)
        assertArrayEquals(newBytes, media.rowNamed("Bill (2).pdf").bytes())
    }

    @Test
    fun `a foreign file with the name is never written and the copy gets its real name`() {
        // An invisible foreign row the app cannot list (real MediaStore hides it):
        // the provider renames our insert. The result must report the real name.
        media.addRow("Bill.pdf", oldBytes, owner = "com.other.app", pending = true)
        val saved = DownloadSaver.savePdfFile(app, newBytes, "Bill.pdf")
        assertEquals("Bill (1).pdf", saved.name)
        assertArrayEquals(oldBytes, media.rows.values.first { it.owner == "com.other.app" }.bytes())
    }

    // ------------------------------------------------------------------
    // Failpoints: the earlier copy survives every one of them
    // ------------------------------------------------------------------

    private fun assertOldSurvives(label: String) {
        val visible = media.scanwhirlPdfs()
        assertEquals("$label: only the earlier export is visible — got ${visible.map { it.name }}", 1, visible.size)
        val row = visible.single()
        assertEquals("Bill.pdf", row.name)
        assertEquals("$label: old checksum", sha(oldBytes), sha(row.bytes()))
        assertEquals("$label: old row never re-opened for writing", 1, row.writes)
    }

    private fun failingSave(label: String, stage: DownloadSaver.Stage? = null, setup: () -> Unit = {}) {
        DownloadSaver.savePdfFile(app, oldBytes, "Bill.pdf")
        setup()
        if (stage != null) {
            DownloadSaver.faultAt = { if (it == stage) throw IOException("injected $stage") }
        }
        try {
            DownloadSaver.savePdfFile(app, newBytes, "Bill.pdf")
            fail("$label: a failed save must not report success")
        } catch (_: Exception) {
        }
        DownloadSaver.resetForTests()
        media.failInsert = false; media.failOpenWrite = false
        media.failUpdate = false; media.updateReturnsZero = false
        assertOldSurvives(label)
        assertTrue("$label: staging row cleaned up", media.pendingRows().isEmpty())
    }

    @Test
    fun `fault right after the staging row exists`() = failingSave("staged", DownloadSaver.Stage.STAGED)

    @Test
    fun `fault mid-write`() = failingSave("mid-write", DownloadSaver.Stage.WRITING)

    @Test
    fun `fault on stream close`() = failingSave("close", DownloadSaver.Stage.CLOSING)

    @Test
    fun `fault just before publishing`() = failingSave("publish", DownloadSaver.Stage.PUBLISHING)

    @Test
    fun `provider refuses the insert`() = failingSave("insert") { media.failInsert = true }

    @Test
    fun `provider refuses open for write`() = failingSave("open") { media.failOpenWrite = true }

    @Test
    fun `provider publish update throws`() = failingSave("update-throws") { media.failUpdate = true }

    @Test
    fun `provider publish update changes nothing`() = failingSave("update-0") { media.updateReturnsZero = true }

    @Test
    fun `source that changes during the save is rejected`() {
        DownloadSaver.savePdfFile(app, oldBytes, "Bill.pdf")
        val src = File(app.cacheDir, "growing.pdf").apply { writeBytes(newBytes) }
        var hits = 0
        DownloadSaver.faultAt = {
            if (it == DownloadSaver.Stage.STAGED && hits++ == 0) src.appendBytes(byteArrayOf(1, 2, 3))
        }
        try {
            DownloadSaver.savePdfFile(app, src, "Bill.pdf")
            fail("a source that changed mid-save must not be published")
        } catch (e: DownloadSaver.ExportException) {
            assertTrue(e.message!!.contains("changed"))
        }
        assertOldSurvives("source changed")
        assertTrue(media.pendingRows().isEmpty())
    }

    @Test
    fun `cleanup failure leaves only a hidden pending row, never a visible partial`() {
        DownloadSaver.savePdfFile(app, oldBytes, "Bill.pdf")
        DownloadSaver.faultAt = {
            if (it == DownloadSaver.Stage.WRITING) throw IOException("injected write")
            if (it == DownloadSaver.Stage.DISCARDING) throw IOException("injected cleanup")
        }
        val error = try {
            DownloadSaver.savePdfFile(app, newBytes, "Bill.pdf"); null
        } catch (e: IOException) {
            e
        }
        assertTrue("original write error reaches the caller", error?.message == "injected write")
        assertTrue("cleanup failure kept as suppressed", error!!.suppressed.any { it.message == "injected cleanup" })
        DownloadSaver.resetForTests()
        assertOldSurvives("cleanup failed")
        assertEquals("the orphan is pending (hidden in Files)", 1, media.pendingRows().size)
        // "Restart": a later save still works and ignores the orphan.
        val later = DownloadSaver.savePdfFile(app, newBytes, "Bill.pdf")
        assertTrue(later.created)
        // The orphan still holds "Bill (2).pdf", so the provider renames the new
        // copy; the result reports the name the file really has.
        assertTrue("got ${later.name}", later.name.startsWith("Bill (2)"))
        assertArrayEquals(newBytes, media.rows.values.single { it.name == later.name }.bytes())
        assertArrayEquals(oldBytes, media.rowNamed("Bill.pdf").bytes())
    }

    @Test
    fun `provider delete failing during cleanup is suppressed not thrown instead`() {
        DownloadSaver.savePdfFile(app, oldBytes, "Bill.pdf")
        media.failOpenWrite = true
        media.failDelete = true
        try {
            DownloadSaver.savePdfFile(app, newBytes, "Bill.pdf")
            fail("must fail")
        } catch (e: Exception) {
            assertTrue("the real cause, not the cleanup error", e is java.io.FileNotFoundException)
        }
        media.failOpenWrite = false; media.failDelete = false
        assertOldSurvives("delete failed")
    }

    @Test
    fun `test-build device trigger fails one save mid-write and is consumed`() {
        assertTrue("trigger exists only in test builds", com.scanwhirl.app.BuildConfig.TEST_BUILD)
        DownloadSaver.savePdfFile(app, oldBytes, "Bill.pdf")
        val trigger = File(app.getExternalFilesDir(null), DownloadSaver.DEVICE_FAULT_FILE)
        trigger.writeText("writing\n")
        try {
            DownloadSaver.savePdfFile(app, newBytes, "Bill.pdf")
            fail("armed trigger must fail the save")
        } catch (e: DownloadSaver.ExportException) {
            assertTrue(e.message!!.contains("WRITING"))
        }
        assertFalse("one shot: the trigger is consumed", trigger.exists())
        assertOldSurvives("device trigger")
        assertTrue(media.pendingRows().isEmpty())
        assertEquals("next save works", "Bill (2).pdf", DownloadSaver.savePdfFile(app, newBytes, "Bill.pdf").name)
    }

    // ------------------------------------------------------------------
    // Undo
    // ------------------------------------------------------------------

    @Test
    fun `undo of a first save removes only that new copy`() {
        val saved = DownloadSaver.savePdfFile(app, oldBytes, "Bill.pdf")
        assertEquals(DownloadSaver.UndoResult.REMOVED, DownloadSaver.undoSave(app, saved))
        assertTrue(media.scanwhirlPdfs().isEmpty())
    }

    @Test
    fun `undo of an identical re-save never deletes the earlier export`() {
        DownloadSaver.savePdfFile(app, oldBytes, "Bill.pdf")
        val again = DownloadSaver.savePdfFile(app, oldBytes, "Bill.pdf")
        assertEquals(DownloadSaver.UndoResult.NOTHING_CREATED, DownloadSaver.undoSave(app, again))
        assertOldSurvives("undo of already-saved")
    }

    @Test
    fun `undo of a changed re-save removes the new copy and keeps the earlier one`() {
        DownloadSaver.savePdfFile(app, oldBytes, "Bill.pdf")
        val second = DownloadSaver.savePdfFile(app, newBytes, "Bill.pdf")
        assertEquals(DownloadSaver.UndoResult.REMOVED, DownloadSaver.undoSave(app, second))
        assertOldSurvives("undo of new copy")
    }

    @Test
    fun `undo keeps a copy that was edited after saving`() {
        val saved = DownloadSaver.savePdfFile(app, oldBytes, "Bill.pdf")
        media.rowNamed("Bill.pdf").file.appendBytes(byteArrayOf(9)) // annotated elsewhere
        assertEquals(DownloadSaver.UndoResult.KEPT_CHANGED, DownloadSaver.undoSave(app, saved))
        assertEquals(1, media.scanwhirlPdfs().size)
    }

    @Test
    fun `undo of an already removed copy reports removed`() {
        val saved = DownloadSaver.savePdfFile(app, oldBytes, "Bill.pdf")
        media.rows.clear()
        assertEquals(DownloadSaver.UndoResult.REMOVED, DownloadSaver.undoSave(app, saved))
    }

    // ------------------------------------------------------------------
    // Concurrency
    // ------------------------------------------------------------------

    @Test
    fun `two simultaneous identical saves produce one file`() {
        val pool = Executors.newFixedThreadPool(2)
        val go = CountDownLatch(1)
        val results = (1..2).map {
            pool.submit<DownloadSaver.SavedFile> {
                go.await()
                DownloadSaver.savePdfFile(app, oldBytes, "Bill.pdf")
            }
        }
        go.countDown()
        val saved = results.map { it.get(20, TimeUnit.SECONDS) }
        pool.shutdown()
        assertEquals("one visible file — got ${media.scanwhirlPdfs().map { it.name }}", 1, media.scanwhirlPdfs().size)
        assertEquals(1, saved.count { it.created })
        assertEquals(1, saved.count { it.outcome == DownloadSaver.Outcome.ALREADY_SAVED })
    }

    // ------------------------------------------------------------------
    // Text exports use the same transaction
    // ------------------------------------------------------------------

    @Test
    fun `text export failure keeps the earlier txt and identical text is not duplicated`() {
        val first = DownloadSaver.saveText(app, "first version", "Notes")
        assertEquals("Notes.txt", first.name)
        assertEquals(
            DownloadSaver.Outcome.ALREADY_SAVED,
            DownloadSaver.saveText(app, "first version", "Notes").outcome
        )
        DownloadSaver.faultAt = { if (it == DownloadSaver.Stage.CLOSING) throw IOException("injected") }
        try {
            DownloadSaver.saveText(app, "second version", "Notes")
            fail("must fail")
        } catch (_: IOException) {
        }
        DownloadSaver.resetForTests()
        assertEquals("first version", String(media.rowNamed("Notes.txt").bytes()))
        assertTrue(media.pendingRows().isEmpty())
        assertEquals("Notes (2).txt", DownloadSaver.saveText(app, "second version", "Notes").name)
        assertEquals("first version", String(media.rowNamed("Notes.txt").bytes()))
    }

    // ------------------------------------------------------------------
    // Ownership: tracking, distinct documents with the same title, delete
    // ------------------------------------------------------------------

    @Test
    fun `same title on two documents never shares or deletes the other's export`() {
        val a = seedDoc("Invoice")
        val b = seedDoc("Invoice")
        val savedA = DownloadSaver.savePdfFile(app, oldBytes, DownloadSaver.pdfNameFor("Invoice"))
        repo.addExport(a, savedA)
        val savedB = DownloadSaver.savePdfFile(app, newBytes, DownloadSaver.pdfNameFor("Invoice"))
        repo.addExport(b, savedB)
        assertNotEquals("distinct documents get distinct files", savedA.uri, savedB.uri)

        val copiesA = DeleteExports.copiesFor(repo, a)
        assertEquals(listOf(savedA.uri), copiesA.map { it.uri })
        repo.deleteDocument(a)
        assertEquals(1 to 0, DeleteExports.removeCopies(app, copiesA))
        assertArrayEquals("B's export survives A's delete", newBytes, media.rowNamed("Invoice (2).pdf").bytes())
    }

    @Test
    fun `a file recorded by another document is kept when one is deleted`() {
        val a = seedDoc("Receipt")
        val b = seedDoc("Receipt copy")
        val saved = DownloadSaver.savePdfFile(app, oldBytes, "Receipt.pdf")
        repo.addExport(a, saved)
        repo.addExport(b, saved.uri, saved.fingerprint) // e.g. an older build rewrote it in place
        assertTrue(DeleteExports.copiesFor(repo, a).isEmpty())
        assertEquals(1, media.scanwhirlPdfs().size)
    }

    @Test
    fun `unrecorded same-name copies are never offered (M07 no title matching)`() {
        val a = seedDoc("Tax")
        seedDoc("Tax (2)")
        DownloadSaver.savePdf(app, oldBytes, "Tax.pdf")
        DownloadSaver.savePdf(app, newBytes, "Tax (2).pdf")
        assertTrue(
            "unrecorded copies are not provably A's, ambiguous family or not",
            DeleteExports.copiesFor(repo, a).isEmpty()
        )
    }

    @Test
    fun `a recorded copy edited after saving is kept on delete`() {
        val a = seedDoc("Lease")
        val saved = DownloadSaver.savePdfFile(app, oldBytes, "Lease.pdf")
        repo.addExport(a, saved)
        media.rowNamed("Lease.pdf").file.appendBytes(byteArrayOf(1))
        val copies = DeleteExports.copiesFor(repo, a)
        assertEquals("the signed record still names it", 1, copies.size)
        assertEquals(0 to 1, DeleteExports.removeCopies(app, copies))
        assertEquals(1, media.scanwhirlPdfs().size)
    }

    @Test
    fun `export records survive remove and keep fingerprints separate`() {
        val a = seedDoc("Rec")
        val s1 = DownloadSaver.savePdfFile(app, oldBytes, "Rec.pdf")
        val s2 = DownloadSaver.savePdfFile(app, newBytes, "Rec.pdf")
        repo.addExport(a, s1.uri, s1.fingerprint)
        repo.addExport(a, s2.uri, s2.fingerprint)
        assertEquals(listOf(s1.fingerprint, s2.fingerprint), repo.exportRecordsOf(a).map { it.fingerprint })
        repo.removeExport(a, s2.uri)
        assertEquals(listOf(s1.uri), repo.exportsOf(a))
        assertEquals(s1.fingerprint, repo.exportRecordsOf(a).single().fingerprint)
        // A legacy record (no fingerprint) still reads.
        val meta = File(app.filesDir, "documents/$a/meta.txt")
        meta.writeText(meta.readText().replace(Regex("exportsigs=.*\\n?"), ""))
        assertEquals(null, repo.exportRecordsOf(a).single().fingerprint)
    }

    // ------------------------------------------------------------------

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun seedDoc(name: String): String = runBlocking {
        val bmp = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
        val uri = android.net.Uri.parse("content://test/m06_${System.nanoTime()}.jpg")
        shadowOf(app.contentResolver).registerInputStream(uri, out.toByteArray().inputStream())
        repo.saveScan(listOf(uri), null, name).id
    }
}
