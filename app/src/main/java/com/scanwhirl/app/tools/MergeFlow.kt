package com.scanwhirl.app.tools

import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.scanwhirl.app.R
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.MergePdfActivity
import com.scanwhirl.app.ui.SwDialog
import com.scanwhirl.app.ui.showSw
import kotlinx.coroutines.launch

/**
 * test26 T2.3 — Merge tool, moved verbatim out of `MainActivity`
 * (`showSortMenu` deliberately stays behind).
 *
 * @param docs the current document list (allDocs on the Activity).
 * @param onMerged leaves selection mode, refreshes and opens the result —
 *   all three are Home-screen state the flow must not reach into directly.
 */
class MergeFlow(
    private val activity: AppCompatActivity,
    private val repo: ScanRepository,
    private val host: HomeHost,
    private val docs: () -> List<ScanDocument>,
    private val onMerged: (ScanDocument) -> Unit
) {

    /** Merge tool: choose saved scans or device PDFs. */
    fun showMergeChooser() {
        com.scanwhirl.app.ui.kit.SwSheet.builder(activity)
            .setTitle(R.string.merge_chooser_title)
            .setOptions(
                listOf(
                    activity.getString(R.string.merge_source_scans),
                    activity.getString(R.string.merge_source_device)
                )
            ) { which, _ ->
                if (which == 0) showMergePicker()
                else activity.startActivity(MergePdfActivity.intent(activity))
            }
            .setSecondary(android.R.string.cancel)
            .build()
            .show()
    }

    /** Merge tool: tick 2+ scans, then name the merged document. */
    private fun showMergePicker() {
        val all = docs()
        if (all.size < 2) {
            host.toast(R.string.merge_needs_two)
            return
        }
        val checked = BooleanArray(all.size)
        com.scanwhirl.app.ui.kit.SwSheet.builder(activity)
            .setTitle(R.string.merge_title)
            .setOptions(all.map { it.name }, multiChecked = checked) { which, isChecked ->
                checked[which] = isChecked
            }
            .setPrimary(R.string.merge) {
                val ids = all.filterIndexed { i, _ -> checked[i] }.map { it.id }
                if (ids.size < 2) {
                    host.toast(R.string.merge_needs_two)
                } else {
                    promptMergeName(ids)
                }
            }
            .setSecondary(android.R.string.cancel)
            .build()
            .show()
    }

    private fun promptMergeName(ids: List<String>) = promptMerge(ids)

    /** Selection-mode merge: name the merged document. */
    fun promptMerge(ids: List<String>) {
        if (ids.size < 2) {
            host.toast(R.string.merge_needs_two)
            return
        }
        val sheet = com.scanwhirl.app.ui.kit.SwSheet.builder(activity)
            .setTitle(R.string.merge_title)
            .setInput(hint = activity.getString(R.string.merge_name_hint))
            .setSecondary(android.R.string.cancel)
            .build()
        sheet.setPrimary(R.string.save) {
            doMerge(ids, sheet.inputView?.text?.toString())
        }
        sheet.show()
    }

    private fun doMerge(ids: List<String>, name: String?) {
        activity.lifecycleScope.launch {
            try {
                val merged = repo.mergeDocuments(ids, name)
                onMerged(merged)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // H5-3: cancellation (screen closed or recreated) is not a failure.
                throw e
            } catch (e: Exception) {
                host.toast(activity.getString(R.string.merge_failed, e.message ?: ""), Toast.LENGTH_LONG)
            }
        }
    }
}
