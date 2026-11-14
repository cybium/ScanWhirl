package com.scanwhirl.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import java.io.File
import kotlin.math.roundToInt

/**
 * PM-4.4 / E20: stitches pages into one tall JPEG for chat apps, rebuilt so the old failures
 * cannot recur.
 *
 * - Output is a fixed [WIDTH] px wide; every page keeps its aspect ratio (no stretching).
 * - Each page is decoded, drawn and recycled on its own (no all-pages list in memory).
 * - The canvas is `RGB_565` and never taller than [MAX_PART_HEIGHT]; a longer document becomes
 *   several images ("part 1 of 3"), it is never squashed.
 * - A single page that would not fit one part is refused instead of being distorted.
 */
object LongImage {

    const val WIDTH = 1080
    const val MAX_PART_HEIGHT = 16_000
    const val GAP = 12
    const val QUALITY = 88

    private const val MAX_DECODE_WIDTH = 3_000

    /** Zero-based page indices per output image, in order. */
    fun plan(scaledHeights: List<Int>, maxHeight: Int = MAX_PART_HEIGHT): List<IntRange> {
        require(scaledHeights.isNotEmpty()) { "No pages" }
        require(maxHeight > GAP) { "Part height is too small" }
        scaledHeights.forEach {
            require(it in 1..maxHeight) {
                "Too large for one image: a page is $it px tall and the limit is $maxHeight px."
            }
        }
        val parts = mutableListOf<IntRange>()
        var start = 0
        var used = 0
        for (i in scaledHeights.indices) {
            val add = if (i == start) scaledHeights[i] else GAP + scaledHeights[i]
            if (i > start && used + add > maxHeight) {
                parts += start..(i - 1)
                start = i
                used = scaledHeights[i]
            } else {
                used += add
            }
        }
        parts += start..(scaledHeights.lastIndex)
        return parts
    }

    /** Height of each page at [width] px, read from the JPEG header without decoding pixels. */
    fun scaledHeights(pages: List<File>, width: Int = WIDTH): List<Int> = pages.map { file ->
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, opts)
        require(opts.outWidth > 0 && opts.outHeight > 0) { "Page ${file.name} can't be read" }
        ((opts.outHeight.toLong() * width + opts.outWidth - 1) / opts.outWidth).toInt()
            .coerceAtLeast(1)
    }

    /** Stitches [pages] into one or more JPEGs, one image per part of at most [maxHeight] px. */
    fun stitch(
        pages: List<File>,
        width: Int = WIDTH,
        maxHeight: Int = MAX_PART_HEIGHT
    ): List<ByteArray> {
        val parts = mutableListOf<ByteArray>()
        stitchEach(pages, width, maxHeight) { _, _, bytes -> parts += bytes }
        return parts
    }

    /**
     * Renders the parts one at a time, so a long document never holds more than one finished
     * image in memory in addition to the page being drawn. [onPart] receives the part number
     * (0-based), the total and the JPEG bytes.
     */
    fun stitchEach(
        pages: List<File>,
        width: Int = WIDTH,
        maxHeight: Int = MAX_PART_HEIGHT,
        onPart: (index: Int, total: Int, bytes: ByteArray) -> Unit
    ) {
        require(pages.isNotEmpty()) { "No pages" }
        val heights = scaledHeights(pages, width)
        val parts = plan(heights, maxHeight)
        parts.forEachIndexed { index, range ->
            onPart(index, parts.size, renderPart(pages, heights, range, width))
        }
    }

    private fun renderPart(
        pages: List<File>,
        heights: List<Int>,
        range: IntRange,
        width: Int
    ): ByteArray {
        val height = range.sumOf { heights[it] } + GAP * (range.last - range.first)
        val canvasBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        try {
            val canvas = Canvas(canvasBitmap)
            canvas.drawColor(Color.WHITE)
            var y = 0
            for (i in range) {
                val bitmap = decodeForWidth(pages[i], width)
                try {
                    val drawnHeight = heights[i]
                    canvas.drawBitmap(
                        bitmap,
                        null,
                        Rect(0, y, width, y + drawnHeight),
                        Paint(Paint.FILTER_BITMAP_FLAG)
                    )
                    y += drawnHeight + GAP
                } finally {
                    bitmap.recycle()
                }
            }
            return ImageTools.encodeJpeg(canvasBitmap, QUALITY)
        } finally {
            canvasBitmap.recycle()
        }
    }

    /** Decodes at a power-of-two sample that keeps at least [width] px, so the bitmap stays small. */
    private fun decodeForWidth(file: File, width: Int): Bitmap {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, opts)
        require(opts.outWidth > 0) { "Page ${file.name} can't be read" }
        var sample = 1
        while (opts.outWidth / (sample * 2) >= minOf(width, MAX_DECODE_WIDTH)) sample *= 2
        val decode = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(file.path, decode)
            ?: throw IllegalStateException("Page ${file.name} can't be read")
    }
}
