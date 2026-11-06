package com.scanwhirl.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Dependency-free "Original as CamScanner" auto-crop spike: finds the paper
 * with gradient edges and straightens it with [Matrix.setPolyToPoly] (which
 * handles a real perspective beautifully). No OpenCV, no extra dependencies,
 * no forced enhancement — the paper is geometry only.
 *
 * Quality is the thing this spike is for: try it on real photos and compare
 * it against Google's scanner before we invest more. Detect runs once on a
 * small copy (fast), then the warp runs once on a bounded-size decode.
 */
object PaperScanner {

    data class Crop(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
        val detectMs: Long,
        val warpMs: Long
    )

    private const val DETECT_DIM = 720
    private const val WARP_DIM = 2400

    fun autoCrop(src: ByteArray): Crop? {
        val t0 = System.currentTimeMillis()
        val quad = detectQuad(src) ?: return null
        val detectMs = System.currentTimeMillis() - t0

        val t1 = System.currentTimeMillis()
        val bmp = decodeBounded(src, WARP_DIM)
        if (bmp.width < 16 || bmp.height < 16) {
            bmp.recycle()
            return null
        }

        // Corner coordinates in detect-space (0..1); scale to the warp decode.
        val utf = FloatArray(8).also { pts ->
            for (i in 0 until 4) {
                pts[i * 2] = quad[i * 2] * bmp.width
                pts[i * 2 + 1] = quad[i * 2 + 1] * bmp.height
            }
        }
        val destW = max(
            hypot(utf[2] - utf[0], utf[3] - utf[1]),
            hypot(utf[6] - utf[4], utf[7] - utf[5])
        ).roundToInt().coerceIn(16, WARP_DIM)
        val destH = max(
            hypot(utf[0] - utf[6], utf[1] - utf[7]),
            hypot(utf[2] - utf[4], utf[3] - utf[5])
        ).roundToInt().coerceIn(16, WARP_DIM)

        val out = Bitmap.createBitmap(destW, destH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE) // outside-the-paper area reads as paper, never black
        val matrix = Matrix()
        val settled = matrix.setPolyToPoly(
            utf, 0,
            floatArrayOf(0f, 0f, destW.toFloat(), 0f, destW.toFloat(), destH.toFloat(), 0f, destH.toFloat()),
            0, 4
        )
        if (!settled) {
            out.recycle()
            bmp.recycle()
            return null
        }
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(bmp, matrix, paint)
        bmp.recycle()
        val bytes = ImageTools.encodeJpeg(out, 95)
        out.recycle()
        val warpMs = System.currentTimeMillis() - t1
        return Crop(bytes, destW, destH, detectMs, warpMs)
    }

    /**
     * Returns 8 floats (x,y pairs TL, TR, BR, BL) normalised to 0..1, or null
     * when strong paper-like corners can't be found.
     */
    private fun detectQuad(src: ByteArray): FloatArray? {
        val bmp = decodeBounded(src, DETECT_DIM)
        val w = bmp.width
        val h = bmp.height
        if (w < 48 || h < 48) {
            bmp.recycle()
            return null
        }
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        bmp.recycle()
        return detectQuadPixels(pixels, w, h)
    }

    /**
     * Quad detection on an already-decoded frame, so the live camera preview can
     * call it on cheap analysis frames (and `autoCrop` on a full capture).
     */
    fun detectQuadPixels(pixels: IntArray, w: Int, h: Int): FloatArray? {
        val gray = FloatArray(w * h)
        for (i in pixels.indices) {
            val c = pixels[i]
            gray[i] = 0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) +
                0.114f * (c and 0xFF)
        }

        // Gradient magnitude (fast max-axis Sobel, interior only)
        val grad = FloatArray(w * h)
        for (y in 1 until h - 1) {
            val above = (y - 1) * w
            val mid = y * w
            val below = (y + 1) * w
            for (x in 1 until w - 1) {
                val ix = x
                val gx = -(gray[above + ix - 1] + 2 * gray[mid + ix - 1] + gray[below + ix - 1]) +
                    (gray[above + ix + 1] + 2 * gray[mid + ix + 1] + gray[below + ix + 1])
                val gy = -(gray[above + ix - 1] + 2 * gray[above + ix] + gray[above + ix + 1]) +
                    (gray[below + ix - 1] + 2 * gray[below + ix] + gray[below + ix + 1])
                grad[mid + ix] = max(abs(gx), abs(gy))
            }
        }

        // Adaptive edge threshold: the strongest ~1.5% of pixels
        var maxGrad = 0f
        for (g in grad) if (g > maxGrad) maxGrad = g
        if (maxGrad <= 0f) return null
        val bins = IntArray(256)
        for (g in grad) bins[((g / maxGrad) * 255f).toInt().coerceIn(0, 255)]++
        var seen = 0
        var threshold = 0f
        for (b in 255 downTo 0) {
            seen += bins[b]
            if (seen >= (w * h) * 0.015f) {
                threshold = (b * maxGrad) / 255f
                break
            }
        }

        // Extreme-corner search: for the paper quad, the corner nearest to each
        // image corner is the one that sits closest along the L1 direction.
        val insetX = (w * 0.03f).toInt()
        val insetY = (h * 0.03f).toInt()
        var tlIdx = -1
        var trIdx = -1
        var brIdx = -1
        var blIdx = -1
        var tlScore = Float.MAX_VALUE
        var trScore = Float.MAX_VALUE
        var brScore = Float.MAX_VALUE
        var blScore = Float.MAX_VALUE
        for (y in insetY until h - insetY) {
            val row = y * w
            for (x in insetX until w - insetX) {
                if (grad[row + x] < threshold) continue
                val tlS = (x + y).toFloat()
                val trS = ((w - 1 - x) + y).toFloat()
                val brS = ((w - 1 - x) + (h - 1 - y)).toFloat()
                val blS = (x + (h - 1 - y)).toFloat()
                if (tlS < tlScore) { tlScore = tlS; tlIdx = row + x }
                if (trS < trScore) { trScore = trS; trIdx = row + x }
                if (brS < brScore) { brScore = brS; brIdx = row + x }
                if (blS < blScore) { blScore = blS; blIdx = row + x }
            }
        }
        if (tlIdx < 0 || trIdx < 0 || brIdx < 0 || blIdx < 0) return null

        val pts = floatArrayOf(
            (tlIdx % w).toFloat() / w, (tlIdx / w).toFloat() / h,
            (trIdx % w).toFloat() / w, (trIdx / w).toFloat() / h,
            (brIdx % w).toFloat() / w, (brIdx / w).toFloat() / h,
            (blIdx % w).toFloat() / w, (blIdx / w).toFloat() / h
        )
        // Sanity: a plausible paper is a sizeable convex quadrilateral.
        val area = shoelace(pts).let { if (it < 0) -it else it }
        if (area < 0.03f) return null
        fun apart(a: FloatArray, b: FloatArray, i: Int, j: Int): Boolean =
            hypot(a[i] - a[j], a[i + 1] - a[j + 1]) > 0.05f
        if (!apart(pts, pts, 0, 2) || !apart(pts, pts, 2, 4) ||
            !apart(pts, pts, 4, 6) || !apart(pts, pts, 6, 0)
        ) return null
        return pts
    }

    private fun shoelace(pts: FloatArray): Float {
        var acc = 0f
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            acc += pts[i * 2] * pts[j * 2 + 1] - pts[j * 2] * pts[i * 2 + 1]
        }
        return acc / 2f
    }

    /** Decodes with the long side bounded to [maxDim] (never overflows memory). */
    private fun decodeBounded(src: ByteArray, maxDim: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(src, 0, src.size, bounds)
        var sample = 1
        var long = maxOf(bounds.outWidth, bounds.outHeight)
        while (long / sample > maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(src, 0, src.size, opts)
            ?: throw IllegalStateException("Could not decode image")
    }
}
