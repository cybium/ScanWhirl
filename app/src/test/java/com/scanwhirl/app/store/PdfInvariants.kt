package com.scanwhirl.app.store

import android.app.Application
import com.scanwhirl.app.util.PdfVerifier
import com.scanwhirl.app.util.Sha256
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.File
import java.io.RandomAccessFile

/**
 * On-disk checks of the architecture invariants (I1, I3, I4, I10, I5 via [dirDigest], I6 via
 * [assertNoOrphans]). Every assertion throws [AssertionError] with a message that names the document.
 */
object PdfInvariants {

    private val STARTXREF = Regex("startxref\n(\\d+)")
    private val COUNT = Regex("/Count (\\d+)")

    private fun bad(message: String): Nothing = throw AssertionError(message)

    /**
     * I5: digest of a directory tree (paths, sizes and content of every entry). Any added, removed,
     * renamed or changed file, including hidden ones, changes it. A missing directory is "absent".
     */
    fun dirDigest(dir: File): String {
        if (!dir.exists()) return "absent"
        val lines = dir.walkTopDown().map { entry ->
            val rel = entry.relativeTo(dir).path.replace(File.separatorChar, '/')
            if (entry.isDirectory) "D $rel" else "F $rel ${entry.length()} ${Sha256.of(entry)}"
        }.sorted().toList()
        return Sha256.of(lines.joinToString("\n").toByteArray())
    }

    private fun tail(pdf: File, max: Int = 4096): String {
        if (!pdf.isFile) fail("PDF is missing: ${pdf.path}")
        RandomAccessFile(pdf, "r").use { raf ->
            val n = minOf(max.toLong(), raf.length()).toInt()
            raf.seek(raf.length() - n)
            val buf = ByteArray(n)
            raf.readFully(buf)
            return String(buf, Charsets.ISO_8859_1)
        }
    }

    /**
     * Whether the effective trailer of a generator-written PDF carries `/Encrypt` (F17: the trailer sits
     * in the last few KiB). Null when no trailer is found.
     */
    fun trailerEncrypted(pdf: File): Boolean? {
        val text = tail(pdf)
        val from = text.lastIndexOf("\ntrailer\n<<")
        if (from < 0) return null
        val end = text.indexOf("\nstartxref\n", from).let { if (it < 0) text.length else it }
        return text.substring(from, end).contains("/Encrypt")
    }

    /**
     * `/Count` of the page tree (object 2), read through the xref table. Page-tree dictionaries are
     * never encrypted, so this works for plain and locked files. Fails (never returns 0) when the shape differs.
     */
    fun pageTreeCount(pdf: File): Int {
        val startxref = STARTXREF.findAll(tail(pdf)).lastOrNull()
            ?: bad("no startxref in ${pdf.path}")
        val xrefOffset = startxref.groupValues[1].toLong()
        RandomAccessFile(pdf, "r").use { raf ->
            if (xrefOffset <= 0 || xrefOffset >= raf.length()) fail("startxref $xrefOffset is outside ${pdf.path}")
            raf.seek(xrefOffset)
            val head = ByteArray(minOf(64L, raf.length() - xrefOffset).toInt())
            raf.readFully(head)
            val headText = String(head, Charsets.ISO_8859_1)
            val header = Regex("^xref\n0 (\\d+)\n").find(headText)
                ?: bad("xref table header not found at $xrefOffset in ${pdf.path}")
            val entries = header.groupValues[1].toInt()
            if (entries < 3) fail("xref has only $entries entries in ${pdf.path}")
            val entryPos = xrefOffset + header.value.length + 20L * 2
            raf.seek(entryPos)
            val entry = ByteArray(20)
            raf.readFully(entry)
            val entryText = String(entry, Charsets.ISO_8859_1)
            val objOffset = Regex("^(\\d{10}) \\d{5} n ").find(entryText)?.groupValues?.get(1)?.toLong()
                ?: bad("xref entry for object 2 is not in-use format: '$entryText'")
            if (objOffset <= 0 || objOffset >= raf.length()) fail("object 2 offset $objOffset is outside ${pdf.path}")
            raf.seek(objOffset)
            val buf = ByteArray(minOf(1L shl 20, raf.length() - objOffset).toInt())
            raf.readFully(buf)
            val obj = String(buf, Charsets.ISO_8859_1).substringBefore("endobj")
            if (!obj.startsWith("2 0 obj")) fail("object 2 not found at offset $objOffset in ${pdf.path}")
            return COUNT.find(obj)?.groupValues?.get(1)?.toInt()
                ?: bad("no /Count in the page tree of ${pdf.path}")
        }
    }

    private fun docDir(repo: ScanRepository, id: String) = File(repo.docsDir, id)

    private fun doc(repo: ScanRepository, id: String) =
        repo.getDocument(id) ?: throw AssertionError("document $id is not visible")

    /** I1: page files = /Count of scan.pdf (and, for a plain PDF, the PDF verifies against those page files). */
    fun assertI1(repo: ScanRepository, id: String) {
        val d = doc(repo, id)
        val pdf = File(docDir(repo, id), "scan.pdf")
        val count = pageTreeCount(pdf)
        assertEquals("I1: document $id has ${d.pageFiles.size} page files but scan.pdf /Count is $count", count, d.pageFiles.size)
        if (trailerEncrypted(pdf) == false) {
            try {
                PdfVerifier.verify(pdf, expectedImages = d.pageFiles)
            } catch (e: Exception) {
                throw AssertionError("I1: scan.pdf of $id does not verify against its page files: ${e.message}", e)
            }
        }
    }

    /** I3: `locked.meta` exists exactly when scan.pdf is encrypted. */
    fun assertI3(repo: ScanRepository, id: String) {
        val dir = docDir(repo, id)
        val hasMeta = File(dir, "locked.meta").exists()
        val encrypted = trailerEncrypted(File(dir, "scan.pdf")) == true
        assertEquals("I3: document $id has locked.meta=$hasMeta but scan.pdf encrypted=$encrypted", encrypted, hasMeta)
    }

    /** I4: every visible document has a non-empty scan.pdf that satisfies I1. */
    fun assertI4(repo: ScanRepository) {
        repo.listDocuments(includeVaulted = true).forEach { d ->
            val pdf = File(docDir(repo, d.id), "scan.pdf")
            assertTrue("I4: visible document ${d.id} has no usable scan.pdf", pdf.isFile && pdf.length() > 0L)
            assertI1(repo, d.id)
        }
    }

    /** I10: the document is queued (`ocr_pending`) or every page has OCR text. */
    fun assertI10(repo: ScanRepository, id: String) {
        if (File(docDir(repo, id), "ocr_pending").exists()) return
        val have = repo.ocrDoc(id).keys
        val missing = doc(repo, id).pageFiles.map { it.name }.filter { it !in have }
        assertTrue("I10: document $id has no ocr_pending and no OCR text for $missing", missing.isEmpty())
    }

    /** I6: nothing in `documents` is neither a loadable document nor an owned save directory; op_staging is empty. */
    fun assertNoOrphans(app: Application, repo: ScanRepository) {
        val docs = File(app.filesDir, "documents")
        val orphans = docs.listFiles { f -> f.isDirectory }.orEmpty()
            .filter { repo.getDocument(it.name) == null && SaveFormat.ownerOf(it) == null }
            .map { it.name }.sorted()
        val staging = File(app.filesDir, "op_staging").list().orEmpty().sorted()
        if (orphans.isNotEmpty() || staging.isNotEmpty()) {
            fail("orphans under documents: $orphans; leftovers in op_staging: $staging")
        }
    }

    fun assertClean(app: Application, repo: ScanRepository) {
        assertI4(repo)
        assertNoOrphans(app, repo)
    }
}
