package com.scanwhirl.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * v2 tool "Book pages" (`70_APP_DESIGN.md` §3, Create): split a photographed two-page spread
 * into its left and right pages.
 *
 * The gutter is the darkest, most uniform vertical band in the middle 30% of the image — a book's
 * fold is a dark, low-variance stripe, while text columns are dark but noisy. When nothing stands
 * out (a flat image, a photo without a fold) the exact middle is used instead of guessing.
 *
 * Decoding is sampled to [MAX_LONG_SIDE] px on the long side ([ImageTools.decodeSampled]), so a
 * 12 MP phone photo never becomes a full-resolution ARGB copy; the two crops are the only copies
 * kept, and callers recycle them after encoding.
 */
object BookSplit {

    /** Longest side of the decoded spread. Two pages at ~1500 px each is plenty for a scan. */
    const val MAX_LONG_SIDE = 3000

    /** The gutter is only searched in the middle 30% of the width. */
    const val SEARCH_BAND = 0.30f

    /** Below this many sampled columns/rows the statistics are meaningless. */
    private const val MIN_SAMPLES = 4

    /** How far the winning column must beat the band average to count as a real fold. */
    private const val MIN_MARGIN = 0.06f

    /** A fold is uniform; a text column is not. */
    private const val MAX_GUTTER_STD_DEV = 70f

    /**
     * A gutter position. [found] is false when the scan fell back to [ratio] = 0.5.
     */
    data class Gutter(val ratio: Float, val found: Boolean) {
        companion object {
            val MIDDLE = Gutter(0.5f, false)
        }
    }

    /** One split: two crops whose widths add up to the source width. */
    data class Split(val gutter: Gutter, val left: Bitmap, val right: Bitmap) {
        val pages: List<Bitmap> get() = listOf(left, right)
    }

    /**
     * Decodes [bytes] with `inSampleSize` chosen so the long side can never exceed [maxLongSide].
     * (An image 1.1× the cap is halved: `inSampleSize` powers of two are the only lossless lever,
     * and a half-size page is far better than a full-resolution ARGB copy.)
     */
    fun decode(bytes: ByteArray, maxLongSide: Int = MAX_LONG_SIDE): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Not a decodable image" }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxLongSide) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IllegalStateException("Could not decode image")
    }

    /** The default split: find the gutter, then crop. */
    fun split(bitmap: Bitmap): Split = split(bitmap, scanGutter(bitmap))

    /** Splits at a known gutter (the UI preview's draggable line). */
    fun split(bitmap: Bitmap, gutter: Gutter): Split {
        val width = bitmap.width
        val height = bitmap.height
        require(width > 1 && height > 1) { "The spread has no usable pixels" }
        val ratio = gutter.ratio.coerceIn(0.05f, 0.95f)
        val cut = (ratio * width).roundToInt().coerceIn(1, width - 1)
        val left = Bitmap.createBitmap(bitmap, 0, 0, cut, height)
        val right = Bitmap.createBitmap(bitmap, cut, 0, width - cut, height)
        return Split(Gutter(cut.toFloat() / width, gutter.found), left, right)
    }

    /**
     * Finds the fold in the middle [SEARCH_BAND] of [bitmap]. Each column's luminance mean and
     * standard deviation are sampled on a bounded grid (never every pixel of a 3000 px image).
     * The winner must be clearly darker/more uniform than the rest of the band, otherwise the
     * answer is [Gutter.MIDDLE].
     */
    fun scanGutter(bitmap: Bitmap): Gutter {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 8 || height < 8) return Gutter.MIDDLE

        val halfBand = (width * SEARCH_BAND / 2f).roundToInt().coerceAtLeast(1)
        val start = (width / 2 - halfBand).coerceIn(1, width - 2)
        val end = (width / 2 + halfBand).coerceIn(start + 1, width - 1)

        val stepX = max(1, (end - start) / 320)
        val stepY = max(1, height / 320)

        var bestScore = -Float.MAX_VALUE
        var bestX = width / 2
        var bestSd = Float.MAX_VALUE
        var scoreSum = 0f
        var columns = 0

        var x = start
        while (x <= end) {
            var sum = 0L
            var sumSq = 0L
            var rows = 0
            var y = 0
            while (y < height) {
                val pixel = bitmap.getPixel(x, y)
                val luminance =
                    (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
                sum += luminance
                sumSq += luminance.toLong() * luminance
                rows++
                y += stepY
            }
            if (rows >= MIN_SAMPLES) {
                val mean = sum.toFloat() / rows
                val variance = (sumSq.toFloat() / rows - mean * mean).coerceAtLeast(0f)
                val sd = sqrt(variance)
                // Dark is good, uneven is bad; both in 0..1.
                val score = (255f - mean) / 255f - sd / 255f
                if (score > bestScore) {
                    bestScore = score
                    bestX = x
                    bestSd = sd
                }
                scoreSum += score
                columns++
            }
            x += stepX
        }

        if (columns < MIN_SAMPLES) return Gutter.MIDDLE
        val average = scoreSum / columns
        val found = bestScore > average + MIN_MARGIN && bestSd <= MAX_GUTTER_STD_DEV
        val ratio = if (found) bestX.toFloat() / width else 0.5f
        return Gutter(ratio.coerceIn(0.05f, 0.95f), found && ratio in 0.05f..0.95f)
    }

    /**
     * Builds the two page JPEGs for [bytes] at [gutter] (or the detected gutter). The source bytes
     * and any provider-owned file are only read, never written.
     */
    fun splitToJpeg(
        bytes: ByteArray,
        gutter: Gutter? = null,
        quality: Int = 90
    ): Pair<ByteArray, ByteArray> {
        val bitmap = decode(bytes)
        val split = try {
            split(bitmap, gutter ?: scanGutter(bitmap))
        } finally {
            bitmap.recycle()
        }
        return try {
            ImageTools.encodeJpeg(split.left, quality) to ImageTools.encodeJpeg(split.right, quality)
        } finally {
            split.left.recycle()
            split.right.recycle()
        }
    }
}
