package com.scanwhirl.app.util

import android.graphics.Bitmap
import com.scanwhirl.app.R

/**
 * Document look filters applied to scan pages before saving.
 *
 * Every filter is a pure function of the **original** page bitmap (never of another filter's
 * output), so switching between them does not stack effects (I9 groundwork).
 *
 * [uiFilters] is what the page viewer/review sheets show: Auto (default look), Colour boost,
 * Grey, Black & white and Original. [CLEAN] stays as the legacy "Clean" id so documents saved by
 * older builds still resolve; it is no longer offered.
 */
enum class ScanFilter(val id: String, val labelRes: Int) {
    ORIGINAL("original", R.string.filter_original),
    ENHANCE("enhance", R.string.filter_enhance),
    COLOUR("colour", R.string.filter_colour),
    GREY("grey", R.string.filter_grey),
    BLACKWHITE("bw", R.string.filter_bw),
    CLEAN("clean", R.string.filter_clean);

    fun applyTo(src: Bitmap): Bitmap = when (this) {
        ORIGINAL -> src
        ENHANCE -> ScanEnhance.auto(src)
        COLOUR -> ScanEnhance.colourBoost(src)
        GREY -> ScanEnhance.grey(src)
        BLACKWHITE -> ScanEnhance.blackWhite(src)
        CLEAN -> ImageTools.cleanBackground(src)
    }

    companion object {
        /** The filters offered in the page viewer/review sheet, in display order. */
        val uiFilters: List<ScanFilter> = listOf(ORIGINAL, ENHANCE, COLOUR, GREY, BLACKWHITE)

        /** The look new scans should use when the user has not chosen one. */
        val defaultFilter: ScanFilter = ENHANCE

        /** Resolves a stored id. Unknown/absent ids fall back to Original for old documents. */
        fun fromId(id: String?): ScanFilter =
            entries.firstOrNull { it.id == id } ?: ORIGINAL
    }
}
