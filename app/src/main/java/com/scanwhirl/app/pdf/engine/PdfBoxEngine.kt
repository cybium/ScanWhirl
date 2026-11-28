package com.scanwhirl.app.pdf.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * PDFBox page-object engine. Every public call runs on one private thread because
 * the port keeps document state in static/soft caches. Resource loading is lazy:
 * nothing from the AAR is touched until the first call, so cold start is unchanged.
 *
 * No PDFBox encryption API is used. [open] decrypts with [PdfDecryptor] into a
 * private temp file and [saveEncrypted] encrypts the saved plain bytes with
 * [PdfEncryptor]; Bouncy Castle is deliberately absent from the build.
 */
class PdfBoxEngine(
    private val context: Context,
    workDir: File = File(context.cacheDir, "pdf-engine"),
    private val budgets: PdfEngineBudgets = PdfEngineBudgets()
) : PdfEngine {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "scanwhirl-pdf-engine").apply { isDaemon = true }
    }
    private val cancelled = AtomicBoolean(false)
    private val sequence = AtomicLong(0)
    private val dir = File(workDir, "engine-${System.nanoTime()}").apply { mkdirs() }
    private var ready = false

    override val isCancelled: Boolean get() = cancelled.get()

    override fun cancel() {
        cancelled.set(true)
    }

    override fun close() {
        cancelled.set(true)
        executor.shutdownNow()
        dir.deleteRecursively()
    }

    // ------------------------------------------------------------------ plumbing

    private fun <T> execute(block: () -> T): T {
        if (executor.isShutdown) throw PdfEngineError.Cancelled()
        return try {
            executor.submit(Callable(block)).get()
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
            "a password format ScanWhirl cannot open"
        )
        is PdfDecryptor.Unsupported, is PdfDecryptor.CannotRewrite ->
            PdfEngineError.MalformedDocument(t.message ?: "unreadable password protection")
        is IOException -> PdfEngineError.MalformedDocument(t.message ?: "unreadable PDF")
        is IllegalArgumentException, is IndexOutOfBoundsException ->
            PdfEngineError.MalformedDocument(t.message ?: "invalid page selection")
        else -> PdfEngineError.Io(t)
    }

    private fun ensureReady() {
        if (!ready) {
            PDFBoxResourceLoader.init(context.applicationContext)
            check(PDFBoxResourceLoader.isReady()) { "PDFBox resource loader failed to start" }
            ready = true
        }
    }

    private fun checkCancelled() {
        if (cancelled.get()) throw PdfEngineError.Cancelled()
    }

    private fun temp(name: String): File =
        File(dir, "${name}-${sequence.incrementAndGet()}.pdf").also { it.deleteOnExit() }

    private fun checkedInput(file: File, started: Long = System.nanoTime()): File {
        checkCancelled()
        deadline(started)
        if (!file.isFile || file.length() <= 0L) {
            throw PdfEngineError.MalformedDocument("the source file is missing or empty")
        }
        if (file.length() > budgets.maxInputBytes) throw PdfEngineError.LimitExceeded("input bytes")
        return file
    }

    private fun deadline(started: Long) {
        val elapsed = (System.nanoTime() - started) / 1_000_000L
        if (elapsed > budgets.wallTimeMs) throw PdfEngineError.LimitExceeded("wall time")
    }

    private fun checkOutput(file: File) {
        if (file.length() > budgets.maxOutputBytes) throw PdfEngineError.LimitExceeded("output bytes")
    }

    private fun atomicReplace(from: File, to: File) {
        checkOutput(from)
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE)
        } catch (_: IOException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
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

    private fun decryptToTemp(source: File, password: String, started: Long): File {
        if (password.isEmpty()) return source
        return try {
            val plain = PdfDecryptor.decrypt(source.readBytes(), password)
            val out = temp("plain")
            out.writeBytes(plain)
            out
        } catch (e: PdfDecryptor.UnsupportedScheme) {
            // A password on a plain file is harmless: nothing to decrypt.
            source
        }
    }

    private fun textOf(file: File, password: String? = null, page: Int = 0): String {
        val plain = if (password.isNullOrEmpty()) file else {
            val bytes = PdfDecryptor.decrypt(file.readBytes(), password)
            val t = temp("verify-plain")
            t.writeBytes(bytes)
            t
        }
        PDDocument.load(checkedInput(plain)).use { doc ->
            val stripper = PDFTextStripper()
            if (page > 0) {
                stripper.startPage = page
                stripper.endPage = page
            }
            return stripper.getText(doc)
        }
    }

    // ------------------------------------------------------------------ operations

    override fun open(source: File, password: String?): PdfHandle = execute {
        ensureReady()
        val started = System.nanoTime()
        val input = checkedInput(source, started)
        val plain = decryptToTemp(input, password.orEmpty(), started)
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
                if (doc.numberOfPages > budgets.maxPages) throw PdfEngineError.LimitExceeded("page count")
                doc.save(out)
            }
        }
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
                doc.save(out)
            }
        }
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
        PDDocument.load(checkedInput(handle.file, started)).use { doc ->
            if (pageIndex !in 0 until doc.numberOfPages) {
                throw PdfEngineError.MalformedDocument("page index outside the document")
            }
            val page: PDPage = doc.getPage(pageIndex)
            expected = ((page.rotation + degrees) % 360 + 360) % 360
            page.rotation = expected
            doc.save(out)
        }
        verifyPages(out, handle.pageCount, started)
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
        PDDocument.load(checkedInput(handle.file, started)).use { doc ->
            for (index in unique.sortedDescending()) {
                checkCancelled()
                deadline(started)
                doc.removePage(index)
            }
            doc.save(out)
        }
        verifyPages(out, handle.pageCount - unique.size, started)
        atomicReplace(out, handle.file)
        Unit
    }

    override fun merge(sources: List<File>, destination: String): PdfHandle = execute {
        ensureReady()
        val started = System.nanoTime()
        if (sources.size < 2) throw PdfEngineError.MalformedDocument("merge needs at least two files")
        val out = temp("merge")
        var expected = 0
        val merger = PDFMergerUtility().apply {
            for (source in sources) {
                addSource(checkedInput(source, started))
            }
            setDestinationStream(FileOutputStream(out))
        }
        merger.mergeDocuments(MemoryUsageSetting.setupTempFileOnly())
        PDDocument.load(checkedInput(out, started)).use { doc ->
            expected = doc.numberOfPages
            if (expected > budgets.maxPages) throw PdfEngineError.LimitExceeded("page count")
        }
        verifyPages(out, expected, started)
        val target = File(destination)
        atomicReplace(out, target)
        PdfHandle(target.canonicalFile, expected)
    }

    override fun split(source: PdfHandle, ranges: List<IntRange>): List<PdfHandle> = execute {
        ensureReady()
        val started = System.nanoTime()
        if (ranges.isEmpty()) throw PdfEngineError.MalformedDocument("no split ranges were given")
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
                    if (doc.numberOfPages > budgets.maxPages) {
                        throw PdfEngineError.LimitExceeded("page count")
                    }
                    doc.save(out)
                }
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
        val out = temp("overlay")
        PDDocument.load(checkedInput(handle.file, started)).use { doc ->
            if (pageIndex !in 0 until doc.numberOfPages) {
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
            doc.save(out)
        }
        verifyPages(out, handle.pageCount, started)
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
                    doc.importPage(src.getPage(i))
                }
                doc.save(out)
            }
        }
        verifyPages(out, source.pageCount, started)
        val hiddenText = textOf(out, page = pageIndex + 1)
        if (hiddenText.trim().isNotEmpty()) {
            throw PdfEngineError.VerificationFailed("a redacted page still has a text layer")
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
        checkCancelled()
        deadline(started)
        val roundTrip = try {
            PdfDecryptor.decrypt(out.readBytes(), userPassword)
        } catch (e: PdfDecryptor.WrongPassword) {
            throw PdfEngineError.InvalidPassword()
        }
        check(roundTrip.isNotEmpty())
        checkOutput(out)
        val target = File(destination)
        atomicReplace(out, target)
        Unit
    }

    override fun verifyText(file: File, password: String?): String = execute {
        ensureReady()
        val started = System.nanoTime()
        textOf(checkedInput(file, started), password)
    }
}
