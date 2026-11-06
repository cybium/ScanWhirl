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
 * Geometric-only page straightening ("deskew"): finds a strong paper-like
 * quad on a small copy, then warps the full-size image so that quad becomes
 * the page. This is exactly what "mild tilted" gallery imports are missing
 * in SCANNER_MODE_BASE.
 *
 * Honesty rules baked in:
 *  - No color, brightness or sharpening is ever applied — geometry only.
 *  - Returns null when no confident quad is found, so photos without a
 *    clear paper edge stay untouched.
 */
object Deskew {

    data class Quad(val pts: FloatArray) // x,y ×4 (TL,TR,BR,BL), normalised 0..1

    private const val DETECT_DIM = 720
    private const val WARP_DIM = 2600

    /** Strong-corner threshold: needs a genuinely paper-like quad. */
    private const val STRONG_AREA = 0.06f

    fun detectQuad(src: ByteArray): Quad? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(src, 0, src.size, bounds)
        var sample = 1
        var long = maxOf(bounds.outWidth, bounds.outHeight)
        while (long / sample > DETECT_DIM) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(
            src, 0, src.size,
            BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        ) ?: return null
        val w = bmp.width
        val h = bmp.height
        if (w < 48 || h < 48) {
            bmp.recycle()
            return null
        }
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        bmp.recycle()

        // Gradient magnitude (max-axis Sobel, interior only).
        val grad = FloatArray(w * h)
        for (y in 1 until h - 1) {
            val above = (y - 1) * w
            val mid = y * w
            val below = (y + 1) * w
            for (x in 1 until w - 1) {
                val gx = -(grayOf(pixels[above + x - 1]) + 2 * grayOf(pixels[mid + x - 1]) + grayOf(pixels[below + x - 1])) +
                    (grayOf(pixels[above + x + 1]) + 2 * grayOf(pixels[mid + x + 1]) + grayOf(pixels[below + x + 1]))
                val gy = -(grayOf(pixels[above + x - 1]) + 2 * grayOf(pixels[above + x]) + grayOf(pixels[above + x + 1])) +
                    (grayOf(pixels[below + x - 1]) + 2 * grayOf(pixels[below + x]) + grayOf(pixels[below + x + 1]))
                grad[mid + x] = max(abs(gx), abs(gy)).toFloat()
            }
        }
        var maxGrad = 0f
        for (g in grad) if (g > maxGrad) maxGrad = g
        if (maxGrad <= 0f) return null

        val insetX = (w * 0.03f).toInt()
        val insetY = (h * 0.03f).toInt()
        var tlIdx = -1; var trIdx = -1; var brIdx = -1; var blIdx = -1
        var sTL = Float.MAX_VALUE; var sTR = Float.MAX_VALUE
        var sBR = Float.MAX_VALUE; var sBL = Float.MAX_VALUE
        for (y in insetY until h - insetY) {
            val row = y * w
            for (x in insetX until w - insetX) {
                if (grad[row + x] < maxGrad * 0.18f) continue
                val l1 = (x + y).toFloat()
                val l2 = ((w - 1 - x) + y).toFloat()
                val l3 = ((w - 1 - x) + (h - 1 - y)).toFloat()
                val l4 = (x + (h - 1 - y)).toFloat()
                if (l1 < sTL) { sTL = l1; tlIdx = row + x }
                if (l2 < sTR) { sTR = l2; trIdx = row + x }
                if (l3 < sBR) { sBR = l3; brIdx = row + x }
                if (l4 < sBL) { sBL = l4; blIdx = row + x }
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
        val area = kotlin.math.abs(shoelace(pts))
        if (area < STRONG_AREA) return null
        fun apart(i: Int, j: Int) = hypot(pts[i] - pts[j], pts[i + 1] - pts[j + 1]) > 0.05f
        if (!apart(0, 2) || !apart(2, 4) || !apart(4, 6) || !apart(6, 0)) return null
        return Quad(pts)
    }

    /** Warps the full image so the detected quad becomes the page. */
    fun warp(src: ByteArray, quad: Quad): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(src, 0, src.size, bounds)
        var sample = 1
        var long = maxOf(bounds.outWidth, bounds.outHeight)
        while (long / sample > WARP_DIM) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(
            src, 0, src.size,
            BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        ) ?: return src
        val p = quad.pts.mapIndexed { i, v -> if (i % 2 == 0) v * bmp.width else v * bmp.height }.toFloatArray()

        val destW = max(hypot(p[2] - p[0], p[3] - p[1]), hypot(p[6] - p[4], p[7] - p[5]))
            .roundToInt().coerceIn(16, WARP_DIM)
        val destH = max(hypot(p[0] - p[6], p[1] - p[7]), hypot(p[2] - p[4], p[3] - p[5]))
            .roundToInt().coerceIn(16, WARP_DIM)

        val out = Bitmap.createBitmap(destW, destH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        val matrix = Matrix()
        val ok = matrix.setPolyToPoly(
            p, 0,
            floatArrayOf(0f, 0f, destW.toFloat(), 0f, destW.toFloat(), destH.toFloat(), 0f, destH.toFloat()),
            0, 4
        )
        if (!ok) {
            out.recycle(); bmp.recycle(); return src
        }
        canvas.drawBitmap(bmp, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        bmp.recycle()
        val result = ImageTools.encodeJpeg(out, 95)
        out.recycle()
        return result
    }

    private fun grayOf(c: Int): Int =
        (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 151 + (c and 0xFF) * 28) shr 8

    private fun shoelace(p: FloatArray): Float {
        var acc = 0f
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            acc += p[i * 2] * p[j * 2 + 1] - p[j * 2] * p[i * 2 + 1]
        }
        return acc / 2f
    }
}
