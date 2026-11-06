package com.scanwhirl.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * Renders PDF pages to JPEG bytes using the platform PdfRenderer
 * (read-only, on-device).
 *
 * Android's PdfRenderer needs a *seekable* file descriptor. SAF providers
 * (Downloads, Drive, WhatsApp…) often hand back a pipe, so every external PDF
 * is copied once into cacheDir and opened from there. That single copy is what
 * fixes the "file descriptor not seekable" failure when editing, merging,
 * compressing, OCR-ing or locking a device PDF.
 */
object PdfPageRenderer {

    /** Number of pages in the PDF (opens the renderer briefly). */
    fun pageCount(fd: ParcelFileDescriptor): Int = PdfRenderer(fd).use { it.pageCount }

    /**
     * Copies [uri] into a cache file, opens it seekably, and runs [block].
     * Suspending so callers can OCR/render page by page while the window is
     * open without holding the whole document in memory.
     */
    suspend fun <T> withSeekablePdf(
        context: Context,
        uri: Uri,
        block: suspend (ParcelFileDescriptor) -> T
    ): T {
        val dir = File(context.cacheDir, "pdf_fd").apply { mkdirs() }
        val tmp = File(dir, "seek_${System.nanoTime()}.pdf")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tmp).use { out -> input.copyTo(out) }
            } ?: throw IllegalStateException("Could not open PDF")
            require(tmp.length() > 0L) { "PDF is empty" }
            ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                return block(fd)
            }
        } finally {
            tmp.delete()
        }
    }

    /** Renders a single page to JPEG bytes. */
    fun renderPageJpeg(
        fd: ParcelFileDescriptor,
        index: Int,
        dpi: Float,
        maxDim: Int,
        quality: Int
    ): ByteArray = PdfRenderer(fd).use { renderer ->
        require(index in 0 until renderer.pageCount) { "No such page" }
        renderer.openPage(index).use { page ->
            var scale = dpi / 72f
            val longest = maxOf(page.width, page.height)
            if (longest * scale > maxDim) {
                scale = maxDim.toFloat() / longest
            }
            val w = (page.width * scale).roundToInt().coerceAtLeast(1)
            val h = (page.height * scale).roundToInt().coerceAtLeast(1)

            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawColor(Color.WHITE)
            val matrix = Matrix().apply { setScale(w.toFloat() / page.width, h.toFloat() / page.height) }
            page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val jpeg = ImageTools.encodeJpeg(bitmap, quality)
            bitmap.recycle()
            jpeg
        }
    }

    /**
     * @param dpi nominal resolution (72 = PDF points). Result capped at [maxDim] longest side.
     * @return one JPEG per page, in document order.
     */
    fun renderJpegs(
        fd: ParcelFileDescriptor,
        dpi: Float,
        maxDim: Int,
        quality: Int
    ): List<ByteArray> = PdfRenderer(fd).use { renderer ->
        require(renderer.pageCount > 0) { "PDF has no pages" }
        (0 until renderer.pageCount).map { index ->
            renderer.openPage(index).use { page ->
                var scale = dpi / 72f
                val longest = maxOf(page.width, page.height)
                if (longest * scale > maxDim) {
                    scale = maxDim.toFloat() / longest
                }
                val w = (page.width * scale).roundToInt().coerceAtLeast(1)
                val h = (page.height * scale).roundToInt().coerceAtLeast(1)

                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                Canvas(bitmap).drawColor(Color.WHITE)

                val matrix = Matrix().apply { setScale(w.toFloat() / page.width, h.toFloat() / page.height) }
                page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                val jpeg = ImageTools.encodeJpeg(bitmap, quality)
                bitmap.recycle()
                jpeg
            }
        }
    }
}
