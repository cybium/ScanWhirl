package com.scanwhirl.app.ui.tools

import androidx.appcompat.app.AppCompatActivity
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.kit.SwSheet
import com.scanwhirl.app.util.PdfImportBudget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * v2 UI: 03_tools_hub — the one place every device-PDF path asks the same huge-import question:
 * count + estimate + free space, Import all / Choose pages / Cancel, and the same progress sheet
 * with Cancel. Import, Vault, Merge, Compress and Protect all call this instead of their own copy.
 */
object DevicePdfGate {

    /**
     * Shows the refusal or the decision sheet. Must be called on the main thread; returns
     * Cancel when the user closes it. A small PDF returns All without showing anything.
     */
    suspend fun decide(
        activity: AppCompatActivity,
        plan: PdfImportBudget.Plan
    ): ScanRepository.ImportDecision = withContext(Dispatchers.Main) {
        if (plan.tooLittleSpace) {
            PdfImportSheets.refuse(activity, plan).show()
            ScanRepository.ImportDecision.Cancel
        } else if (!plan.needsDecision) {
            ScanRepository.ImportDecision.All
        } else {
            suspendCancellableCoroutine<ScanRepository.ImportDecision> { cont ->
                PdfImportSheets.ask(activity, plan) { cont.resume(it) }.show()
            }
        }
    }

    /** The shared "Importing page k of N" sheet. */
    fun progress(activity: AppCompatActivity, onCancel: () -> Unit): SwSheet =
        PdfImportSheets.progress(activity, onCancel)

    /** Page text for the progress sheet. */
    fun progressText(activity: AppCompatActivity, done: Int, total: Int): String =
        PdfImportSheets.progressText(activity, done, total)

    /** Shared free-space copy, for a caller that renders through its own path. */
    fun refuse(activity: AppCompatActivity, plan: PdfImportBudget.Plan): SwSheet =
        PdfImportSheets.refuse(activity, plan)

    /** The missing-pages note for a range that selects nothing. */
    fun emptyChoice(activity: AppCompatActivity): String =
        activity.getString(R.string.tools_import_range_error)
}
