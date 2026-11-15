package com.scanwhirl.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Path
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * v2 tool "Book pages" (`70_APP_DESIGN.md` §3, Create).
 *
 * The scanner perspective-crops every page first. Then, on the cropped image:
 *  1. **Text direction** from projection profiles: horizontal text makes the ROW profile
 *     periodic at the line pitch, vertical text the COLUMN profile (autocorrelation in 8-60 px).
 *     Vertical text is rotated 90/270 so the spread is upright and the spine becomes vertical:
 *     the upright half is the one whose blank page margin (page number / running head at the
 *     top, the wide margin below the text) is at the bottom. ML Kit confirms it on device and
 *     the preview always offers "Flip pages" for the other half.
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
        val textBlocks: Int = 0,
        /** The spine at the top and bottom of the image, as fractions of the width. */
        val topRatio: Float = ratio,
        val bottomRatio: Float = ratio
    ) {
        val lowConfidence: Boolean get() = !found || confidence < LOW_CONFIDENCE

        /** True when the gutter is a slanted line rather than a vertical one. */
        val slanted: Boolean get() = found && abs(topRatio - bottomRatio) >= 0.004f

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
        val rotation = if (verticalText) {
            sidewaysRotation(bitmap)
        } else {
            uprightFlip(bitmap)
        }
        return analyseUpright(bitmap, rotation).copy(textVertical = verticalText)
    }

    /**
     * 90 vs 270 for a sideways page. A printed page has its running head / folio at the top
     * and its widest blank margin below the last text line, so the upright candidate is the
     * one whose blank band sits in the lower half of the page. The old outer-margin test had
     * this sign inverted (it chose the larger *top* blank, i.e. the upside-down half).
     *
     * The cue is deliberately reported as weak unless one candidate wins by 10% of the page
     * height: then the UI asks ML Kit and the preview always offers "Flip pages".
     */
    private fun sidewaysRotation(bitmap: Bitmap): Int {
        val at90 = blankMarginPosition(bitmap, 90)
        val at270 = blankMarginPosition(bitmap, 270)
        return when {
            at90 == null || at270 == null -> 90
            at270 - at90 > 0.10f -> 270
            at90 - at270 > 0.10f -> 90
            else -> 90
        }
    }

    /**
     * 0 vs 180 for an upright spread: flip only when the ascender/descender asymmetry points
     * clearly the other way. Otherwise keep 0; the device path asks ML Kit, and the preview
     * has the one-tap "Flip pages" button.
     */
    private fun uprightFlip(bitmap: Bitmap): Int {
        // Offline an upside-down spread is genuinely ambiguous: the ascender asymmetry that
        // looks conclusive on synthetic text is a few thousandths on real photos, and books
        // differ in where the folio sits (top corner, bottom corner). Do not flip on that;
        // the device path asks ML Kit (0 vs 180) and every preview has "Flip pages".
        return 0
    }

    /**
     * Height (0 = top, 1 = bottom) of the quietest band in the [rotation] candidate.
     *
     * A printed page's widest blank margin sits below the last text line (the running head and
     * page number are above it), so the upright candidate has its quiet band near the bottom
     * and the 180° turn near the top. Text rows, the page stack and the dark cloth around the
     * book all carry ink; only the blank margin is quiet, so the band is found from the
     * lowest-ink rows of the whole image. The device confirms the winner with ML Kit and the
     * preview always offers "Flip pages".
     */
    internal fun blankMarginPosition(bitmap: Bitmap, rotation: Int): Float? {
        val turned = rotate(bitmap, rotation)
        val targetWidth = 500
        val small = if (turned.width <= targetWidth) turned else Bitmap.createScaledBitmap(
            turned,
            targetWidth,
            max(1, turned.height * targetWidth / turned.width),
            true
        )
        val w = small.width
        val h = small.height
        val grey = FloatArray(w * h) { i -> luminance(small.getPixel(i % w, i / w)).toFloat() }
        if (small !== turned) small.recycle()
        if (turned !== bitmap) turned.recycle()

        val x0 = (w * 0.10f).toInt()
        val x1 = (w * 0.90f).toInt()
        val stride = max(1, (x1 - x0) / 200)
        val pixels = ArrayList<Int>(h * 160)
        for (y in 0 until h) {
            var x = x0
            while (x < x1) {
                pixels.add(grey[y * w + x].toInt())
                x += stride
            }
        }
        if (pixels.size < 64) return null
        pixels.sort()
        val pageMedian = pixels[pixels.size / 2].toFloat()

        val ink = FloatArray(h)
        for (y in 0 until h) {
            var dark = 0
            var n = 0
            var x = x0
            while (x < x1) {
                if (grey[y * w + x] < pageMedian - 35f) dark++
                n++
                x += stride
            }
            ink[y] = dark.toFloat() / n
        }
        smooth(ink, 1)

        // The quiet band is the longest run at (or just above) the lowest ink. Start narrow so
        // inter-line gaps cannot merge into the margin; widen only if no run is found.
        val minInk = ink.min()
        for (extra in floatArrayOf(0.02f, 0.03f, 0.04f, 0.06f)) {
            val threshold = minInk + extra
            var runStart = -1
            var bestStart = -1
            var bestEnd = -2
            for (i in ink.indices) {
                if (ink[i] <= threshold) {
                    if (runStart < 0) runStart = i
                    if (i - runStart > bestEnd - bestStart) {
                        bestStart = runStart
                        bestEnd = i
                    }
                } else {
                    runStart = -1
                }
            }
            if (bestEnd - bestStart >= 2) {
                return ((bestStart + bestEnd + 1) / 2f) / h
            }
        }
        return null
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
                singlePage = false, textBlocks = blocks,
                topRatio = valley.topRatio, bottomRatio = valley.bottomRatio
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
        if (gutter.slanted) return splitLine(bitmap, gutter)
        val ratio = gutter.ratio.coerceIn(0.05f, 0.95f)
        val cut = (ratio * width).roundToInt().coerceIn(1, width - 1)
        val left = Bitmap.createBitmap(bitmap, 0, 0, cut, height)
        val right = Bitmap.createBitmap(bitmap, cut, 0, width - cut, height)
        return Split(
            gutter.copy(ratio = cut.toFloat() / width, horizontal = false),
            left, right
        )
    }

    /**
     * Two pages cut along a slanted spine. Each page is its side of the line, the other side is
     * filled white, and the result is cropped to that side's bounding box (a white wedge remains
     * on the tilted edge). This keeps the first letters of the far page's top lines and the ends
     * of the near page's bottom lines on the correct page.
     */
    private fun splitLine(bitmap: Bitmap, gutter: Gutter): Split {
        val width = bitmap.width
        val height = bitmap.height
        val top = gutter.topRatio.coerceIn(0.03f, 0.97f) * width
        val bottom = gutter.bottomRatio.coerceIn(0.03f, 0.97f) * width
        val leftWidth = ceil(max(top, bottom)).toInt().coerceIn(1, width)
        val rightStart = floor(min(top, bottom)).toInt().coerceIn(0, width - 1)

        val left = Bitmap.createBitmap(leftWidth, height, Bitmap.Config.ARGB_8888)
        left.eraseColor(Color.WHITE)
        Canvas(left).apply {
            val path = Path().apply {
                moveTo(0f, 0f)
                lineTo(top, 0f)
                lineTo(bottom, height.toFloat())
                lineTo(0f, height.toFloat())
                close()
            }
            save()
            clipPath(path)
            drawBitmap(bitmap, 0f, 0f, null)
            restore()
        }

        val right = Bitmap.createBitmap(width - rightStart, height, Bitmap.Config.ARGB_8888)
        right.eraseColor(Color.WHITE)
        Canvas(right).apply {
            translate(-rightStart.toFloat(), 0f)
            val path = Path().apply {
                moveTo(top, 0f)
                lineTo(width.toFloat(), 0f)
                lineTo(width.toFloat(), height.toFloat())
                lineTo(bottom, height.toFloat())
                close()
            }
            save()
            clipPath(path)
            drawBitmap(bitmap, 0f, 0f, null)
            restore()
        }

        return Split(
            gutter.copy(
                horizontal = false,
                topRatio = (top / width).coerceIn(0.05f, 0.95f),
                bottomRatio = (bottom / width).coerceIn(0.05f, 0.95f)
            ),
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
        val blocks: Int,
        val topRatio: Float = ratio,
        val bottomRatio: Float = ratio
    )

    private fun findValley(upright: Bitmap): Valley {
        val h = upright.height
        val full = valleyInBand(
            upright,
            (h * 0.15f).toInt(),
            (h * 0.85f).toInt()
        ) ?: return Valley(0.5f, false, 0f, 0)
        // The gutter is straight but the photo is usually tilted: estimate it separately in
        // a top and a bottom band, then cut along the line through the two estimates.
        val topY0 = (h * 0.15f).toInt()
        val topY1 = (h * 0.42f).toInt()
        val bottomY0 = (h * 0.58f).toInt()
        val bottomY1 = (h * 0.85f).toInt()
        val top = valleyInBand(upright, topY0, topY1)
        val bottom = valleyInBand(upright, bottomY0, bottomY1)
        if (top != null && bottom != null) {
            val centerTop = (topY0 + topY1) / 2f
            val centerBottom = (bottomY0 + bottomY1) / 2f
            val dy = centerBottom - centerTop
            if (dy > 0f) {
                val slope = (bottom.ratio - top.ratio) / dy
                val rawTop = top.ratio + slope * (0f - centerTop)
                val rawBottom = top.ratio + slope * (h - 1f - centerTop)
                // The two band centres are noisy; keep 60% of their lean towards the full-band
                // estimate. A line that disagrees with the full estimate is a bad band pick.
                val lean = 0.45f
                val lineTop = (full.ratio + lean * (rawTop - full.ratio)).coerceIn(0.05f, 0.95f)
                val lineBottom = (full.ratio + lean * (rawBottom - full.ratio)).coerceIn(0.05f, 0.95f)
                val lineMid = (lineTop + lineBottom) / 2f
                val angle = Math.toDegrees(
                    kotlin.math.atan2(
                        (abs(lineBottom - lineTop) * upright.width).toDouble(),
                        (h - 1f).toDouble()
                    )
                ).toFloat()
                if (angle <= 8f && abs(lineBottom - lineTop) >= 0.01f &&
                    abs(lineMid - full.ratio) <= 0.012f
                ) {
                    return Valley(
                        full.ratio,
                        true,
                        min(top.confidence, bottom.confidence),
                        full.blocks,
                        lineTop,
                        lineBottom
                    )
                }
            }
        }
        return full
    }

    private fun valleyInBand(upright: Bitmap, y0: Int, y1: Int): Valley? {
        val (candidates, blocks) = valleyScan(upright, y0, y1)
        val chosen = candidates.maxByOrNull { it.score } ?: return null
        return Valley(
            (chosen.ratio + 0.01f).coerceIn(0.05f, 0.95f),
            true,
            clamp01(0.55f * chosen.score + 0.45f * clamp01((chosen.depth - 10f) / 20f)),
            blocks
        )
    }

    private data class Candidate(val ratio: Float, val score: Float, val depth: Float)

    private fun valleyScan(upright: Bitmap, y0: Int, y1: Int): Pair<List<Candidate>, Int> {
        val band = BandStats.of(upright, y0, y1)
        val blocks = band.textBlocks()
        val out = ArrayList<Candidate>()
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
            // A cut through a text line has ink at the cut and similar ink on BOTH sides of it.
            // That is never the spine. A real gutter is a dark shadow (deep on both sides) or a
            // clean gap where the text does not continue across the cut.
            val deepShadow = min(left, right) - valley >= 25f
            val textCrossing = abs(inkL - inkR) < 0.06f && inkAt > 0.08f &&
                inkAt >= min(inkL, inkR) - 0.03f
            val gap = deepShadow || !textCrossing
            val accepted = bright && balance >= 0.80f && gap &&
                (depth >= 10f || contrast >= 0.05f)
            if (accepted) {
                val score = 0.60f * clamp01(depth / 30f) + 0.40f * clamp01(contrast / 0.15f)
                out += Candidate(x.toFloat() / band.cols, score, depth)
            }
            x++
        }
        return out to blocks
    }

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
