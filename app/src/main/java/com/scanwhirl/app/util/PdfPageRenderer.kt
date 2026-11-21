package com.scanwhirl.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
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
 *
 * PERF item 4: a native PdfRenderer is opened once for a whole multi-page
 * operation ([withRenderer]) instead of once per page. [pageCount] and
 * [renderPageJpeg] keep their one-shot behaviour for legacy single-page callers,
 * but loops should hold one [Renderer] and call [renderPageJpeg] on it.
 */
object PdfPageRenderer {

    /** One page of an open renderer. Closeable so bitmap/native state is released per page. */
    interface Page : Closeable {
        val width: Int
        val height: Int
        fun render(bitmap: Bitmap, transform: Matrix, mode: Int)
        override fun close()
    }

    /**
     * A native renderer (or a test fake) held open for a whole render pass.
     * The renderer owns the duplicate descriptor it was opened with and closes
     * it in [close].
     */
    interface Renderer : Closeable {
        val pageCount: Int
        fun openPage(index: Int): Page
        override fun close()
    }

    private class AndroidRenderer(fd: ParcelFileDescriptor) : Renderer {
        // PdfRenderer takes ownership of the descriptor it is given and closes it
        // when the renderer closes. The caller keeps its own descriptor (M07).
        private val renderer: PdfRenderer = PdfRenderer(fd.dup())

        override val pageCount: Int get() = renderer.pageCount

        override fun openPage(index: Int): Page = AndroidPage(renderer.openPage(index))

        override fun close() {
            renderer.close()
        }

        private class AndroidPage(private val page: PdfRenderer.Page) : Page {
            override val width: Int get() = page.width
            override val height: Int get() = page.height

            override fun render(bitmap: Bitmap, transform: Matrix, mode: Int) {
                page.render(bitmap, null, transform, mode)
            }

            override fun close() {
                page.close()
            }
        }
    }

    private fun open(fd: ParcelFileDescriptor): Renderer = AndroidRenderer(fd)

    /**
     * Test seam: Robolectric cannot construct a real [PdfRenderer], so tests
     * supply fakes that count opens/closes and return deterministic pages.
     */
    @Volatile
    internal var rendererFactory: (ParcelFileDescriptor) -> Renderer = { open(it) }

    /**
     * Opens exactly one renderer for [block] and closes it on every path —
     * success, exception, cancellation and OutOfMemoryError. This is the
     * multi-page entry point: loops must render all their pages inside [block].
     */
    fun <T> withRenderer(fd: ParcelFileDescriptor, block: (Renderer) -> T): T =
        rendererFactory(fd).use { renderer -> block(renderer) }

    /**
     * Suspending counterpart for callers that dispatch to Main (or otherwise
     * suspend) between pages. Closes the renderer on success, failure,
     * cancellation and OutOfMemoryError, exactly like [withRenderer].
     */
    suspend fun <T> withRendererSuspend(
        fd: ParcelFileDescriptor,
        block: suspend (Renderer) -> T
    ): T {
        val renderer = rendererFactory(fd)
        try {
            return block(renderer)
        } finally {
            renderer.close()
        }
    }

    /** Number of pages in the PDF (opens the renderer briefly, for legacy callers). */
    fun pageCount(fd: ParcelFileDescriptor): Int = withRenderer(fd) { it.pageCount }

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
            BoundedCopy.requireOk(BoundedCopy.copy(context, uri, tmp), context)
            ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                return block(fd)
            }
        } finally {
            BoundedCopy.release(tmp)
        }
    }

    /** Renders a single page to JPEG bytes from an already-open [renderer]. */
    fun renderPageJpeg(
        renderer: Renderer,
        index: Int,
        dpi: Float,
        maxDim: Int,
        quality: Int
    ): ByteArray {
        require(index in 0 until renderer.pageCount) { "No such page" }
        return renderer.openPage(index).use { page ->
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
            page.render(bitmap, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val jpeg = ImageTools.encodeJpeg(bitmap, quality)
            bitmap.recycle()
            jpeg
        }
    }

    /** One-shot single-page entry point for legacy callers; opens and closes its own renderer. */
    fun renderPageJpeg(
        fd: ParcelFileDescriptor,
        index: Int,
        dpi: Float,
        maxDim: Int,
        quality: Int
    ): ByteArray = withRenderer(fd) { renderPageJpeg(it, index, dpi, maxDim, quality) }

    /**
     * @param dpi nominal resolution (72 = PDF points). Result capped at [maxDim] longest side.
     * @return one JPEG per page, in document order, from a single native renderer.
     */
    fun renderJpegs(
        fd: ParcelFileDescriptor,
        dpi: Float,
        maxDim: Int,
        quality: Int
    ): List<ByteArray> = withRenderer(fd) { renderer ->
        require(renderer.pageCount > 0) { "PDF has no pages" }
        (0 until renderer.pageCount).map { index ->
            renderPageJpeg(renderer, index, dpi, maxDim, quality)
        }
    }
}
