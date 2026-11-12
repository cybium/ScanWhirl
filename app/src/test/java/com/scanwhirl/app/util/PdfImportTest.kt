package com.scanwhirl.app.util

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
import com.scanwhirl.app.store.ScanRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * M07 WP-06: the import's read layer through real ContentResolver/provider
 * semantics, including a **pipe** (non-seekable) provider like Drive or chat apps,
 * a provider that dies mid-stream, revoked access, size limits, cancellation and
 * cleanup. Rendering itself (password, damage, page order) needs the platform
 * PdfRenderer, which Robolectric cannot run on this host; that is proven on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PdfImportTest {

    private lateinit var app: Application
    private lateinit var provider: PipeProvider

    private val pdfBytes: ByteArray =
        ("%PDF-1.4\n" + "x".repeat(300_000) + "\n%%EOF\n").toByteArray(Charsets.US_ASCII)

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        provider = PipeProvider().also {
            it.attachInfo(app, ProviderInfo().apply { authority = AUTH; exported = true })
            ShadowContentResolver.registerProviderInternal(AUTH, it)
        }
    }

    @After
    fun tearDown() {
        PdfImport.resetForTests()
        ScanRepository.resetTestSeams()
    }

    private fun uri(name: String) = Uri.parse("content://$AUTH/$name")

    private fun cacheCopies(): List<File> = File(app.cacheDir, "pdf_fd").listFiles()?.toList() ?: emptyList()

    @Test
    fun `a streamed, unsized source is copied whole into a seekable private file`() = runBlocking {
        val u = streamUri(Streaming(pdfBytes, slowMs = 1))
        val (size, statSize) = PdfImport.withSeekableCopy(app, u) { fd, total -> total to fd.statSize }
        assertEquals(pdfBytes.size.toLong(), size)
        assertEquals("the copy is a real seekable file", pdfBytes.size.toLong(), statSize)
        assertTrue("the copy is removed afterwards", cacheCopies().isEmpty())
    }

    /**
     * Robolectric's pipe shadow is not faithful for streamed writers (a reader can see
     * EOF early), so streamed sources are real InputStreams handed to the resolver.
     * A true pipe through the system picker is exercised on the device (test-build
     * fixture provider).
     */
    private fun streamUri(stream: java.io.InputStream): Uri {
        val u = Uri.parse("content://m07.stream.test/${System.nanoTime()}.pdf")
        org.robolectric.Shadows.shadowOf(app.contentResolver).registerInputStream(u, stream)
        return u
    }

    private class Streaming(
        private val bytes: ByteArray,
        private val failAfter: Int = -1,
        private val slowMs: Long = 0
    ) : java.io.InputStream() {
        private var pos = 0
        override fun read(): Int = throw UnsupportedOperationException()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (failAfter in 0..pos) throw IOException("Connection reset by provider")
            if (pos >= bytes.size) return -1
            if (slowMs > 0) Thread.sleep(slowMs)
            val n = minOf(len, 8 * 1024, bytes.size - pos)
            System.arraycopy(bytes, pos, b, off, n)
            pos += n
            return n
        }
    }

    @Test
    fun `a provider that dies mid-stream is READ_FAILED, never a partial import`() = runBlocking {
        val u = streamUri(Streaming(pdfBytes, failAfter = 100_000))
        expect(PdfImport.Reason.READ_FAILED) { PdfImport.withSeekableCopy(app, u) { _, _ -> } }
        assertTrue(cacheCopies().isEmpty())
    }

    @Test
    fun `revoked permission and a vanished file are NO_ACCESS, not password`() = runBlocking {
        provider.deny = true
        expect(PdfImport.Reason.NO_ACCESS) { PdfImport.withSeekableCopy(app, uri("x.pdf")) { _, _ -> } }
        provider.deny = false
        expect(PdfImport.Reason.NO_ACCESS) { PdfImport.withSeekableCopy(app, uri("missing.pdf")) { _, _ -> } }
    }

    @Test
    fun `size limits apply to declared and undeclared sizes`() = runBlocking {
        provider.serve("declared.pdf", pdfBytes, declaredSize = PdfImport.MAX_BYTES + 1)
        expect(PdfImport.Reason.TOO_LARGE) { PdfImport.withSeekableCopy(app, uri("declared.pdf")) { _, _ -> } }
        assertFalse("rejected before reading", provider.opened)

        val undeclared = streamUri(Streaming(pdfBytes))
        expect(PdfImport.Reason.TOO_LARGE) {
            PdfImport.withSeekableCopy(app, undeclared, maxBytes = 100_000) { _, _ -> }
        }
        assertTrue(cacheCopies().isEmpty())
    }

    @Test
    fun `empty files and non-PDFs are classified`() = runBlocking {
        val empty = streamUri(Streaming(ByteArray(0)))
        expect(PdfImport.Reason.EMPTY) { PdfImport.withSeekableCopy(app, empty) { _, _ -> } }
        val photo = streamUri(Streaming(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(5000)))
        expect(PdfImport.Reason.DAMAGED) { PdfImport.withSeekableCopy(app, photo) { _, _ -> } }
    }

    @Test
    fun `not enough cache space for a declared size is NO_SPACE`() = runBlocking {
        PdfImport.cacheSpace = { 1_000L }
        provider.serve("big.pdf", pdfBytes, declaredSize = pdfBytes.size.toLong())
        expect(PdfImport.Reason.NO_SPACE) { PdfImport.withSeekableCopy(app, uri("big.pdf")) { _, _ -> } }
    }

    @Test
    fun `cancellation stops the copy and leaves nothing behind`() = runBlocking {
        val u = streamUri(Streaming(pdfBytes, slowMs = 20))
        val job = async(Dispatchers.IO) { PdfImport.withSeekableCopy(app, u) { _, _ -> } }
        delay(150)
        job.cancel()
        try {
            job.await()
            fail("cancelled")
        } catch (_: CancellationException) {
        }
        assertTrue(cacheCopies().isEmpty())
    }

    @Test
    fun `a failed import publishes no document and leaves no staging`() = runBlocking {
        val repo = ScanRepository(app)
        try {
            repo.importPdfAsScan(streamUri(Streaming(ByteArray(4000) { 7 })), "Not a PDF")
            fail("must fail")
        } catch (e: PdfImport.ImportException) {
            assertEquals(PdfImport.Reason.DAMAGED, e.reason)
        }
        ScanRepository.importFault = { if (it == ScanRepository.Companion.ImportStage.COPIED) throw IOException("died") }
        try {
            repo.importPdfAsScan(streamUri(Streaming(pdfBytes)), "Crash")
            fail("must fail")
        } catch (_: IOException) {
        }
        assertTrue("no phantom document", repo.listDocuments(includeVaulted = true).isEmpty())
        assertTrue("no staging left", File(app.filesDir, "import_staging").listFiles()!!.isEmpty())
        assertTrue(cacheCopies().isEmpty())
    }

    @Test
    fun `startup recovery removes only stale import staging`() {
        val repo = ScanRepository(app)
        val root = File(app.filesDir, "import_staging").apply { mkdirs() }
        val stale = File(root, "old").apply { mkdirs(); File(this, "page_000.jpg").writeText("x") }
        stale.setLastModified(System.currentTimeMillis() - ScanRepository.STALE_IMPORT_MS - 60_000)
        val live = File(root, "running").apply { mkdirs() }
        assertEquals(1, repo.recoverImports())
        assertFalse(stale.exists())
        assertTrue("an import in progress is left alone", live.exists())
    }

    @Test
    fun `write failures are classified as space or save problems`() {
        assertEquals(
            PdfImport.Reason.NO_SPACE,
            PdfImport.classifyWrite(IOException("write failed: ENOSPC (No space left on device)")).reason
        )
        assertEquals(PdfImport.Reason.SAVE_FAILED, PdfImport.classifyWrite(IOException("EIO")).reason)
        assertTrue(PdfImport.looksLikePdf("\n\n%PDF-1.7".toByteArray(), 10))
        assertFalse(PdfImport.looksLikePdf("hello".toByteArray(), 5))
    }

    private suspend fun expect(reason: PdfImport.Reason, block: suspend () -> Unit) {
        try {
            block()
            fail("expected $reason")
        } catch (e: PdfImport.ImportException) {
            assertEquals(e.message, reason, e.reason)
        }
    }

    /** A DocumentsProvider-like source that streams through a pipe, like cloud/chat providers. */
    class PipeProvider : ContentProvider() {
        private class Item(val bytes: ByteArray, val failAfter: Int, val declared: Long?, val slowMs: Long)

        private val items = HashMap<String, Item>()
        @Volatile var deny = false
        @Volatile var servedAsPipe = false
        @Volatile var opened = false
        @Volatile var writes = 0

        fun serve(name: String, bytes: ByteArray, failAfter: Int = -1, declaredSize: Long? = null, slowMs: Long = 0) {
            items[name] = Item(bytes, failAfter, declaredSize, slowMs)
            opened = false
        }

        override fun onCreate() = true
        override fun getType(uri: Uri) = "application/pdf"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor {
            val item = items[uri.lastPathSegment]
            val c = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
            if (item != null) c.addRow(arrayOf<Any?>(uri.lastPathSegment, item.declared))
            return c
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (mode.contains('w')) {
                writes++
                throw SecurityException("read-only source")
            }
            if (deny) throw SecurityException("Permission Denial: grant revoked")
            val item = items[uri.lastPathSegment] ?: throw FileNotFoundException("gone")
            opened = true
            servedAsPipe = true
            val (read, write) = ParcelFileDescriptor.createReliablePipe()
            Thread {
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(write).use { out ->
                        var off = 0
                        while (off < item.bytes.size) {
                            if (item.failAfter in 0..off) {
                                write.closeWithError("provider crashed")
                                return@Thread
                            }
                            val n = minOf(16 * 1024, item.bytes.size - off)
                            out.write(item.bytes, off, n)
                            off += n
                            if (item.slowMs > 0) Thread.sleep(item.slowMs)
                        }
                    }
                } catch (_: Exception) {
                }
            }.start()
            return read
        }
    }

    companion object {
        private const val AUTH = "m07.pipe.test"
    }
}
