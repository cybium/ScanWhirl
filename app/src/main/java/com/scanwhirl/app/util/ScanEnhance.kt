package com.scanwhirl.app.util

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Scan Enhance 2.0 — the document look filters, each a **pure** function of one sampled bitmap
 * (long side ≤ [MAX_DIM]) and always applied to the original page, never to another filter's
 * output (I9 groundwork).
 *
 * - [auto]: paper/illumination normalisation (large-scale per-channel closing + blur, divided
 *   out), so shadows and pink/yellow paper go white while ink, stamps and photos keep their
 *   colour; then a gentle luma stretch and an unsharp mask.
 * - [colourBoost]: Auto plus vibrance (low-saturation colours gain most).
 * - [grey]: Auto rendered as luminance.
 * - [blackWhite]: Auto, thin ruling lines lightened, then a tiled Sauvola adaptive threshold.
 *
 * Memory: the work bitmap (≤3000 px long side), one IntArray of pixels and one ShortArray of
 * luminance; no full-resolution background planes. Everything is one page at a time.
 */
object ScanEnhance {

    /** Filters never process more than this on the long side (a 12 MP phone photo is sampled). */
    const val MAX_DIM = 3000

    /** Long side of the illumination estimate; upscaled bilinearly per pixel. */
    private const val BACKDROP_SIDE = 320

    private const val STRETCH_LOW = 0.02
    private const val STRETCH_HIGH = 0.995
    private const val STRETCH_AMOUNT = 0.55f
    private const val UNSHARP_AMOUNT = 0.28f
    private const val UNSHARP_THRESHOLD = 6
    private const val UNSHARP_CLAMP = 36

    /** At/above this pixel saturation the original colour is kept (stamps, pens, photos). */
    private const val KEEP_COLOUR_FROM = 0.40f
    private const val KEEP_COLOUR_FULL = 0.72f

    private class Work(val bitmap: Bitmap, val w: Int, val h: Int, val pixels: IntArray)

    // ------------------------------------------------------------------
    // Public filters
    // ------------------------------------------------------------------

    fun auto(src: Bitmap): Bitmap = emit(base(src))

    fun colourBoost(src: Bitmap): Bitmap {
        val work = base(src)
        vibrance(work)
        return emit(work)
    }

    fun grey(src: Bitmap): Bitmap {
        val work = base(src)
        val px = work.pixels
        for (i in px.indices) {
            val v = luma(px[i])
            px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return emit(work)
    }

    fun blackWhite(src: Bitmap): Bitmap {
        val work = base(src)
        // Thin notebook ruling is content-ish in colour, but in B/W it becomes a hard black
        // line; lighten it before thresholding, exactly like the old Clean filter did.
        ImageTools.lightenRuledLines(work.pixels, work.w, work.h)
        val picture = pictureMask(work)
        val gray = adaptiveThreshold(work)
        removeThinHorizontalRuns(work.pixels, work.w, work.h)
        removeThinVerticalRuns(work.pixels, work.w, work.h)
        removeRulingLines(work.pixels, work.w, work.h, gray)
        removeBorderConnectedBlobs(work.pixels, work.w, work.h)
        // Pictures (a portrait on an ID card, a photo on a page) are continuous tone, not text:
        // render them as grey from the Auto image instead of a black/white blotch.
        if (picture != null) {
            for (i in work.pixels.indices) {
                if (picture[i].toInt() == 0) continue
                val v = gray[i].toInt().coerceIn(18, 242)
                work.pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        return emit(work)
    }

    // ------------------------------------------------------------------
    // Auto base: illumination normalisation + stretch + unsharp
    // ------------------------------------------------------------------

    private fun base(src: Bitmap): Work {
        val longSide = max(src.width, src.height)
        val scaled: Bitmap? = if (longSide > MAX_DIM) {
            val scale = MAX_DIM.toFloat() / longSide
            Bitmap.createScaledBitmap(
                src,
                max(1, (src.width * scale).roundToInt()),
                max(1, (src.height * scale).roundToInt()),
                true
            )
        } else null
        val source = scaled ?: src
        val w = source.width
        val h = source.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h)
        source.getPixels(px, 0, w, 0, 0, w, h)

        // Illumination estimate from a small copy: close away thin dark strokes, then a wide
        // blur. Per channel, so a pink/yellow paper tint is removed rather than amplified.
        val sw = max(16, (w.toFloat() * BACKDROP_SIDE / longSide).roundToInt())
        val sh = max(16, (h.toFloat() * BACKDROP_SIDE / longSide).roundToInt())
        val small = Bitmap.createScaledBitmap(source, sw, sh, true)
        if (scaled != null) scaled.recycle()
        divideByIllumination(px, w, h, small)
        small.recycle()

        stretch(px, w, h)
        unsharp(px, w, h)
        return Work(out, w, h, px)
    }

    private fun emit(work: Work): Bitmap {
        work.bitmap.setPixels(work.pixels, 0, work.w, 0, 0, work.w, work.h)
        return work.bitmap
    }

    /**
     * Estimates the paper illumination per channel on [small] and divides the full image by it.
     * Saturated pixels (chroma ≥ [KEEP_COLOUR_FROM]) blend back toward their original RGB, so
     * stamps and coloured ink do not get bleached by the local background estimate.
     */
    private fun divideByIllumination(px: IntArray, w: Int, h: Int, small: Bitmap) {
        val sw = small.width
        val sh = small.height
        val sp = IntArray(sw * sh)
        small.getPixels(sp, 0, sw, 0, 0, sw, sh)

        val r = FloatArray(sw * sh)
        val g = FloatArray(sw * sh)
        val b = FloatArray(sw * sh)
        for (i in sp.indices) {
            val c = sp[i]
            r[i] = ((c shr 16) and 0xFF).toFloat()
            g[i] = ((c shr 8) and 0xFF).toFloat()
            b[i] = (c and 0xFF).toFloat()
        }
        // Closing removes thin dark text from the background estimate (r=2 at 320 px ≈ 20 px
        // on a 3000 px page); two wide box passes make the estimate smooth.
        closing(r, sw, sh, 2)
        closing(g, sw, sh, 2)
        closing(b, sw, sh, 2)
        val radius = max(2, min(sw, sh) / 10)
        boxBlur(r, sw, sh, radius)
        boxBlur(r, sw, sh, radius)
        boxBlur(g, sw, sh, radius)
        boxBlur(g, sw, sh, radius)
        boxBlur(b, sw, sh, radius)
        boxBlur(b, sw, sh, radius)

        val x0 = IntArray(w)
        val x1 = IntArray(w)
        val wx = FloatArray(w)
        for (x in 0 until w) {
            val fx = (x + 0.5f) * sw / w - 0.5f
            val base = kotlin.math.floor(fx).toInt()
            val cx = base.coerceIn(0, sw - 1)
            x0[x] = cx
            x1[x] = min(sw - 1, cx + 1)
            wx[x] = (fx - base).coerceIn(0f, 1f)
        }
        val y0 = IntArray(h)
        val y1 = IntArray(h)
        val wy = FloatArray(h)
        for (y in 0 until h) {
            val fy = (y + 0.5f) * sh / h - 0.5f
            val base = kotlin.math.floor(fy).toInt()
            val cy = base.coerceIn(0, sh - 1)
            y0[y] = cy
            y1[y] = min(sh - 1, cy + 1)
            wy[y] = (fy - base).coerceIn(0f, 1f)
        }

        for (y in 0 until h) {
            val row = y * w
            val ra = y0[y] * sw
            val rb = y1[y] * sw
            val fy = wy[y]
            for (x in 0 until w) {
                val xa = x0[x]
                val xb = x1[x]
                val fx = wx[x]
                val w00 = (1 - fx) * (1 - fy)
                val w10 = fx * (1 - fy)
                val w01 = (1 - fx) * fy
                val w11 = fx * fy
                val br = r[ra + xa] * w00 + r[ra + xb] * w10 + r[rb + xa] * w01 + r[rb + xb] * w11
                val bg = g[ra + xa] * w00 + g[ra + xb] * w10 + g[rb + xa] * w01 + g[rb + xb] * w11
                val bb = b[ra + xa] * w00 + b[ra + xb] * w10 + b[rb + xa] * w01 + b[rb + xb] * w11

                val c = px[row + x]
                val cr = (c shr 16) and 0xFF
                val cg = (c shr 8) and 0xFF
                val cb = c and 0xFF
                val maxC = max(cr, max(cg, cb))
                val minC = min(cr, min(cg, cb))
                val sat = if (maxC == 0) 0f else (maxC - minC).toFloat() / maxC
                val keep = ((sat - KEEP_COLOUR_FROM) / (KEEP_COLOUR_FULL - KEEP_COLOUR_FROM))
                    .coerceIn(0f, 1f)

                val nr = if (keep >= 0.999f) cr else {
                    val n = (cr * 255f / max(12f, br)).roundToInt().coerceIn(0, 255)
                    (n * (1 - keep) + cr * keep).roundToInt()
                }
                val ng = if (keep >= 0.999f) cg else {
                    val n = (cg * 255f / max(12f, bg)).roundToInt().coerceIn(0, 255)
                    (n * (1 - keep) + cg * keep).roundToInt()
                }
                val nb = if (keep >= 0.999f) cb else {
                    val n = (cb * 255f / max(12f, bb)).roundToInt().coerceIn(0, 255)
                    (n * (1 - keep) + cb * keep).roundToInt()
                }
                px[row + x] = (c and 0xFF000000.toInt()) or
                    (nr.coerceIn(0, 255) shl 16) or
                    (ng.coerceIn(0, 255) shl 8) or
                    nb.coerceIn(0, 255)
            }
        }
    }

    /** Separable max then min (grayscale closing) in place. */
    private fun closing(a: FloatArray, w: Int, h: Int, radius: Int) {
        val tmp = FloatArray(a.size)
        // dilate
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var v = a[row + x]
                for (d in 1..radius) {
                    if (x - d >= 0) v = max(v, a[row + x - d])
                    if (x + d < w) v = max(v, a[row + x + d])
                }
                tmp[row + x] = v
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var v = tmp[y * w + x]
                for (d in 1..radius) {
                    if (y - d >= 0) v = max(v, tmp[(y - d) * w + x])
                    if (y + d < h) v = max(v, tmp[(y + d) * w + x])
                }
                a[y * w + x] = v
            }
        }
        // erode
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var v = a[row + x]
                for (d in 1..radius) {
                    if (x - d >= 0) v = min(v, a[row + x - d])
                    if (x + d < w) v = min(v, a[row + x + d])
                }
                tmp[row + x] = v
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var v = tmp[y * w + x]
                for (d in 1..radius) {
                    if (y - d >= 0) v = min(v, tmp[(y - d) * w + x])
                    if (y + d < h) v = min(v, tmp[(y + d) * w + x])
                }
                a[y * w + x] = v
            }
        }
    }

    /** Separable box blur in place, radius in small-image pixels. */
    private fun boxBlur(a: FloatArray, w: Int, h: Int, radius: Int) {
        if (radius < 1) return
        val tmp = FloatArray(a.size)
        val prefix = FloatArray(max(w, h) + 1)
        for (y in 0 until h) {
            val row = y * w
            prefix[0] = 0f
            for (x in 0 until w) prefix[x + 1] = prefix[x] + a[row + x]
            for (x in 0 until w) {
                val lo = max(0, x - radius)
                val hi = min(w, x + radius + 1)
                tmp[row + x] = (prefix[hi] - prefix[lo]) / (hi - lo)
            }
        }
        for (x in 0 until w) {
            prefix[0] = 0f
            for (y in 0 until h) prefix[y + 1] = prefix[y] + tmp[y * w + x]
            for (y in 0 until h) {
                val lo = max(0, y - radius)
                val hi = min(h, y + radius + 1)
                a[y * w + x] = (prefix[hi] - prefix[lo]) / (hi - lo)
            }
        }
    }

    /** Gentle luma stretch between the 2nd and 99.5th percentile. */
    private fun stretch(px: IntArray, w: Int, h: Int) {
        val n = px.size
        val gray = ShortArray(n)
        val hist = IntArray(256)
        for (i in 0 until n) {
            val v = luma(px[i]).toShort()
            gray[i] = v
            hist[v.toInt()]++
        }
        val lo = percentile(hist, n, STRETCH_LOW)
        val hi = percentile(hist, n, STRETCH_HIGH)
        if (hi - lo < 32) return
        val lut = IntArray(256)
        for (v in 0..255) {
            val stretched = ((v - lo) * 255 / (hi - lo)).coerceIn(0, 255)
            lut[v] = (v + (stretched - v) * STRETCH_AMOUNT).roundToInt().coerceIn(0, 255)
        }
        for (i in 0 until n) {
            val g = gray[i].toInt()
            val target = lut[g]
            if (target == g) continue
            val k = target * 256 / max(1, g)
            val c = px[i]
            val r = ((((c shr 16) and 0xFF) * k) shr 8).coerceIn(0, 255)
            val gg = ((((c shr 8) and 0xFF) * k) shr 8).coerceIn(0, 255)
            val b = (((c and 0xFF) * k) shr 8).coerceIn(0, 255)
            px[i] = (c and 0xFF000000.toInt()) or (r shl 16) or (gg shl 8) or b
        }
    }

    private fun percentile(hist: IntArray, n: Int, p: Double): Int {
        val target = (n * p).toInt().coerceIn(0, max(0, n - 1))
        var seen = 0
        for (v in 0..255) {
            seen += hist[v]
            if (seen > target) return v
        }
        return 255
    }

    /** 3×3 unsharp mask on luminance; colour ratios are kept by scaling RGB together. */
    private fun unsharp(px: IntArray, w: Int, h: Int) {
        if (w < 3 || h < 3) return
        val gray = ShortArray(w * h)
        for (i in px.indices) gray[i] = luma(px[i]).toShort()
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                val g = gray[i].toInt()
                val blur = (
                    gray[i - w - 1] + 2 * gray[i - w] + gray[i - w + 1] +
                        2 * gray[i - 1] + 4 * gray[i] + 2 * gray[i + 1] +
                        gray[i + w - 1] + 2 * gray[i + w] + gray[i + w + 1]
                    ).toInt() / 16
                val d = g - blur
                if (abs(d) < UNSHARP_THRESHOLD) continue
                val delta = (d * UNSHARP_AMOUNT).roundToInt().coerceIn(-UNSHARP_CLAMP, UNSHARP_CLAMP)
                val c = px[i]
                val r = (((c shr 16) and 0xFF) + delta).coerceIn(0, 255)
                val gg = (((c shr 8) and 0xFF) + delta).coerceIn(0, 255)
                val b = ((c and 0xFF) + delta).coerceIn(0, 255)
                px[i] = (c and 0xFF000000.toInt()) or (r shl 16) or (gg shl 8) or b
            }
        }
    }

    /** Low-saturation colours gain more, saturated stamps/photos are left near their own hue. */
    private fun vibrance(work: Work) {
        val px = work.pixels
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val maxC = max(r, max(g, b))
            val minC = min(r, min(g, b))
            val sat = if (maxC == 0) 0f else (maxC - minC).toFloat() / maxC
            val boost = (1.18f + 0.55f * (1f - sat)).coerceAtMost(1.9f)
            if (boost <= 1.001f) continue
            val l = luma(c)
            val nr = (l + (r - l) * boost).roundToInt().coerceIn(0, 255)
            val ng = (l + (g - l) * boost).roundToInt().coerceIn(0, 255)
            val nb = (l + (b - l) * boost).roundToInt().coerceIn(0, 255)
            px[i] = (c and 0xFF000000.toInt()) or (nr shl 16) or (ng shl 8) or nb
        }
    }

    // ------------------------------------------------------------------
    // Black & white: tiled Sauvola
    // ------------------------------------------------------------------

    /**
     * Sauvola on a grid of 32 px tiles, bilinearly interpolated. Per-tile statistics keep memory
     * O(tiles), unlike integral images (two 27 MB planes on a 3000 px page) and avoid the global
     * Otsu failure mode where one shadow turns a whole region solid black.
     */
    private fun adaptiveThreshold(work: Work): ShortArray {
        val w = work.w
        val h = work.h
        val px = work.pixels
        val gray = ShortArray(w * h)
        for (i in px.indices) gray[i] = luma(px[i]).toShort()

        val tile = 32
        val tw = (w + tile - 1) / tile
        val th = (h + tile - 1) / tile
        val thresholds = FloatArray(tw * th)
        for (ty in 0 until th) {
            val y0 = ty * tile
            val y1 = min(h, y0 + tile)
            for (tx in 0 until tw) {
                val x0 = tx * tile
                val x1 = min(w, x0 + tile)
                var sum = 0L
                var sumSq = 0L
                for (y in y0 until y1) {
                    val row = y * w
                    for (x in x0 until x1) {
                        val v = gray[row + x].toInt()
                        sum += v
                        sumSq += v.toLong() * v
                    }
                }
                val count = (x1 - x0) * (y1 - y0)
                val mean = sum.toDouble() / count
                val variance = (sumSq.toDouble() / count - mean * mean).coerceAtLeast(0.0)
                val std = sqrt(variance)
                // Sauvola: T = m (1 + k (s/R - 1)), k = 0.25, R = 128.
                thresholds[ty * tw + tx] =
                    (mean * (1.0 + 0.25 * (std / 128.0 - 1.0))).toFloat().coerceIn(8f, 245f)
            }
        }

        val cx = IntArray(w)
        val cx1 = IntArray(w)
        val fx = FloatArray(w)
        for (x in 0 until w) {
            val f = (x + 0.5f) / tile - 0.5f
            val base = kotlin.math.floor(f).toInt()
            cx[x] = base.coerceIn(0, tw - 1)
            cx1[x] = min(tw - 1, cx[x] + 1)
            fx[x] = (f - base).coerceIn(0f, 1f)
        }
        val cy = IntArray(h)
        val cy1 = IntArray(h)
        val fy = FloatArray(h)
        for (y in 0 until h) {
            val f = (y + 0.5f) / tile - 0.5f
            val base = kotlin.math.floor(f).toInt()
            cy[y] = base.coerceIn(0, th - 1)
            cy1[y] = min(th - 1, cy[y] + 1)
            fy[y] = (f - base).coerceIn(0f, 1f)
        }

        for (y in 0 until h) {
            val row = y * w
            val ra = cy[y] * tw
            val rb = cy1[y] * tw
            val wy = fy[y]
            for (x in 0 until w) {
                val xa = cx[x]
                val xb = cx1[x]
                val wx = fx[x]
                val t = thresholds[ra + xa] * (1 - wx) * (1 - wy) +
                    thresholds[ra + xb] * wx * (1 - wy) +
                    thresholds[rb + xa] * (1 - wx) * wy +
                    thresholds[rb + xb] * wx * wy
                val v = if (gray[row + x] < t) 0 else 255
                px[row + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        return gray
    }

    private fun luma(c: Int): Int {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return (r * 77 + g * 151 + b * 28) shr 8
    }

    /**
     * B/W only: regions that are continuous-tone pictures (a portrait, a printed photo) rather
     * than text strokes. Cells are classified on a coarse grid by colour saturation and by the
     * mix of mid-tones (photos) versus black+white (text), then interior holes are filled,
     * small marks (a red heading, a stamp) are dropped and the result is dilated/upsampled.
     * Returns null when the page has no picture at all.
     */
    private fun pictureMask(work: Work): ByteArray? {
        val w = work.w
        val h = work.h
        val longSide = max(w, h)
        val cell = max(4, longSide / 220)
        val gw = max(1, w / cell)
        val gh = max(1, h / cell)
        val cand = ByteArray(gw * gh)
        var any = false
        for (gy in 0 until gh) {
            val y0 = gy * cell
            val y1 = min(h, y0 + cell)
            for (gx in 0 until gw) {
                val x0 = gx * cell
                val x1 = min(w, x0 + cell)
                var sumL = 0L
                var sumSat = 0f
                var mid = 0
                var dark = 0
                var bright = 0
                var count = 0
                for (y in y0 until y1) {
                    val row = y * w
                    for (x in x0 until x1) {
                        val c = work.pixels[row + x]
                        val r = (c shr 16) and 0xFF
                        val g = (c shr 8) and 0xFF
                        val b = c and 0xFF
                        val mx = max(r, max(g, b))
                        val mn = min(r, min(g, b))
                        val l = (r * 77 + g * 151 + b * 28) shr 8
                        sumL += l
                        sumSat += if (mx == 0) 0f else (mx - mn).toFloat() / mx
                        if (l < 70) dark++ else if (l > 210) bright++ else mid++
                        count++
                    }
                }
                if (count == 0) continue
                val meanL = sumL.toDouble() / count
                val sat = sumSat / count
                val midFrac = mid.toDouble() / count
                val darkFrac = dark.toDouble() / count
                val brightFrac = bright.toDouble() / count
                val picture = sat > 0.30f ||
                    (midFrac > 0.45 && darkFrac < 0.55 && brightFrac < 0.60 && meanL in 60.0..235.0)
                if (picture) {
                    cand[gy * gw + gx] = 1
                    any = true
                }
            }
        }
        if (!any) return null

        // Fill enclosed holes (a photo's dark hair, a shirt edge).
        val outside = ByteArray(gw * gh)
        val queue = IntArray(gw * gh)
        var head = 0
        var tail = 0
        fun push(i: Int) {
            if (cand[i].toInt() != 0 || outside[i].toInt() != 0) return
            outside[i] = 1
            queue[tail++] = i
        }
        for (x in 0 until gw) {
            push(x)
            push((gh - 1) * gw + x)
        }
        for (y in 0 until gh) {
            push(y * gw)
            push(y * gw + gw - 1)
        }
        while (head < tail) {
            val i = queue[head++]
            val x = i % gw
            if (x > 0) push(i - 1)
            if (x < gw - 1) push(i + 1)
            if (i >= gw) push(i - gw)
            if (i < gw * gh - gw) push(i + gw)
        }
        for (i in cand.indices) {
            if (cand[i].toInt() == 0 && outside[i].toInt() == 0) cand[i] = 1
        }

        // Keep only large picture areas (a photo), so red headings/stamps stay black text.
        val visited = ByteArray(gw * gh)
        val minArea = max(6, (gw * gh) / 45)
        val comp = IntArray(gw * gh)
        for (start in cand.indices) {
            if (cand[start].toInt() == 0 || visited[start].toInt() != 0) continue
            var cHead = 0
            var cTail = 0
            comp[cTail++] = start
            visited[start] = 1
            while (cHead < cTail) {
                val i = comp[cHead++]
                val x = i % gw
                if (x > 0 && cand[i - 1].toInt() != 0 && visited[i - 1].toInt() == 0) {
                    visited[i - 1] = 1; comp[cTail++] = i - 1
                }
                if (x < gw - 1 && cand[i + 1].toInt() != 0 && visited[i + 1].toInt() == 0) {
                    visited[i + 1] = 1; comp[cTail++] = i + 1
                }
                if (i >= gw && cand[i - gw].toInt() != 0 && visited[i - gw].toInt() == 0) {
                    visited[i - gw] = 1; comp[cTail++] = i - gw
                }
                if (i < gw * gh - gw && cand[i + gw].toInt() != 0 && visited[i + gw].toInt() == 0) {
                    visited[i + gw] = 1; comp[cTail++] = i + gw
                }
            }
            if (cTail < minArea) {
                for (k in 0 until cTail) cand[comp[k]] = 0
            }
        }

        // Dilate one cell so the photo frame is included.
        val dilated = ByteArray(gw * gh)
        for (y in 0 until gh) {
            for (x in 0 until gw) {
                var on = false
                for (dy in -1..1) {
                    val yy = y + dy
                    if (yy !in 0 until gh) continue
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx in 0 until gw && cand[yy * gw + xx].toInt() != 0) {
                            on = true
                            break
                        }
                    }
                    if (on) break
                }
                dilated[y * gw + x] = if (on) 1 else 0
            }
        }

        val mask = ByteArray(w * h)
        for (y in 0 until h) {
            val gy = min(gh - 1, y * gh / h)
            for (x in 0 until w) {
                val gx = min(gw - 1, x * gw / w)
                mask[y * w + x] = dilated[gy * gw + gx]
            }
        }
        return mask
    }

    /**
     * B/W only: ruling lines are thin (1-2 px) but slanted under perspective, so they appear as
     * long horizontal black runs per row. A run ≥ 1/6 of the width whose vertical thickness is
     * ≤ 4 px is ruling, not ink; it becomes white. Handwriting strokes are thicker and shorter.
     */
    private fun removeThinHorizontalRuns(px: IntArray, w: Int, h: Int) {
        if (w < 48) return
        val minRun = max(24, w / 20)
        val white = (0xFF shl 24) or 0x00FFFFFF
        for (y in 0 until h) {
            val row = y * w
            var x = 0
            while (x < w) {
                if (!isBlack(px[row + x])) { x++; continue }
                var end = x
                while (end < w && isBlack(px[row + end])) end++
                if (end - x >= minRun) {
                    val cx = x + (end - x) / 2
                    var up = 0
                    var yy = y - 1
                    while (yy >= 0 && up < 4 && isBlack(px[yy * w + cx])) { up++; yy-- }
                    var down = 0
                    yy = y + 1
                    while (yy < h && down < 4 && isBlack(px[yy * w + cx])) { down++; yy++ }
                    if (up + down <= 3) {
                        for (i in x until end) px[row + i] = white
                    }
                }
                x = end
            }
        }
    }

    /**
     * The same rule for pages photographed in portrait: ruling can run vertically (the page is
     * turned 90° in the frame), so long thin vertical runs are whitened too.
     */
    private fun removeThinVerticalRuns(px: IntArray, w: Int, h: Int) {
        if (h < 48) return
        val minRun = max(24, h / 6)
        val white = (0xFF shl 24) or 0x00FFFFFF
        for (x in 0 until w) {
            var y = 0
            while (y < h) {
                if (!isBlack(px[y * w + x])) { y++; continue }
                var end = y
                while (end < h && isBlack(px[end * w + x])) end++
                if (end - y >= minRun) {
                    val cy = y + (end - y) / 2
                    var left = 0
                    var xx = x - 1
                    while (xx >= 0 && left < 4 && isBlack(px[cy * w + xx])) { left++; xx-- }
                    var right = 0
                    xx = x + 1
                    while (xx < w && right < 4 && isBlack(px[cy * w + xx])) { right++; xx++ }
                    if (left + right <= 3) {
                        for (i in y until end) px[i * w + x] = white
                    }
                }
                y = end
            }
        }
    }

    /**
     * B/W only: connected components that are long and thin are notebook ruling (they slant
     * under perspective, so a row-run test misses them). Text characters stay.
     */
    private fun removeRulingLines(px: IntArray, w: Int, h: Int, gray: ShortArray) {
        val n = w * h
        val black = ByteArray(n)
        for (i in 0 until n) if (isBlack(px[i])) black[i] = 1
        var queue = IntArray(8192)
        val white = (0xFF shl 24) or 0x00FFFFFF
        for (i in 0 until n) {
            if (black[i].toInt() == 0) continue
            var tail = 0
            var head = 0
            if (tail == queue.size) queue = queue.copyOf(queue.size * 2)
            black[i] = 0
            queue[tail++] = i
            var minX = w
            var maxX = 0
            var minY = h
            var maxY = 0
            while (head < tail) {
                val idx = queue[head++]
                val x = idx % w
                val y = idx / w
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (x > 0) {
                    val j = idx - 1
                    if (black[j].toInt() != 0) {
                        black[j] = 0
                        if (tail == queue.size) queue = queue.copyOf(queue.size * 2)
                        queue[tail++] = j
                    }
                }
                if (x < w - 1) {
                    val j = idx + 1
                    if (black[j].toInt() != 0) {
                        black[j] = 0
                        if (tail == queue.size) queue = queue.copyOf(queue.size * 2)
                        queue[tail++] = j
                    }
                }
                if (idx >= w) {
                    val j = idx - w
                    if (black[j].toInt() != 0) {
                        black[j] = 0
                        if (tail == queue.size) queue = queue.copyOf(queue.size * 2)
                        queue[tail++] = j
                    }
                }
                if (idx < n - w) {
                    val j = idx + w
                    if (black[j].toInt() != 0) {
                        black[j] = 0
                        if (tail == queue.size) queue = queue.copyOf(queue.size * 2)
                        queue[tail++] = j
                    }
                }
            }
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            val area = tail
            val longThin = (bw > w / 4 && bh <= max(24, bw / 20) && area < bw * 3) ||
                (bh > h / 4 && bw <= max(24, bh / 20) && area < bh * 3)
            // A background blob (cloth motif, wood grain, lanyard) sits on a dark surround; text
            // and stamps sit on paper, whose ring is bright. This is what removes the large black
            // background patches without needing a perfect page mask.
            val ring = ringLuma(gray, w, h, minX, maxX, minY, maxY)
            if (longThin || ring < 150) {
                for (k in 0 until tail) px[queue[k]] = white
            }
        }
    }

    /** Mean luminance on a thin ring just outside a component's bounding box. */
    private fun ringLuma(
        gray: ShortArray,
        w: Int,
        h: Int,
        minX: Int,
        maxX: Int,
        minY: Int,
        maxY: Int
    ): Int {
        val left = (minX - 3).coerceIn(0, w - 1)
        val right = (maxX + 3).coerceIn(0, w - 1)
        val top = (minY - 3).coerceIn(0, h - 1)
        val bottom = (maxY + 3).coerceIn(0, h - 1)
        var sum = 0L
        var count = 0
        val stepX = max(1, (right - left) / 12)
        val stepY = max(1, (bottom - top) / 12)
        for (x in left..right step stepX) {
            sum += gray[top * w + x]
            sum += gray[bottom * w + x]
            count += 2
        }
        for (y in top..bottom step stepY) {
            sum += gray[y * w + left]
            sum += gray[y * w + right]
            count += 2
        }
        return if (count == 0) 255 else (sum / count).toInt()
    }

    /**
     * B/W only: black regions connected to the image border are the desk, cloth, lanyard or a
     * hand, not page content. A flood fill from every black border pixel clears them, which is
     * what removes the large black background blobs without touching text (or a photo's hair,
     * which does not touch the border).
     */
    private fun removeBorderConnectedBlobs(px: IntArray, w: Int, h: Int) {
        val n = w * h
        val black = ByteArray(n)
        for (i in 0 until n) if (isBlack(px[i])) black[i] = 1

        var stack = IntArray(4096)
        var top = 0
        fun push(index: Int) {
            if (black[index].toInt() == 0) return
            black[index] = 0
            if (top == stack.size) stack = stack.copyOf(stack.size * 2)
            stack[top++] = index
        }
        for (x in 0 until w) {
            push(x)
            push((h - 1) * w + x)
        }
        for (y in 0 until h) {
            push(y * w)
            push(y * w + w - 1)
        }
        while (top > 0) {
            val i = stack[--top]
            val x = i % w
            if (x > 0) push(i - 1)
            if (x < w - 1) push(i + 1)
            if (i >= w) push(i - w)
            if (i < n - w) push(i + w)
        }
        val white = (0xFF shl 24) or 0x00FFFFFF
        for (i in 0 until n) {
            if (isBlack(px[i]) && black[i].toInt() == 0) px[i] = white
        }
    }

    private fun isBlack(c: Int): Boolean = (c and 0x00FFFFFF) == 0
}
