package com.scanwhirl.app.ui

import android.content.Context
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.kit.SwSheet

/**
 * DQA D1 / VQA import doors: the one place that states what a PDF import does to pages.
 *
 * Every door into a device PDF (Library Import, Scan FAB → Import PDF, and the import plan
 * sheet) uses this helper. When the digital-PDF path lands it can pass `rasterises = false`
 * here and the notice disappears from that door only — no per-screen copy edits.
 *
 * The notice is a fact about the current import, not a promise to edit the result.
 */
object ImportRasterNotice {

    fun text(context: Context): String = context.getString(R.string.import_raster_notice)

    /**
     * Show the notice before the picker opens. The caller's action runs only after the person
     * continues; cancelling leaves the library untouched.
     */
    fun show(context: Context, rasterises: Boolean = true, onContinue: () -> Unit): SwSheet? {
        if (!rasterises) {
            onContinue()
            return null
        }
        val sheet = SwSheet.builder(context)
            .setTitle(R.string.import_raster_notice_title)
            .setMessage(text(context))
            .setPrimary(R.string.import_raster_notice_continue) { onContinue() }
            .setSecondary(R.string.sheet_cancel)
            .build()
        return sheet.show()
    }

    /** For surfaces that already have their own sheet: the same sentence, one source. */
    fun appendTo(context: Context, message: CharSequence): String =
        "$message\n\n${text(context)}"
}
