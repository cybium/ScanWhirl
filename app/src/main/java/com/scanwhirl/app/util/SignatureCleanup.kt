package com.scanwhirl.app.util

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/**
 * FORM1 / FX2: optional, predictable signature cleanup.
 *
 * The old global luminance threshold turned shaded paper, page borders and the phone shadow into
 * solid black and erased pale blue ink (phone screenshot: a black bar at the left edge and a blob
 * at the bottom). The ink is now judged RELATIVE TO THE LOCAL PAPER: a large box blur estimates
 * the slowly varying background, the pixel is divided by that estimate, and only dark valleys are
 * kept. This is deterministic, offline, and has no hidden model or randomness.
 *
 * The caller owns the source bitmap (the original photo is never changed) and must recycle the
 * returned bitmap. The Form screen keeps cleanup OFF by default; the toggle is opt-in.
 */
object SignatureCleanup {

    const val DEFAULT_THRESHOLD = 165

    fun clean(src: Bitmap, threshold: Int = DEFAULT_THRESHOLD): Bitmap {
        require(threshold in 1..256) { "Threshold must be between 1 and 256" }
        val w = src.width
        val h = src.height
        val n = w * h
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(n)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val lum = IntArray(n)
        for (i in 0 until n) {
            val c = pixels[i]
            lum[i] = (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 151 + (c and 0xFF) * 28) shr 8
        }
        // A large window follows paper curl, vignette and slow shading but bridges thin ink strokes.
        val radius = max(4, min(w, h) / 8)
        val background = boxBlur(lum, w, h, radius)
        // A tiny image (or a crop whose window is entirely ink) needs the page's own paper level
        // as a floor, otherwise ink would be measured against ink and vanish.
        val paperFloor = (globalPaper(lum) * 0.85f).toInt()

        // Preserve the historical extremes: 1 = nothing is ink, 256 = everything is ink.
        var limit = threshold / 256f
        if (limit <= 1f / 256f) {
            java.util.Arrays.fill(pixels, 0xFFFFFFFF.toInt())
            out.setPixels(pixels, 0, w, 0, 0, w, h)
            return out
        }
        if (limit >= 1f) {
            java.util.Arrays.fill(pixels, 0xFF000000.toInt())
            out.setPixels(pixels, 0, w, 0, 0, w, h)
            return out
        }
        // The paper-relative test rejects gradual shading and page edges; a crude "dark fraction"
        // cap was removed because a genuine two-tone signature can legitimately cover >25% of a
        // small crop and the cap erased real strokes. Solid black regions are prevented by the
        // background normalisation itself and checked numerically in FormFx2PhoneEvidenceTest.
        for (i in 0 until n) {
            val bg = max(1, max(background[i], paperFloor))
            val v = if (lum[i].toFloat() / bg < limit && bg - lum[i] > 12) 0 else 255
            pixels[i] = 0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
        }
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * FX2: deterministic ink bounds in an upright source bitmap. No face/shape model: dark valleys
     * relative to the locally estimated paper, with a border margin so page edges, shadows and the
     * printed heading cannot hijack the signature frame. Null when no ink is found.
     */
    fun inkBounds(src: Bitmap, marginFraction: Float = 0.06f): RectF? {
        val w = src.width
        val h = src.height
        if (w < 16 || h < 16) return null
        val n = w * h
        val px = IntArray(n)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val lum = IntArray(n)
        for (i in 0 until n) {
            val c = px[i]
            lum[i] = (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 151 + (c and 0xFF) * 28) shr 8
        }
        val bg = boxBlur(lum, w, h, max(6, min(w, h) / 10))
        val paperFloor = (globalPaper(lum) * 0.85f).toInt()
        val mx = (w * marginFraction).toInt()
        val my = (h * marginFraction).toInt()
        val xs = ArrayList<Int>()
        val ys = ArrayList<Int>()
        for (y in my until h - my) {
            for (x in mx until w - mx) {
                val i = y * w + x
                val paper = max(1, max(bg[i], paperFloor))
                if (lum[i].toFloat() / paper < 0.72f && paper - lum[i] > 24) {
                    xs.add(x)
                    ys.add(y)
                }
            }
        }
        if (xs.size < 20) return null
        fun pct(sorted: List<Int>, p: Double): Int {
            val pos = ((sorted.size - 1) * p).toInt()
            return sorted[pos]
        }
        val sx = xs.sorted()
        val sy = ys.sorted()
        return RectF(
            pct(sx, 0.01).toFloat(), pct(sy, 0.01).toFloat(),
            (pct(sx, 0.99) + 1).toFloat(), (pct(sy, 0.99) + 1).toFloat()
        )
    }

    /** The page's paper level: 90th percentile of luminance, so dark ink cannot lower it. */
    private fun globalPaper(lum: IntArray): Int {
        val sorted = lum.copyOf()
        java.util.Arrays.sort(sorted)
        return sorted[(sorted.size - 1) * 9 / 10]
    }

    /** Separable-style box mean through an integral image; O(width*height), no allocations per pixel. */
    private fun boxBlur(src: IntArray, w: Int, h: Int, r: Int): IntArray {
        val stride = w + 1
        val integral = LongArray((h + 1) * stride)
        for (y in 0 until h) {
            var row = 0L
            for (x in 0 until w) {
                row += src[y * w + x].toLong()
                integral[(y + 1) * stride + x + 1] = integral[y * stride + x + 1] + row
            }
        }
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val y0 = max(0, y - r)
            val y1 = min(h - 1, y + r)
            for (x in 0 until w) {
                val x0 = max(0, x - r)
                val x1 = min(w - 1, x + r)
                val sum = integral[(y1 + 1) * stride + x1 + 1] -
                    integral[y0 * stride + x1 + 1] -
                    integral[(y1 + 1) * stride + x0] + integral[y0 * stride + x0]
                val count = (x1 - x0 + 1) * (y1 - y0 + 1)
                out[y * w + x] = (sum / count).toInt()
            }
        }
        return out
    }
}
