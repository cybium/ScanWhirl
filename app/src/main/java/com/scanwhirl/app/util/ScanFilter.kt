package com.scanwhirl.app.util

import android.graphics.Bitmap
import com.scanwhirl.app.R

/** Document look filters applied to scan pages before saving. */
enum class ScanFilter(val id: String, val labelRes: Int) {
    ORIGINAL("original", R.string.filter_original),
    ENHANCE("enhance", R.string.filter_enhance),
    CLEAN("clean", R.string.filter_clean),
    BLACKWHITE("bw", R.string.filter_bw);

    fun applyTo(src: Bitmap): Bitmap = when (this) {
        ORIGINAL -> src
        ENHANCE -> ImageTools.enhance(src)
        CLEAN -> ImageTools.cleanBackground(src)
        BLACKWHITE -> ImageTools.blackWhite(src)
    }

    companion object {
        fun fromId(id: String?): ScanFilter =
            entries.firstOrNull { it.id == id } ?: ORIGINAL
    }
}
