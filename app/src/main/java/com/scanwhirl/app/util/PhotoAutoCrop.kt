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

    fun crop(bytes: ByteArray): Result {
        if (bytes.isEmpty()) return Result(bytes, false)
        val quad = runCatching { Deskew.detectQuad(bytes) }.getOrNull() ?: return Result(bytes, false)
        val warped = runCatching { Deskew.warp(bytes, quad) }.getOrNull() ?: return Result(bytes, false)
        return Result(warped, warped !== bytes)
    }
}
