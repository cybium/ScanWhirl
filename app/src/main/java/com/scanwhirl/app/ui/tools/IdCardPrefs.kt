package com.scanwhirl.app.ui.tools

import android.content.Context

/**
 * v2 UI: 03_tools_hub — remembers the last ID-card layout so the question is answered the same
 * way next time (one A4 page with both sides, or one page per side). One small preference file.
 */
object IdCardPrefs {

    enum class Layout { ONE_PAGE, OWN_PAGES }

    private const val PREFS = "scanwhirl_id_card"
    private const val KEY_LAYOUT = "layout"

    fun layout(context: Context): Layout {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAYOUT, null)
        return when (value) {
            "own_pages" -> Layout.OWN_PAGES
            else -> Layout.ONE_PAGE
        }
    }

    fun remember(context: Context, layout: Layout) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAYOUT, if (layout == Layout.OWN_PAGES) "own_pages" else "one_page")
            .apply()
    }
}
