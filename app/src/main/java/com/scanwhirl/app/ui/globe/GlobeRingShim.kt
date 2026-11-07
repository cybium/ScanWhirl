package com.scanwhirl.app.ui.globe

import com.scanwhirl.app.util.CategoryRules
import com.scanwhirl.app.util.CategoryStyle

/**
 * test26 T1.3 — temporary shim for the globe.
 *
 * T1.3 deleted `CategoryStyle.shortTag/tagForRing/colorForRing/ringIndex`
 * (ring-only, and the app now has exactly one naming source,
 * `CategoryRules.label()`). The globe's two index-based lookups cannot be
 * expressed through it, so they live here until Phase 7 deletes the globe
 * together with this file.
 *
 * Ring index order is [CategoryRules.ALL]; an out-of-range index (focus ring
 * unset = −1) falls back to Other, as the old `else` branches did.
 */
internal object GlobeRingShim {

    fun colorForRing(ring: Int): Int =
        CategoryStyle.color(ringOf(ring))

    fun tagForRing(ring: Int): String =
        CategoryRules.label(ringOf(ring)).uppercase()

    fun shortTag(category: String): String =
        CategoryRules.label(category).uppercase()

    private fun ringOf(ring: Int): String =
        CategoryRules.ALL.getOrElse(ring) { CategoryRules.OTHER }
}
