package com.scanwhirl.app.tools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.Durable
import com.scanwhirl.app.util.ExactKbEncoder
import com.scanwhirl.app.util.PdfPageRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.math.roundToInt

/**
 * FF-J engine adapter. It wires the setup's explicit page choice into the existing engine and
 * verifies every published file: type magic, decoded pixels and (for the one-image form path)
 * the final byte band.
 *
 * Library scans carry only their retained page rasters, so the normal path reuses those pages
 * byte-for-byte at quality 100. When a source PDF *is* retained (device source, FF-S),
 * [renderFromPdfJpegs] asks the platform renderer for the requested DPI/px instead of renaming
 * a fixed 150-DPI import. One page for a form goes through the same [ExactKbEncoder] the Form
 * photo screen uses, so the pixels and the KB receipt are measured from the saved file.
 */
object PdfToJExport {

    data class FormSpec(
        val width: Int,
        val height: Int,
        val minBytes: Long,
        val maxBytes: Long,
        val dpi: Int = 200
    )

    data class Request(
        val docId: String,
        val pageIndices: List<Int>,
        val format: ScanRepository.ImageExportFormat,
        val layout: PdfToJOptions.Layout,
        val quality: Int = 90,
        val form: FormSpec? = null
    )

    data class Output(
        val file: File,
        val pageIndex: Int,
        val mime: String,
        val bytes: Long,
        val width: Int,
        val height: Int
    )

    data class Result(val outputs: List<Output>, val zip: File?) {
        val files: List<File> get() = zip?.let { listOf(it) } ?: outputs.map { it.file }
        val mime: String get() = zip?.let { "application/zip" } ?: outputs.first().mime
    }

    suspend fun export(
        repo: ScanRepository,
        request: Request,
        outDir: File,
        baseName: String
    ): Result = withContext(Dispatchers.IO) {
        require(request.pageIndices.isNotEmpty()) { "Choose at least one page" }
        val doc = repo.getDocument(request.docId) ?: throw IllegalStateException("Scan not found")
        val indices = request.pageIndices.distinct().sorted()
        require(indices.all { it in doc.pageFiles.indices }) { "No such page" }
        if (request.form != null) require(indices.size == 1) { "A form image needs one page" }
        if (!outDir.exists() && !outDir.mkdirs()) throw IOException("Could not prepare the images")

        when (request.layout) {
            PdfToJOptions.Layout.SEPARATE -> {
                val outputs = mutableListOf<Output>()
                for (index in indices) {
                    currentCoroutineContext().ensureActive()
                    outputs += separateOne(repo, request, doc.pageFiles[index], outDir, baseName, index)
                }
                Result(outputs, null)
            }
            PdfToJOptions.Layout.ZIP -> {
                val zip = File(outDir, PdfToJOptions.zipName(baseName))
                repo.pdfToImages(
                    request.docId, request.format, zip = true, outFile = zip,
                    quality = request.quality, pageIndices = indices
                )
                val extension = if (request.format == ScanRepository.ImageExportFormat.JPG) "jpg" else "png"
                val expected = indices.map { String.format(Locale.ROOT, "page_%03d.%s", it + 1, extension) }
                ZipFile(zip).use { archive ->
                    require(archive.entries().toList().map { it.name } == expected) {
                        "ZIP entries are missing or out of order"
                    }
                }
                Result(emptyList(), zip)
            }
        }
    }

    private suspend fun separateOne(
        repo: ScanRepository,
        request: Request,
        page: File,
        outDir: File,
        baseName: String,
        index: Int
    ): Output {
        val form = request.form
        val extension = if (request.format == ScanRepository.ImageExportFormat.JPG) "jpg" else "png"
        val out = File(outDir, PdfToJOptions.fileName(baseName, index + 1, extension))
        val bytes: ByteArray
        val width: Int
        val height: Int
        if (form != null) {
            val source = BitmapFactory.decodeFile(page.path)
                ?: throw IllegalStateException("Page ${page.name} can't be read")
            val cropped = cropToAspect(source, form.width, form.height)
            try {
                val encoded = ExactKbEncoder.encode(
                    cropped, form.width, form.height, form.dpi,
                    form.minBytes, form.maxBytes, shrinkToFit = false
                )
                require(encoded.outcome == ExactKbEncoder.Outcome.IN_BAND) {
                    "Could not reach ${form.minBytes}-${form.maxBytes} bytes at ${form.width}x${form.height}"
                }
                writeAtomically(out, encoded.bytes)
                bytes = encoded.bytes
                width = encoded.width
                height = encoded.height
            } finally {
                cropped.recycle()
                source.recycle()
            }
        } else {
            repo.pdfToImages(
                request.docId, request.format, zip = false, outFile = out,
                quality = request.quality, pageIndices = listOf(index)
            )
            bytes = out.readBytes()
            val bounds = bounds(bytes)
            width = bounds.first
            height = bounds.second
        }
        verifyMagic(bytes, request.format)
        return Output(out, index, mime(request.format), bytes.size.toLong(), width, height)
    }

    /** Largest centred crop with the requested aspect: scaling it afterwards never stretches. */
    fun cropToAspect(source: Bitmap, width: Int, height: Int): Bitmap {
        require(width > 0 && height > 0) { "Invalid form size" }
        val target = width.toFloat() / height
        val cropW: Int
        val cropH: Int
        if (source.width.toFloat() / source.height > target) {
            cropH = source.height
            cropW = (source.height * target).roundToInt().coerceAtLeast(1)
        } else {
            cropW = source.width
            cropH = (source.width / target).roundToInt().coerceAtLeast(1)
        }
        return Bitmap.createBitmap(
            source,
            (source.width - cropW) / 2,
            (source.height - cropH) / 2,
            cropW,
            cropH
        )
    }

    /**
     * True raster from a retained PDF source: the requested DPI drives the pixel dimensions,
     * never the 150-DPI import default. The existing renderer seam makes this testable.
     */
    fun renderFromPdfJpegs(
        fd: ParcelFileDescriptor,
        pageIndices: List<Int>,
        dpi: Float,
        maxDim: Int,
        quality: Int
    ): List<ByteArray> = PdfPageRenderer.withRenderer(fd) { renderer ->
        val indices = pageIndices.distinct()
        require(indices.all { it in 0 until renderer.pageCount }) { "No such page" }
        indices.map { PdfPageRenderer.renderPageJpeg(renderer, it, dpi, maxDim, quality) }
    }

    private fun mime(format: ScanRepository.ImageExportFormat): String =
        if (format == ScanRepository.ImageExportFormat.PNG) "image/png" else "image/jpeg"

    private fun verifyMagic(bytes: ByteArray, format: ScanRepository.ImageExportFormat) {
        require(bytes.size >= 4) { "The exported image is empty" }
        if (format == ScanRepository.ImageExportFormat.PNG) {
            require(bytes[0] == 0x89.toByte() && String(bytes, 1, 3, Charsets.US_ASCII) == "PNG") {
                "PNG export has no PNG signature"
            }
        } else {
            require(bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
                "JPG export is not a JPEG"
            }
        }
    }

    private fun bounds(bytes: ByteArray): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        require(options.outWidth > 0 && options.outHeight > 0) { "The exported image cannot be decoded" }
        return options.outWidth to options.outHeight
    }

    private fun writeAtomically(out: File, bytes: ByteArray) {
        val tmp = File(out.parentFile, out.name + ".part")
        tmp.parentFile?.mkdirs()
        try {
            Durable.writeFile(tmp, bytes)
            Durable.syncFile(tmp)
            if (!tmp.renameTo(out)) throw IOException("Could not save ${out.name}")
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }
}
