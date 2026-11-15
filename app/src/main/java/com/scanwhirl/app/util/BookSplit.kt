package com.scanwhirl.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * v2 tool "Book pages" (`70_APP_DESIGN.md` §3, Create).
 *
 * The scanner perspective-crops every page first. Then, on the cropped image:
 *  1. **Text direction** from projection profiles: horizontal text makes the ROW profile
 *     periodic at the line pitch, vertical text the COLUMN profile (autocorrelation in 8-60 px).
 *     Vertical text is rotated 90° (ML Kit on device; margin heuristic offline) so the spread is
 *     upright and the spine becomes vertical.
 *  2. **Shape** on the upright image: aspect width/height ≥ 1.15 is a two-page spread, ≤ 0.95 is
 *     one page ("Keep as one page" is the default), in between the gutter evidence decides.
 *     This is what stops a two-column single page from being split at its column gap.
 *  3. **Cut** only in the middle 30% of the width: a dark narrow valley / brightness dip between
 *     two text blocks. No confident valley → the exact middle and "Check the line."
 *
 * Decoding is sampled to [MAX_LONG_SIDE]; the rotated full-resolution copy exists only inside
 * [splitToJpeg] and is recycled with the crops.
 */
object BookSplit {

    const val MAX_LONG_SIDE = 3000
    const val LOW_CONFIDENCE = 0.40f

    private const val ANALYSIS_COLUMNS = 1000
    private const val ROW_SAMPLES = 240
    private const val INK_THRESHOLD = 28
    private const val MIN_PLATEAU = 90f
    private const val SPREAD_ASPECT = 1.15f
    private const val PAGE_ASPECT = 0.95f
    private const val MIDDLE = 0.30f

    data class Gutter(
        val ratio: Float,
        val found: Boolean,
        val confidence: Float = if (found) 1f else 0f,
        val singlePage: Boolean = false,
        /** Kept for source compatibility; the upright-spread model is always vertical. */
        val horizontal: Boolean = false,
        val textBlocks: Int = 0
    ) {
        val lowConfidence: Boolean get() = !found || confidence < LOW_CONFIDENCE

        companion object {
            val MIDDLE = Gutter(0.5f, false)
        }
    }

    /** A prepared spread: [rotation] is the clockwise degrees applied to make text upright. */
    data class Analysis(
        val rotation: Int,
        val gutter: Gutter,
        val textVertical: Boolean,
        /** Width/height after the text-direction rotation. */
        val aspect: Float = 1f
    )

    data class Split(val gutter: Gutter, val left: Bitmap, val right: Bitmap) {
        val pages: List<Bitmap> get() = listOf(left, right)
    }

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

    fun rotate(source: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return source
        return Bitmap.createBitmap(
            source, 0, 0, source.width, source.height,
            Matrix().apply { postRotate(degrees.toFloat()) }, true
        )
    }

    /**
     * Full analysis of a scanner-cropped page: text direction, rotation, shape and gutter.
     * The returned bitmap rotation is applied by the UI preview and by [splitToJpeg].
     */
    fun analyse(bitmap: Bitmap): Analysis {
        val (rowScore, colScore) = textScores(bitmap)
        val ambiguous = max(rowScore, colScore) < 0.25f
        val verticalText = when {
            // A strong spine in one axis is the reliable cue when the projections are ambiguous
            // (dense/curved pages): the owner's sideways book is exactly this case.
            ambiguous -> {
                val horizontalSpine = spineStrength(bitmap, horizontal = true)
                val verticalSpine = spineStrength(bitmap, horizontal = false)
                horizontalSpine > verticalSpine * 1.25f && horizontalSpine > 25f
            }
            colScore > rowScore * 1.20f && colScore > 0.12f -> true
            else -> false
        }
        val rotation = if (verticalText) uprightRotation(bitmap) else 0
        return analyseUpright(bitmap, rotation).copy(textVertical = verticalText)
    }

    /** Strength of a dark spine in the middle 30% of the axis (row band or column band). */
    private fun spineStrength(bitmap: Bitmap, horizontal: Boolean): Float {
        val small = downscale(bitmap, 300)
        val w = small.width
        val h = small.height
        val grey = FloatArray(w * h) { i ->
            luminance(small.getPixel(i % w, i / w)).toFloat()
        }
        if (small !== bitmap) small.recycle()
        val n = if (horizontal) h else w
        val profile = FloatArray(n) { a ->
            var sum = 0f
            if (horizontal) {
                for (x in 0 until w) sum += grey[a * w + x]
                sum / w
            } else {
                for (y in 0 until h) sum += grey[y * w + a]
                sum / h
            }
        }
        val lo = (n * 0.35f).toInt()
        val hi = (n * 0.65f).toInt()
        val plateau = max(2, (n * 0.04f).toInt())
        fun meanRange(from: Int, to: Int): Float {
            var sum = 0f
            var count = 0
            for (i in from.coerceAtLeast(0)..to.coerceAtMost(n - 1)) {
                sum += profile[i]
                count++
            }
            return if (count > 0) sum / count else 0f
        }

        var best = 0f
        for (x in lo + plateau..hi - plateau) {
            val dark = profile[x]
            val left = meanRange(x - plateau * 2, x - plateau)
            val right = meanRange(x + plateau, x + plateau * 2)
            if (min(left, right) < MIN_PLATEAU) continue
            best = max(best, min(left, right) - dark)
        }
        return best
    }

    /** Shape and gutter on an image already turned upright by [rotation]. */
    fun analyseUpright(bitmap: Bitmap, rotation: Int): Analysis {
        val upright = rotate(bitmap, rotation)
        val aspect = upright.width.toFloat() / upright.height
        val valley = findValley(upright)
        val blocks = valley.blocks
        val onePageByShape = aspect <= PAGE_ASPECT
        val spreadByShape = aspect >= SPREAD_ASPECT
        val confidentValley = valley.found
        val single = onePageByShape ||
            (!spreadByShape && !confidentValley && blocks <= 1)
        val gutter = when {
            single -> Gutter(0.5f, found = false, confidence = 0f, singlePage = true,
                textBlocks = blocks)
            spreadByShape && !confidentValley ->
                Gutter(0.5f, found = false, confidence = 0f, singlePage = false,
                    textBlocks = blocks)
            confidentValley -> Gutter(
                valley.ratio, found = true, confidence = valley.confidence,
                singlePage = false, textBlocks = blocks
            )
            else -> Gutter(0.5f, found = false, confidence = 0f, singlePage = false,
                textBlocks = blocks)
        }
        if (upright !== bitmap) upright.recycle()
        return Analysis(rotation, gutter, textVertical = false, aspect = aspect)
    }

    /** The old entry point used by simple callers/tests: no text-rotation, no shape override. */
    fun scanGutter(bitmap: Bitmap): Gutter = analyse(bitmap).gutter

    fun split(bitmap: Bitmap): Split = split(bitmap, scanGutter(bitmap))

    fun split(bitmap: Bitmap, gutter: Gutter): Split {
        val width = bitmap.width
        val height = bitmap.height
        require(width > 1 && height > 1) { "The spread has no usable pixels" }
        val ratio = gutter.ratio.coerceIn(0.05f, 0.95f)
        val cut = (ratio * width).roundToInt().coerceIn(1, width - 1)
        val left = Bitmap.createBitmap(bitmap, 0, 0, cut, height)
        val right = Bitmap.createBitmap(bitmap, cut, 0, width - cut, height)
        return Split(
            gutter.copy(ratio = cut.toFloat() / width, horizontal = false),
            left, right
        )
    }

    /** Compatibility overload for callers that already hold a gutter. */
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

    /** Splits [bytes] using an analysis (rotation included); two upright pages in reading order. */
    fun splitToJpeg(
        bytes: ByteArray,
        analysis: Analysis,
        quality: Int = 90
    ): Pair<ByteArray, ByteArray> {
        val bitmap = decode(bytes)
        val upright = rotate(bitmap, analysis.rotation)
        if (upright !== bitmap) bitmap.recycle()
        val split = try {
            split(upright, analysis.gutter)
        } finally {
            if (upright !== bitmap) upright.recycle()
        }
        return try {
            ImageTools.encodeJpeg(split.left, quality) to ImageTools.encodeJpeg(split.right, quality)
        } finally {
            split.left.recycle()
            split.right.recycle()
        }
    }

    /** Keeps one page whole, rotated upright for the reading direction. */
    fun singlePageJpeg(bytes: ByteArray, rotation: Int, quality: Int = 90): ByteArray {
        val bitmap = decode(bytes)
        val upright = rotate(bitmap, rotation)
        return try {
            ImageTools.encodeJpeg(upright, quality)
        } finally {
            if (upright !== bitmap) upright.recycle()
            bitmap.recycle()
        }
    }

    // ------------------------------------------------------------------
    // 1. Text direction
    // ------------------------------------------------------------------

    private fun textScores(bitmap: Bitmap): Pair<Float, Float> {
        val small = downscale(bitmap, 600)
        val grey = FloatArray(small.width * small.height)
        for (y in 0 until small.height) {
            for (x in 0 until small.width) {
                grey[y * small.width + x] = luminance(small.getPixel(x, y)).toFloat()
            }
        }
        val w = small.width
        val h = small.height
        if (small !== bitmap) small.recycle()

        val row = FloatArray(h) { y ->
            var sum = 0f
            for (x in 0 until w) sum += grey[y * w + x]
            sum / w
        }
        val col = FloatArray(w) { x ->
            var sum = 0f
            for (y in 0 until h) sum += grey[y * w + x]
            sum / h
        }
        return periodicity(row, 8, 60) to periodicity(col, 8, 60)
    }

    /** Autocorrelation peak in the line-pitch band, normalised by the profile variance. */
    private fun periodicity(profile: FloatArray, minLag: Int, maxLag: Int): Float {
        val n = profile.size
        if (n < maxLag * 2) return 0f
        val mean = profile.average().toFloat()
        val centered = FloatArray(n) { profile[it] - mean }
        val energy = centered.fold(0f) { acc, v -> acc + v * v }
        if (energy <= 1e-6f) return 0f
        val top = min(maxLag, n / 2)
        val corr = FloatArray(top + 3)
        for (lag in minLag..top) {
            var sum = 0f
            for (i in 0 until n - lag) sum += centered[i] * centered[i + lag]
            corr[lag] = sum / energy
        }
        // A single dark band also correlates with its own width; real text has a periodic
        // local maximum at the line pitch. Require that peak, not just any correlation.
        var best = 0f
        for (lag in (minLag + 2)..(top - 2)) {
            val value = corr[lag]
            if (value >= best && value >= corr[lag - 2] && value >= corr[lag + 2]) {
                best = value
            }
        }
        return if (best >= 0.15f) best else 0f
    }

    /** Clockwise rotation (90 or 270) that puts the outer margin on top. */
    private fun uprightRotation(bitmap: Bitmap): Int {
        val a = rotate(bitmap, 90)
        val b = rotate(bitmap, 270)
        val clockwise = topMargin(a) >= topMargin(b)
        if (a !== bitmap) a.recycle()
        if (b !== bitmap) b.recycle()
        return if (clockwise) 90 else 270
    }

    private fun topMargin(page: Bitmap): Float {
        val stepY = max(1, page.height / 200)
        val stepX = max(1, page.width / 200)
        var first = -1
        var last = -1
        var y = 0
        while (y < page.height) {
            var sum = 0f
            var n = 0
            var x = 0
            while (x < page.width) {
                sum += luminance(page.getPixel(x, y))
                n++
                x += stepX
            }
            val median = sum / n
            var dark = 0
            x = 0
            while (x < page.width) {
                if (luminance(page.getPixel(x, y)) < median - INK_THRESHOLD) dark++
                x += stepX
            }
            if (dark.toFloat() / n >= 0.02f) {
                if (first < 0) first = y
                last = y
            }
            y += stepY
        }
        if (first < 0) return 0f
        return (first.toFloat() / page.height) - (1f - last.toFloat() / page.height)
    }

    // ------------------------------------------------------------------
    // 2-3. Shape and cut
    // ------------------------------------------------------------------

    private data class Valley(
        val ratio: Float,
        val found: Boolean,
        val confidence: Float,
        val blocks: Int
    )

    private fun findValley(upright: Bitmap): Valley {
        val y0 = (upright.height * 0.15f).toInt()
        val y1 = (upright.height * 0.85f).toInt()
        val band = BandStats.of(upright, y0, y1)
        val blocks = band.textBlocks()
        var best: Candidate? = null
        val lo = (band.cols * (0.5f - MIDDLE / 2f)).toInt().coerceAtLeast(3)
        val hi = (band.cols * (0.5f + MIDDLE / 2f)).toInt().coerceAtMost(band.cols - 3)
        var x = lo
        while (x <= hi) {
            val valley = band.valleyAt(x)
            val left = band.plateauLeft(x)
            val right = band.plateauRight(x)
            val depth = max(band.nearLeft(x), band.nearRight(x)) - valley
            val inkAt = band.inkAt(x)
            val inkL = band.medianInk(x - band.actOuter, x - band.actInner)
            val inkR = band.medianInk(x + band.actInner, x + band.actOuter)
            val contrast = max(inkL, inkR) - inkAt
            val bright = min(left, right) >= MIN_PLATEAU
            val balance = if (max(left, right) > 0f) min(left, right) / max(left, right) else 0f
            if (bright && balance >= 0.80f && (depth >= 10f || contrast >= 0.05f)) {
                val score = 0.60f * clamp01(depth / 30f) + 0.40f * clamp01(contrast / 0.15f)
                if (best == null || score > best!!.score) {
                    best = Candidate(x, x.toFloat() / band.cols, score, depth, contrast)
                }
            }
            x++
        }
        val chosen = best
        return if (chosen == null) {
            Valley(0.5f, false, 0f, blocks)
        } else {
            Valley(
                (chosen.ratio + 0.01f).coerceIn(0.05f, 0.95f),
                true,
                clamp01(0.55f * chosen.score + 0.45f * clamp01((chosen.depth - 10f) / 20f)),
                blocks
            )
        }
    }

    private data class Candidate(
        val x: Int,
        val ratio: Float,
        val score: Float,
        val depth: Float,
        val contrast: Float
    )

    // ------------------------------------------------------------------

    private class BandStats(
        val profile: FloatArray,
        val ink: FloatArray,
        val cols: Int,
        val actInner: Int,
        val actOuter: Int,
        val wideInner: Int,
        val wideOuter: Int
    ) {
        fun valleyAt(x: Int): Float = minRange(profile, x - 4, x + 4)
        fun nearLeft(x: Int): Float = maxRange(profile, x - wideInner, x - wideInner / 3)
        fun nearRight(x: Int): Float = maxRange(profile, x + wideInner / 3, x + wideInner)
        fun plateauLeft(x: Int): Float = median(profile, x - wideOuter, x - wideInner)
        fun plateauRight(x: Int): Float = median(profile, x + wideInner, x + wideOuter)
        fun inkAt(x: Int): Float = median(ink, x - 3, x + 3)
        fun medianInk(from: Int, to: Int): Float = median(ink, from, to)

        fun textBlocks(): Int {
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

        companion object {
            fun of(bitmap: Bitmap, y0: Int, y1: Int): BandStats {
                val width = bitmap.width
                val cols = min(ANALYSIS_COLUMNS, width)
                val sy = max(4, y1 - y0)
                val rowStep = max(1, sy / ROW_SAMPLES)
                val samples = IntArray((sy / rowStep) + 2)
                val profile = FloatArray(cols)
                val ink = FloatArray(cols)
                for (i in 0 until cols) {
                    val x = ((i + 0.5f) * width / cols).toInt().coerceIn(0, width - 1)
                    var n = 0
                    var y = y0
                    while (y < y1) {
                        samples[n++] = luminance(bitmap.getPixel(x, y))
                        y += rowStep
                    }
                    java.util.Arrays.sort(samples, 0, n)
                    val m = samples[n / 2]
                    profile[i] = m.toFloat()
                    var dark = 0
                    while (dark < n && samples[dark] < m - INK_THRESHOLD) dark++
                    ink[i] = dark.toFloat() / n
                }
                smooth(profile, 1)
                smooth(ink, max(1, cols / 150))
                return BandStats(
                    profile, ink, cols,
                    actInner = max(2, (cols * 0.03f).roundToInt()),
                    actOuter = max(6, (cols * 0.14f).roundToInt()),
                    wideInner = max(2, (cols * 0.02f).roundToInt()),
                    wideOuter = max(4, (cols * 0.07f).roundToInt())
                )
            }

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
        }
    }

    private fun downscale(source: Bitmap, maxWidth: Int): Bitmap =
        if (source.width <= maxWidth) source else Bitmap.createScaledBitmap(
            source, maxWidth, max(1, source.height * maxWidth / source.width), true
        )

    private fun luminance(pixel: Int): Int =
        (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000

    private fun clamp01(value: Float): Float = value.coerceIn(0f, 1f)

    private fun median(values: FloatArray, from: Int, to: Int): Float {
        val lo = from.coerceIn(0, values.size - 1)
        val hi = (to + 1).coerceIn(lo + 1, values.size)
        val copy = values.copyOfRange(lo, hi)
        copy.sort()
        return copy[copy.size / 2]
    }
}
