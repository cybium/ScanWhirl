package com.scanwhirl.app.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlin.math.roundToInt

/** Renders PDF pages to JPEG bytes using the platform PdfRenderer (read-only, on-device). */
object PdfPageRenderer {

    /** Number of pages in the PDF (opens the renderer briefly). */
    fun pageCount(fd: ParcelFileDescriptor): Int = PdfRenderer(fd).use { it.pageCount }

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
