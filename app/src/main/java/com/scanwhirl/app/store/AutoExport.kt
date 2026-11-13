package com.scanwhirl.app.store

import android.content.Context
import com.scanwhirl.app.R
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.util.DownloadSaver
import com.scanwhirl.app.util.Prefs

/**
 * T4.7 / M06: the opt-in copy into Documents/ScanWhirl that follows a verified save.
 * Shared by Review's Save and the direct save after Google's scanner (M08), so both
 * behave identically. Streams from the saved PDF; the original is only read.
 */
object AutoExport {

    /**
     * Returns the message to show, or null when nothing was exported (setting off, or the
     * export failed — a failed copy never turns a finished save into a failed one).
     * Call off the main thread.
     */
    fun afterSave(context: Context, repo: ScanRepository, doc: ScanDocument): String? {
        if (!Prefs.autoCopyToDocuments(context) || !doc.pdfFile.exists() || doc.pdfFile.length() == 0L) return null
        return try {
            val copy = DownloadSaver.savePdfFile(context, doc.pdfFile, DownloadSaver.pdfNameFor(doc.name))
            // T4.8: remember the copy so delete can remove it; failing to record it is not a failed export.
            try {
                repo.addExport(doc.id, copy)
            } catch (_: Exception) {
            }
            when {
                !copy.created -> context.getString(R.string.export_already_saved, copy.name)
                copy.keptEarlier -> context.getString(R.string.export_saved_new_copy, copy.name)
                else -> context.getString(R.string.pdf_saved_docs, copy.name)
            }
        } catch (_: Exception) {
            null
        }
    }
}
