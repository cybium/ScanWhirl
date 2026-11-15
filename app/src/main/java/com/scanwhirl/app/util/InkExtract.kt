package com.scanwhirl.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * L5: import ink (a signature or a company stamp) from a photo.
 *
 * The paper is removed, not thresholded globally: a coarse local background is estimated with a
 * per-block high percentile plus a max filter (a morphological closing), so grey paper, gradients
 * and soft shadows disappear while the ink stays. Alpha is a smooth function of
 * `background luminance - pixel luminance`; the ink colour is detected (black / blue / other) and
 * can be recoloured. The result is trimmed to the ink bounds with a small margin.
 *
 * Input is decoded sampled (<= 2000 px). The output is an ARGB bitmap whose background is fully
 * transparent; callers save it as a PNG.
 */
object InkExtract {

    enum class InkHue { BLACK, BLUE, OTHER }

    enum class Recolor { ORIGINAL, BLACK, BLUE }

    data class Extracted(val bitmap: Bitmap, val hue: InkHue)

    /** Dominant blue used by the "Blue" recolour (a document-pen blue, not cyan). */
    const val BLUE_INK = 0xFF1B3F8C.toInt()

    /** Decodes [file] sampled so neither side is larger than [maxDim]. */
    fun decodeSampled(file: File, maxDim: Int = 2000): Bitmap? {
        if (!file.isFile || file.length() == 0L) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        var w = bounds.outWidth
        var h = bounds.outHeight
        while (max(w, h) / sample > maxDim) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(file.path, options)
    }

    /**
     * Extracts the ink of [source]. Returns null when there is no ink at all.
     * The caller owns (and should recycle) the returned bitmap.
     */
    fun extract(
        source: Bitmap,
        recolor: Recolor = Recolor.ORIGINAL
    ): Extracted? {
        if (source.isRecycled || source.width < 8 || source.height < 8) return null
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        // 1. Coarse local paper luminance (an 80th percentile per block, then a max filter).
        val block = max(16, max(w, h) / 40)
        val gw = ceil(w.toFloat() / block).toInt()
        val gh = ceil(h.toFloat() / block).toInt()
        val paper = FloatArray(gw * gh)
        val lum = FloatArray(pixels.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = pixels[y * w + x]
                // A transparent pixel means no photo there; treat it as white paper.
                val a = Color.alpha(p)
                val r = if (a == 0) 255 else Color.red(p)
                val g = if (a == 0) 255 else Color.green(p)
                val b = if (a == 0) 255 else Color.blue(p)
                lum[y * w + x] = 0.299f * r + 0.587f * g + 0.114f * b
            }
        }
        val hist = IntArray(256)
        for (gy in 0 until gh) {
            for (gx in 0 until gw) {
                hist.fill(0)
                var n = 0
                val yStart = gy * block
                val yEnd = min(h, yStart + block)
                val xStart = gx * block
                val xEnd = min(w, xStart + block)
                for (y in yStart until yEnd) {
                    val row = y * w
                    for (x in xStart until xEnd) {
                        hist[lum[row + x].toInt().coerceIn(0, 255)]++
                        n++
                    }
                }
                if (n == 0) continue
                var seen = 0
                val target = (n * 0.80f).toInt()
                var value = 255
                for (v in 0..255) {
                    seen += hist[v]
                    if (seen >= target) {
                        value = v
                        break
                    }
                }
                paper[gy * gw + gx] = value.toFloat()
            }
        }
        // Morphological closing over the block grid removes ink-heavy blocks and small gaps.
        val smoothed = FloatArray(paper.size)
        for (gy in 0 until gh) {
            for (gx in 0 until gw) {
                var m = 0f
                for (dy in -1..1) for (dx in -1..1) {
                    val yy = gy + dy
                    val xx = gx + dx
                    if (yy in 0 until gh && xx in 0 until gw) m = max(m, paper[yy * gw + xx])
                }
                smoothed[gy * gw + gx] = m
            }
        }

        // 2. Smooth alpha and the dominant ink colour.
        val out = IntArray(pixels.size)
        val colInk = IntArray(w)
        val rowInk = IntArray(h)
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var strongCount = 0
        val low = 14f     // luminance difference where ink starts (paper noise)
        val high = 58f    // fully opaque ink
        // A photo frame or a scanned page border is not ink: ignore a thin edge band when
        // measuring the ink bounds (the crop still keeps a real stroke that reaches an edge).
        val edgeInset = max(2, (min(w, h) * 0.015f).roundToInt())
        for (y in 0 until h) {
            val fy = (y + 0.5f) / block - 0.5f
            val y0 = fy.toInt().coerceIn(0, gh - 1)
            val y1 = (y0 + 1).coerceAtMost(gh - 1)
            val ty = (fy - y0).coerceIn(0f, 1f)
            for (x in 0 until w) {
                val fx = (x + 0.5f) / block - 0.5f
                val x0 = fx.toInt().coerceIn(0, gw - 1)
                val x1 = (x0 + 1).coerceAtMost(gw - 1)
                val tx = (fx - x0).coerceIn(0f, 1f)
                val bg = lerp(
                    lerp(smoothed[y0 * gw + x0], smoothed[y0 * gw + x1], tx),
                    lerp(smoothed[y1 * gw + x0], smoothed[y1 * gw + x1], tx),
                    ty
                )
                val diff = bg - lum[y * w + x]
                val alpha = ((diff - low) / (high - low)).coerceIn(0f, 1f)
                if (alpha <= 0.02f) continue
                val p = pixels[y * w + x]
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)
                if (alpha > 0.65f) {
                    sumR += r
                    sumG += g
                    sumB += b
                    strongCount++
                }
                if (alpha > 0.10f &&
                    x >= edgeInset && x < w - edgeInset &&
                    y >= edgeInset && y < h - edgeInset
                ) {
                    colInk[x]++
                    rowInk[y]++
                }
                out[y * w + x] = Color.argb((alpha * 255f).roundToInt(), r, g, b)
            }
        }
        if (strongCount == 0) return null

        // A scanned page often keeps a thin straight line at an edge (a frame remnant). Drop
        // full-height/full-width isolated lines in the outer 12%, before measuring the bounds.
        val railZoneX = (w * 0.12f).toInt()
        val railZoneY = (h * 0.12f).toInt()
        val railColumns = ArrayList<Int>()
        val railRows = ArrayList<Int>()
        for (x in 0 until w) {
            if (x > railZoneX && x < w - railZoneX) continue
            val strong = colInk[x] > h * 0.5f
            val isolatedLeft = x < 3 || colInk[x - 3] < h * 0.2f
            val isolatedRight = x >= w - 3 || colInk[x + 3] < h * 0.2f
            if (strong && isolatedLeft && isolatedRight) railColumns += x
        }
        for (y in 0 until h) {
            if (y > railZoneY && y < h - railZoneY) continue
            val strong = rowInk[y] > w * 0.5f
            val isolatedUp = y < 3 || rowInk[y - 3] < w * 0.2f
            val isolatedDown = y >= h - 3 || rowInk[y + 3] < w * 0.2f
            if (strong && isolatedUp && isolatedDown) railRows += y
        }
        if (railColumns.isNotEmpty() || railRows.isNotEmpty()) {
            for (y in 0 until h) {
                for (x in railColumns) {
                    out[y * w + x] = 0
                    colInk[x] = 0
                }
                if (y in railRows) {
                    for (x in 0 until w) out[y * w + x] = 0
                }
            }
            railRows.forEach { rowInk[it] = 0 }
        }

        val avgR = (sumR / strongCount).toFloat()
        val avgG = (sumG / strongCount).toFloat()
        val avgB = (sumB / strongCount).toFloat()
        val hue = classify(avgR, avgG, avgB)
        val ink = when (recolor) {
            Recolor.ORIGINAL -> null
            Recolor.BLACK -> Color.BLACK
            Recolor.BLUE -> BLUE_INK
        }
        if (ink != null) {
            for (i in out.indices) {
                val a = Color.alpha(out[i])
                if (a > 0) out[i] = Color.argb(a, Color.red(ink), Color.green(ink), Color.blue(ink))
            }
        }

        // 3. Trim to the ink's bounding box, ignoring isolated specks: a column/row must carry a
        // few ink pixels to count (a lone JPEG speck does not). Then add a 4% margin.
        val support = max(4, (min(w, h) * 0.003f).toInt())
        var left = w
        var right = -1
        var top = h
        var bottom = -1
        for (x in 0 until w) {
            if (colInk[x] >= support) {
                if (x < left) left = x
                right = x
            }
        }
        for (y in 0 until h) {
            if (rowInk[y] >= support) {
                if (y < top) top = y
                bottom = y
            }
        }
        if (right < left || bottom < top) {
            // Only specks: no usable ink.
            return null
        }
        val marginX = max(2, ((right - left + 1) * 0.04f).roundToInt())
        val marginY = max(2, ((bottom - top + 1) * 0.04f).roundToInt())
        val cropLeft = (left - marginX).coerceAtLeast(0)
        val cropTop = (top - marginY).coerceAtLeast(0)
        val cropRight = (right + marginX).coerceAtMost(w - 1)
        val cropBottom = (bottom + marginY).coerceAtMost(h - 1)
        val cw = cropRight - cropLeft + 1
        val ch = cropBottom - cropTop + 1
        val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        full.setPixels(out, 0, w, 0, 0, w, h)
        val trimmed = Bitmap.createBitmap(full, cropLeft, cropTop, cw, ch)
        if (trimmed !== full) full.recycle()
        return Extracted(trimmed, hue)
    }

    /** Detects whether the strong ink pixels are black, blue or another colour. */
    fun classify(r: Float, g: Float, b: Float): InkHue {
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val saturation = if (maxC <= 0f) 0f else (maxC - minC) / maxC
        if (maxC < 90f || saturation < 0.18f) return InkHue.BLACK
        if (b > r * 1.12f && b > g * 1.02f) return InkHue.BLUE
        return InkHue.OTHER
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
