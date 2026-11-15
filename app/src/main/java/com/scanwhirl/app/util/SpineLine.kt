package com.scanwhirl.app.util

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Finds a (possibly tilted) book spine as a straight line through the empty gap between the two
 * text blocks of an upright spread. Books are photographed at an angle, so the gutter is often
 * further left at the top than at the bottom; a single vertical cut then slices words.
 *
 * Method (lead, 11-15):
 *  1. INK = a pixel clearly darker than its own neighbourhood (local box mean). Text strokes are
 *     thin and sharp; the fold's shadow is a smooth gradient, so it is NOT ink. (A global
 *     darkness threshold counts the shadow as ink, and then no clean gap ever exists.)
 *  2. In ~16 horizontal bands, find the widest ink-free run of columns near the expected spine;
 *     the band's vote is the darkest column (fold shadow) inside that run.
 *  3. Fit x = a + b*y through the votes with Theil-Sen (median of pairwise slopes), drop outliers,
 *     refit. Tilt is limited to about 8 degrees.
 *  4. Verify: walk the line and count rows where it touches ink. If it does, slide the line
 *     sideways within +-2% of the width to the offset touching the least ink; accept only if
 *     the line then touches ink on at most [MAX_INK_ROWS_FRACTION] of the rows.
 * Returns null when there is no confident line; callers keep the vertical cut.
 */
object SpineLine {

    /** [topRatio]/[bottomRatio]: x of the line at the top/bottom edge, as a fraction of width. */
    data class Line(
        val topRatio: Float,
        val bottomRatio: Float,
        /** Fraction of rows where the line touches ink (after the sideways adjustment). */
        val inkRowsFraction: Float,
        val votes: Int
    ) {
        val middleRatio: Float get() = (topRatio + bottomRatio) / 2f
        fun ratioAt(yFraction: Float): Float = topRatio + (bottomRatio - topRatio) * yFraction
    }

    private const val WORK_WIDTH = 900
    private const val BANDS = 16
    private const val SEARCH_HALF = 0.14f
    private const val MIN_GAP = 0.006f
    private const val MAX_TILT = 0.14f // about 8 degrees
    private const val MAX_INK_ROWS_FRACTION = 0.01f
    private const val MIN_VOTES = 5

    /** The work grid: luminance plus an ink mask, at about [WORK_WIDTH] px wide. */
    class Grid(val w: Int, val h: Int, val grey: IntArray, val ink: BooleanArray)

    fun grid(bitmap: Bitmap): Grid {
        val scale = min(1f, WORK_WIDTH.toFloat() / bitmap.width)
        val small = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap, max(1, (bitmap.width * scale).roundToInt()),
                max(1, (bitmap.height * scale).roundToInt()), true
            )
        } else {
            bitmap
        }
        val w = small.width
        val h = small.height
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== bitmap) small.recycle()
        val grey = IntArray(w * h) { i ->
            val c = px[i]
            (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
        }
        return Grid(w, h, grey, inkMask(grey, w, h))
    }

    /** Ink = darker than the local mean of a (2r+1)^2 box by a margin. Shadows are not ink. */
    internal fun inkMask(grey: IntArray, w: Int, h: Int): BooleanArray {
        val r = max(6, w / 90)
        val integral = LongArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0L
            for (x in 0 until w) {
                row += grey[y * w + x]
                integral[(y + 1) * (w + 1) + (x + 1)] = integral[y * (w + 1) + (x + 1)] + row
            }
        }
        val ink = BooleanArray(w * h)
        for (y in 0 until h) {
            val y0 = max(0, y - r)
            val y1 = min(h - 1, y + r)
            for (x in 0 until w) {
                val x0 = max(0, x - r)
                val x1 = min(w - 1, x + r)
                val sum = integral[(y1 + 1) * (w + 1) + (x1 + 1)] - integral[y0 * (w + 1) + (x1 + 1)] -
                    integral[(y1 + 1) * (w + 1) + x0] + integral[y0 * (w + 1) + x0]
                val mean = sum / ((x1 - x0 + 1) * (y1 - y0 + 1))
                val g = grey[y * w + x]
                // Dark relative to the neighbourhood: 12% of the local brightness, at least 18 levels.
                ink[y * w + x] = mean - g > max(18L, mean * 12 / 100)
            }
        }
        return ink
    }

    fun find(bitmap: Bitmap, expectedRatio: Float): Line? = find(grid(bitmap), expectedRatio)

    fun find(g: Grid, expectedRatio: Float): Line? {
        val w = g.w
        val h = g.h
        if (w < 50 || h < 50) return null
        val centre = (expectedRatio * w).roundToInt()
        val lo = max(2, centre - (SEARCH_HALF * w).roundToInt())
        val hi = min(w - 3, centre + (SEARCH_HALF * w).roundToInt())
        val minGap = max(3, (MIN_GAP * w).roundToInt())
        val top = (h * 0.04f).roundToInt()
        val bottom = (h * 0.96f).roundToInt()
        val bandH = max(4, (bottom - top) / BANDS)

        val ys = ArrayList<Float>()
        val xs = ArrayList<Float>()
        var y0 = top
        while (y0 + bandH <= bottom) {
            val y1 = y0 + bandH
            // Ink count per column in this band (a column with ink on >1.5% of its rows is "inky").
            val inky = BooleanArray(w)
            val shade = FloatArray(w)
            for (x in lo..hi) {
                var n = 0
                var s = 0L
                for (y in y0 until y1) {
                    if (g.ink[y * w + x]) n++
                    s += g.grey[y * w + x]
                }
                inky[x] = n > max(1, (bandH * 0.015f).roundToInt())
                shade[x] = s.toFloat() / bandH
            }
            // Band has text on both sides? Otherwise it cannot locate a gap between text blocks.
            val inkLeft = (lo until centre).count { inky[it] }
            val inkRight = (centre..hi).count { inky[it] }
            if (inkLeft >= 3 && inkRight >= 3) {
                // The ink-free run nearest the expected centre, preferring wider runs.
                var best: IntRange? = null
                var bestScore = Float.NEGATIVE_INFINITY
                var x = lo
                while (x <= hi) {
                    if (inky[x]) { x++; continue }
                    val start = x
                    while (x <= hi && !inky[x]) x++
                    val end = x - 1
                    val len = end - start + 1
                    if (len >= minGap && start > lo && end < hi) {
                        val mid = (start + end) / 2f
                        val score = len - 0.35f * abs(mid - centre)
                        if (score > bestScore) { bestScore = score; best = start..end }
                    }
                }
                best?.let { run ->
                    // Vote: the fold shadow (darkest column) when there is a real shadow in the run,
                    // otherwise the middle of the gap. Kept off the run's edges.
                    val a = run.first + min(2, (run.last - run.first) / 4)
                    val b = run.last - min(2, (run.last - run.first) / 4)
                    val middle = (a + b) / 2
                    var dark = middle
                    for (c in a..b) {
                        val darker = shade[c] < shade[dark] - 0.5f
                        val tieNearer = abs(shade[c] - shade[dark]) <= 0.5f && abs(c - middle) < abs(dark - middle)
                        if (darker || tieNearer) dark = c
                    }
                    val runShades = (a..b).map { shade[it] }.sorted()
                    val hasShadow = runShades[runShades.size / 2] - shade[dark] >= 6f
                    ys += (y0 + y1) / 2f
                    xs += (if (hasShadow) dark else middle).toFloat()
                }
            }
            y0 = y1
        }
        if (xs.size < MIN_VOTES) return null

        var (a, b) = theilSen(ys, xs) ?: return null
        // Drop outlier votes (> 2% of the width off the line) and refit.
        val keep = ys.indices.filter { abs(xs[it] - (a + b * ys[it])) <= 0.02f * w }
        if (keep.size < MIN_VOTES) return null
        theilSen(keep.map { ys[it] }, keep.map { xs[it] })?.let { a = it.first; b = it.second }
        if (abs(b) > MAX_TILT) return null

        // Verify, and slide sideways to the offset that touches the least ink.
        val reach = (0.02f * w).roundToInt()
        var bestOffset = 0
        var bestRows = Int.MAX_VALUE
        for (off in -reach..reach) {
            val rows = inkRows(g, a + off, b, top, bottom)
            if (rows < bestRows || (rows == bestRows && abs(off) < abs(bestOffset))) {
                bestRows = rows
                bestOffset = off
            }
        }
        val fraction = bestRows.toFloat() / max(1, bottom - top)
        if (fraction > MAX_INK_ROWS_FRACTION) return null
        val a2 = a + bestOffset
        return Line(
            topRatio = (a2 / w).coerceIn(0.05f, 0.95f),
            bottomRatio = ((a2 + b * h) / w).coerceIn(0.05f, 0.95f),
            inkRowsFraction = fraction,
            votes = keep.size
        )
    }

    /** Rows between [top] and [bottom] where the line x = a + b*y touches ink within +-1 px. */
    internal fun inkRows(g: Grid, a: Float, b: Float, top: Int, bottom: Int): Int {
        var rows = 0
        for (y in top until bottom) {
            val x = (a + b * y).roundToInt()
            var hit = false
            for (dx in -1..1) {
                val xx = x + dx
                if (xx in 0 until g.w && g.ink[y * g.w + xx]) { hit = true; break }
            }
            if (hit) rows++
        }
        return rows
    }

    /** Ink rows touched by a line given as ratios (for evals and previews). */
    fun inkRowsFraction(g: Grid, topRatio: Float, bottomRatio: Float): Float {
        val a = topRatio * g.w
        val b = (bottomRatio - topRatio) * g.w / g.h
        val top = (g.h * 0.04f).roundToInt()
        val bottom = (g.h * 0.96f).roundToInt()
        return inkRows(g, a, b, top, bottom).toFloat() / max(1, bottom - top)
    }

    private fun theilSen(ys: List<Float>, xs: List<Float>): Pair<Float, Float>? {
        if (ys.size < 2) return null
        val slopes = ArrayList<Float>()
        for (i in ys.indices) for (j in i + 1 until ys.size) {
            val dy = ys[j] - ys[i]
            if (abs(dy) > 1e-3f) slopes += (xs[j] - xs[i]) / dy
        }
        if (slopes.isEmpty()) return null
        slopes.sort()
        val b = slopes[slopes.size / 2]
        val intercepts = ys.indices.map { xs[it] - b * ys[it] }.sorted()
        return intercepts[intercepts.size / 2] to b
    }
}
