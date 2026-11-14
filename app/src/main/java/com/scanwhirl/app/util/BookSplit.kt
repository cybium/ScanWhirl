package com.scanwhirl.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * v2 tool "Book pages" (`70_APP_DESIGN.md` §3, Create): split a photographed two-page spread
 * into its left and right pages.
 *
 * The gutter is a **narrow dark vertical valley with a page-brightness plateau on both sides**,
 * searched in the middle 40% of the image. The column profile is the median of the middle 70% of
 * rows (the top/bottom 15% are where hands, shadows and clutter usually are), so a hand at the
 * bottom or a bright object at the top cannot pull the line.
 *
 * Guards learned from real textbook photos (see `BookSplitRealEval`):
 *  - both sides of the valley must be paper-bright, not a dark table or wall;
 *  - at least one side must show text/graphics (unless the dip is very deep);
 *  - a valley far from the centre must be much deeper (no perspective correction exists here);
 *  - a one-page close-up has no deep valley and one wide text block; the UI then offers
 *    "Keep as one page" and uses it by default.
 *
 * When no valley clears the guards the result is the exact middle with [Gutter.found] false:
 * the UI says "Check the line" instead of pretending to be sure.
 *
 * Decoding is sampled to [MAX_LONG_SIDE] px on the long side, so a 12 MP phone photo never
 * becomes a full-resolution ARGB copy; the two crops are the only copies kept, and callers
 * recycle them after encoding.
 */
object BookSplit {

    /** Longest side of the decoded spread. Two pages at ~1500 px each is plenty for a scan. */
    const val MAX_LONG_SIDE = 3000

    /** The gutter is only searched in the middle 40% of the width. */
    const val SEARCH_BAND = 0.40f

    /** Analysis resolution: ~1000 columns is enough for a 6-10 px fold, and fast on a phone. */
    private const val ANALYSIS_COLUMNS = 1000

    /** Row samples per column; the top and bottom 15% are always ignored. */
    private const val ROW_SAMPLES = 240
    private const val BAND_TOP = 0.15f
    private const val BAND_BOTTOM = 0.85f
    private const val INK_THRESHOLD = 28

    /** A fold is at most this far from the centre unless it is very deep. */
    private const val CENTRE_WINDOW = 0.09f
    private const val DEEP_FOR_FAR = 45f

    /** A chosen valley must beat this score to be treated as a real gutter. */
    private const val FOUND_SCORE = 0.50f

    /** Below this depth plus one text block, the photo looks like a single page. */
    private const val SINGLE_PAGE_DEPTH = 12f

    /**
     * A gutter position. [found] is false when the scan fell back to [ratio] = 0.5; then
     * [confidence] is 0 and the UI asks the owner to check the line. [singlePage] is true when
     * the photo looks like one page (no fold + one text block): the UI offers to keep it whole.
     */
    data class Gutter(
        val ratio: Float,
        val found: Boolean,
        val confidence: Float = if (found) 1f else 0f,
        val singlePage: Boolean = false
    ) {
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
        return Split(
            Gutter(cut.toFloat() / width, gutter.found, gutter.confidence, gutter.singlePage),
            left,
            right
        )
    }

    /**
     * Finds the fold in the middle 40% of [bitmap]. See the class comment for the guards; every
     * window is a fraction of the analysis width, so a 900 px preview and a 3000 px page decode
     * give the same answer.
     */
    fun scanGutter(bitmap: Bitmap): Gutter {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 32 || height < 32) return Gutter.MIDDLE

        val cols = min(ANALYSIS_COLUMNS, width)
        val y0 = (height * BAND_TOP).toInt()
        val y1 = (height * BAND_BOTTOM).toInt()
        if (y1 - y0 < 4) return Gutter.MIDDLE
        val rowStep = max(1, (y1 - y0) / ROW_SAMPLES)
        val samples = IntArray((y1 - y0) / rowStep + 2)

        val profile = FloatArray(cols)
        val activity = FloatArray(cols)
        val ink = FloatArray(cols)

        for (i in 0 until cols) {
            val x = ((i + 0.5f) * width / cols).toInt().coerceIn(0, width - 1)
            val x2 = ((i + 1.5f) * width / cols).toInt().coerceIn(0, width - 1)
            var n = 0
            var y = y0
            var gradient = 0L
            while (y < y1) {
                val pixel = luminance(bitmap.getPixel(x, y))
                samples[n++] = pixel
                gradient += abs(pixel - luminance(bitmap.getPixel(x2, y)))
                y += rowStep
            }
            if (n == 0) return Gutter.MIDDLE
            java.util.Arrays.sort(samples, 0, n)
            val median = samples[n / 2]
            profile[i] = median.toFloat()
            activity[i] = gradient.toFloat() / n
            var dark = 0
            while (dark < n && samples[dark] < median - INK_THRESHOLD) dark++
            ink[i] = dark.toFloat() / n
        }

        smooth(profile, 1)
        smooth(activity, max(1, cols / 160))
        smooth(ink, max(1, cols / 200))

        val halfValley = max(2, (cols * 0.004f).roundToInt())
        val shoulder = max(1, (cols * 0.003f).roundToInt())
        val maxValleyWidth = max(halfValley + 1, (cols * 0.02f).roundToInt())
        val wideInner = max(2, (cols * 0.02f).roundToInt())
        val wideOuter = max(wideInner + 2, (cols * 0.07f).roundToInt())
        val actInner = max(2, (cols * 0.03f).roundToInt())
        val actOuter = max(actInner + 2, (cols * 0.14f).roundToInt())

        val from = max(maxValleyWidth + shoulder + 1, (cols * (0.5f - SEARCH_BAND / 2f)).toInt())
        val to = min(cols - maxValleyWidth - shoulder - 2, (cols * (0.5f + SEARCH_BAND / 2f)).toInt())

        var bestDepth = 0f
        var best: Candidate? = null
        var runnerUp: Candidate? = null
        var x = from
        while (x <= to) {
            val valley = minRange(profile, x - halfValley, x + halfValley)
            // Walk out of the dark valley: a fold is narrow, a shadow or a table edge is not.
            val edge = valley + 3f
            var leftEdge = x
            while (leftEdge > x - maxValleyWidth && profile[leftEdge - 1] <= edge) leftEdge--
            var rightEdge = x
            while (rightEdge < x + maxValleyWidth && profile[rightEdge + 1] <= edge) rightEdge++
            if (rightEdge - leftEdge + 1 > cols * 0.045f) {
                x++
                continue
            }
            val leftPeak = maxRange(profile, leftEdge - shoulder - 1, leftEdge - 1)
            val rightPeak = maxRange(profile, rightEdge + 1, rightEdge + shoulder + 1)
            if (min(leftPeak, rightPeak) - valley < 2f) {
                x++
                continue
            }
            val depth = max(leftPeak, rightPeak) - valley
            bestDepth = max(bestDepth, depth)
            if (depth < 4f) {
                x++
                continue
            }

            val leftPlateau = medianRange(profile, x - wideOuter, x - wideInner)
            val rightPlateau = medianRange(profile, x + wideInner, x + wideOuter)
            if (min(leftPlateau, rightPlateau) < 85f) {
                x++
                continue
            }
            val leftActivity = medianRange(activity, x - actOuter, x - actInner)
            val rightActivity = medianRange(activity, x + actInner, x + actOuter)
            val leftInk = medianRange(ink, x - actOuter, x - actInner)
            val rightInk = medianRange(ink, x + actInner, x + actOuter)
            val ratio = x.toFloat() / cols
            // Both sides must look like printed pages (ink on each side), not a wall shadow;
            // a very deep dip is accepted even without ink (blank pages, synthetic tests).
            val hasText = (leftInk >= 0.04f && rightInk >= 0.04f) || depth >= 60f
            val farOk = abs(ratio - 0.5f) <= CENTRE_WINDOW || depth >= DEEP_FOR_FAR
            if (!hasText || !farOk) {
                x++
                continue
            }

            val textScore = clamp01(max(leftActivity, rightActivity) / 12f)
            val brightScore = clamp01((min(leftPlateau, rightPlateau) - 85f) / 80f)
            val depthScore = clamp01((depth - 6f) / 22f)
            val centrePrior = 1f - 0.35f * abs(ratio - 0.5f) / 0.2f
            val score = depthScore * (0.6f + 0.4f * textScore) *
                (0.5f + 0.5f * brightScore) * centrePrior

            val candidate = Candidate(x, ratio, depth, depthScore, textScore, brightScore, score)
            val currentBest = best
            if (currentBest == null || score > currentBest.score) {
                if (currentBest != null) {
                    val currentRunnerUp = runnerUp
                    if (currentRunnerUp == null ||
                        (abs(currentBest.x - currentRunnerUp.x) > cols * 0.02f &&
                            currentBest.score > currentRunnerUp.score)
                    ) {
                        runnerUp = currentBest
                    }
                }
                best = candidate
            } else if (abs(x - currentBest.x) > cols * 0.02f) {
                val currentRunnerUp = runnerUp
                if (currentRunnerUp == null || score > currentRunnerUp.score) runnerUp = candidate
            }
            x++
        }

        val chosen = best?.takeIf { it.score >= FOUND_SCORE }
            ?: return Gutter(
                0.5f,
                found = false,
                confidence = 0f,
                singlePage = bestDepth < SINGLE_PAGE_DEPTH && countTextBlocks(ink, cols) == 1
            )

        val margin = clamp01(
            (chosen.score - (runnerUp?.score ?: 0f)) / max(chosen.score, 0.0001f)
        )
        val confidence = clamp01(
            0.55f * chosen.depthScore + 0.2f * chosen.textScore +
                0.15f * chosen.brightScore + 0.1f * margin
        )
        return Gutter(chosen.ratio, found = true, confidence = confidence, singlePage = false)
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

    /** One whole page (a one-page photo kept as-is), sampled to the same cap. */
    fun singlePageJpeg(bytes: ByteArray, quality: Int = 90): ByteArray {
        val bitmap = decode(bytes)
        return try {
            ImageTools.encodeJpeg(bitmap, quality)
        } finally {
            bitmap.recycle()
        }
    }

    // ------------------------------------------------------------------

    private data class Candidate(
        val x: Int,
        val ratio: Float,
        val depth: Float,
        val depthScore: Float,
        val textScore: Float,
        val brightScore: Float,
        val score: Float
    )

    private fun clamp01(value: Float): Float = value.coerceIn(0f, 1f)

    private fun luminance(pixel: Int): Int =
        (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000

    private fun smooth(values: FloatArray, radius: Int) {
        if (radius < 1 || values.size < 3) return
        val copy = values.copyOf()
        for (i in values.indices) {
            var sum = 0f
            var count = 0
            for (j in i - radius..i + radius) {
                if (j in copy.indices) {
                    sum += copy[j]
                    count++
                }
            }
            values[i] = sum / count
        }
    }

    private fun minRange(values: FloatArray, from: Int, to: Int): Float {
        var result = Float.MAX_VALUE
        for (i in from.coerceAtLeast(0)..to.coerceAtMost(values.size - 1)) {
            if (values[i] < result) result = values[i]
        }
        return result
    }

    private fun maxRange(values: FloatArray, from: Int, to: Int): Float {
        var result = -Float.MAX_VALUE
        for (i in from.coerceAtLeast(0)..to.coerceAtMost(values.size - 1)) {
            if (values[i] > result) result = values[i]
        }
        return result
    }

    private fun medianRange(values: FloatArray, from: Int, to: Int): Float {
        val lo = from.coerceIn(0, values.size - 1)
        val hi = (to + 1).coerceIn(lo + 1, values.size)
        val copy = values.copyOfRange(lo, hi)
        copy.sort()
        return copy[copy.size / 2]
    }

    /** Wide, ink-rich vertical runs: a spread has two, a one-page close-up has one. */
    private fun countTextBlocks(ink: FloatArray, cols: Int): Int {
        val minWidth = max(2, (cols * 0.05f).roundToInt())
        var blocks = 0
        var start = -1
        for (i in ink.indices) {
            if (ink[i] >= 0.05f) {
                if (start < 0) start = i
            } else if (start >= 0) {
                if (i - start >= minWidth) blocks++
                start = -1
            }
        }
        if (start >= 0 && ink.size - start >= minWidth) blocks++
        return blocks
    }
}
