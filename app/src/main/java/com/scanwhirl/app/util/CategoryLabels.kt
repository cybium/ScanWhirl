package com.scanwhirl.app.util

import android.content.Context
import com.scanwhirl.app.R

/**
 * Display name of a stored category value.
 *
 * The stored keys in [CategoryRules] (`"Study"`, `"Unsorted"`, …) are written to
 * `meta.txt` line 3 and must stay English for compatibility, so they can never be
 * translated in place. Every screen instead asks this object for the label, which
 * maps the stored key to the translated string resource — one mapping, used by the
 * Home chip rail, the category picker, the document card and the viewer header.
 */
object CategoryLabels {

    /** Translated label for [category]; an unknown value is shown exactly as stored. */
    fun display(context: Context, category: String?): String {
        val res = when (category) {
            CategoryRules.STUDY -> R.string.cat_label_study
            CategoryRules.RECEIPTS -> R.string.cat_label_receipts
            CategoryRules.IDS -> R.string.cat_label_ids
            CategoryRules.FORMS -> R.string.cat_label_forms
            CategoryRules.OTHER -> R.string.cat_label_other
            CategoryRules.UNSORTED -> R.string.cat_label_unsorted
            else -> return category.orEmpty()
        }
        return context.getString(res)
    }
}
