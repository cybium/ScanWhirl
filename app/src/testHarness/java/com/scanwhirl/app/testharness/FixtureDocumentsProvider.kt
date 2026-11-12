package com.scanwhirl.app.testharness

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException

/**
 * M07 device acceptance harness. **Compiled only into `-PtestBuild` APKs** (source
 * set `src/testHarness`, see app/build.gradle.kts); production builds do not contain
 * it. It appears in the system file picker as "ScanWhirl test fixtures" and serves
 * synthetic PDFs pushed to `<app external files>/m07_fixtures/`, so the real SAF
 * import path can be exercised against provider behaviours a phone rarely offers
 * on demand:
 *
 *  - `*.pipe.pdf`    streamed through a pipe (non-seekable, size unknown), like Drive or chat apps;
 *  - `*.dropout.pdf` a pipe that fails half-way, like a lost connection;
 *  - `*.revoked.pdf` opening throws SecurityException, like a revoked grant;
 *  - anything else   an ordinary seekable file descriptor.
 */
class FixtureDocumentsProvider : DocumentsProvider() {

    private val dir: File
        get() = File(context!!.getExternalFilesDir(null), "m07_fixtures").apply { mkdirs() }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: ROOT_COLUMNS).apply {
            newRow()
                .add(Root.COLUMN_ROOT_ID, ROOT)
                .add(Root.COLUMN_DOCUMENT_ID, ROOT)
                .add(Root.COLUMN_TITLE, "ScanWhirl test fixtures")
                .add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY)
                .add(Root.COLUMN_ICON, android.R.drawable.ic_menu_agenda)
                .add(Root.COLUMN_MIME_TYPES, "application/pdf")
        }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DOC_COLUMNS).apply { addDoc(this, documentId) }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor = MatrixCursor(projection ?: DOC_COLUMNS).apply {
        dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.forEach { addDoc(this, it.name) }
    }

    private fun addDoc(c: MatrixCursor, id: String) {
        if (id == ROOT) {
            c.newRow()
                .add(Document.COLUMN_DOCUMENT_ID, ROOT)
                .add(Document.COLUMN_DISPLAY_NAME, "fixtures")
                .add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
                .add(Document.COLUMN_FLAGS, 0)
            return
        }
        val f = File(dir, id)
        if (!f.isFile) return
        val streamed = id.endsWith(".pipe.pdf") || id.endsWith(".dropout.pdf")
        c.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, id)
            .add(Document.COLUMN_DISPLAY_NAME, id)
            .add(Document.COLUMN_MIME_TYPE, "application/pdf")
            .add(Document.COLUMN_SIZE, if (streamed) null else f.length())
            .add(Document.COLUMN_LAST_MODIFIED, f.lastModified())
            .add(Document.COLUMN_FLAGS, 0)
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        if (mode.contains('w')) throw SecurityException("fixtures are read-only")
        if (documentId.endsWith(".revoked.pdf")) {
            throw SecurityException("Permission Denial: fixture grant revoked")
        }
        val f = File(dir, documentId)
        if (!f.isFile) throw FileNotFoundException(documentId)
        val pipe = documentId.endsWith(".pipe.pdf")
        val dropout = documentId.endsWith(".dropout.pdf")
        if (!pipe && !dropout) return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)

        val (read, write) = ParcelFileDescriptor.createReliablePipe()
        Thread {
            val out = ParcelFileDescriptor.AutoCloseOutputStream(write)
            try {
                FileInputStream(f).use { input ->
                    val buf = ByteArray(4 * 1024)
                    var sent = 0L
                    val cut = f.length() / 2
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        sent += n
                        Thread.sleep(2) // a slow network-ish stream
                        if (dropout && sent >= cut) {
                            write.closeWithError("fixture connection dropped")
                            return@Thread
                        }
                    }
                }
                out.close()
            } catch (_: Exception) {
                try { write.closeWithError("fixture stream failed") } catch (_: Exception) {}
            }
        }.start()
        return read
    }

    companion object {
        private const val ROOT = "m07root"
        private val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE,
            Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_MIME_TYPES
        )
        private val DOC_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS
        )
    }
}
