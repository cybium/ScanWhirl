package com.scanwhirl.app.pdf.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Looper
import android.os.Process
import com.scanwhirl.app.util.Durable
import com.scanwhirl.app.util.PdfDecryptor
import com.scanwhirl.app.util.PdfEncryptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream.AppendMode
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.rendering.ImageType
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * PDFBox page-object engine. One private worker thread; no PDFBox class is loaded
 * on cold start. Bouncy Castle is absent: every password operation is delegated to
 * the app's pure-JCA PdfEncryptor/PdfDecryptor, and an encrypted file is NEVER
 * passed to PDDocument.load.
 */
class PdfBoxEngine(
    private val context: Context,
    workDir: File = File(context.applicationContext.filesDir, "pdf-engine-private"),
    private val budgets: PdfEngineBudgets = PdfEngineBudgets(),
    private val enforceWorkerThread: Boolean = true
) : PdfEngine {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "scanwhirl-pdf-engine").apply { isDaemon = true }
    }
    private val cancelled = AtomicBoolean(false)
    private val sequence = AtomicLong(0)
    private val engineId = "${Process.myPid()}-${System.nanoTime()}"
    private val dir = workDir.apply { mkdirs() }
    private val opTemps = ThreadLocal<MutableList<File>>()
    private val retained = Collections.synchronizedList(mutableListOf<File>())
    private var ready = false

    override val isCancelled: Boolean get() = cancelled.get()

    init {
        synchronized(activeEngines) { activeEngines.add(engineId) }
        sweep()
    }

    /**
     * Removes plaintext staging not owned by a live engine in this process. A second
     * engine can never delete the first engine's handle plaintext.
     */
    private fun sweep() {
        val owned = synchronized(activeEngines) { activeEngines.toSet() }
        context.filesDir.listFiles { f ->
            f.name.startsWith(TEMP_PREFIX) && owned.none { id -> f.name.contains("-$id-") }
        }?.forEach { it.delete() }
        dir.listFiles()?.forEach { it.delete() }
    }

    /** Test-only visibility for the encryption gate's three-state answer. */
    internal fun encryptionProbeName(raw: ByteArray): String = encryptionProbe(raw).name

    /** Test-only visibility for this engine's temp-hygiene assertions. */
    internal fun stagedTempPaths(): List<File> =
        (context.filesDir.listFiles { f ->
            f.name.startsWith(TEMP_PREFIX) && f.name.contains("-$engineId-")
        }?.toList().orEmpty() + (dir.listFiles()?.toList().orEmpty())).sortedBy { it.name }

    private fun temp(name: String): File {
        val fileName = "$TEMP_PREFIX$engineId-${sequence.incrementAndGet()}-$name.pdf"
        // MODE_PRIVATE creates 0600; never use writeBytes/default umask for plaintext.
        context.openFileOutput(fileName, Context.MODE_PRIVATE).use { }
        val file = File(context.filesDir, fileName)
        opTemps.get()?.add(file)
        return file
    }

    /** Keeps an intentionally long-lived (handle-owned) decrypt temp out of op cleanup. */
    private fun retain(file: File) {
        opTemps.get()?.remove(file)
        retained.add(file)
    }

    private fun <T> execute(block: () -> T): T {
        if (executor.isShutdown) throw PdfEngineError.Cancelled()
        if (enforceWorkerThread && Looper.myLooper() == Looper.getMainLooper()) {
            throw IllegalStateException("PdfEngine must not be called on the main thread")
        }
        return try {
            executor.submit(Callable {
                // The registry must live on the worker: temp() runs inside this Callable.
                val temps = mutableListOf<File>()
                opTemps.set(temps)
                try {
                    if (cancelled.get()) throw PdfEngineError.Cancelled()
                    block()
                } finally {
                    temps.forEach { it.delete() }
                    opTemps.remove()
                }
            }).get()
        } catch (e: CancellationException) {
            throw PdfEngineError.Cancelled()
        } catch (e: ExecutionException) {
            throw mapError(e.cause ?: e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw PdfEngineError.Cancelled()
        }
    }

    private fun mapError(t: Throwable): PdfEngineError = when (t) {
        is PdfEngineError -> t
        is PdfDecryptor.WrongPassword -> PdfEngineError.InvalidPassword()
        is PdfDecryptor.UnsupportedScheme -> PdfEngineError.UnsupportedFeature(
            t.message ?: "a password format ScanWhirl cannot open"
        )
        is PdfDecryptor.Unsupported, is PdfDecryptor.CannotRewrite ->
            PdfEngineError.MalformedDocument(t.message ?: "unreadable password protection")
        is Durable.SyncException -> PdfEngineError.Io(t)
        is IOException -> PdfEngineError.MalformedDocument(t.message ?: "unreadable PDF")
        is IllegalArgumentException, is IndexOutOfBoundsException ->
            PdfEngineError.MalformedDocument(t.message ?: "invalid input")
        else -> PdfEngineError.Io(t)
    }

    private fun ensureReady() {
        if (!ready) {
            PDFBoxResourceLoader.init(context.applicationContext)
            if (!PDFBoxResourceLoader.isReady()) {
                throw PdfEngineError.VerificationFailed("PDFBox resource loader")
            }
            sweep()
            ready = true
        }
    }

    private fun checkCancelled() {
        if (cancelled.get()) throw PdfEngineError.Cancelled()
    }

    private fun deadline(started: Long) {
        if ((System.nanoTime() - started) / 1_000_000L > budgets.wallTimeMs) {
            throw PdfEngineError.LimitExceeded("wall time")
        }
    }

    private fun checkedInput(file: File, started: Long = System.nanoTime()): File {
        checkCancelled()
        deadline(started)
        if (!file.isFile || file.length() <= 0L) {
            throw PdfEngineError.MalformedDocument("the source file is missing or empty")
        }
        if (file.length() > budgets.maxInputBytes) throw PdfEngineError.LimitExceeded("input bytes")
        return file
    }

    private enum class EncryptionProbe { Absent, Present, Unknown }

    /**
     * Decides whether a file carries an /Encrypt key before any decryptor work.
     *
     * Section selection mirrors PDFBox 2.0.27's lenient COSParser: the last `%%EOF` in
     * its 2048-byte lookup window, then the last `startxref` before that marker. Bytes
     * after that `%%EOF` that are not whitespace make the result Unknown, as does any
     * section that is not a real xref table or a structurally valid `/Type /XRef`
     * stream. A backward dictionary scan then flags any dictionary (classic trailer or
     * xref stream) carrying an /Encrypt key anywhere in the file, which is what PDFBox's
     * lenient `rebuildTrailer()` recovery can re-attach to the trailer.
     *
     * Absent therefore means: the PDFBox-selected section chain has no /Encrypt and no
     * dictionary in the file has one either. Present means the app decryptor must run.
     * Unknown means neither could be proved, and the caller runs PdfDecryptor, whose own
     * parser either types the refusal or confirms there is no encryption.
     */
    private fun encryptionProbe(raw: ByteArray): EncryptionProbe {
        val eof = lastEofMarker(raw) ?: return EncryptionProbe.Unknown
        for (i in eof + EOF_MARKER.length until raw.size) {
            if (!isPdfWhitespace(raw[i])) return EncryptionProbe.Unknown
        }
        val marker = lastStartxrefBefore(raw, eof) ?: return EncryptionProbe.Unknown
        val first = trailerOffset(raw, marker + STARTXREF.length) ?: return EncryptionProbe.Unknown
        val chain = probeTrailerChain(raw, first)
        if (chain == EncryptionProbe.Present) return EncryptionProbe.Present

        val dictionaries = scanEncryptDictionaries(raw)
        if (dictionaries == EncryptionProbe.Present) return EncryptionProbe.Present
        val trailers = scanMalformedTrailers(raw)
        if (trailers == EncryptionProbe.Present) return EncryptionProbe.Present
        if (chain == EncryptionProbe.Unknown || dictionaries == EncryptionProbe.Unknown ||
            trailers == EncryptionProbe.Unknown
        ) {
            return EncryptionProbe.Unknown
        }
        return EncryptionProbe.Absent
    }

    /** Last `%%EOF` inside PDFBox's 2048-byte trailing lookup window. */
    private fun lastEofMarker(raw: ByteArray): Int? {
        val from = maxOf(0, raw.size - EOF_LOOKUP_BYTES)
        var i = raw.size - EOF_MARKER.length
        while (i >= from) {
            if (matchesAt(raw, i, EOF_MARKER)) return i
            i--
        }
        return null
    }

    /** Last `startxref` strictly before [limit] (the effective `%%EOF`). */
    private fun lastStartxrefBefore(raw: ByteArray, limit: Int): Int? {
        var i = limit - STARTXREF.length
        while (i >= 0) {
            if (matchesAt(raw, i, STARTXREF)) return i
            i--
        }
        return null
    }

    private fun matchesAt(raw: ByteArray, at: Int, text: String): Boolean {
        if (at < 0 || at + text.length > raw.size) return false
        for (i in text.indices) if (raw[at + i] != text[i].code.toByte()) return false
        return true
    }

    private fun isPdfWhitespace(byte: Byte): Boolean {
        val c = byte.toInt() and 0xFF
        return c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32
    }

    private fun trailerOffset(raw: ByteArray, from: Int): Int? {
        val c = ProbeCursor(raw, from)
        c.skip()
        val value = c.readLong() ?: return null
        return if (value > 0 && value < raw.size) value.toInt() else null
    }

    private fun probeTrailerChain(raw: ByteArray, first: Int): EncryptionProbe {
        val pending = ArrayDeque<Int>()
        pending.addLast(first)
        val visited = HashSet<Int>()
        while (pending.isNotEmpty()) {
            if (visited.size >= MAX_PROBE_SECTIONS) return EncryptionProbe.Unknown
            val offset = pending.removeFirst()
            if (offset <= 0 || offset >= raw.size) return EncryptionProbe.Unknown
            if (!visited.add(offset)) continue
            val section = readTrailerSection(raw, offset) ?: return EncryptionProbe.Unknown
            if (section.hasEncrypt) return EncryptionProbe.Present
            section.links.forEach { pending.addLast(it) }
        }
        return EncryptionProbe.Absent
    }

    /**
     * Lexical scan for any dictionary with a top-level /Encrypt key, outside strings and
     * comments. PDFBox's lenient trailer rebuild scans the whole file for a `trailer`
     * dictionary and copies /Encrypt from it, so trusting only the startxref section
     * would leave that recovery route open.
     */
    private fun scanEncryptDictionaries(raw: ByteArray): EncryptionProbe {
        val c = ProbeCursor(raw, 0)
        var examined = 0
        while (true) {
            c.skip()
            if (c.pos >= c.size) return EncryptionProbe.Absent
            when {
                c.peek() == '('.code -> {
                    val at = c.pos
                    // A literal that never closes is binary data, not a PDF string.
                    if (!skipProbeLiteral(c)) c.pos = at + 1
                }
                c.peek() == '<'.code && c.peekNext() == '<'.code -> {
                    if (++examined > MAX_PROBE_DICTIONARIES) return EncryptionProbe.Unknown
                    val at = c.pos
                    val entries = readProbeDictionary(c, 0)
                    if (entries == null) {
                        c.pos = at + 2
                    } else if (entries.containsKey("Encrypt")) {
                        return EncryptionProbe.Present
                    }
                }
                c.peek() == '<'.code -> {
                    val at = c.pos
                    if (!skipProbeHex(c)) c.pos = at + 1
                }
                c.peek() == '/'.code -> {
                    if (c.readName() == null) c.pos++
                }
                else -> {
                    val before = c.pos
                    c.readToken()
                    if (c.pos == before) c.pos++
                }
            }
        }
    }

    /**
     * A `trailer` keyword followed by a dictionary that cannot be parsed leaves the
     * question open: PDFBox's repair may still read it. Parsed dictionaries are handled
     * by [scanEncryptDictionaries]; this pass only adds the fail-closed Unknown.
     */
    private fun scanMalformedTrailers(raw: ByteArray): EncryptionProbe {
        var before = raw.size
        var examined = 0
        while (true) {
            val at = lastTokenBefore(raw, TRAILER_MARKER, before)
            if (at < 0) return EncryptionProbe.Absent
            before = at
            val c = ProbeCursor(raw, at + TRAILER_MARKER.length)
            c.skip()
            if (c.pos >= c.size || c.peek() != '<'.code || c.peekNext() != '<'.code) continue
            if (++examined > MAX_PROBE_TRAILERS) return EncryptionProbe.Unknown
            val entries = readProbeDictionary(c, 0) ?: return EncryptionProbe.Unknown
            if (entries.containsKey("Encrypt")) return EncryptionProbe.Present
        }
    }

    private fun lastTokenBefore(raw: ByteArray, text: String, before: Int): Int {
        var i = minOf(before, raw.size) - text.length
        while (i >= 0) {
            if (matchesAt(raw, i, text)) return i
            i--
        }
        return -1
    }

    private class TrailerSection(val hasEncrypt: Boolean, val links: List<Int>)

    /** Parses one xref section: either a classic table plus trailer, or one xref stream object. */
    private fun readTrailerSection(raw: ByteArray, offset: Int): TrailerSection? {
        val c = ProbeCursor(raw, offset)
        c.skip()
        val entries: Map<String, ProbeValue>
        if (c.word("xref")) {
            if (!skipProbeXrefTable(c)) return null
            c.skip()
            entries = readProbeDictionary(c, 0) ?: return null
        } else {
            // startxref points at an xref stream object: `<number> <gen> obj << … >>`.
            if (c.readLong() == null) return null
            c.skip()
            if (c.readLong() == null) return null
            c.skip()
            if (!c.word("obj")) return null
            c.skip()
            entries = readProbeDictionary(c, 0) ?: return null
            if (!isGenuineXrefStream(c, entries)) return null
        }
        val links = ArrayList<Int>(2)
        for (name in listOf("Prev", "XRefStm")) {
            if (!entries.containsKey(name)) continue
            val link = entries[name]?.integer ?: return null
            if (link <= 0 || link >= raw.size) return null
            links += link.toInt()
        }
        return TrailerSection(entries.containsKey("Encrypt"), links)
    }

    /**
     * A non-classic startxref target must be a real xref stream, not any dictionary
     * object: `/Type /XRef`, a 3-number `/W`, a direct `/Length`, and a `stream` body
     * closed by `endstream`. Anything else is Unknown.
     */
    private fun isGenuineXrefStream(c: ProbeCursor, entries: Map<String, ProbeValue>): Boolean {
        if (entries["Type"]?.name != "XRef") return false
        val widths = entries["W"]?.array ?: return false
        if (widths.size != 3) return false
        var total = 0L
        for (width in widths) {
            val value = width.integer ?: return false
            if (value < 0 || value > 8) return false
            total += value
        }
        if (total <= 0) return false
        val length = entries["Length"]?.integer ?: return false
        if (length < 0 || length > c.size) return false
        c.skip()
        if (!c.word("stream")) return false
        if (c.pos >= c.size) return false
        if (c.peek() == '\r'.code) {
            c.pos++
            if (c.pos < c.size && c.peek() == '\n'.code) c.pos++
        } else if (c.peek() == '\n'.code) {
            c.pos++
        } else {
            return false
        }
        val bodyEnd = c.pos.toLong() + length
        if (bodyEnd > c.size) return false
        c.pos = bodyEnd.toInt()
        c.skip()
        return c.word("endstream")
    }

    private fun skipProbeXrefTable(c: ProbeCursor): Boolean {
        var rows = 0L
        while (true) {
            c.skip()
            if (c.pos >= c.size) return false
            if (c.atWord("trailer")) {
                c.pos += "trailer".length
                return true
            }
            val first = c.readLong() ?: return false
            c.skip()
            val count = c.readLong() ?: return false
            if (first < 0 || count < 0 || count > MAX_PROBE_XREF_ROWS) return false
            rows += count
            if (rows > MAX_PROBE_XREF_ROWS) return false
            var row = 0L
            while (row < count) {
                c.skip()
                if (c.readLong() == null) return false
                c.skip()
                if (c.readLong() == null) return false
                c.skip()
                val type = c.readToken()
                if (type != "n" && type != "f") return false
                row++
            }
        }
    }

    /** Top-level dictionary entries; a nested `/Encrypt` is a value, never a trailer key. */
    private fun readProbeDictionary(c: ProbeCursor, depth: Int): Map<String, ProbeValue>? {
        if (depth > MAX_PROBE_DEPTH) return null
        c.skip()
        if (c.peek() != '<'.code || c.peekNext() != '<'.code) return null
        c.pos += 2
        val entries = HashMap<String, ProbeValue>()
        while (true) {
            c.skip()
            if (c.pos >= c.size) return null
            if (c.peek() == '>'.code && c.peekNext() == '>'.code) {
                c.pos += 2
                return entries
            }
            if (c.peek() != '/'.code) return null
            val key = c.readName() ?: return null
            val value = skipProbeValue(c, depth + 1) ?: return null
            if (entries.size >= MAX_PROBE_KEYS) return null
            entries[key] = value
        }
    }

    private class ProbeValue(
        val integer: Long? = null,
        val name: String? = null,
        val array: List<ProbeValue>? = null
    )

    /**
     * Skips any PDF value, honouring lexical rules: a `%` inside a literal or hex
     * string is content, not a comment, so it can never hide the rest of the trailer.
     */
    private fun skipProbeValue(c: ProbeCursor, depth: Int): ProbeValue? {
        if (depth > MAX_PROBE_DEPTH) return null
        c.skip()
        if (c.pos >= c.size) return null
        when (c.peek()) {
            '('.code -> return if (skipProbeLiteral(c)) ProbeValue() else null
            '<'.code -> {
                if (c.peekNext() == '<'.code) {
                    return if (readProbeDictionary(c, depth + 1) != null) ProbeValue() else null
                }
                return if (skipProbeHex(c)) ProbeValue() else null
            }
            '['.code -> {
                c.pos++
                val items = ArrayList<ProbeValue>()
                while (true) {
                    c.skip()
                    if (c.pos >= c.size) return null
                    if (c.peek() == ']'.code) {
                        c.pos++
                        return ProbeValue(array = items)
                    }
                    if (items.size >= MAX_PROBE_ARRAY) return null
                    items += skipProbeValue(c, depth + 1) ?: return null
                }
            }
            '/'.code -> {
                val name = c.readName() ?: return null
                return ProbeValue(name = name)
            }
            else -> {
                val token = c.readToken()
                if (token.isEmpty()) return null
                if (token.length <= 10 && token.all { it in '0'..'9' }) {
                    val after = c.pos
                    c.skip()
                    if (c.readLong() != null) {
                        c.skip()
                        if (c.word("R")) return ProbeValue() // a reference, not a plain integer
                    }
                    c.pos = after
                    return ProbeValue(token.toLongOrNull())
                }
                return ProbeValue()
            }
        }
    }

    private fun skipProbeLiteral(c: ProbeCursor): Boolean {
        if (c.peek() != '('.code) return false
        val start = c.pos
        c.pos++
        var depth = 1
        while (c.pos < c.size) {
            if (c.pos - start > MAX_PROBE_LITERAL) return false
            val ch = c.raw[c.pos].toInt() and 0xFF
            c.pos++
            when {
                ch == '\\'.code -> {
                    if (c.pos >= c.size) return false
                    c.pos++
                }
                ch == '('.code -> depth++
                ch == ')'.code -> {
                    depth--
                    if (depth == 0) return true
                }
            }
        }
        return false
    }

    private fun skipProbeHex(c: ProbeCursor): Boolean {
        if (c.peek() != '<'.code) return false
        c.pos++
        while (c.pos < c.size) {
            val ch = c.raw[c.pos].toInt() and 0xFF
            c.pos++
            if (ch == '>'.code) return true
        }
        return false
    }

    /**
     * Plain files skip the decryptor entirely; encrypted and unparseable files go through
     * it, and a genuinely unsupported scheme is a typed refusal before PDFBox sees bytes.
     */
    private fun decryptToTemp(raw: ByteArray, password: String): File? {
        val probe = encryptionProbe(raw)
        if (probe == EncryptionProbe.Absent) return null
        val plain = try {
            PdfDecryptor.decrypt(raw, password)
        } catch (e: PdfDecryptor.WrongPassword) {
            throw PdfEngineError.InvalidPassword()
        } catch (e: PdfDecryptor.UnsupportedScheme) {
            if (e.message == NO_ENCRYPTION_MESSAGE) {
                // Only trust "no encryption" when our own probe could not parse the trailer.
                if (probe == EncryptionProbe.Unknown) return null
                throw PdfEngineError.UnsupportedFeature("an /Encrypt dictionary ScanWhirl cannot read")
            }
            // Real encrypted-but-unsupported scheme: never hand those bytes to PDFBox.
            throw PdfEngineError.UnsupportedFeature(
                e.message ?: "a password format ScanWhirl cannot open"
            )
        } catch (e: PdfDecryptor.Unsupported) {
            throw PdfEngineError.MalformedDocument(e.message ?: "unreadable password protection")
        }
        val out = temp("plain")
        Durable.writeFile(out, plain)
        return out
    }

    /** Replace without deleting the old file first; sync file and both directory entries. */
    private fun atomicReplace(from: File, to: File) {
        checkOutput(from)
        Durable.syncFile(from)
        if (!Durable.renameSeam(from, to)) {
            throw PdfEngineError.Io(IOException("Could not replace ${to.name} atomically"))
        }
        Durable.syncDir(to.parentFile ?: to.absoluteFile.parentFile)
        Durable.syncDir(from.parentFile ?: from.absoluteFile.parentFile)
    }

    private fun checkOutput(file: File) {
        if (file.length() > budgets.maxOutputBytes) throw PdfEngineError.LimitExceeded("output bytes")
    }

    /** Fails in the middle of a write, not only at publish time. */
    private inner class BoundedOutputStream(
        private val delegate: OutputStream,
        private val started: Long
    ) : OutputStream() {
        private var written = 0L
        override fun write(byte: Int) {
            count(1)
            delegate.write(byte)
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            count(length.toLong())
            delegate.write(bytes, offset, length)
        }
        private fun count(n: Long) {
            written += n
            if (written > budgets.maxOutputBytes) throw PdfEngineError.LimitExceeded("output bytes")
            deadline(started)
        }
        override fun flush() = delegate.flush()
        override fun close() = delegate.close()
    }

    /** Every write path is bounded in-flight, not only checked at publish time. */
    private fun saveBounded(doc: PDDocument, file: File, started: Long) {
        FileOutputStream(file).use { stream ->
            doc.save(BoundedOutputStream(stream, started))
        }
        deadline(started)
    }

    private fun textOf(file: File, password: String? = null, page: Int = 0): String {
        val raw = checkedInput(file).readBytes()
        val decrypted = decryptToTemp(raw, password.orEmpty())
        if (decrypted != null && password.isNullOrEmpty()) throw PdfEngineError.InvalidPassword()
        val plain = decrypted ?: file
        PDDocument.load(checkedInput(plain)).use { doc ->
            val stripper = PDFTextStripper()
            if (page > 0) {
                stripper.startPage = page
                stripper.endPage = page
            }
            return stripper.getText(doc)
        }
    }

    /** Drops carriers that could hide data on a page we otherwise keep digital. */
    private fun sanitizePage(page: PDPage) {
        val dict = page.cosObject
        for (name in listOf("Annots", "Names", "AA", "Metadata", "StructParents", "PieceInfo")) {
            dict.removeItem(COSName.getPDFName(name))
        }
    }

    // ------------------------------------------------------------------ operations

    override fun open(source: File, password: String?): PdfHandle = execute {
        ensureReady()
        val started = System.nanoTime()
        val input = checkedInput(source, started)
        val raw = input.readBytes()
        val plain = decryptToTemp(raw, password.orEmpty())?.also { retain(it) } ?: input
        PDDocument.load(checkedInput(plain, started)).use { doc ->
            if (doc.numberOfPages > budgets.maxPages) throw PdfEngineError.LimitExceeded("page count")
            deadline(started)
            PdfHandle(plain.canonicalFile, doc.numberOfPages)
        }
    }

    override fun copyPages(source: PdfHandle, indices: IntArray, destination: String): PdfHandle = execute {
        ensureReady()
        val started = System.nanoTime()
        if (indices.isEmpty()) throw PdfEngineError.MalformedDocument("no pages were selected")
        if (indices.size > budgets.maxPages) throw PdfEngineError.LimitExceeded("page count")
        val out = temp("copy")
        PDDocument.load(checkedInput(source.file, started)).use { src ->
            if (indices.any { it !in 0 until src.numberOfPages }) {
                throw PdfEngineError.MalformedDocument("page index outside the document")
            }
            PDDocument().use { doc ->
                for (index in indices) {
                    checkCancelled()
                    deadline(started)
                    doc.importPage(src.getPage(index))
                }
                saveBounded(doc, out, started)
            }
        }
        deadline(started)
        verifyPages(out, indices.size, started)
        val target = File(destination)
        atomicReplace(out, target)
        PdfHandle(target.canonicalFile, indices.size)
    }

    override fun reorder(handle: PdfHandle, order: IntArray) = execute {
        ensureReady()
        val started = System.nanoTime()
        if (order.size != handle.pageCount || order.toSet() != (0 until handle.pageCount).toSet()) {
            throw PdfEngineError.MalformedDocument("reorder needs each page exactly once")
        }
        val out = temp("reorder")
        PDDocument.load(checkedInput(handle.file, started)).use { src ->
            PDDocument().use { doc ->
                for (index in order) {
                    checkCancelled()
                    deadline(started)
                    doc.importPage(src.getPage(index))
                }
                saveBounded(doc, out, started)
            }
        }
        deadline(started)
        verifyPages(out, order.size, started)
        atomicReplace(out, handle.file)
        Unit
    }

    override fun rotate(handle: PdfHandle, pageIndex: Int, degrees: Int) = execute {
        ensureReady()
        val started = System.nanoTime()
        if (degrees % 90 != 0) throw PdfEngineError.MalformedDocument("rotation must be a multiple of 90")
        val out = temp("rotate")
        var expected = 0
        var actualPages = 0
        PDDocument.load(checkedInput(handle.file, started)).use { doc ->
            actualPages = doc.numberOfPages
            if (pageIndex !in 0 until actualPages) {
                throw PdfEngineError.MalformedDocument("page index outside the document")
            }
            val page = doc.getPage(pageIndex)
            expected = ((page.rotation + degrees) % 360 + 360) % 360
            page.rotation = expected
            saveBounded(doc, out, started)
        }
        deadline(started)
        verifyPages(out, actualPages, started)
        PDDocument.load(out).use { doc ->
            if (doc.getPage(pageIndex).rotation != expected) {
                throw PdfEngineError.VerificationFailed("rotation was not preserved")
            }
        }
        atomicReplace(out, handle.file)
        Unit
    }

    override fun delete(handle: PdfHandle, indices: IntArray) = execute {
        ensureReady()
        val started = System.nanoTime()
        val unique = indices.toSet()
        if (unique.isEmpty() || unique.any { it !in 0 until handle.pageCount }) {
            throw PdfEngineError.MalformedDocument("page index outside the document")
        }
        val out = temp("delete")
        var actualPages = 0
        PDDocument.load(checkedInput(handle.file, started)).use { doc ->
            actualPages = doc.numberOfPages
            for (index in unique.sortedDescending()) {
                checkCancelled()
                deadline(started)
                doc.removePage(index)
            }
            saveBounded(doc, out, started)
        }
        deadline(started)
        verifyPages(out, actualPages - unique.size, started)
        atomicReplace(out, handle.file)
        Unit
    }

    override fun merge(sources: List<File>, destination: String): PdfHandle = execute {
        ensureReady()
        val started = System.nanoTime()
        if (sources.size < 2) throw PdfEngineError.MalformedDocument("merge needs at least two files")
        var expected = 0
        for (source in sources) {
            PDDocument.load(checkedInput(source, started)).use { doc ->
                expected += doc.numberOfPages
                if (expected > budgets.maxPages) throw PdfEngineError.LimitExceeded("page count")
            }
        }
        val out = temp("merge")
        val merger = PDFMergerUtility().apply {
            for (source in sources) addSource(checkedInput(source, started))
        }
        FileOutputStream(out).use { stream ->
            merger.setDestinationStream(BoundedOutputStream(stream, started))
            merger.mergeDocuments(MemoryUsageSetting.setupTempFileOnly())
        }
        deadline(started)
        verifyPages(out, expected, started)
        val target = File(destination)
        atomicReplace(out, target)
        PdfHandle(target.canonicalFile, expected)
    }

    override fun split(source: PdfHandle, ranges: List<IntRange>): List<PdfHandle> = execute {
        ensureReady()
        val started = System.nanoTime()
        if (ranges.isEmpty()) throw PdfEngineError.MalformedDocument("no split ranges were given")
        val expectedTotal = ranges.sumOf { it.count() }
        if (expectedTotal > budgets.maxPages) throw PdfEngineError.LimitExceeded("page count")
        val handles = ArrayList<PdfHandle>(ranges.size)
        PDDocument.load(checkedInput(source.file, started)).use { src ->
            ranges.forEachIndexed { part, range ->
                checkCancelled()
                deadline(started)
                if (range.isEmpty() || range.first < 0 || range.last >= src.numberOfPages) {
                    throw PdfEngineError.MalformedDocument("split range outside the document")
                }
                val out = temp("split-$part")
                PDDocument().use { doc ->
                    for (index in range) {
                        checkCancelled()
                        doc.importPage(src.getPage(index))
                    }
                    saveBounded(doc, out, started)
                }
                deadline(started)
                verifyPages(out, range.count(), started)
                val target = File(source.file.parentFile,
                    "${source.file.nameWithoutExtension}-part-$part.pdf")
                atomicReplace(out, target)
                handles += PdfHandle(target.canonicalFile, range.count())
            }
        }
        handles
    }

    override fun overlay(
        handle: PdfHandle,
        pageIndex: Int,
        signaturePng: ByteArray,
        rect: PdfRect,
        text: String,
        date: String
    ) = execute {
        ensureReady()
        val started = System.nanoTime()
        if (signaturePng.size > budgets.maxInputBytes) throw PdfEngineError.LimitExceeded("overlay image bytes")
        val out = temp("overlay")
        var actualPages = 0
        PDDocument.load(checkedInput(handle.file, started)).use { doc ->
            actualPages = doc.numberOfPages
            if (pageIndex !in 0 until actualPages) {
                throw PdfEngineError.MalformedDocument("page index outside the document")
            }
            val page = doc.getPage(pageIndex)
            val resources = page.resources ?: PDResources().also { page.resources = it }
            val image = PDImageXObject.createFromByteArray(doc, signaturePng, "signature.png")
            resources.put(COSName.getPDFName("ScanWhirlSignature"), image)
            PDPageContentStream(doc, page, AppendMode.APPEND, true, true).use { cs ->
                cs.drawImage(image, rect.x, rect.y, rect.width, rect.height)
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA_BOLD, 11f)
                cs.newLineAtOffset(rect.x, rect.y + rect.height + 12f)
                cs.showText(text)
                cs.newLineAtOffset(0f, -16f)
                cs.showText(date)
                cs.endText()
            }
            saveBounded(doc, out, started)
        }
        deadline(started)
        verifyPages(out, actualPages, started)
        val verified = textOf(out, page = pageIndex + 1)
        if (!verified.contains(text) || !verified.contains(date)) {
            throw PdfEngineError.VerificationFailed("overlay text is not selectable")
        }
        atomicReplace(out, handle.file)
        Unit
    }

    override fun redact(
        source: PdfHandle,
        pageIndex: Int,
        boxes: List<PdfRect>,
        destination: String
    ): PdfHandle = execute {
        ensureReady()
        val started = System.nanoTime()
        if (pageIndex !in 0 until source.pageCount) {
            throw PdfEngineError.MalformedDocument("page index outside the document")
        }
        val out = temp("redacted")
        val png = ByteArrayOutputStream()
        PDDocument.load(checkedInput(source.file, started)).use { src ->
            checkCancelled()
            deadline(started)
            val page = src.getPage(pageIndex)
            val crop = page.cropBox
            val scale = 2f // 144 dpi
            val pixels = crop.width.toLong() * crop.height.toLong() * scale.toLong() * scale.toLong()
            if (pixels > budgets.maxRenderPixels) throw PdfEngineError.LimitExceeded("render pixels")
            val bitmap = PDFRenderer(src).renderImageWithDPI(pageIndex, 144f, ImageType.RGB)
            val canvas = Canvas(bitmap)
            val paint = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
            for (box in boxes) {
                checkCancelled()
                deadline(started)
                canvas.drawRect(
                    box.x * scale, bitmap.height - (box.y + box.height) * scale,
                    (box.x + box.width) * scale, bitmap.height - box.y * scale, paint
                )
            }
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, png)
            bitmap.recycle()

            PDDocument().use { doc ->
                val box = PDRectangle(crop.cosArray)
                val imagePage = PDPage(box)
                doc.addPage(imagePage)
                val image = PDImageXObject.createFromByteArray(doc, png.toByteArray(), "redacted.png")
                PDPageContentStream(doc, imagePage).use { cs ->
                    cs.drawImage(image, 0f, 0f, box.width, box.height)
                }
                for (i in 0 until src.numberOfPages) {
                    if (i == pageIndex) continue
                    checkCancelled()
                    deadline(started)
                    val imported = doc.importPage(src.getPage(i))
                    sanitizePage(imported)
                }
                saveBounded(doc, out, started)
            }
        }
        deadline(started)
        verifyPages(out, source.pageCount, started)
        val hiddenText = textOf(out, page = pageIndex + 1)
        if (hiddenText.trim().isNotEmpty()) {
            throw PdfEngineError.VerificationFailed("a redacted page still has a text layer")
        }
        val raw = out.readBytes().toString(Charset.forName("ISO-8859-1"))
        if (raw.contains("/Annots") || raw.contains("/Names") || Regex("%%EOF").findAll(raw).count() != 1) {
            throw PdfEngineError.VerificationFailed("redacted output retained annotations or history")
        }
        val target = File(destination)
        atomicReplace(out, target)
        PdfHandle(target.canonicalFile, source.pageCount)
    }

    override fun saveEncrypted(handle: PdfHandle, destination: File, userPassword: String) = execute {
        ensureReady()
        val started = System.nanoTime()
        if (userPassword.isEmpty()) throw PdfEngineError.InvalidPassword()
        val out = temp("protected")
        // Encryption output is about the source size: bound it before the cipher runs.
        if (handle.file.length() > budgets.maxOutputBytes) throw PdfEngineError.LimitExceeded("output bytes")
        try {
            PdfEncryptor.encrypt(handle.file, out, userPassword)
        } catch (e: IOException) {
            throw mapError(e)
        }
        deadline(started)
        Durable.syncFile(out)
        val roundTrip = decryptToTemp(out.readBytes(), userPassword)
            ?: throw PdfEngineError.VerificationFailed("encrypted output is not encrypted")
        PDDocument.load(checkedInput(roundTrip, started)).use { doc ->
            var sourcePages = 0
            PDDocument.load(checkedInput(handle.file, started)).use { source ->
                sourcePages = source.numberOfPages
            }
            if (doc.numberOfPages != sourcePages) {
                throw PdfEngineError.VerificationFailed("protected page count changed")
            }
            val text = PDFTextStripper().getText(doc)
            val original = textOf(handle.file)
            if (text.replace(Regex("\\s+"), " ") != original.replace(Regex("\\s+"), " ")) {
                throw PdfEngineError.VerificationFailed("protected text changed")
            }
        }
        deadline(started)
        checkOutput(out)
        atomicReplace(out, destination)
        Unit
    }

    override fun verifyText(file: File, password: String?): String = execute {
        ensureReady()
        val started = System.nanoTime()
        checkedInput(file, started)
        textOf(file, password)
    }

    override fun cancel() {
        cancelled.set(true)
    }

    override fun close() {
        cancelled.set(true)
        executor.shutdown()
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
        synchronized(retained) {
            retained.forEach { it.delete() }
            retained.clear()
        }
        synchronized(activeEngines) { activeEngines.remove(engineId) }
        sweep()
    }

    private fun verifyPages(file: File, expected: Int, started: Long) {
        checkCancelled()
        deadline(started)
        PDDocument.load(checkedInput(file, started)).use { doc ->
            if (doc.numberOfPages != expected) {
                throw PdfEngineError.VerificationFailed("page count ${doc.numberOfPages} != $expected")
            }
        }
    }

    companion object {
        private const val TEMP_PREFIX = "swpdf-private-"
        private const val NO_ENCRYPTION_MESSAGE = "This PDF does not use the standard password format"
        private val activeEngines = mutableSetOf<String>()
    }
}

private const val STARTXREF = "startxref"
private const val EOF_MARKER = "%%EOF"
private const val EOF_LOOKUP_BYTES = 2048
private const val TRAILER_MARKER = "trailer"
private const val MAX_PROBE_SECTIONS = 64
private const val MAX_PROBE_XREF_ROWS = 2_000_000L
private const val MAX_PROBE_KEYS = 512
private const val MAX_PROBE_DEPTH = 32
private const val MAX_PROBE_ARRAY = 1024
private const val MAX_PROBE_LITERAL = 65_536
private const val MAX_PROBE_DICTIONARIES = 100_000
private const val MAX_PROBE_TRAILERS = 256

private fun isProbeDelimiter(c: Int): Boolean = c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32 ||
    c == 0x28 || c == 0x29 || c == 0x3C || c == 0x3E || c == 0x5B || c == 0x5D ||
    c == 0x7B || c == 0x7D || c == 0x2F || c == 0x25

/** Byte cursor over one PDF file; public PDF lexical rules, no whole-file String copy. */
private class ProbeCursor(val raw: ByteArray, var pos: Int) {

    val size: Int get() = raw.size

    fun peek(): Int = raw[pos].toInt() and 0xFF

    fun peekNext(): Int = if (pos + 1 < raw.size) raw[pos + 1].toInt() and 0xFF else -1

    fun skip() {
        while (pos < raw.size) {
            val c = raw[pos].toInt() and 0xFF
            when {
                c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32 -> pos++
                c == 0x25 -> while (pos < raw.size && raw[pos] != '\n'.code.toByte() &&
                    raw[pos] != '\r'.code.toByte()
                ) pos++
                else -> return
            }
        }
    }

    fun atWord(word: String): Boolean {
        if (pos + word.length > raw.size) return false
        for (i in word.indices) if (raw[pos + i] != word[i].code.toByte()) return false
        val after = pos + word.length
        return after >= raw.size || isProbeDelimiter(raw[after].toInt() and 0xFF)
    }

    fun word(word: String): Boolean {
        if (!atWord(word)) return false
        pos += word.length
        return true
    }

    fun readLong(): Long? {
        var value = 0L
        var digits = 0
        while (pos < raw.size) {
            val d = raw[pos].toInt() and 0xFF
            if (d < 0x30 || d > 0x39) break
            if (value > (Long.MAX_VALUE - 9) / 10) return null
            value = value * 10 + (d - 0x30)
            pos++
            digits++
            if (digits > 12) return null
        }
        return if (digits == 0) null else value
    }

    fun readToken(): String {
        val start = pos
        while (pos < raw.size && !isProbeDelimiter(raw[pos].toInt() and 0xFF)) pos++
        return String(raw, start, pos - start, Charsets.ISO_8859_1)
    }

    fun readName(): String? {
        if (pos >= raw.size || raw[pos] != '/'.code.toByte()) return null
        pos++
        val out = StringBuilder()
        while (pos < raw.size) {
            val ch = raw[pos].toInt() and 0xFF
            if (isProbeDelimiter(ch)) break
            if (ch == 0x23 && pos + 2 < raw.size) {
                val hex = String(raw, pos + 1, 2, Charsets.ISO_8859_1).toIntOrNull(16)
                if (hex != null) {
                    out.append(hex.toChar())
                    pos += 3
                    continue
                }
            }
            out.append(ch.toChar())
            pos++
        }
        return out.toString()
    }
}
