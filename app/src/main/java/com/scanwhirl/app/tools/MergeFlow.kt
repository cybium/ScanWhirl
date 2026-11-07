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
        SwDialog.sheet(activity)
            .setTitle(R.string.merge_chooser_title)
            .setItems(
                arrayOf(
                    activity.getString(R.string.merge_source_scans),
                    activity.getString(R.string.merge_source_device)
                )
            ) { _, which ->
                if (which == 0) showMergePicker()
                else activity.startActivity(MergePdfActivity.intent(activity))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showSw(isSheet = true)
    }

    /** Merge tool: tick 2+ scans, then name the merged document. */
    private fun showMergePicker() {
        val all = docs()
        if (all.size < 2) {
            host.toast(R.string.merge_needs_two)
            return
        }
        val checked = BooleanArray(all.size)
        val labels = all.map { it.name }.toTypedArray()
        SwDialog.sheet(activity)
            .setTitle(R.string.merge_title)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.merge) { _, _ ->
                val ids = all.filterIndexed { i, _ -> checked[i] }.map { it.id }
                if (ids.size < 2) {
                    host.toast(R.string.merge_needs_two)
                } else {
                    promptMergeName(ids)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showSw(isSheet = true)
    }

    private fun promptMergeName(ids: List<String>) {
        val (builder, input) = SwDialog.input(activity, hint = activity.getString(R.string.merge_name_hint))
        builder
            .setTitle(R.string.merge_title)
            .setPositiveButton(R.string.save) { _, _ ->
                doMerge(ids, input.text?.toString())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showSw(isSheet = false)
    }

    /** Selection-mode merge: name the merged document. */
    fun promptMerge(ids: List<String>) {
        if (ids.size < 2) {
            host.toast(R.string.merge_needs_two)
            return
        }
        val (builder, input) = SwDialog.input(activity, hint = activity.getString(R.string.merge_name_hint))
        builder
            .setTitle(R.string.merge_title)
            .setPositiveButton(R.string.save) { _, _ ->
                doMerge(ids, input.text?.toString())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showSw(isSheet = false)
    }

    private fun doMerge(ids: List<String>, name: String?) {
        activity.lifecycleScope.launch {
            try {
                val merged = repo.mergeDocuments(ids, name)
                onMerged(merged)
            } catch (e: Exception) {
                host.toast("Merge failed: ${e.message}", Toast.LENGTH_LONG)
            }
        }
    }
}
