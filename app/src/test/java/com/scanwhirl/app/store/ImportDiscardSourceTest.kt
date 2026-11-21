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
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

/**
 * A read-only source URI is an INPUT, never a target of discard/Undo/expiry. The bytes are read
 * through the real ContentResolver/provider path; the app's private copy is the standard saved
 * fixture because Robolectric cannot run the platform PdfRenderer on this host JDK (the whole
 * import rasteriser is proven on a device). What this test proves is the data-safety boundary:
 * no delete, write or write-mode open ever reaches the source during the discard transaction.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ImportDiscardSourceTest {
    @Test fun `source provider sees reads only through real discard Undo and expiry`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val repo = ScanRepository(app)
        val imported = DocFixtures.saved(repo, app, pages = 2, name = "Fictional source PDF")
        val source = File(app.cacheDir, "fictional-external-source.pdf")
        requireNotNull(repo.getDocument(imported)).pdfFile.copyTo(source)
        val provider = ReadOnlySource(source)
        provider.attachInfo(app, ProviderInfo().apply { authority = AUTH; exported = true })
        ShadowContentResolver.registerProviderInternal(AUTH, provider)
        val hash = Sha256.of(source)

        // The real resolver reads the source (a real "r" open) and never asks for a write.
        val bytes = app.contentResolver.openInputStream(Uri.parse("content://$AUTH/source.pdf"))!!
            .use { it.readBytes() }
        assertArrayEquals("the resolver read the source bytes", source.readBytes(), bytes)
        assertEquals("source is opened read-only", listOf("r"), provider.modes)

        val before = PdfInvariants.dirDigest(File(repo.docsDir, imported))
        var elapsed = 1_000L
        val store = ImportDiscardStore(app, repo, { elapsed }, { 1 })
        val token = store.discard(imported, 8_000)
        assertEquals(hash, Sha256.of(source))
        assertEquals(imported, store.restore(token.key))
        assertEquals(before, PdfInvariants.dirDigest(File(repo.docsDir, imported)))
        assertEquals(hash, Sha256.of(source))

        store.discard(imported, 8_000)
        elapsed += 9_000
        store.recover()
        assertNull(repo.getDocument(imported))
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
