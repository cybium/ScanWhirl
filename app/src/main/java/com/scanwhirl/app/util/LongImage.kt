package com.scanwhirl.app.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import java.io.File
import java.io.FileOutputStream

/**
 * Stitches a document's pages into one tall JPEG ("share as long image",
 * Pro). Pages are decoded at a bounded size and drawn top-to-bottom on a
 * white canvas, so no enhancement, no fake DPI — real pixels, honestly
 * pasted together. Height is capped so a document with many pages cannot
 * overflow the encoder.
 */
object LongImage {

    /** Encoder/decoder safety cap on the stitched height, in pixels. */
    private const val MAX_TOTAL_HEIGHT = 20_000

    fun stitch(pages: List<File>, outFile: File, quality: Int = 90) {
        require(pages.isNotEmpty()) { "No pages to stitch" }

        val bitmaps = pages.filter { it.exists() && it.length() > 0 }.mapNotNull { page ->
            try {
                ImageTools.decodeSampled(page.readBytes(), 2048)
            } catch (_: Exception) {
                null
            }
        }
        require(bitmaps.isNotEmpty()) { "Pages are unreadable" }

        val width = bitmaps.maxOf { it.width }
        val unscaledHeight = bitmaps.sumOf { it.height }
        val scale = if (unscaledHeight > MAX_TOTAL_HEIGHT) {
            MAX_TOTAL_HEIGHT.toFloat() / unscaledHeight
        } else {
            1f
        }
        val height = (unscaledHeight * scale).toInt()

        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        var y = 0f
        for (bmp in bitmaps) {
            val drawH = bmp.height * scale
            canvas.drawBitmap(bmp, null, android.graphics.RectF(0f, y, width.toFloat(), y + drawH), null)
            y += drawH
            bmp.recycle()
        }
        try {
            if (out.isRecycled) throw IllegalStateException("Result bitmap is gone")
            outFile.parentFile?.mkdirs()
            FileOutputStream(outFile).use { stream ->
                out.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            }
        } finally {
            out.recycle()
        }
    }
}
