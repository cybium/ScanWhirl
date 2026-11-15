package com.scanwhirl.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * v2 tool "Book pages" (`70_APP_DESIGN.md` §3, Create): split a photographed two-page spread
 * into its left and right pages.
 *
 * **Crop first.** The screen sends camera and gallery pages through the Google document scanner,
 * which detects the book edges and lets the owner adjust the corners; the manual photo fallback
 * crops before this engine runs. The engine still guards itself for un-cropped input:
 *  - [pageBox] finds the bright, ink-rich page region and searches inside it, never on the table;
 *  - the gutter is the narrowest lowest-ink band between text, taken from the **middle band** so
 *    a tilted book gives the mid-height spine (a straight split cannot follow a diagonal);
 *  - both sides must be page-bright and within 20%, the gap must carry real ink contrast, and
 *    the three bands must not disagree wildly.
 *
 * **Both orientations.** People hold the phone upright and photograph an open book sideways, so
 * the spine often runs **horizontally** (one page above the other). The engine scans both axes,
 * picks the more confident gutter, and for a horizontal spine returns two **upright** pages in
 * reading order: the top page rotated one way and the bottom page the other (their printed tops
 * face opposite edges). [topRotatesClockwise] is the offline margin heuristic; the screen can
 * replace it with ML Kit text recognition ([ui.book.TextRotation]) and the owner can flip it.
 *
 * When the evidence is weak the line goes to the middle (or stays at the best guess) with
 * [Gutter.confidence] below 0.4: the UI says "Check the line" and pulses it once. A photo with
 * one text block and no coherent gutter is offered as "Keep as one page".
 */
object BookSplit {

    const val MAX_LONG_SIDE = 3000
    const val SEARCH_BAND = 0.40f

    private const val ANALYSIS_COLUMNS = 1000
    private const val ROW_SAMPLES = 240
    private const val INK_THRESHOLD = 28

    private const val MIN_CONTRAST = 0.025f
    private const val MIN_PLATEAU = 90f
    private const val MAX_BAND_SPREAD = 0.20f
    private const val MIN_TIGHTNESS = 0.45f
    const val LOW_CONFIDENCE = 0.40f

    /**
     * A gutter position as a fraction of the bitmap along its axis (width for a vertical spine,
     * height for a horizontal one). [confidence] below 0.4 means the line is a best guess.
     */
    data class Gutter(
        val ratio: Float,
        val found: Boolean,
        val confidence: Float = if (found) 1f else 0f,
        val singlePage: Boolean = false,
        val horizontal: Boolean = false,
        /** Wide text blocks along the axis when nothing was found (one page vs a spread). */
        val textBlocks: Int = 0
    ) {
        val lowConfidence: Boolean get() = !found || confidence < LOW_CONFIDENCE
    }

    data class Split(
        val gutter: Gutter,
        val left: Bitmap,
        val right: Bitmap,
        val rotateTopClockwise: Boolean = true
    ) {
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

    fun split(bitmap: Bitmap, rotateTopClockwise: Boolean = true): Split =
        split(bitmap, scanGutter(bitmap), rotateTopClockwise)

    fun split(bitmap: Bitmap, gutter: Gutter, rotateTopClockwise: Boolean = true): Split {
        val width = bitmap.width
        val height = bitmap.height
        require(width > 1 && height > 1) { "The spread has no usable pixels" }
        val ratio = gutter.ratio.coerceIn(0.05f, 0.95f)
        if (!gutter.horizontal) {
            val cut = (ratio * width).roundToInt().coerceIn(1, width - 1)
            val left = Bitmap.createBitmap(bitmap, 0, 0, cut, height)
            val right = Bitmap.createBitmap(bitmap, cut, 0, width - cut, height)
            return Split(
                Gutter(cut.toFloat() / width, gutter.found, gutter.confidence, gutter.singlePage),
                left, right, rotateTopClockwise
            )
        }
        val cut = (ratio * height).roundToInt().coerceIn(1, height - 1)
        val top = Bitmap.createBitmap(bitmap, 0, 0, width, cut)
        val bottom = Bitmap.createBitmap(bitmap, 0, cut, width, height - cut)
        // The printed top of the top page faces one edge and the bottom page's the other.
        val topUpright = rotate(top, if (rotateTopClockwise) 90f else -90f)
        val bottomUpright = rotate(bottom, if (rotateTopClockwise) -90f else 90f)
        if (topUpright !== top) top.recycle()
        if (bottomUpright !== bottom) bottom.recycle()
        return Split(
            gutter.copy(ratio = cut.toFloat() / height),
            topUpright, bottomUpright, rotateTopClockwise
        )
    }

    internal fun debugScan(bitmap: Bitmap): String {
        val box = pageBox(bitmap)
        val v = analyse(bitmap, box, horizontal = false)
        val h = analyse(bitmap, box, horizontal = true)
        return "box=$box v=$v h=$h"
    }

    /** Finds the spread's gutter; scans the vertical and horizontal axes and keeps the better. */
    fun scanGutter(bitmap: Bitmap): Gutter {
        if (bitmap.width < 32 || bitmap.height < 32) {
            return Gutter(0.5f, false)
        }
        val box = pageBox(bitmap)
        val vertical = analyse(bitmap, box, horizontal = false)
        val horizontal = analyse(bitmap, box, horizontal = true)
        return when {
            vertical.found && !horizontal.found -> vertical
            horizontal.found && !vertical.found -> horizontal
            vertical.found && horizontal.found ->
                if (horizontal.confidence > vertical.confidence + 0.10f) horizontal else vertical
            else -> {
                // One page only when neither axis sees two page blocks: a sideways spread has
                // two blocks along its horizontal axis, so it must never be "Keep as one".
                val single = (vertical.singlePage || horizontal.singlePage) &&
                    vertical.textBlocks <= 1 && horizontal.textBlocks <= 1
                val horizontalLikely = horizontal.confidence > vertical.confidence + 0.10f
                Gutter(
                    0.5f, found = false, confidence = 0f, singlePage = single,
                    horizontal = horizontalLikely
                )
            }
        }
    }

    /**
     * Offline choice of the top page's rotation for a horizontal gutter: the page's outer margin
     * (top) is wider than its inner margin (bottom). The screen can override this with ML Kit.
     */
    fun topRotatesClockwise(bitmap: Bitmap, gutter: Gutter): Boolean {
        if (!gutter.horizontal) return true
        val cut = (gutter.ratio * bitmap.height).roundToInt().coerceIn(1, bitmap.height - 1)
        val top = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, cut)
        val small = Bitmap.createScaledBitmap(top, 400, max(1, 400 * cut / bitmap.width), true)
        if (small !== top) top.recycle()
        var best: Boolean? = null
        var bestMargin = -1f
        for (clockwise in listOf(true, false)) {
            val rotated = rotate(small, if (clockwise) 90f else -90f)
            val margin = topMargin(rotated)
            if (margin > bestMargin) {
                bestMargin = margin
                best = clockwise
            }
            rotated.recycle()
        }
        small.recycle()
        return best ?: true
    }

    // ------------------------------------------------------------------

    private data class Box(val x0: Int, val y0: Int, val x1: Int, val y1: Int)

    private fun analyse(bitmap: Bitmap, box: Box, horizontal: Boolean): Gutter {
        val along0 = if (horizontal) box.y0 else box.x0
        val along1 = if (horizontal) box.y1 else box.x1
        val cross0 = if (horizontal) box.x0 else box.y0
        val cross1 = if (horizontal) box.x1 else box.y1
        val length = along1 - along0
        if (length < 16 || cross1 - cross0 < 16) return Gutter(0.5f, false, horizontal = horizontal)

        val crossLength = cross1 - cross0
        val middle = BandStats.of(
            bitmap,
            cross0 + (crossLength * 0.38f).toInt(),
            cross0 + (crossLength * 0.61f).toInt(),
            along0, along1, horizontal
        )
        val top = BandStats.of(
            bitmap,
            cross0 + (crossLength * 0.15f).toInt(),
            cross0 + (crossLength * 0.38f).toInt(),
            along0, along1, horizontal
        )
        val bottom = BandStats.of(
            bitmap,
            cross0 + (crossLength * 0.61f).toInt(),
            cross0 + (crossLength * 0.84f).toInt(),
            along0, along1, horizontal
        )

        val bandRatios = listOf(top, middle, bottom).map { it.inkMinRatio() }
        val spread = if (bandRatios.size >= 2) bandRatios.max() - bandRatios.min() else 1f
        val agreement = clamp01(1f - spread / 0.15f)
        val imageLength = if (horizontal) bitmap.height else bitmap.width
        val tightness = clamp01(((length.toFloat() / imageLength) - 0.65f) / 0.20f)

        // A vertical spine (a book on a table) is best found by the lowest-ink margin; a
        // horizontal one (phone held upright, book sideways) needs a full scan because a blank
        // top margin can have less ink than the spine.
        val darkFold = middle.darkestFold()
        if (!horizontal) {
            val gap = middle.lowestInk()
            val inkAt = middle.inkAt(gap.x)
            val inkLeft = middle.medianInk(gap.x - middle.actOuter, gap.x - middle.actInner)
            val inkRight = middle.medianInk(gap.x + middle.actInner, gap.x + middle.actOuter)
            val contrast = max(inkLeft, inkRight) - inkAt
            val leftPlateau = middle.medianProfile(gap.x - middle.wideOuter, gap.x - middle.wideInner)
            val rightPlateau = middle.medianProfile(gap.x + middle.wideInner, gap.x + middle.wideOuter)
            val pageBright = min(leftPlateau, rightPlateau) >= MIN_PLATEAU
            val oneSidedBlank = (inkLeft < 0.02f) != (inkRight < 0.02f)
            val blankExtent = when {
                inkLeft < 0.02f && inkRight >= 0.02f -> gap.x.toFloat() / middle.cols
                inkRight < 0.02f && inkLeft >= 0.02f -> 1f - gap.x.toFloat() / middle.cols
                else -> 1f
            }
            val textOk = !oneSidedBlank || blankExtent >= 0.25f
            val contrastScore = clamp01(contrast / 0.15f)
            val brightScore = clamp01((min(leftPlateau, rightPlateau) - MIN_PLATEAU) / 80f)
            var confidence = clamp01(
                0.35f * contrastScore + 0.30f * agreement + 0.20f * brightScore
            )
            val gapValid = contrast >= MIN_CONTRAST && pageBright && textOk &&
                spread <= MAX_BAND_SPREAD && tightness >= MIN_TIGHTNESS
            if (!gapValid && darkFold == null) {
                val blocks = middle.textBlocks()
                return Gutter(
                    0.5f, found = false, confidence = 0f,
                    singlePage = blocks == 1,
                    horizontal = false,
                    textBlocks = blocks
                )
            }
            if (!gapValid) confidence = max(confidence, 0.75f)
            val local = if (gapValid) {
                (gap.x.toFloat() / middle.cols + 0.015f).coerceIn(0.05f, 0.95f)
            } else {
                darkFold!!.ratio
            }
            val fullRatio = (along0 + local * length) / imageLength.toFloat()
            return Gutter(
                fullRatio.coerceIn(0.05f, 0.95f), true, confidence, horizontal = false
            )
        }

        var best: Candidate? = null
        val lo = (middle.cols * (0.5f - middle.searchBand / 2f)).toInt().coerceAtLeast(2)
        val hi = (middle.cols * (0.5f + middle.searchBand / 2f)).toInt()
            .coerceAtMost(middle.cols - 2)
        var x = lo
        while (x <= hi) {
            val valley = middle.valleyAt(x)
            val nearLeft = middle.nearLeft(x)
            val nearRight = middle.nearRight(x)
            val depth = max(nearLeft, nearRight) - valley
            val leftPlateau = middle.plateauLeft(x)
            val rightPlateau = middle.plateauRight(x)
            val inkAt = middle.inkAt(x)
            val inkLeft = middle.medianInk(x - middle.actOuter, x - middle.actInner)
            val inkRight = middle.medianInk(x + middle.actInner, x + middle.actOuter)
            val contrast = max(inkLeft, inkRight) - inkAt
            val pageBright = min(leftPlateau, rightPlateau) >= MIN_PLATEAU
            val balance = if (max(leftPlateau, rightPlateau) > 0f) {
                min(leftPlateau, rightPlateau) / max(leftPlateau, rightPlateau)
            } else {
                0f
            }
            val oneSidedBlank = (inkLeft < 0.02f) != (inkRight < 0.02f)
            val blankExtent = when {
                inkLeft < 0.02f && inkRight >= 0.02f -> x.toFloat() / middle.cols
                inkRight < 0.02f && inkLeft >= 0.02f -> 1f - x.toFloat() / middle.cols
                else -> 1f
            }
            if (depth >= 4f && contrast >= MIN_CONTRAST && pageBright && balance >= 0.80f &&
                (!oneSidedBlank || blankExtent >= 0.25f)
            ) {
                val ratio = x.toFloat() / middle.cols
                val prior = 1f - 0.35f * kotlin.math.abs(ratio - 0.5f) / 0.2f
                val score = (0.45f * clamp01(contrast / 0.15f) +
                    0.45f * clamp01((depth - 5f) / 20f) +
                    0.10f * clamp01((min(leftPlateau, rightPlateau) - MIN_PLATEAU) / 80f)) *
                    (0.5f + 0.5f * clamp01((min(leftPlateau, rightPlateau) - MIN_PLATEAU) / 80f)) *
                    prior
                if (best == null || score > best!!.score) {
                    best = Candidate(x, ratio, score, depth, contrast, min(leftPlateau, rightPlateau))
                }
            }
            x++
        }

        val fold = darkFold
        val candidate = best
        val foldOk = fold != null && tightness >= MIN_TIGHTNESS
        val bestOk = candidate != null && candidate.score >= 0.40f &&
            tightness >= MIN_TIGHTNESS
        if (!foldOk && !bestOk) {
            val blocks = middle.textBlocks()
            return Gutter(
                0.5f, found = false, confidence = 0f,
                singlePage = blocks == 1,
                horizontal = horizontal,
                textBlocks = blocks
            )
        }
        val confidence: Float
        val local: Float
        if (foldOk) {
            confidence = clamp01(0.75f + 0.15f * agreement)
            local = fold!!.ratio
        } else {
            confidence = clamp01(
                0.50f * candidate!!.score + 0.30f * agreement +
                    0.20f * clamp01((candidate.plateau - MIN_PLATEAU) / 80f)
            )
            local = (candidate.ratio + 0.015f).coerceIn(0.05f, 0.95f)
        }
        val fullRatio = (along0 + local * length) / imageLength.toFloat()
        return Gutter(
            fullRatio.coerceIn(0.05f, 0.95f), true, confidence, horizontal = horizontal
        )
    }

    private data class Candidate(
        val x: Int,
        val ratio: Float,
        val score: Float,
        val depth: Float,
        val contrast: Float,
        val plateau: Float
    )

    // ------------------------------------------------------------------
    // Page region
    // ------------------------------------------------------------------

    private fun pageBox(bitmap: Bitmap): Box {
        val (cx0, cx1) = pageBoxAxis(bitmap, horizontal = true)
        val (cy0, cy1) = pageBoxAxis(bitmap, horizontal = false)
        return Box(cx0, cy0, cx1, cy1)
    }

    /** Columns (horizontal=true) or rows of the bright, ink-rich page region. */
    private fun pageBoxAxis(bitmap: Bitmap, horizontal: Boolean): Pair<Int, Int> {
        val along = if (horizontal) bitmap.width else bitmap.height
        val cross = if (horizontal) bitmap.height else bitmap.width
        val count = 160
        val med = FloatArray(count)
        val ink = FloatArray(count)
        val step = max(1, (cross + count - 1) / count)
        val samples = IntArray(cross / step + 2)
        for (i in 0 until count) {
            val a = ((i + 0.5f) * along / count).toInt().coerceIn(0, along - 1)
            var n = 0
            var c = 0
            while (c < cross) {
                val x = if (horizontal) a else c
                val y = if (horizontal) c else a
                samples[n++] = luminance(bitmap.getPixel(x, y))
                c += step
            }
            java.util.Arrays.sort(samples, 0, n)
            val m = samples[n / 2]
            med[i] = m.toFloat()
            var dark = 0
            while (dark < n && samples[dark] < m - INK_THRESHOLD) dark++
            ink[i] = dark.toFloat() / n
        }
        val (start, end) = expandBox(med, ink, along)
        return start to end
    }

    private fun expandBox(med: FloatArray, ink: FloatArray, size: Int): Pair<Int, Int> {
        var first = -1
        var last = -1
        for (i in ink.indices) {
            if (ink[i] >= 0.03f) {
                if (first < 0) first = i
                last = i
            }
        }
        if (first < 0) return 0 to size - 1
        val limit = 0.60f * percentile90(med)
        val cap = (med.size * 0.15f).roundToInt()
        var a = first
        while (a > 0 && first - a < cap && med[a - 1] >= limit) a--
        var b = last
        while (b < med.size - 1 && b - last < cap && med[b + 1] >= limit) b++
        val start = (a * size / med.size.toFloat()).roundToInt()
        val end = ((b + 1) * size / med.size.toFloat()).roundToInt()
        if (end - start < size * 0.15f) return 0 to size - 1
        return start.coerceIn(0, size - 1) to end.coerceIn(1, size)
    }

    private fun percentile90(values: FloatArray): Float {
        val copy = values.copyOf()
        copy.sort()
        return copy[(copy.size * 0.90f).toInt().coerceIn(0, copy.size - 1)]
    }

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

    private fun rotate(source: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
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

    fun splitToJpeg(
        bytes: ByteArray,
        gutter: Gutter? = null,
        rotateTopClockwise: Boolean = true,
        quality: Int = 90
    ): Pair<ByteArray, ByteArray> {
        val bitmap = decode(bytes)
        val split = try {
            split(bitmap, gutter ?: scanGutter(bitmap), rotateTopClockwise)
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

    fun singlePageJpeg(bytes: ByteArray, quality: Int = 90): ByteArray {
        val bitmap = decode(bytes)
        return try {
            ImageTools.encodeJpeg(bitmap, quality)
        } finally {
            bitmap.recycle()
        }
    }

    // ------------------------------------------------------------------

    private class BandStats(
        val profile: FloatArray,
        val ink: FloatArray,
        /** Mean luminance change across the perpendicular axis: text is busy, a fold is smooth. */
        val vary: FloatArray,
        val cols: Int,
        val actInner: Int,
        val actOuter: Int,
        val wideInner: Int,
        val wideOuter: Int,
        /** Fraction of the axis searched around the middle. A sideways notebook's fold can sit
         *  near an edge, so the horizontal axis searches wider than the classic 40%. */
        val searchBand: Float
    ) {
        data class Gap(val x: Int, val localRatio: Float)

        fun lowestInk(): Gap {
            val lo = (cols * (0.5f - searchBand / 2f)).toInt()
            val hi = (cols * (0.5f + searchBand / 2f)).toInt()
            var bestX = cols / 2
            var best = Float.MAX_VALUE
            var x = lo.coerceAtLeast(4)
            while (x < hi.coerceAtMost(cols - 5)) {
                val value = (ink[x - 3] + ink[x - 2] + ink[x - 1] + ink[x] +
                    ink[x + 1] + ink[x + 2] + ink[x + 3]) / 7f
                if (value < best) {
                    best = value
                    bestX = x
                }
                x++
            }
            return Gap(bestX, bestX.toFloat() / cols)
        }

        data class Valley(
            val ratio: Float,
            val depth: Float,
            val leftPlateau: Float,
            val rightPlateau: Float
        )

        fun deepValley(): Valley? {
            val lo = (cols * (0.5f - searchBand / 2f)).toInt()
            val hi = (cols * (0.5f + searchBand / 2f)).toInt()
            val half = max(2, (cols * 0.004f).roundToInt())
            var best: Valley? = null
            var x = lo.coerceAtLeast(half + 2)
            while (x < hi.coerceAtMost(cols - half - 2)) {
                val v = minRange(profile, x - half, x + half)
                val l = maxRange(profile, x - wideInner, x - wideInner / 3)
                val r = maxRange(profile, x + wideInner / 3, x + wideInner)
                val depth = max(l, r) - v
                if (depth >= 40f && (best == null || depth > best!!.depth)) {
                    best = Valley(x.toFloat() / cols, depth, l, r)
                }
                x++
            }
            return best
        }

        /** The darkest narrow column between page-bright plateaus, or null when there is none. */
        fun darkestFold(): Valley? {
            val lo = (cols * (0.5f - searchBand / 2f)).toInt().coerceAtLeast(2)
            val hi = (cols * (0.5f + searchBand / 2f)).toInt().coerceAtMost(cols - 2)
            var darkX = -1
            var dark = Float.MAX_VALUE
            var x = lo
            while (x <= hi) {
                val value = minOf(profile[x], profile[max(0, x - 1)], profile[min(cols - 1, x + 1)])
                if (value < dark) {
                    dark = value
                    darkX = x
                }
                x++
            }
            if (darkX < 0) return null
            // A text line is one or two pixels; a fold has a real width.
            val edge = dark + 3f
            var a = darkX
            while (a > 0 && profile[a - 1] <= edge && darkX - a < cols * 0.05f) a--
            var b = darkX
            while (b < cols - 1 && profile[b + 1] <= edge && b - darkX < cols * 0.05f) b++
            val width = (b - a + 1).toFloat() / cols
            if (width < 0.008f || width > 0.06f) return null
            val left = medianProfile(darkX - wideOuter, darkX - wideInner)
            val right = medianProfile(darkX + wideInner, darkX + wideOuter)
            val depth = min(left, right) - dark
            val balance = if (max(left, right) > 0f) min(left, right) / max(left, right) else 0f
            // A text line also looks dark in a row profile; a real fold has no ink at its centre.
            val valleyInk = inkAt(darkX)
            val valleyVary = medianVary(darkX - 2, darkX + 2)
            val flankVary = min(
                medianVary(darkX - wideOuter, darkX - wideInner),
                medianVary(darkX + wideInner, darkX + wideOuter)
            )
            return if (depth >= 50f && min(left, right) >= MIN_PLATEAU && balance >= 0.80f &&
                valleyInk <= 0.06f && valleyVary <= max(3f, 0.6f * flankVary)
            ) {
                Valley(darkX.toFloat() / cols, depth, left, right)
            } else {
                null
            }
        }

        fun inkAt(x: Int): Float = medianInk(x - 3, x + 3)
        fun medianInk(from: Int, to: Int): Float = median(ink, from, to)
        fun medianProfile(from: Int, to: Int): Float = median(profile, from, to)
        fun medianVary(from: Int, to: Int): Float = median(vary, from, to)

        /** The lowest-ink position, for the three-band agreement check. */
        fun inkMinRatio(): Float {
            val lo = (cols * (0.5f - searchBand / 2f)).toInt().coerceAtLeast(2)
            val hi = (cols * (0.5f + searchBand / 2f)).toInt().coerceAtMost(cols - 2)
            var bestX = lo
            var best = Float.MAX_VALUE
            var x = lo
            while (x <= hi) {
                val value = (ink[(x - 2).coerceAtLeast(0)] + ink[x] +
                    ink[(x + 2).coerceAtMost(cols - 1)]) / 3f
                if (value < best) {
                    best = value
                    bestX = x
                }
                x++
            }
            return bestX.toFloat() / cols
        }

        fun valleyAt(x: Int): Float = minRange(profile, x - 4, x + 4)
        fun nearLeft(x: Int): Float = maxRange(profile, x - wideInner, x - wideInner / 3)
        fun nearRight(x: Int): Float = maxRange(profile, x + wideInner / 3, x + wideInner)
        fun plateauLeft(x: Int): Float = medianProfile(x - wideOuter, x - wideInner)
        fun plateauRight(x: Int): Float = medianProfile(x + wideInner, x + wideOuter)

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
            fun of(
                bitmap: Bitmap,
                cross0: Int,
                cross1: Int,
                along0: Int,
                along1: Int,
                horizontal: Boolean
            ): BandStats {
                val searchBand = if (horizontal) 0.80f else SEARCH_BAND
                val width = bitmap.width
                val height = bitmap.height
                val length = max(1, along1 - along0)
                val cols = min(ANALYSIS_COLUMNS, length)
                val cross = max(4, cross1 - cross0)
                val crossStep = max(1, cross / ROW_SAMPLES)
                val samples = IntArray((cross / crossStep) + 2)
                val profile = FloatArray(cols)
                val ink = FloatArray(cols)
                val vary = FloatArray(cols)
                for (i in 0 until cols) {
                    val a = (along0 + (i + 0.5f) * length / cols).toInt()
                    var n = 0
                    var c = cross0
                    var previous = -1
                    var change = 0L
                    while (c < cross1) {
                        val x = if (horizontal) c else a
                        val y = if (horizontal) a else c
                        if (x in 0 until width && y in 0 until height) {
                            val value = luminance(bitmap.getPixel(x, y))
                            samples[n++] = value
                            if (previous >= 0) change += kotlin.math.abs(value - previous)
                            previous = value
                        }
                        c += crossStep
                    }
                    java.util.Arrays.sort(samples, 0, n)
                    val m = samples[n / 2]
                    profile[i] = m.toFloat()
                    vary[i] = if (n > 1) change.toFloat() / (n - 1) else 0f
                    var dark = 0
                    while (dark < n && samples[dark] < m - INK_THRESHOLD) dark++
                    ink[i] = dark.toFloat() / n
                }
                smooth(profile, 1)
                smooth(ink, max(1, cols / 150))
                smooth(vary, max(1, cols / 150))
                return BandStats(
                    profile, ink, vary, cols,
                    actInner = max(2, (cols * 0.03f).roundToInt()),
                    actOuter = max(6, (cols * 0.14f).roundToInt()),
                    wideInner = max(2, (cols * 0.02f).roundToInt()),
                    wideOuter = max(4, (cols * 0.07f).roundToInt()),
                    searchBand = searchBand
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
}
