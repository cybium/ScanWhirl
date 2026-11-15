package com.scanwhirl.app.ui.tools

import android.content.Context
import com.scanwhirl.app.util.PaperSize

/**
 * v2 UI: 03_tools_hub — remembers the last ID-card layout and paper size so the choices are
 * answered the same way next time. One small preference file.
 */
object IdCardPrefs {

    enum class Layout { ONE_PAGE, OWN_PAGES }

    private const val PREFS = "scanwhirl_id_card"
    private const val KEY_LAYOUT = "layout"
    private const val KEY_PAPER = "paper"

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

    /**
     * The remembered paper size, or the device-locale default the first time (US, CA, MX, PH,
     * CL, CO, VE → US Letter; every other country → A4).
     */
    fun paper(context: Context): PaperSize {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PAPER, null)
        return if (stored == null) PaperSize.deviceDefault() else PaperSize.fromId(stored)
    }

    fun rememberPaper(context: Context, size: PaperSize) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PAPER, size.id)
            .apply()
    }
}
