package com.scanwhirl.app.util

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Shared facts about password protection (PM-3.1b, decision A3).
 *
 *  - I3: `locked.meta` exists exactly when `scan.pdf` carries `/Encrypt`. The one designed crash
 *    window leaves `locked.meta` beside the previous plain `scan.pdf`.
 *  - [PlainWriteRefused] is thrown by [PdfGenerator.imagesToPdf]: no code path can write a plain
 *    `scan.pdf` beside `locked.meta`.
 */
object PdfProtection {

    const val LOCK_FILE = "locked.meta"
    const val PDF_FILE = "scan.pdf"

    private const val TAIL_BYTES = 4096

    /** A page edit or tool was asked to change a locked document. */
    class DocumentLocked(message: String) : IOException(message)

    /** The password entered does not match the document's lock. */
    class WrongPassword(message: String) : IOException(message)

    /** The generator was asked to write a plain `scan.pdf` beside `locked.meta`. */
    class PlainWriteRefused(file: File) :
        IOException("Refusing to write a plain ${file.name} beside ${LOCK_FILE}")

    /**
     * Whether the effective trailer of a generator-written PDF carries `/Encrypt`. The generator writes
     * the trailer last and the encryptor only replaces it, so it sits in the file's last few KiB. Null when
     * the file is missing or no trailer is found. Same algorithm as the test helper `PdfInvariants.trailerEncrypted`.
     */
    fun isEncrypted(pdf: File): Boolean? {
        if (!pdf.isFile) return null
        val text = try {
            RandomAccessFile(pdf, "r").use { raf ->
                val n = minOf(TAIL_BYTES.toLong(), raf.length()).toInt()
                raf.seek(raf.length() - n)
                val buf = ByteArray(n)
                raf.readFully(buf)
                String(buf, Charsets.ISO_8859_1)
            }
        } catch (_: IOException) {
            return null
        }
        val from = text.lastIndexOf("\ntrailer\n<<")
        if (from < 0) return null
        val end = text.indexOf("\nstartxref\n", from).let { if (it < 0) text.length else it }
        return text.substring(from, end).contains("/Encrypt")
    }

    /** True when the PDF password and its lock hash agree: printable Latin-1 only (F15). */
    fun isEncodable(pw: String): Boolean = pw.all { it in ' '..'~' || it in ' '..'ÿ' }
}
