package com.scanwhirl.app.pdf.engine

import java.io.Closeable
import java.io.File

/** A rectangle in PDF points, with the origin at the bottom-left of the page. */
data class PdfRect(val x: Float, val y: Float, val width: Float, val height: Float)

/**
 * A plain-text working PDF plus the page count observed when it was opened or built.
 * The engine owns the bytes: callers may read the file but must not edit it while a
 * handle is in use.
 */
data class PdfHandle internal constructor(val file: File, val pageCount: Int)

/** Hard limits applied before any parse, on every page, and for the whole operation. */
data class PdfEngineBudgets(
    val maxInputBytes: Long = 128L * 1024 * 1024,
    val maxOutputBytes: Long = 256L * 1024 * 1024,
    val maxPages: Int = 500,
    val maxRenderPixels: Long = 24L * 1024 * 1024,
    val wallTimeMs: Long = 180_000L
)

/**
 * Page-object engine. No method may rasterise an untouched page. Encryption is
 * delegated to the app's pure-JCA PdfEncryptor/PdfDecryptor: PDFBox never sees a
 * password and never loads an encrypted file.
 */
interface PdfEngine : Closeable {
    /** Decrypts [password] into a private temp file when needed, then opens plain bytes. */
    fun open(source: File, password: String? = null): PdfHandle

    fun copyPages(source: PdfHandle, indices: IntArray, destination: String): PdfHandle

    fun reorder(handle: PdfHandle, order: IntArray)

    fun rotate(handle: PdfHandle, pageIndex: Int, degrees: Int)

    fun delete(handle: PdfHandle, indices: IntArray)

    fun merge(sources: List<File>, destination: String): PdfHandle

    fun split(source: PdfHandle, ranges: List<IntRange>): List<PdfHandle>

    /** Appends an image XObject and text/date; never overwrites the original content stream. */
    fun overlay(
        handle: PdfHandle,
        pageIndex: Int,
        signaturePng: ByteArray,
        rect: PdfRect,
        text: String,
        date: String
    )

    /**
     * Full rewrite: only the listed boxes' page becomes an opaque image page; all
     * other pages stay digital. The original object graph, Info and history do not
     * survive into [destination].
     */
    fun redact(
        source: PdfHandle,
        pageIndex: Int,
        boxes: List<PdfRect>,
        destination: String
    ): PdfHandle

    /** Saves plain bytes, then encrypts those bytes with the app's PdfEncryptor. */
    fun saveEncrypted(handle: PdfHandle, destination: File, userPassword: String)

    /** Re-opens [file] and returns its text; used by tests and by post-save verification. */
    fun verifyText(file: File, password: String? = null): String

    fun cancel()

    val isCancelled: Boolean
}
