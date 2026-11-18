package com.scanwhirl.app.util

/**
 * Automatic crop for a photo picked from the gallery: the card/page is cut out of the photo and
 * straightened before anyone sees it, so a table or desk around a card never reaches the paper.
 *
 * This is the app's existing page normalisation, [Deskew], not a second detector: the strongest
 * paper-like quad is found on a small copy and the full image is warped so that quad becomes the
 * page (geometry only, no colour changes). Photos import's own "Auto-crop pages" runs Google's
 * ML Kit scanner instead, which returns cropped pages and has no corners to hand on.
 *
 * A photo without a confident quad is returned untouched ([Result.cropped] is false): a guess is
 * worse than the manual crop screen, which the caller still offers.
 */
object PhotoAutoCrop {

    /** [bytes] are the (cropped) photo; [cropped] is false when they are the input, unchanged. */
    class Result(val bytes: ByteArray, val cropped: Boolean)

    /**
     * A quad with every corner within 5% of a frame border is the frame, not a page: the
     * detector found no page edge and fell back to the strongest gradients at the edges of the
     * photo. Cropping it would trim a pointless ~3% border (and hide the honest "no page edge
     * found" state), so the photo is returned untouched instead.
     */
    internal fun isFrameFit(pts: FloatArray): Boolean = (0 until 4).all { c ->
        val x = pts[c * 2]
        val y = pts[c * 2 + 1]
        minOf(x, 1f - x, y, 1f - y) < 0.05f
    }

    fun crop(bytes: ByteArray): Result {
        if (bytes.isEmpty()) return Result(bytes, false)
        val quad = runCatching { Deskew.detectQuad(bytes) }.getOrNull() ?: return Result(bytes, false)
        if (isFrameFit(quad.pts)) return Result(bytes, false)
        val warped = runCatching { Deskew.warp(bytes, quad) }.getOrNull() ?: return Result(bytes, false)
        return Result(warped, warped !== bytes)
    }
}
