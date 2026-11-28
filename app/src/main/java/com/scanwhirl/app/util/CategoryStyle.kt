package com.scanwhirl.app.util

import android.content.Context
import androidx.core.content.ContextCompat
import com.scanwhirl.app.R

/**
 * Visual styling tokens for document categories.
 *
 * W5a: UI callers use [color] with a Context, which reads the theme-aware `cat_*`
 * token aliases (AA contrast in light and dark). The COLOR_* constants below are
 * the frozen dark identity hues kept for pure-JVM callers and legacy tests; they
 * must not be used to paint a UI surface.
 *
 * Display names live in [CategoryLabels.display] only; this object never
 * invents a second naming system. The ring-index helpers that used to live
 * here were deleted in test26 T1.3 (the globe that needed them dies in P7 —
 * its two index-based lookups survive in `ui/globe/GlobeRingShim.kt`).
 */
object CategoryStyle {

    const val COLOR_STUDY: Int = 0xFF38EF7D.toInt()
    const val COLOR_RECEIPTS: Int = 0xFFFF8A4C.toInt()
    const val COLOR_IDS: Int = 0xFF5B9DFF.toInt()
    const val COLOR_FORMS: Int = 0xFFB07CFF.toInt()
    const val COLOR_OTHER: Int = 0xFF94A3B8.toInt()

    /** Same grey as Other — Unsorted is told apart by its dashed outline (T8.4). */
    const val COLOR_UNSORTED: Int = COLOR_OTHER

    fun color(category: String): Int = when (category) {
        CategoryRules.STUDY -> COLOR_STUDY
        CategoryRules.RECEIPTS -> COLOR_RECEIPTS
        CategoryRules.IDS -> COLOR_IDS
        CategoryRules.FORMS -> COLOR_FORMS
        CategoryRules.UNSORTED -> COLOR_UNSORTED
        else -> COLOR_OTHER
    }

    /**
     * W5a: the colour actually painted by UI callers. Reads the theme-aware `cat_*`
     * token aliases (AA >= 4.5 in either theme). The constants above stay as the
     * frozen dark identities for pure-JVM callers and legacy tests.
     */
    fun color(category: String, context: Context): Int {
        val res = when (category) {
            CategoryRules.STUDY -> R.color.cat_study
            CategoryRules.RECEIPTS -> R.color.cat_receipts
            CategoryRules.IDS -> R.color.cat_ids
            CategoryRules.FORMS -> R.color.cat_forms
            else -> R.color.cat_other
        }
        return ContextCompat.getColor(context, res)
    }
}
