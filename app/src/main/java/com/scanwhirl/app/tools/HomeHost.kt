package com.scanwhirl.app.tools

import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.scanwhirl.app.R

/**
 * test26 Phase 2 — what a tool flow (extracted from `MainActivity`) may ask
 * of the Home screen hosting it. `MainActivity` implements this and passes
 * itself as the `host`; the flows never reach into the Activity's views.
 */
interface HomeHost {

    /** Reload the document list (a flow changed something on disk). */
    fun refreshList()

    /** Indeterminate kit progress sheet; the caller dismisses it. */
    fun showProgress(titleRes: Int = R.string.progress_title): com.scanwhirl.app.ui.kit.SwProgress

    fun toast(res: Int, duration: Int = Toast.LENGTH_SHORT)

    fun toast(message: CharSequence, duration: Int = Toast.LENGTH_SHORT)

    /** Background work started (OCR/merge/compress) — host may light a signal dot. */
    fun workStarted() = Unit

    /** Background work ended — host may dim the signal dot. */
    fun workEnded() = Unit

    /** M08: a scan was saved straight from Google's scanner; [exportNote] is the opt-in copy result. */
    fun scanSaved(doc: com.scanwhirl.app.model.ScanDocument, exportNote: String?) = refreshList()
}
