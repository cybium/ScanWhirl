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

    /** Indeterminate progress dialog; the caller dismisses it. */
    fun showProgress(titleRes: Int = R.string.progress_title): AlertDialog

    fun toast(res: Int, duration: Int = Toast.LENGTH_SHORT)

    fun toast(message: CharSequence, duration: Int = Toast.LENGTH_SHORT)
}
