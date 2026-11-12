package com.scanwhirl.app.util

import android.app.Application
import android.content.pm.ProviderInfo
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.DeleteExports
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowEnvironment
import java.io.File

/**
 * M07 P0 regression (GPT-6 finding after M06): a file name plus
 * `owner_package_name` is not document identity.
 *
 * A named "Invoice" exports Invoice.pdf. A is deleted and the person chooses to
 * KEEP the exported copy. Later B, also named "Invoice", is created and never
 * exported. Deleting B must not offer, count or remove A's retained file.
 *
 * On HEAD 34bf726 the first two tests FAILED: `copiesFor(B)` returned A's
 * `content://media/external/file/1` through the legacy title fallback
 * (evidence: LIBRARY_INTEGRITY/evidence/p0_red_on_34bf726.xml).
 *
 * Runs on the Android 10+ MediaStore model (API 35, [FakeMediaStore] stamps the
 * owner package on insert exactly like MediaProvider), and the pre-Q filesystem
 * path (API 28).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ExportOwnershipTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository
    private lateinit var media: FakeMediaStore

    private val aBytes = "A's invoice, kept on purpose".toByteArray()

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
        media.close()
        DownloadSaver.resetForTests()
        ScanRepository.resetTestSeams()
    }

    @Test
    fun `deleting a never-exported namesake does not offer the retained copy of a deleted document`() {
        val a = seedDoc("Invoice")
        val saved = DownloadSaver.savePdfFile(app, aBytes, DownloadSaver.pdfNameFor("Invoice"))
        repo.addExport(a, saved)
        assertEquals("A can prove its own copy", listOf(saved.uri), DeleteExports.copiesFor(repo, a).map { it.uri })
        // Delete A, choosing "keep exported copies": only the document goes.
        repo.deleteDocument(a)
        assertEquals(1, media.scanwhirlPdfs().size)

        val b = seedDoc("Invoice") // never exported
        val offered = DeleteExports.copiesFor(repo, b)
        assertTrue(
            "B never exported anything; A's retained Invoice.pdf must not be offered — got $offered",
            offered.isEmpty()
        )

        // Even if a caller removed whatever was offered, A's file must survive.
        DeleteExports.removeCopies(app, offered)
        repo.deleteDocument(b)
        assertEquals("A's retained copy must survive B's delete", 1, media.scanwhirlPdfs().size)
        assertArrayEquals(aBytes, media.rowNamed("Invoice.pdf").bytes())
    }

    @Test
    fun `a renamed predecessor's retained copy is not claimed by a later document with that name`() {
        val a = seedDoc("Receipt")
        val saved = DownloadSaver.savePdfFile(app, aBytes, DownloadSaver.pdfNameFor("Receipt"))
        repo.addExport(a, saved)
        repo.renameDocument(a, "Old receipt")
        repo.deleteDocument(a) // copies kept

        val b = seedDoc("Receipt")
        val offered = DeleteExports.copiesFor(repo, b)
        assertTrue("got $offered", offered.isEmpty())
    }

    @Test
    fun `the same journey through Trash keeps the retained copy`() {
        val a = seedDoc("Invoice")
        repo.addExport(a, DownloadSaver.savePdfFile(app, aBytes, DownloadSaver.pdfNameFor("Invoice")))
        val keyA = repo.moveToTrash(a)!!
        repo.deleteFromTrash(keyA) // permanent, copies kept

        val b = seedDoc("Invoice")
        val keyB = repo.moveToTrash(b)!!
        val item = repo.listTrash().single { it.key == keyB }
        assertTrue(DeleteExports.copiesFor(repo, item).isEmpty())
        repo.deleteFromTrash(keyB)
        assertArrayEquals(aBytes, media.rowNamed("Invoice.pdf").bytes())
    }

    @Test
    fun `an identical re-save that adopts a retained copy is recorded but never proven`() {
        val a = seedDoc("Invoice")
        repo.addExport(a, DownloadSaver.savePdfFile(app, aBytes, DownloadSaver.pdfNameFor("Invoice")))
        repo.deleteDocument(a) // copy kept

        val b = seedDoc("Invoice")
        val adopted = DownloadSaver.savePdfFile(app, aBytes, DownloadSaver.pdfNameFor("Invoice"))
        assertEquals(DownloadSaver.Outcome.ALREADY_SAVED, adopted.outcome)
        repo.addExport(b, adopted)
        assertEquals("recorded, so no other delete can take it", listOf(adopted.uri), repo.exportsOf(b))
        assertTrue("but B did not create it", DeleteExports.copiesFor(repo, b).isEmpty())
    }

    @Test
    fun `a proof copied into another document does not verify`() {
        val a = seedDoc("Invoice")
        repo.addExport(a, DownloadSaver.savePdfFile(app, aBytes, DownloadSaver.pdfNameFor("Invoice")))
        val b = seedDoc("Other")
        val metaA = File(app.filesDir, "documents/$a/meta.txt").readLines()
        val metaB = File(app.filesDir, "documents/$b/meta.txt")
        metaB.writeText(metaB.readText().trimEnd('\n') + "\n" + metaA.filter { it.startsWith("export") }.joinToString("\n") + "\n")
        repo.deleteDocument(a)
        assertTrue("A's signature is bound to A's id", DeleteExports.copiesFor(repo, b).isEmpty())
    }

    @Test
    fun `a copy also recorded by a trashed document is protected`() {
        val a = seedDoc("Lease")
        val saved = DownloadSaver.savePdfFile(app, aBytes, "Lease.pdf")
        repo.addExport(a, saved)
        val b = seedDoc("Lease copy")
        repo.addExport(b, saved.uri, saved.fingerprint) // B points at the same file
        repo.moveToTrash(b)
        assertTrue("B (in Trash) still records it", DeleteExports.copiesFor(repo, a).isEmpty())
    }

    @Test
    fun `a missing install key leaves every record unproven`() {
        val a = seedDoc("Invoice")
        repo.addExport(a, DownloadSaver.savePdfFile(app, aBytes, DownloadSaver.pdfNameFor("Invoice")))
        File(app.noBackupFilesDir, "export_provenance.key").delete()
        com.scanwhirl.app.store.ExportProvenance.resetForTests()
        assertTrue(DeleteExports.copiesFor(repo, a).isEmpty())
    }

    @Test
    fun `a proven copy edited elsewhere is kept on permanent delete`() {
        val a = seedDoc("Bill")
        repo.addExport(a, DownloadSaver.savePdfFile(app, aBytes, "Bill.pdf"))
        media.rowNamed("Bill.pdf").file.appendBytes("annotated".toByteArray())
        val copies = DeleteExports.copiesFor(repo, a)
        assertEquals(1, copies.size)
        assertEquals(0 to 1, DeleteExports.removeCopies(app, copies))
        assertEquals(1, media.scanwhirlPdfs().size)
    }

    @Test
    @Config(sdk = [28])
    fun `pre-Q filesystem - a namesake never claims a deleted document's retained file`() {
        ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED)
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "ScanWhirl")
        dir.deleteRecursively()
        val a = seedDoc("Invoice")
        repo.addExport(a, DownloadSaver.savePdfFile(app, aBytes, DownloadSaver.pdfNameFor("Invoice")))
        repo.deleteDocument(a)
        val b = seedDoc("Invoice")
        assertTrue(DeleteExports.copiesFor(repo, b).isEmpty())
        assertArrayEquals(aBytes, File(dir, "Invoice.pdf").readBytes())
    }

    private fun seedDoc(name: String): String = runBlocking {
        val uri = android.net.Uri.parse("content://test/own_${System.nanoTime()}.jpg")
        shadowOf(app.contentResolver).registerInputStream(uri, jpegBytes().inputStream())
        repo.saveScan(listOf(uri), null, name).id
    }

    private fun jpegBytes(): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(120, 160, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bmp).drawColor(android.graphics.Color.WHITE)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
        bmp.recycle()
        return out.toByteArray()
    }
}
