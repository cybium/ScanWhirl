package com.scanwhirl.app.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import kotlinx.coroutines.CancellationException
import kotlin.math.max
import kotlin.math.min

/**
 * FORM1: the exact-KB JPEG engine.
 *
 * Binary-searches the highest JPEG quality that still fits the maximum, then reports one of three
 * honest outcomes: IN_BAND (measured bytes inside min..max), BELOW_MIN (the largest honest file is
 * still under the minimum; the screen says it could not reach it without changing the picture) or
 * ABOVE_MAX (even the smallest honest file overflows the maximum; only reachable for absurd limits).
 *
 * No padding bytes are ever appended to fake a size: the file is always a real JPEG. KB is decimal
 * (1000 bytes, [SizeUnits]). The chosen DPI is written into the JFIF density header, so another
 * viewer sees the same DPI as the screen.
 */
object ExactKbEncoder {

    enum class Outcome { IN_BAND, BELOW_MIN, ABOVE_MAX }

    data class Result(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
        val dpi: Int,
        val quality: Int,
        val outcome: Outcome
    ) {
        val sizeBytes: Long get() = bytes.size.toLong()
        val inBand: Boolean get() = outcome == Outcome.IN_BAND
        override fun equals(other: Any?): Boolean =
            other is Result && bytes.contentEquals(other.bytes) && width == other.width &&
                height == other.height && dpi == other.dpi && quality == other.quality &&
                outcome == other.outcome
        override fun hashCode(): Int {
            var h = bytes.contentHashCode()
            h = 31 * h + width
            h = 31 * h + height
            h = 31 * h + dpi
            h = 31 * h + quality
            return 31 * h + outcome.hashCode()
        }
    }

    const val MIN_QUALITY = 1
    const val MAX_QUALITY = 100
    private const val MAX_SCALE_STEPS = 12
    private const val MIN_DIM = 8

    /**
     * Test seam: lets a test inject an OutOfMemoryError and prove the screen reports it instead
     * of crashing. Null in production.
     */
    @Volatile
    internal var beforeEncode: (() -> Unit)? = null

    fun encode(
        source: Bitmap,
        width: Int,
        height: Int,
        dpi: Int,
        minBytes: Long,
        maxBytes: Long,
        isCancelled: () -> Boolean = { false },
        /**
         * Lane F1: OFF by default. The pixels a person asked for are the ground truth; if the
         * smallest honest JPEG still overflows the maximum, report ABOVE_MAX at those dimensions
         * instead of quietly rasterizing a smaller picture and calling it a success.
         */
        shrinkToFit: Boolean = false
    ): Result {
        require(
            FormPresets.fitsFormLimit(width, height)
        ) { "This size is too large for a form photo. Use up to ${FormPresets.MAX_FORM_LONG_SIDE} px." }
        require(dpi in 1..65535) { "Invalid DPI" }
        require(minBytes > 0 && maxBytes >= minBytes) { "Invalid KB band" }
        checkCancelled(isCancelled)
        beforeEncode?.invoke()

        var w = width
        var h = height
        var raster = rasterize(source, w, h)
        try {
            var best: ByteArray? = null
            var bestQ = MIN_QUALITY
            var attempts = 0
            while (best == null && attempts++ < MAX_SCALE_STEPS) {
                checkCancelled(isCancelled)
                val fit = bestQualityAtMost(raster, maxBytes, isCancelled)
                if (fit != null) {
                    best = fit.second
                    bestQ = fit.first
                    break
                }
                // Even quality 1 overflows the maximum. Lane F keeps the requested pixels by
                // default and reports ABOVE_MAX; shrinking is an explicit opt-in only.
                if (!shrinkToFit) break
                val nw = max(MIN_DIM, (w * 0.8f).toInt())
                val nh = max(MIN_DIM, (h * 0.8f).toInt())
                if (nw == w && nh == h) break
                val smaller = rasterize(source, nw, nh)
                raster.recycle()
                raster = smaller
                w = nw
                h = nh
            }
            val encoded = best ?: ImageTools.encodeJpeg(raster, MIN_QUALITY)
            checkCancelled(isCancelled)
            val withDpi = ImageTools.patchJpegDpi(encoded, dpi)
            val outcome = when {
                withDpi.size.toLong() > maxBytes -> Outcome.ABOVE_MAX
                withDpi.size.toLong() >= minBytes -> Outcome.IN_BAND
                else -> Outcome.BELOW_MIN
            }
            return Result(withDpi, w, h, dpi, bestQ, outcome)
        } finally {
            raster.recycle()
        }
    }

    /** Largest quality whose JPEG is at most [maxBytes]; null when even quality 1 is too big. */
    private fun bestQualityAtMost(
        bitmap: Bitmap,
        maxBytes: Long,
        isCancelled: () -> Boolean
    ): Pair<Int, ByteArray>? {
        var lo = MIN_QUALITY
        var hi = MAX_QUALITY
        var best: ByteArray? = null
        var bestQ = -1
        while (lo <= hi) {
            checkCancelled(isCancelled)
            val mid = (lo + hi) ushr 1
            val out = ImageTools.encodeJpeg(bitmap, mid)
            if (out.size.toLong() <= maxBytes) {
                best = out
                bestQ = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (best != null) {
            // JPEG size is normally monotonic in quality, but a small probe keeps the result
            // deterministic and correct on the rare non-monotonic encoder blip.
            for (q in (bestQ + 1)..min(MAX_QUALITY, bestQ + 3)) {
                checkCancelled(isCancelled)
                val out = ImageTools.encodeJpeg(bitmap, q)
                if (out.size.toLong() <= maxBytes) {
                    best = out
                    bestQ = q
                }
            }
        }
        return best?.let { bestQ to it }
    }

    /** A fresh exact-size ARGB bitmap: alpha composited on white so ink never turns black in JPEG. */
    private fun rasterize(source: Bitmap, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(source, null, Rect(0, 0, w, h), paint)
        return out
    }

    private fun checkCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) throw CancellationException("Form-ready encoding cancelled")
    }

    /** Reads the JFIF dots-per-inch density written by [ImageTools.patchJpegDpi]. */
    fun readJfifDpi(bytes: ByteArray): Int? {
        val sig = byteArrayOf(0x4A, 0x46, 0x49, 0x46, 0x00) // "JFIF\0"
        var idx = -1
        outer@ for (i in 0..bytes.size - sig.size) {
            for (k in sig.indices) {
                if (bytes[i + k] != sig[k]) continue@outer
            }
            idx = i
            break@outer
        }
        if (idx < 0 || idx + 11 >= bytes.size) return null
        if (bytes[idx + 7].toInt() != 1) return null // units = dots per inch
        val x = ((bytes[idx + 8].toInt() and 0xFF) shl 8) or (bytes[idx + 9].toInt() and 0xFF)
        return if (x > 0) x else null
    }
}
