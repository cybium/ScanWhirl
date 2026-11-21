package com.scanwhirl.app.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import kotlin.math.roundToInt

/**
 * M07 WP-06: bounded, classified reading of a device PDF for import.
 *
 * Android's PdfRenderer needs a seekable descriptor, and SAF providers (Drive,
 * chat apps, cloud providers) often hand back a pipe. The source is therefore
 * copied once, read-only, into a private cache file with a hard size limit, and
 * rendered from there. The source itself is never written.
 *
 * Every failure becomes an [ImportException] with a [Reason], so the screen can
 * tell a password-protected PDF from a damaged one, a revoked permission or a
 * full phone, instead of one generic error.
 */
object PdfImport {

    /** Largest source file accepted (bytes). */
    const val MAX_BYTES: Long = 256L * 1024 * 1024

    /** Most pages one import may hold; range selection handles bigger books. */
    const val MAX_PAGES = 10_000

    /** Rough upper estimate of one rendered page on disk (JPEG + its share of the PDF). */
    internal const val EST_PAGE_BYTES: Long = PdfImportBudget.TYPICAL_PAGE_BYTES

    /** Headroom always left free on the device. */
    internal const val SPACE_MARGIN: Long = 16L * 1024 * 1024

    private const val CHUNK = 64 * 1024

    enum class Reason {
        /** The file can no longer be read: permission revoked, file moved/deleted. */
        NO_ACCESS,

        /** The PDF needs a password to open. */
        PASSWORD,

        /** Not a PDF, or too damaged for the renderer (truncated, corrupt). */
        DAMAGED,

        /** A PDF with zero pages, or an empty file. */
        EMPTY,

        /** Larger than [MAX_BYTES]. */
        TOO_LARGE,

        /**
         * Too large to unlock in memory on this phone. A protected PDF is decrypted whole
         * before its pages are rendered, so it cannot use the streaming import path.
         */
        LOW_MEMORY,

        /** More than [MAX_PAGES] pages. */
        TOO_MANY_PAGES,

        /** Not enough free storage to finish. */
        NO_SPACE,

        /** The provider stopped delivering the file part-way (network, app closed). */
        READ_FAILED,

        /** Writing the imported pages into the app's storage failed (not a space problem). */
        SAVE_FAILED
    }

    class ImportException(
        val reason: Reason,
        message: String,
        cause: Throwable? = null,
        /** Page count (TOO_MANY_PAGES, or the total when one page failed). */
        val pages: Int = 0,
        /** 1-based page that could not be read, when known. */
        val page: Int = 0
    ) : IOException(message, cause)

    /**
     * Advisory free-space probe for the cache copy (real ENOSPC is still caught on
     * every write); replaceable in tests.
     */
    @android.annotation.SuppressLint("UsableSpace")
    private fun probeSpace(dir: File): Long = dir.usableSpace

    @Volatile internal var cacheSpace: (File) -> Long = ::probeSpace

    internal fun resetForTests() {
        cacheSpace = ::probeSpace
        pageCountSeam = null
    }

    /**
     * Copies [uri] (read-only) into a private cache file, opens it seekably and runs
     * [block] with the descriptor and the copied size. The copy is always removed.
     * Cancellation is honoured between chunks.
     */
    suspend fun <T> withSeekableCopy(
        context: Context,
        uri: Uri,
        maxBytes: Long = MAX_BYTES,
        block: suspend (ParcelFileDescriptor, Long) -> T
    ): T {
        val declared = declaredSize(context, uri)
        if (declared != null && declared > maxBytes) {
            throw ImportException(Reason.TOO_LARGE, "PDF is larger than ${SizeUnits.format(maxBytes)}")
        }
        val dir = File(context.cacheDir, "pdf_fd").apply { mkdirs() }
        if (declared != null && cacheSpace(dir) < declared + SPACE_MARGIN) {
            throw ImportException(Reason.NO_SPACE, "Not enough storage to read this PDF")
        }
        val tmp = File(dir, "seek_${System.nanoTime()}.pdf")
        BoundedCopy.retain(tmp)
        try {
            val input = try {
                context.contentResolver.openInputStream(uri)
            } catch (e: SecurityException) {
                throw ImportException(Reason.NO_ACCESS, "No permission to read this file", e)
            } catch (e: FileNotFoundException) {
                throw ImportException(Reason.NO_ACCESS, "The file is no longer available", e)
            } catch (e: IllegalArgumentException) {
                throw ImportException(Reason.NO_ACCESS, "The file is no longer available", e)
            } ?: throw ImportException(Reason.NO_ACCESS, "The file is no longer available")

            var total = 0L
            val head = ByteArray(1024)
            var headLen = 0
            input.use { src ->
                try {
                    FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(CHUNK)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = try {
                                src.read(buf)
                            } catch (e: IOException) {
                                throw ImportException(Reason.READ_FAILED, "The file stopped loading part-way", e)
                            } catch (e: SecurityException) {
                                throw ImportException(Reason.NO_ACCESS, "Permission to read the file was removed", e)
                            }
                            if (n < 0) break
                            if (headLen < head.size) {
                                val take = minOf(n, head.size - headLen)
                                System.arraycopy(buf, 0, head, headLen, take)
                                headLen += take
                            }
                            total += n
                            if (total > maxBytes) {
                                throw ImportException(Reason.TOO_LARGE, "PDF is larger than ${SizeUnits.format(maxBytes)}")
                            }
                            out.write(buf, 0, n)
                        }
                    }
                } catch (e: ImportException) {
                    throw e
                } catch (e: IOException) {
                    throw classifyWrite(e)
                }
            }
            if (total == 0L) throw ImportException(Reason.EMPTY, "The file is empty")
            if (!looksLikePdf(head, headLen)) throw ImportException(Reason.DAMAGED, "This file is not a PDF")
            val fd = try {
                ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
            } catch (e: IOException) {
                throw ImportException(Reason.READ_FAILED, "Could not reopen the copied PDF", e)
            }
            return fd.use { block(it, total) }
        } finally {
            BoundedCopy.release(tmp)
        }
    }

    /** A PDF page source: the real renderer, or a fake in tests (huge-import memory checks). */
    interface PageSource {
        fun pageCount(): Int
        fun render(index: Int): ByteArray
    }

    /** Page count without rendering; the caller owns [fd]. */
    fun pageCount(fd: ParcelFileDescriptor): Int =
        pageCountSeam?.invoke(fd) ?: realPageCount(fd)

    /** Fault seam: lets a test make the pre-check see a protected/odd PDF. */
    @Volatile internal var pageCountSeam: ((ParcelFileDescriptor) -> Int)? = null

    private fun realPageCount(fd: ParcelFileDescriptor): Int = openRenderer(fd).use { renderer ->
        val count = renderer.pageCount
        if (count <= 0) throw ImportException(Reason.EMPTY, "This PDF has no pages")
        if (count > MAX_PAGES) {
            throw ImportException(Reason.TOO_MANY_PAGES, "This PDF has $count pages", pages = count)
        }
        count
    }

    /** The real renderer behind [PageSource]; keep it open for the whole render pass. */
    class PdfRendererSource(
        fd: ParcelFileDescriptor,
        private val dpi: Float,
        private val maxDim: Int,
        private val quality: Int
    ) : PageSource, java.io.Closeable {
        private val renderer = openRenderer(fd)

        override fun pageCount(): Int = renderer.pageCount

        override fun render(index: Int): ByteArray = renderPage(renderer, index, dpi, maxDim, quality)

        override fun close() = renderer.close()
    }

    private fun openRenderer(fd: ParcelFileDescriptor): PdfRenderer = try {
        // The renderer owns (and closes) what it is given; the caller keeps fd.
        PdfRenderer(fd.dup())
    } catch (e: SecurityException) {
        throw ImportException(Reason.PASSWORD, "This PDF is password-protected", e)
    } catch (e: IOException) {
        throw ImportException(Reason.DAMAGED, "This PDF is damaged or not a PDF", e)
    } catch (e: RuntimeException) {
        throw ImportException(Reason.DAMAGED, "This PDF is damaged or not a PDF", e)
    }

    /**
     * Renders every page of the open PDF into [dir] as `page_000.jpg`… with one renderer.
     * Throws a classified [ImportException]; never returns with pages missing.
     */
    suspend fun renderAll(
        fd: ParcelFileDescriptor,
        sourceBytes: Long,
        dir: File,
        freeBytes: Long,
        dpi: Float,
        maxDim: Int,
        quality: Int,
        onPage: () -> Unit = {}
    ): Int {
        val source = PdfRendererSource(fd, dpi, maxDim, quality)
        return source.use {
            val count = source.pageCount()
            if (count <= 0) throw ImportException(Reason.EMPTY, "This PDF has no pages")
            if (count > MAX_PAGES) {
                throw ImportException(Reason.TOO_MANY_PAGES, "This PDF has $count pages", pages = count)
            }
            renderPages(source, dir, (0 until count).toList(), freeBytes) { _, _ -> onPage() }
            count
        }
    }

    /**
     * Renders only [indices] (0-based, in the order given) into [dir] as `page_000.jpg`…, one page
     * at a time, and reports progress. Memory holds one rendered page; cancellation is honoured
     * before each page and the caller removes the staging folder.
     */
    suspend fun renderPages(
        source: PageSource,
        dir: File,
        indices: List<Int>,
        freeBytes: Long,
        onPage: (done: Int, total: Int) -> Unit = { _, _ -> }
    ) {
        val count = source.pageCount()
        if (count <= 0) throw ImportException(Reason.EMPTY, "This PDF has no pages")
        if (indices.isEmpty()) throw ImportException(Reason.EMPTY, "No pages selected")
        if (indices.any { it !in 0 until count }) {
            throw ImportException(Reason.DAMAGED, "A chosen page is outside this PDF", pages = count)
        }
        if (freeBytes < indices.size * EST_PAGE_BYTES + SPACE_MARGIN) {
            throw ImportException(
                Reason.NO_SPACE, "Not enough storage for ${indices.size} pages", pages = count
            )
        }
        indices.forEachIndexed { done, pageIndex ->
            currentCoroutineContext().ensureActive()
            val jpeg = try {
                source.render(pageIndex)
            } catch (e: OutOfMemoryError) {
                throw ImportException(
                    Reason.DAMAGED, "Page ${pageIndex + 1} is too large to render", IOException(e),
                    pages = count, page = pageIndex + 1
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                throw ImportException(
                    Reason.DAMAGED, "Page ${pageIndex + 1} of $count could not be read", e,
                    pages = count, page = pageIndex + 1
                )
            }
            try {
                Durable.writeFile(File(dir, String.format(Locale.ROOT, "page_%03d.jpg", done)), jpeg)
            } catch (e: IOException) {
                throw classifyWrite(e)
            }
            onPage(done + 1, indices.size)
        }
    }

    private fun renderPage(r: PdfRenderer, index: Int, dpi: Float, maxDim: Int, quality: Int): ByteArray =
        r.openPage(index).use { page ->
            var scale = dpi / 72f
            val longest = maxOf(page.width, page.height).coerceAtLeast(1)
            if (longest * scale > maxDim) scale = maxDim.toFloat() / longest
            val w = (page.width * scale).roundToInt().coerceIn(1, maxDim)
            val h = (page.height * scale).roundToInt().coerceIn(1, maxDim)
            val bitmap = androidx.core.graphics.createBitmap(w, h)
            try {
                Canvas(bitmap).drawColor(Color.WHITE)
                val matrix = Matrix().apply {
                    setScale(w.toFloat() / page.width.coerceAtLeast(1), h.toFloat() / page.height.coerceAtLeast(1))
                }
                page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                ImageTools.encodeJpeg(bitmap, quality)
            } finally {
                bitmap.recycle()
            }
        }

    /** A full disk or a failed write. */
    fun classifyWrite(e: Throwable): ImportException {
        if (e is ImportException) return e
        val msg = generateSequence(e) { it.cause }.mapNotNull { it.message }.joinToString(" ")
        return if (msg.contains("ENOSPC") || msg.contains("No space", ignoreCase = true) ||
            e is Durable.SyncException
        ) {
            ImportException(Reason.NO_SPACE, "Not enough storage to finish the import", e)
        } else {
            ImportException(Reason.SAVE_FAILED, "Could not save the imported pages", e)
        }
    }

    /** `%PDF-` must appear in the first KiB (the spec allows leading junk). */
    internal fun looksLikePdf(head: ByteArray, len: Int): Boolean {
        val sig = "%PDF-".toByteArray(Charsets.US_ASCII)
        if (len < sig.size) return false
        outer@ for (i in 0..len - sig.size) {
            for (j in sig.indices) if (head[i + j] != sig[j]) continue@outer
            return true
        }
        return false
    }

    private fun declaredSize(context: Context, uri: Uri): Long? = try {
        if (uri.scheme == "file") {
            uri.path?.let { File(it).length() }?.takeIf { it > 0 }
        } else {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) c.getLong(idx).takeIf { it > 0 } else null
            }
        }
    } catch (_: Exception) {
        null // unknown size: the copy loop still enforces the limit
    }
}
