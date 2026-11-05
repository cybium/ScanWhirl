package com.scanwhirl.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object ImageTools {

    private val PNG_SIG = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    data class ResizeOptions(
        /** null = keep original dimension (or derive from the other axis when keeping ratio). */
        val width: Int? = null,
        val height: Int? = null,
        val dpi: Int = 96,
        val keepRatio: Boolean = true,
        /** When set, output is re-encoded (JPEG) until the file fits within this many bytes. */
        val maxBytes: Long? = null,
        /** Preserve PNG format (only honored when no [maxBytes] target is set). */
        val keepPng: Boolean = false
    )

    data class ResizeResult(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
        val isPng: Boolean
    ) {
        override fun equals(other: Any?): Boolean =
            other is ResizeResult &&
                bytes.contentEquals(other.bytes) &&
                width == other.width && height == other.height && isPng == other.isPng
        override fun hashCode(): Int =
            (bytes.contentHashCode() * 31 + width) * 31 + height
    }

    data class FitResult(val bytes: ByteArray, val width: Int, val height: Int) {
        override fun equals(other: Any?): Boolean =
            other is FitResult && bytes.contentEquals(other.bytes) &&
                width == other.width && height == other.height
        override fun hashCode(): Int = (bytes.contentHashCode() * 31 + width) * 31 + height
    }

    /** Decode an image from bytes, downsampling so neither side exceeds [maxDim]. */
    fun decodeSampled(bytes: ByteArray, maxDim: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Not a decodable image" }

        var sample = 1
        var w = bounds.outWidth
        var h = bounds.outHeight
        while (w / 2 >= maxDim || h / 2 >= maxDim) {
            sample *= 2
            w /= 2
            h /= 2
        }

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: throw IllegalStateException("Could not decode image")
    }

    fun encodeJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)
        return out.toByteArray()
    }

    /** Decodes a downsampled thumbnail from a file for lists; null when unreadable. */
    fun decodeThumb(file: java.io.File, maxDim: Int): Bitmap? = try {
        if (!file.exists() || file.length() == 0L) null
        else decodeSampled(file.readBytes(), maxDim)
    } catch (e: Exception) {
        null
    }

    fun encodePng(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    /** Bounds-only read: returns Pair(width, height) or null when undecodable. */
    fun imageSize(bytes: ByteArray): Pair<Int, Int>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return if (bounds.outWidth > 0 && bounds.outHeight > 0)
            bounds.outWidth to bounds.outHeight else null
    }

    fun isPng(bytes: ByteArray): Boolean =
        bytes.size >= 8 && (0..7).all { bytes[it] == PNG_SIG[it] }

    /**
     * Parses a human size like "500 KB", "3 MB", "1.5m" or a bare number (treated as KB).
     * Returns bytes, or null when the text is empty/unparseable.
     */
    fun parseSizeInput(text: String?): Long? {
        val cleaned = text?.trim()?.lowercase()?.replace(" ", "") ?: return null
        if (cleaned.isEmpty()) return null
        val match = Regex("(\\d+(?:\\.\\d+)?)(kb|mb|k|m|b)?").matchEntire(cleaned) ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        val bytes = when (match.groupValues[2]) {
            "mb", "m" -> value * 1_048_576.0
            "kb", "k" -> value * 1024.0
            "b" -> value
            else -> value * 1024.0 // bare number = KB
        }
        return bytes.roundToInt().coerceAtLeast(1).toLong()
    }

    /** Resolves output size from the options and encodes (JPEG/PNG, optional size cap + DPI tag). */
    fun resize(src: ByteArray, opts: ResizeOptions): ResizeResult {
        val orig = imageSize(src) ?: throw IllegalStateException("Not a decodable image")

        var w = opts.width
        var h = opts.height
        if (w == null && h == null) {
            w = orig.first
            h = orig.second
        } else {
            if (w == null) {
                w = if (opts.keepRatio) derive(h!!, orig.second, orig.first) else orig.first
            }
            if (h == null) {
                h = if (opts.keepRatio) derive(w!!, orig.first, orig.second) else orig.second
            }
        }
        val outW = w!!.coerceIn(1, 12000)
        val outH = h!!.coerceIn(1, 12000)

        var bmp = decodeSampled(src, maxOf(outW, outH, 2048))
        if (bmp.width != outW || bmp.height != outH) {
            val scaled = Bitmap.createScaledBitmap(bmp, outW, outH, true)
            if (scaled !== bmp) bmp.recycle()
            bmp = scaled
        }

        val pngOut = isPng(src) && opts.keepPng && opts.maxBytes == null
        val result = when {
            opts.maxBytes != null -> {
                val enc = compressToFit(bmp, opts.maxBytes, asPng = false, dpi = opts.dpi)
                ResizeResult(enc.bytes, enc.width, enc.height, isPng = false)
            }
            pngOut -> ResizeResult(patchPngDpi(encodePng(bmp), opts.dpi), outW, outH, isPng = true)
            else -> ResizeResult(patchJpegDpi(encodeJpeg(flatten(bmp), 95), opts.dpi), outW, outH, isPng = false)
        }
        bmp.recycle()
        return result
    }

    /** Resize to exact pixel dimensions (lossy JPEG only). */
    fun resizeJpeg(src: ByteArray, targetW: Int, targetH: Int, quality: Int = 95): ByteArray {
        require(targetW in 1..12000 && targetH in 1..12000) { "Invalid target size" }
        val decoded = decodeSampled(src, maxOf(targetW, targetH, 2048))
        val scaled = Bitmap.createScaledBitmap(decoded, targetW, targetH, true)
        if (scaled !== decoded) decoded.recycle()
        val flat = flatten(scaled)
        val result = encodeJpeg(flat, quality)
        if (flat !== scaled) flat.recycle()
        scaled.recycle()
        return result
    }

    /**
     * Re-encode at [quality]; optionally downscale so the longest side is at most [maxDim].
     * [maxDim] null keeps original dimensions.
     */
    fun recompressJpeg(src: ByteArray, quality: Int, maxDim: Int?): ByteArray {
        val bitmap = if (maxDim == null) {
            BitmapFactory.decodeByteArray(src, 0, src.size)
                ?: throw IllegalStateException("Could not decode image")
        } else {
            decodeSampled(src, maxDim)
        }

        val target = if (maxDim != null &&
            (bitmap.width > maxDim || bitmap.height > maxDim)
        ) {
            val scale = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
            val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, w, h, true)
        } else {
            bitmap
        }

        if (target !== bitmap) bitmap.recycle()
        val result = encodeJpeg(target, quality)
        target.recycle()
        return result
    }

    /**
     * Encodes as JPEG, lowering quality (then shrinking the image further when needed)
     * until the output fits within [maxBytes]. Best effort: if even the smallest
     * rendition is too big, the smallest produced file is returned.
     */
    fun compressToFit(bitmap: Bitmap, maxBytes: Long, asPng: Boolean = false, dpi: Int = 0): FitResult {
        require(maxBytes > 0) { "Target size must be positive" }
        var current = flatten(bitmap)
        var best: ByteArray? = null

        while (true) {
            var quality = 90
            while (quality >= 12) {
                val out = if (asPng) encodePng(current) else encodeJpeg(current, quality)
                if (best == null || out.size < best.size) best = out
                if (out.size <= maxBytes) {
                    val dims = current.width to current.height
                    if (current !== bitmap) current.recycle()
                    val patched = if (!asPng && dpi > 0) patchJpegDpi(out, dpi) else out
                    return FitResult(patched, dims.first, dims.second)
                }
                quality -= 8
            }
            // Quality floor reached without fitting — shrink the image and retry.
            val nw = (current.width * 0.7).toInt().coerceAtLeast(64)
            val nh = (current.height * 0.7).toInt().coerceAtLeast(64)
            if (nw >= current.width || nh >= current.height) {
                val dims = current.width to current.height
                val smallest = best ?: encodeJpeg(current, 12)
                if (current !== bitmap) current.recycle()
                return FitResult(smallest, dims.first, dims.second)
            }
            val scaled = Bitmap.createScaledBitmap(current, nw, nh, true)
            if (current !== bitmap) current.recycle()
            current = scaled
        }
    }

    /** Sets JFIF density (X/Y dpi) in a JPEG's APP0 header. No-op when absent. */
    fun patchJpegDpi(bytes: ByteArray, dpi: Int): ByteArray {
        if (dpi !in 1..65535) return bytes
        val idx = indexOfJfif(bytes) ?: return bytes
        // layout after "JFIF\0": major(1) minor(1) units(1) X(2) Y(2)
        val units = idx + 7
        if (units + 4 >= bytes.size) return bytes
        bytes[units] = 1 // dots per inch
        bytes[units + 1] = ((dpi shr 8) and 0xFF).toByte()
        bytes[units + 2] = (dpi and 0xFF).toByte()
        bytes[units + 3] = ((dpi shr 8) and 0xFF).toByte()
        bytes[units + 4] = (dpi and 0xFF).toByte()
        return bytes
    }

    /** Writes/updates a pHYs chunk (pixels per metre) so viewers see [dpi]. */
    fun patchPngDpi(bytes: ByteArray, dpi: Int): ByteArray {
        if (dpi !in 1..65535 || !isPng(bytes)) return bytes
        val ppm = (dpi * 39.3700787).roundToInt().toLong()
        val data = ByteArray(9)
        fun putU32(at: Int, v: Long) {
            data[at] = ((v shr 24) and 0xFF).toByte()
            data[at + 1] = ((v shr 16) and 0xFF).toByte()
            data[at + 2] = ((v shr 8) and 0xFF).toByte()
            data[at + 3] = (v and 0xFF).toByte()
        }
        putU32(0, ppm)
        putU32(4, ppm)
        data[8] = 1 // unit = metre

        val chunk = buildChunk("pHYs", data)

        var pos = 8
        while (pos + 12 <= bytes.size) {
            val len = ((bytes[pos].toInt() and 0xFF) shl 24) or
                ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
                (bytes[pos + 3].toInt() and 0xFF)
            val type = String(bytes, pos + 4, 4, Charsets.US_ASCII)
            if (type == "pHYs") {
                val out = bytes.copyOf()
                chunk.copyInto(out, destinationOffset = pos)
                return out
            }
            if (type == "IEND") {
                val out = ByteArray(bytes.size + chunk.size)
                bytes.copyInto(out, destinationOffset = 0, startIndex = 0, endIndex = pos)
                chunk.copyInto(out, destinationOffset = pos)
                bytes.copyInto(out, destinationOffset = pos + chunk.size, startIndex = pos)
                return out
            }
            pos += 12 + len
        }
        return bytes
    }

    private fun buildChunk(type: String, data: ByteArray): ByteArray {
        val typeAndData = type.toByteArray(Charsets.US_ASCII) + data
        val crc = CRC32().apply { update(typeAndData) }.value
        val out = ByteArray(12 + data.size)
        val len = data.size
        out[0] = ((len shr 24) and 0xFF).toByte()
        out[1] = ((len shr 16) and 0xFF).toByte()
        out[2] = ((len shr 8) and 0xFF).toByte()
        out[3] = (len and 0xFF).toByte()
        typeAndData.copyInto(out, destinationOffset = 4)
        val crcAt = 4 + typeAndData.size
        out[crcAt] = ((crc shr 24) and 0xFF).toByte()
        out[crcAt + 1] = ((crc shr 16) and 0xFF).toByte()
        out[crcAt + 2] = ((crc shr 8) and 0xFF).toByte()
        out[crcAt + 3] = (crc and 0xFF).toByte()
        return out
    }

    private fun indexOfJfif(bytes: ByteArray): Int? {
        val sig = byteArrayOf(0x4A, 0x46, 0x49, 0x46, 0x00) // "JFIF\0"
        outer@ for (i in 0..bytes.size - sig.size) {
            for (k in sig.indices) {
                if (bytes[i + k] != sig[k]) continue@outer
            }
            return i
        }
        return null
    }

    /** Composites onto white so transparent pixels don't turn black in JPEG. */
    /**
     * Auto-levels "Enhance": stretches each color channel between its 0.5th and
     * 99.5th percentile, so washed-out pages gain contrast and brightness.
     */
    fun enhance(src: Bitmap): Bitmap {
        val bmp = argb8888(src)
        val w = bmp.width
        val h = bmp.height
        val n = w * h
        if (n == 0) return bmp
        val pixels = IntArray(n)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        val rh = IntArray(256)
        val gh = IntArray(256)
        val bh = IntArray(256)
        for (c in pixels) {
            rh[(c shr 16) and 0xFF]++
            gh[(c shr 8) and 0xFF]++
            bh[c and 0xFF]++
        }
        val (loR, hiR) = stretchRange(rh, n)
        val (loG, hiG) = stretchRange(gh, n)
        val (loB, hiB) = stretchRange(bh, n)
        val lutR = buildLut(loR, hiR)
        val lutG = buildLut(loG, hiG)
        val lutB = buildLut(loB, hiB)

        for (i in 0 until n) {
            val c = pixels[i]
            val r = lutR[(c shr 16) and 0xFF]
            val g = lutG[(c shr 8) and 0xFF]
            val b = lutB[c and 0xFF]
            pixels[i] = (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }

    /**
     * "Clean" stain/shadow removal: every pixel is divided by its local (blurred)
     * background, which pushes paper, shadows, and light stains to white while text
     * stays dark; thin notebook ruling lines are lightened, then a percentile stretch.
     */
    fun cleanBackground(src: Bitmap): Bitmap {
        val bmp = argb8888(src)
        val w = bmp.width
        val h = bmp.height
        val n = w * h
        if (n == 0) return bmp
        val pixels = IntArray(n)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        divideByBackground(pixels, bmp)
        lightenRuledLines(pixels, w, h)
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return enhance(bmp)
    }

    /**
     * "B/W": normalize by the local background, drop thin notebook ruling lines,
     * then adaptive (Otsu) binarization — pure black text on pure white paper.
     */
    fun blackWhite(src: Bitmap): Bitmap {
        val bmp = argb8888(src)
        val w = bmp.width
        val h = bmp.height
        val n = w * h
        if (n == 0) return bmp
        val pixels = IntArray(n)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        divideByBackground(pixels, bmp)
        lightenRuledLines(pixels, w, h)

        val hist = IntArray(256)
        for (i in 0 until n) hist[grayOf(pixels[i])]++
        val threshold = otsuThreshold(hist, n)

        for (i in 0 until n) {
            val v = if (grayOf(pixels[i]) <= threshold) 0 else 255
            pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }

    /** Divides every pixel by its local (blurred) background estimate, in place. */
    private fun divideByBackground(pixels: IntArray, bmp: Bitmap) {
        val w = bmp.width
        val h = bmp.height

        // Local background estimate on a small copy (fast, smooth).
        val longSide = max(w, h)
        val sample = if (longSide > 256) Bitmap.createScaledBitmap(bmp, max(8, w * 256 / longSide), max(8, h * 256 / longSide), true) else bmp
        val sw = sample.width
        val sh = sample.height
        val small = IntArray(sw * sh)
        sample.getPixels(small, 0, sw, 0, 0, sw, sh)
        if (sample !== bmp) sample.recycle()

        val bg = FloatArray(sw * sh)
        for (i in 0 until sw * sh) {
            val c = small[i]
            bg[i] = (0.299f * ((c shr 16) and 0xFF)) +
                (0.587f * ((c shr 8) and 0xFF)) +
                (0.114f * (c and 0xFF))
        }
        val radius = max(2, min(sw, sh) / 15)
        boxBlur(bg, sw, sh, radius)
        boxBlur(bg, sw, sh, radius)

        for (y in 0 until h) {
            val byRow = min(sh - 1, y * sh / h) * sw
            for (x in 0 until w) {
                val local = max(12f, bg[byRow + min(sw - 1, x * sw / w)])
                val scale = 255f / local
                val c = pixels[y * w + x]
                val a = c and 0xFF000000.toInt()
                val r = (((c shr 16) and 0xFF) * scale).toInt().coerceIn(0, 255)
                val g = (((c shr 8) and 0xFF) * scale).toInt().coerceIn(0, 255)
                val b = ((c and 0xFF) * scale).toInt().coerceIn(0, 255)
                pixels[y * w + x] = a or (r shl 16) or (g shl 8) or b
            }
        }
    }

    /**
     * Lightens thin horizontal ruling lines: a run of lighter marks (gray 90–210)
     * spanning at least a fifth of the row and only 1–3 px thick vertically.
     * Dark ink and thick structures (text blocks, photos) are left alone, and so is
     * anything strongly colored (pen ink) via the chroma guard.
     */
    private fun lightenRuledLines(pixels: IntArray, w: Int, h: Int) {
        if (w < 48 || h < 8) return
        val n = w * h
        val minRun = max(16, w / 5)
        val ink = BooleanArray(n)
        for (i in 0 until n) ink[i] = grayOf(pixels[i]) < 210

        for (y in 0 until h) {
            var x = 0
            while (x < w) {
                if (!ink[y * w + x]) { x++; continue }
                var end = x
                while (end < w && ink[y * w + end]) end++
                if (end - x >= minRun) {
                    for (px in x until end) {
                        val idx = y * w + px
                        val c = pixels[idx]
                        val g = grayOf(c)
                        if (g < 90 || g >= 210) continue
                        val r = (c shr 16) and 0xFF
                        val g8 = (c shr 8) and 0xFF
                        val b = c and 0xFF
                        if (max(r, max(g8, b)) - min(r, min(g8, b)) > 45) continue // colored pen stays
                        var thick = 0
                        for (dy in -2..2) {
                            val yy = y + dy
                            if (yy in 0 until h && ink[yy * w + px]) thick++
                        }
                        if (thick <= 3) {
                            pixels[idx] = (c and 0xFF000000.toInt()) or 0x00FFFFFF
                        }
                    }
                }
                x = end
            }
        }
    }

    private fun grayOf(c: Int): Int =
        (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 151 + (c and 0xFF) * 28) shr 8

    /** Otsu global threshold; falls back to a safe mid value on flat histograms. */
    private fun otsuThreshold(hist: IntArray, total: Int): Int {
        var sum = 0L
        for (v in 0..255) sum += v.toLong() * hist[v]
        var sumB = 0L
        var wB = 0L
        var best = 0
        var maxVar = -1.0
        for (v in 0..255) {
            wB += hist[v]
            if (wB == 0L) continue
            val wF = total - wB
            if (wF == 0L) break
            sumB += v.toLong() * hist[v]
            val mB = sumB.toDouble() / wB
            val mF = (sum - sumB).toDouble() / wF
            val between = wB.toDouble() * wF.toDouble() * (mB - mF) * (mB - mF)
            if (between > maxVar) { maxVar = between; best = v }
        }
        return if (best in 40..215) best else 165
    }

    /** Separable box blur (prefix sums per row/column), used by cleanBackground. */
    private fun boxBlur(gray: FloatArray, w: Int, h: Int, r: Int) {
        if (r < 1) return
        val tmp = FloatArray(w * h)
        val px = FloatArray(w + 1)
        for (y in 0 until h) {
            val row = y * w
            px[0] = 0f
            for (x in 0 until w) px[x + 1] = px[x] + gray[row + x]
            for (x in 0 until w) {
                val lo = max(0, x - r)
                val hi = min(w, x + r + 1)
                tmp[row + x] = (px[hi] - px[lo]) / (hi - lo)
            }
        }
        val py = FloatArray(h + 1)
        for (x in 0 until w) {
            py[0] = 0f
            for (y in 0 until h) py[y + 1] = py[y] + tmp[y * w + x]
            for (y in 0 until h) {
                val lo = max(0, y - r)
                val hi = min(h, y + r + 1)
                gray[y * w + x] = (py[hi] - py[lo]) / (hi - lo)
            }
        }
    }

    private fun argb8888(src: Bitmap): Bitmap =
        if (src.config == Bitmap.Config.ARGB_8888 && src.isMutable) src
        else src.copy(Bitmap.Config.ARGB_8888, true) ?: src

    /** (low, high) percentile pair; a near-flat histogram keeps the original range. */
    private fun stretchRange(hist: IntArray, total: Int): Pair<Int, Int> {
        val lowCount = max(1, total / 200)
        val highCount = max(1, total - lowCount)
        var cum = 0
        var lo = 0
        for (v in 0..255) {
            cum += hist[v]
            if (cum >= lowCount) { lo = v; break }
        }
        cum = 0
        var hi = 255
        for (v in 0..255) {
            cum += hist[v]
            if (cum >= highCount) { hi = v; break }
        }
        return if (hi - lo < 12) 0 to 255 else lo to hi
    }

    private fun buildLut(lo: Int, hi: Int): IntArray {
        val lut = IntArray(256)
        val range = (hi - lo).coerceAtLeast(1)
        for (v in 0..255) {
            lut[v] = ((v - lo) * 255 / range).coerceIn(0, 255)
        }
        return lut
    }

    private fun flatten(src: Bitmap): Bitmap {
        if (!src.hasAlpha()) return src
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(src, 0f, 0f, null)
        return out
    }

    private fun derive(from: Int, fromOrig: Int, toOrig: Int): Int =
        if (fromOrig > 0) (from.toDouble() * toOrig / fromOrig).roundToInt() else from
}
