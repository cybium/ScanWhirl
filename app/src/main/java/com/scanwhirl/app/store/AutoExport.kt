package com.scanwhirl.app.store

import android.content.Context
import com.scanwhirl.app.R
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.util.DownloadSaver
import com.scanwhirl.app.util.PdfProtection
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
        // W7b-3 owns digital output authority and export routing. No implicit external
        // copy from a synthetic or newly restored digital authority before that gate.
        if (doc.contentKind == com.scanwhirl.app.model.ContentKind.DIGITAL) return null
        if (!Prefs.autoCopyToDocuments(context) || !doc.pdfFile.exists() || doc.pdfFile.length() == 0L) return null
        // PM-3.1c: never copy a PDF whose lock record says "protected" while the PDF is not.
        if (PdfProtection.state(doc.pdfFile.parentFile!!) == PdfProtection.State.NEEDS_REPAIR) return null
        // H2-4: on Android 7-9 the Documents copy needs the runtime storage grant. This runs right
        // after a scan with no screen of its own to ask on, so it never prompts; it skips the copy
        // and says so, instead of a copy the person switched on silently never appearing.
        if (DownloadSaver.needsLegacyStoragePermission(context)) {
            return context.getString(R.string.f7save_autocopy_no_permission)
        }
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
