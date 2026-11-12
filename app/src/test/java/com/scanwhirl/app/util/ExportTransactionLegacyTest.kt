package com.scanwhirl.app.util

import android.app.Application
import android.net.Uri
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.DeleteExports
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment
import java.io.File
import java.io.IOException

/**
 * M06 P0, pre-Android-10 path (API 24–28 write straight to
 * Documents/ScanWhirl): staging into a uniquely named hidden `.part` file,
 * fsync, read-back verification, then a no-clobber rename. An existing export
 * is never opened for writing, so every failpoint leaves it byte-identical and
 * leaves no visible partial file.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(sdk = [28])
class ExportTransactionLegacyTest {

    private lateinit var app: Application
    private lateinit var dir: File

    private val oldBytes = ByteArray(150_000) { (it % 241).toByte() }
    private val newBytes = ByteArray(170_000) { (it % 17 + 3).toByte() }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED)
        @Suppress("DEPRECATION")
        dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "ScanWhirl")
        dir.deleteRecursively()
    }

    @After
    fun tearDown() {
        DownloadSaver.resetForTests()
    }

    private fun visible(): List<String> =
        dir.listFiles()?.filter { !it.name.startsWith(".") }?.map { it.name }?.sorted() ?: emptyList()

    private fun temps(): List<File> =
        dir.listFiles()?.filter { it.name.startsWith(DownloadSaver.TEMP_PREFIX) } ?: emptyList()

    @Test
    fun `first save, identical re-save and changed re-save`() {
        val first = DownloadSaver.savePdfFile(app, oldBytes, "Bill.pdf")
        assertEquals("file", first.uri.scheme)
        assertArrayEquals(oldBytes, File(dir, "Bill.pdf").readBytes())

        val same = DownloadSaver.savePdfFile(app, oldBytes, "Bill.pdf")
        assertEquals(DownloadSaver.Outcome.ALREADY_SAVED, same.outcome)
        assertEquals(first.uri, same.uri)

        val changed = DownloadSaver.savePdfFile(app, newBytes, "Bill.pdf")
        assertTrue(changed.keptEarlier)
        assertEquals("Bill (2).pdf", changed.name)
        assertArrayEquals("earlier copy untouched", oldBytes, File(dir, "Bill.pdf").readBytes())
        assertArrayEquals(newBytes, File(dir, "Bill (2).pdf").readBytes())
        assertEquals(listOf("Bill (2).pdf", "Bill.pdf"), visible())
        assertTrue("no staging files left", temps().isEmpty())
    }

    @Test
    fun `every failpoint preserves the earlier export and leaves no partial file`() {
        for (stage in listOf(
            DownloadSaver.Stage.STAGED, DownloadSaver.Stage.WRITING,
            DownloadSaver.Stage.CLOSING, DownloadSaver.Stage.PUBLISHING
        )) {
            dir.deleteRecursively()
            DownloadSaver.savePdfFile(app, oldBytes, "Bill.pdf")
            val before = File(dir, "Bill.pdf").lastModified()
            DownloadSaver.faultAt = { if (it == stage) throw IOException("injected $stage") }
            try {
                DownloadSaver.savePdfFile(app, newBytes, "Bill.pdf")
                fail("$stage: failure must reach the caller")
            } catch (_: IOException) {
            }
            DownloadSaver.resetForTests()
            assertEquals("$stage: only the earlier copy is visible", listOf("Bill.pdf"), visible())
            assertArrayEquals("$stage: old bytes", oldBytes, File(dir, "Bill.pdf").readBytes())
            assertEquals("$stage: old file not rewritten", before, File(dir, "Bill.pdf").lastModified())
            assertTrue("$stage: staging file removed", temps().isEmpty())
        }
    }

    @Test
    fun `cleanup failure leaves a hidden temp that a later save sweeps once stale`() {
        DownloadSaver.savePdfFile(app, oldBytes, "Bill.pdf")
        DownloadSaver.faultAt = {
            if (it == DownloadSaver.Stage.WRITING) throw IOException("write")
            if (it == DownloadSaver.Stage.DISCARDING) throw IOException("cleanup")
        }
        try {
            DownloadSaver.savePdfFile(app, newBytes, "Bill.pdf")
            fail("must fail")
        } catch (e: IOException) {
            assertEquals("write", e.message)
        }
        DownloadSaver.resetForTests()
        val orphan = temps().single()
        assertTrue("hidden from file browsers", orphan.name.startsWith("."))
        assertEquals(listOf("Bill.pdf"), visible())

        // A fresh temp (another save could own it) survives the sweep; a stale one does not.
        DownloadSaver.savePdfFile(app, "x".toByteArray(), "Other.pdf")
        assertTrue("fresh orphan kept", orphan.exists())
        orphan.setLastModified(System.currentTimeMillis() - DownloadSaver.STALE_TEMP_AGE_MS - 60_000)
        DownloadSaver.savePdfFile(app, "y".toByteArray(), "Other2.pdf")
        assertFalse("stale orphan swept on restart", orphan.exists())
        assertArrayEquals(oldBytes, File(dir, "Bill.pdf").readBytes())
    }

    @Test
    fun `an old fixed-name part file is never reused or clobbered`() {
        dir.mkdirs()
        val legacyPart = File(dir, "Bill.pdf.part").apply { writeBytes(byteArrayOf(7, 7, 7)) }
        DownloadSaver.savePdfFile(app, oldBytes, "Bill.pdf")
        assertArrayEquals(byteArrayOf(7, 7, 7), legacyPart.readBytes())
        assertArrayEquals(oldBytes, File(dir, "Bill.pdf").readBytes())
    }

    @Test
    fun `undo removes only an unchanged copy this save created`() {
        val first = DownloadSaver.savePdfFile(app, oldBytes, "Bill.pdf")
        val again = DownloadSaver.savePdfFile(app, oldBytes, "Bill.pdf")
        assertEquals(DownloadSaver.UndoResult.NOTHING_CREATED, DownloadSaver.undoSave(app, again))
        assertTrue(File(dir, "Bill.pdf").exists())

        val changed = DownloadSaver.savePdfFile(app, newBytes, "Bill.pdf")
        File(dir, "Bill (2).pdf").appendBytes(byteArrayOf(1))
        assertEquals(DownloadSaver.UndoResult.KEPT_CHANGED, DownloadSaver.undoSave(app, changed))
        assertTrue(File(dir, "Bill (2).pdf").exists())

        assertEquals(DownloadSaver.UndoResult.REMOVED, DownloadSaver.undoSave(app, first))
        assertFalse(File(dir, "Bill.pdf").exists())
    }

    @Test
    fun `unmounted storage fails without touching anything`() {
        DownloadSaver.savePdfFile(app, oldBytes, "Bill.pdf")
        ShadowEnvironment.setExternalStorageState(Environment.MEDIA_UNMOUNTED)
        try {
            DownloadSaver.savePdfFile(app, newBytes, "Bill.pdf")
            fail("must fail")
        } catch (_: DownloadSaver.ExportException) {
        }
        ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED)
        assertArrayEquals(oldBytes, File(dir, "Bill.pdf").readBytes())
    }

    @Test
    fun `pre-Q delete-with-document removes only fingerprinted unchanged copies`() {
        val repo = ScanRepository(app)
        val id = kotlinx.coroutines.runBlocking {
            val bmp = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888)
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
            val uri = Uri.parse("content://test/legacy_${System.nanoTime()}.jpg")
            org.robolectric.Shadows.shadowOf(app.contentResolver)
                .registerInputStream(uri, out.toByteArray().inputStream())
            repo.saveScan(listOf(uri), null, "Doc").id
        }
        val ours = DownloadSaver.savePdfFile(app, oldBytes, "Doc.pdf")
        repo.addExport(id, ours.uri, ours.fingerprint)
        // A record from an older build: a bare public path is no proof of ownership.
        val unproven = File(dir, "Doc (9).pdf").apply { writeBytes(newBytes) }
        repo.addExport(id, Uri.fromFile(unproven))

        val copies = DeleteExports.copiesFor(app, repo, id, "Doc")
        assertEquals(listOf(ours.uri), copies.map { it.uri })
        assertEquals(1 to 0, DeleteExports.removeCopies(app, copies))
        assertFalse(File(dir, "Doc.pdf").exists())
        assertTrue("unproven pre-Q file kept", unproven.exists())
    }
}
