package com.scanwhirl.app.util

import android.graphics.Bitmap

/**
 * FORM1: optional, predictable signature cleanup.
 *
 * Paper signatures are black ink on white paper. A single luminance threshold maps dark ink to
 * pure black and pale paper to pure white; there is no hidden model, no network and no randomness.
 * The caller owns the source bitmap (the original photo is never changed) and must recycle the
 * returned bitmap.
 */
object SignatureCleanup {

    const val DEFAULT_THRESHOLD = 165

    fun clean(src: Bitmap, threshold: Int = DEFAULT_THRESHOLD): Bitmap {
        require(threshold in 1..256) { "Threshold must be between 1 and 256" }
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val gray = (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 151 + (c and 0xFF) * 28) shr 8
            val v = if (gray < threshold) 0 else 255
            pixels[i] = 0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
        }
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }
}
