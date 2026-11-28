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

    /**
     * Bounded parse of the final trailer dictionary. A literal "/Encrypt" in page
     * content or a comment cannot make a plain file look encrypted; an encrypted
     * dictionary in the trailer is found regardless of file size.
     */
    private fun hasEncryptDictionary(raw: ByteArray): Boolean {
        val text = String(raw, Charset.forName("ISO-8859-1"))
        val start = text.lastIndexOf("trailer")
        if (start < 0) return false
        var i = start + "trailer".length
        var depth = 0
        var inLiteral = false
        var inHex = false
        while (i < text.length) {
            if (text.startsWith("startxref", i)) break
            val c = text[i]
            when {
                c == '%' -> {
                    while (i < text.length && text[i] != '\n' && text[i] != '\r') i++
                    continue
                }
                inLiteral -> {
                    if (c == '\\') i++ else if (c == ')') inLiteral = false
                }
                inHex -> if (c == '>') inHex = false
                c == '(' -> inLiteral = true
                c == '<' && i + 1 < text.length && text[i + 1] != '<' -> inHex = true
                c == '<' && i + 1 < text.length && text[i + 1] == '<' -> { depth++; i++ }
                c == '>' && i + 1 < text.length && text[i + 1] == '>' -> { depth--; i++ }
                c == '/' && depth == 1 -> {
                    i++
                    val from = i
                    while (i < text.length && !isNameDelimiter(text[i])) i++
                    if (text.substring(from, i) == "Encrypt") return true
                    continue
                }
            }
            i++
        }
        return false
    }

    private fun isNameDelimiter(c: Char): Boolean =
        c.isWhitespace() || c in "/[]()<>{}%"

    /**
     * Distinguishes a genuinely plain PDF from an encrypted/unsupported one using
     * the parser, not a byte window. Returns null only when there is no /Encrypt
     * dictionary in the trailer.
     */
    private fun decryptToTemp(raw: ByteArray, password: String): File? {
        val plain = try {
            PdfDecryptor.decrypt(raw, password)
        } catch (e: PdfDecryptor.WrongPassword) {
            throw PdfEngineError.InvalidPassword()
        } catch (e: PdfDecryptor.UnsupportedScheme) {
            if (e.message == NO_ENCRYPTION_MESSAGE && !hasEncryptDictionary(raw)) return null
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
