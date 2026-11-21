package com.scanwhirl.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.RenderParams
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.annotation.RequiresApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileInputStream
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

    /**
     * Rough upper estimate of one rendered page on disk: the staging JPEG **and** the copy of
     * that JPEG embedded in `scan.pdf`. Mirrors the save path's `sources * 2`.
     */
    internal const val EST_PAGE_BYTES: Long = PdfImportBudget.TYPICAL_PAGE_BYTES * PdfImportBudget.DISK_COPIES

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
            throw ImportException(Reason.TOO_LARGE, "PDF is larger than ${maxBytes / (1024 * 1024)} MB")
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
                                throw ImportException(Reason.TOO_LARGE, "PDF is larger than ${maxBytes / (1024 * 1024)} MB")
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
        // Read before the renderer takes the descriptor: the seekable copy is already cached and
        // bounded, and an unreadable page tree simply leaves every rotation at zero (old behaviour).
        private val rotations: List<Int> = PdfImport.readPageRotations(fd)
        private val renderer = openRenderer(fd)

        override fun pageCount(): Int = renderer.pageCount

        override fun render(index: Int): ByteArray = renderPage(
            renderer, index, dpi, maxDim, quality, rotations.getOrElse(index) { 0 }
        )

        override fun close() = renderer.close()
    }

    /** Reads the seekable copy once and returns each page's effective /Rotate, or empty on doubt. */
    private fun readPageRotations(fd: ParcelFileDescriptor): List<Int> = try {
        val dup = fd.dup()
        val bytes = FileInputStream(dup.fileDescriptor).use { it.readBytes() }
        // The dup shares the file offset with the caller's fd; rewind before PdfRenderer opens it.
        try {
            android.system.Os.lseek(fd.fileDescriptor, 0L, android.system.OsConstants.SEEK_SET)
        } catch (_: Exception) {
        }
        parsePageRotations(bytes)
    } catch (_: Throwable) {
        emptyList()
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

    /** API level from which [RenderParams] can ask the renderer to draw annotations/forms. */
    internal const val ANNOTATION_API_LEVEL = 35

    internal fun useAnnotatedRender(api: Int): Boolean = api >= ANNOTATION_API_LEVEL

    /** Output box and the matrix the renderer receives; a quarter turn swaps width and height. */
    internal data class RenderPlan(val width: Int, val height: Int, val matrix: Matrix)

    internal fun renderPlan(
        pageWidth: Int, pageHeight: Int, rotation: Int, dpi: Float, maxDim: Int
    ): RenderPlan {
        val quarter = (((rotation % 360) + 360) % 360) / 90
        val turned = quarter % 2 == 1
        val pw = pageWidth.coerceAtLeast(1)
        val ph = pageHeight.coerceAtLeast(1)
        var scale = dpi / 72f
        val longest = maxOf(pw, ph).toFloat()
        if (longest * scale > maxDim) scale = maxDim.toFloat() / longest
        val w = (pw * scale).roundToInt().coerceIn(1, maxDim)
        val h = (ph * scale).roundToInt().coerceIn(1, maxDim)
        val outW = if (turned) h else w
        val outH = if (turned) w else h
        val matrix = Matrix().apply {
            setScale(scale, scale)
            // Rotate about the page's centre, then slide the turned box into the output bitmap.
            postRotate(quarter * 90f, pw * scale / 2f, ph * scale / 2f)
            postTranslate(outW / 2f - pw * scale / 2f, outH / 2f - ph * scale / 2f)
        }
        return RenderPlan(outW, outH, matrix)
    }

    private fun renderPage(
        r: PdfRenderer, index: Int, dpi: Float, maxDim: Int, quality: Int, rotation: Int
    ): ByteArray = r.openPage(index).use { page ->
        val plan = renderPlan(page.width, page.height, rotation, dpi, maxDim)
        val bitmap = androidx.core.graphics.createBitmap(plan.width, plan.height)
        try {
            Canvas(bitmap).drawColor(Color.WHITE)
            if (Build.VERSION.SDK_INT >= 35 && useAnnotatedRender(Build.VERSION.SDK_INT)) {
                try {
                    renderAnnotated(page, bitmap, plan.matrix)
                } catch (e: Throwable) {
                    if (e is OutOfMemoryError) throw e
                    // PDF is a mainline module: some API-35 devices still ship a module without
                    // RenderParams. Fall back to the honest legacy render rather than lose the page.
                    page.render(bitmap, null, plan.matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            } else {
                page.render(bitmap, null, plan.matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
            ImageTools.encodeJpeg(bitmap, quality)
        } finally {
            bitmap.recycle()
        }
    }

    @RequiresApi(ANNOTATION_API_LEVEL)
    @android.annotation.SuppressLint("NewApi") // guarded above + legacy fallback; PDF module needs ext 13.
    private fun renderAnnotated(page: PdfRenderer.Page, bitmap: Bitmap, matrix: Matrix) {
        val params = RenderParams.Builder(RenderParams.RENDER_MODE_FOR_DISPLAY)
            .setRenderFlags(
                RenderParams.FLAG_RENDER_HIGHLIGHT_ANNOTATIONS or
                    RenderParams.FLAG_RENDER_TEXT_ANNOTATIONS
            )
            .build()
        page.render(bitmap, null, matrix, params)
    }

    // ---- Bounded PDF page-tree walk for /Rotate (no native renderer needed) ----

    private fun isDigit(b: ByteArray, i: Int): Boolean = (b[i].toInt() and 0xFF) in 0x30..0x39

    private fun isWs(b: ByteArray, i: Int): Boolean = (b[i].toInt() and 0xFF).let {
        it == 0x20 || it == 0x09 || it == 0x0A || it == 0x0D || it == 0x0C || it == 0x00
    }

    private fun skipWs(b: ByteArray, from: Int, limit: Int): Int {
        var i = from
        while (i < limit && isWs(b, i)) i++
        return i
    }

    private fun asciiInt(b: ByteArray, from: Int, to: Int): Int? {
        if (from >= to) return null
        var value = 0
        for (i in from until to) {
            val digit = (b[i].toInt() and 0xFF) - 0x30
            if (digit !in 0..9) return null
            value = value * 10 + digit
            if (value > 1_000_000_000) return null
        }
        return value
    }

    private fun startsWith(b: ByteArray, at: Int, limit: Int, text: String): Boolean {
        if (at < 0 || at + text.length > limit) return false
        for (i in text.indices) if (b[at + i] != text[i].code.toByte()) return false
        return true
    }

    private fun indexOf(b: ByteArray, needle: String, from: Int, limit: Int): Int {
        for (i in from..(limit - needle.length)) if (startsWith(b, i, limit, needle)) return i
        return -1
    }

    /** Object dictionary text with any `stream … endstream` payload blanked out. */
    private fun dictText(b: ByteArray, range: IntRange): String {
        val raw = String(b, range.first, range.last - range.first + 1, Charsets.ISO_8859_1)
        var out: StringBuilder? = null
        var pos = 0
        while (true) {
            val start = raw.indexOf("stream", pos)
            if (start < 0) break
            val end = raw.indexOf("endstream", start)
            if (end < 0) break
            if (out == null) out = StringBuilder(raw)
            for (k in start until minOf(end + 9, raw.length)) out.setCharAt(k, ' ')
            pos = end + 9
        }
        return out?.toString() ?: raw
    }

    private fun skipWs(s: String, from: Int): Int {
        var i = from
        while (i < s.length && s[i].isWhitespace()) i++
        return i
    }

    private fun intAfter(text: String, key: String): Int? {
        var pos = 0
        while (true) {
            val k = text.indexOf(key, pos)
            if (k < 0) return null
            val after = k + key.length
            if (after < text.length && !text[after].isWhitespace() && text[after] !in "/[]<>()") {
                pos = after
                continue
            }
            var i = skipWs(text, after)
            var negative = false
            if (i < text.length && (text[i] == '-' || text[i] == '+')) {
                negative = text[i] == '-'
                i++
            }
            val start = i
            while (i < text.length && text[i].isDigit()) i++
            if (i > start) {
                val value = text.substring(start, i).toIntOrNull() ?: return null
                return if (negative) -value else value
            }
            pos = after
        }
    }

    private fun refAfter(text: String, key: String): Int? {
        var pos = 0
        while (true) {
            val k = text.indexOf(key, pos)
            if (k < 0) return null
            var i = skipWs(text, k + key.length)
            val a = i
            while (i < text.length && text[i].isDigit()) i++
            val obj = text.substring(a, i).toIntOrNull()
            i = skipWs(text, i)
            val g = i
            while (i < text.length && text[i].isDigit()) i++
            i = skipWs(text, i)
            if (obj != null && i > g && i < text.length && text[i] == 'R') return obj
            pos = k + key.length
        }
    }

    private fun refsInArray(text: String, key: String): List<Int> {
        val k = text.indexOf(key)
        if (k < 0) return emptyList()
        var i = text.indexOf('[', k + key.length)
        if (i < 0) return emptyList()
        i++
        val out = ArrayList<Int>()
        while (i < text.length) {
            i = skipWs(text, i)
            if (i >= text.length || text[i] == ']') break
            val a = i
            while (i < text.length && text[i].isDigit()) i++
            if (i == a) {
                i++
                continue
            }
            val obj = text.substring(a, i).toIntOrNull()
            val g = skipWs(text, i)
            var j = g
            while (j < text.length && text[j].isDigit()) j++
            val r = skipWs(text, j)
            if (obj != null && j > g && r < text.length && text[r] == 'R') {
                out += obj
                i = r + 1
            } else {
                i = a + 1
            }
        }
        return out
    }

    /**
     * Walks the page tree of an already-copied seekable PDF and returns each leaf page's effective
     * rotation (own `/Rotate` overrides the inherited one), in page order. Bounded by object count,
     * depth and [MAX_PAGES]; any malformed cycle or missing object fails safe with an empty list so
     * the caller keeps the previous, unrotated behaviour.
     */
    internal fun parsePageRotations(bytes: ByteArray): List<Int> {
        if (bytes.size < 8) return emptyList()
        return try {
            val limit = minOf(bytes.size, MAX_BYTES.toInt())
            val objects = HashMap<Int, IntRange>()
            var i = 0
            var found = 0
            while (i < limit && found < 200_000) {
                if (!isDigit(bytes, i)) {
                    i++
                    continue
                }
                val numStart = i
                while (i < limit && isDigit(bytes, i)) i++
                val objNum = asciiInt(bytes, numStart, i) ?: run { i = numStart + 1; continue }
                var p = skipWs(bytes, i, limit)
                val genStart = p
                while (p < limit && isDigit(bytes, p)) p++
                if (p == genStart) {
                    i = numStart + 1
                    continue
                }
                p = skipWs(bytes, p, limit)
                if (!startsWith(bytes, p, limit, "obj")) {
                    i = numStart + 1
                    continue
                }
                val body = p + 3
                val end = indexOf(bytes, "endobj", body, limit)
                if (end < 0) break
                objects.putIfAbsent(objNum, body until end)
                i = end + 6
                found++
            }
            if (objects.isEmpty()) return emptyList()

            val catalog = objects.entries.firstOrNull { dictText(bytes, it.value).contains("/Type /Catalog") }
                ?: return emptyList()
            val root = refAfter(dictText(bytes, catalog.value), "/Pages") ?: return emptyList()

            val result = ArrayList<Int>()
            val visited = HashSet<Int>()
            fun walk(number: Int, inherited: Int, depth: Int) {
                if (depth > 64 || !visited.add(number)) throw IllegalArgumentException("cyclic page tree")
                val text = dictText(bytes, objects[number] ?: throw IllegalArgumentException("missing object"))
                val own = intAfter(text, "/Rotate")
                val effective = if (own != null && own % 90 == 0) ((own % 360) + 360) % 360 else inherited
                val isPages = text.contains("/Type /Pages") || text.contains("/Type/Pages")
                when {
                    isPages -> refsInArray(text, "/Kids").forEach { walk(it, effective, depth + 1) }
                    text.contains("/Type /Page") || text.contains("/Type/Page") -> {
                        if (result.size > MAX_PAGES) throw IllegalArgumentException("too many pages")
                        result += effective
                    }
                    else -> throw IllegalArgumentException("not a page")
                }
            }
            walk(root, 0, 0)
            result.toList()
        } catch (_: Throwable) {
            emptyList()
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
