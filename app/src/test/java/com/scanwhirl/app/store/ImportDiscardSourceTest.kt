package com.scanwhirl.app.store

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.util.Sha256
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

/** Real repository import through a recording, read-only provider; no owner document fixtures. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ImportDiscardSourceTest {
    @Test fun `source provider sees reads only through real import discard Undo and expiry`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val repo = ScanRepository(app)
        val fixture = DocFixtures.saved(repo, app, pages = 2, name = "Fictional source PDF")
        val source = File(app.cacheDir, "fictional-external-source.pdf")
        requireNotNull(repo.getDocument(fixture)).pdfFile.copyTo(source)
        val provider = ReadOnlySource(source)
        provider.attachInfo(app, ProviderInfo().apply { authority = AUTH; exported = true })
        ShadowContentResolver.registerProviderInternal(AUTH, provider)
        val hash = Sha256.of(source)
        val imported = repo.importPdfAsScan(Uri.parse("content://$AUTH/source.pdf"), "काल्पनिक PDF")
        assertEquals(2, imported.pageCount)
        assertTrue("the actual source provider was read", provider.modes.isNotEmpty())
        val before = PdfInvariants.dirDigest(File(repo.docsDir, imported.id))
        var elapsed = 1_000L
        val store = ImportDiscardStore(app, repo, { elapsed }, { 1 })
        val token = store.discard(imported.id, 8_000)
        assertEquals(hash, Sha256.of(source))
        assertEquals(imported.id, store.restore(token.key))
        assertEquals(before, PdfInvariants.dirDigest(File(repo.docsDir, imported.id)))
        assertEquals(hash, Sha256.of(source))
        store.discard(imported.id, 8_000)
        elapsed += 9_000
        store.recover()
        assertNull(repo.getDocument(imported.id))
        assertEquals(hash, Sha256.of(source))
        assertEquals("never open the original for writing", setOf("r"), provider.modes.toSet())
        assertEquals("never delete the original", 0, provider.deletes)
        assertEquals("never update the original", 0, provider.writes)
    }

    private class ReadOnlySource(private val source: File) : ContentProvider() {
        val modes = mutableListOf<String>()
        var deletes = 0
        var writes = 0
        override fun onCreate() = true
        override fun getType(uri: Uri) = "application/pdf"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
                addRow(arrayOf("Fictional source.pdf", source.length()))
            }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            modes += mode
            check(mode == "r") { "Source is read-only" }
            return ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            deletes++
            throw AssertionError("Source must never be deleted")
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? {
            writes++
            throw AssertionError("Source must never be written")
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?,
            selectionArgs: Array<out String>?): Int {
            writes++
            throw AssertionError("Source must never be written")
        }
    }
    companion object { private const val AUTH = "scanwhirl.u1.source" }
}
