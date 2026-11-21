package com.scanwhirl.app.ui

import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.annotation.StringRes
import androidx.lifecycle.lifecycleScope
import com.scanwhirl.app.R
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.kit.SwSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * W1: the ONE place an output action (Share, Save PDF, Compress, Sign) is bound to a document.
 *
 * The document screen's panel, the Actions sheet and a Library row can all start the same four
 * outputs. Each of them passes the id it is showing, never "the current/latest document", and this
 * dispatcher re-reads that id at the moment of the tap:
 *
 *  1. a document that left the library in between is refused with [R.string.w1_doc_gone];
 *  2. a document whose saved file and pages are both gone is refused with
 *     [R.string.w1_saved_missing] (a raced/missing file never opens a chooser or writes a copy);
 *  3. while an unsaved page-edit draft exists (or its manifest cannot be read, which is not
 *     "no draft"), the user is asked explicitly: [R.string.w1_draft_finish_first],
 *     [R.string.w1_use_saved_version] or [R.string.sheet_cancel]. Only the second answer runs the
 *     output, and it runs it on the SAVED document handle, so the bytes are the saved version's.
 *     Cancel commits nothing; "Finish editing first" commits nothing and hands the host back.
 *
 * The read-only checks run off the main thread; the host's [run] always runs on the main thread.
 *
 * F11 (H3-4): [confirmSavedPages] reuses the same question for the Tools tab, which has no draft
 * bar of its own and would otherwise run a copy tool on the pre-edit pages.
 */
object DocumentActionDispatcher {

    /** The four document-bound outputs this lane owns (Read text is the viewer's own route). */
    enum class Action(@StringRes val labelRes: Int) {
        SHARE(R.string.share_pdf),
        SAVE_PDF(R.string.doc_action_save_pdf),
        COMPRESS(R.string.tool_compress),
        SIGN(R.string.tool_sign)
    }

    /**
     * Dispatches [action] for [docId].
     *
     * @param onUsed the real handler is about to run: the host records that the user acted on the
     *   document (a fresh import must survive Back without a delete question afterwards).
     * @param onFinishEditing the user chose "Finish editing first": the host opens its own draft
     *   save choices and, once the edits are saved, continues the action on the saved result.
     *   Nothing is shared, saved or committed here.
     * @param run the host's existing guarded handler, called with the re-read saved document.
     */
    fun dispatch(
        activity: AppCompatActivity,
        repo: ScanRepository,
        docId: String,
        action: Action,
        onUsed: (ScanDocument) -> Unit = {},
        onFinishEditing: (ScanDocument) -> Unit = {},
        run: (ScanDocument) -> Unit
    ) {
        activity.lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { repo.getDocument(docId) }.getOrNull()
            }
            if (loaded == null) {
                missing(activity, null)
                return@launch
            }
            if (!hasSavedContent(loaded)) {
                missing(activity, loaded)
                return@launch
            }
            // A damaged or unreadable draft is NOT "no draft": it still gets the explicit question.
            val draft = withContext(Dispatchers.IO) { runCatching { repo.draftOf(docId) } }
            val hasDraft = draft.isFailure || (draft.getOrNull()?.changes ?: 0) > 0
            if (hasDraft) {
                // DV: Sign names the consequence explicitly. "Use saved version" runs on the
                // saved bytes, so the signed copy can never carry the unsaved page edits.
                // The choice and the behaviour are unchanged; only the words are honest.
                val message = if (action == Action.SIGN) {
                    activity.getString(R.string.w1_draft_sign_msg, loaded.name)
                } else {
                    activity.getString(R.string.w1_draft_action_msg, loaded.name)
                }
                ask(activity, loaded, onFinishEditing, message) {
                    onUsed(loaded)
                    guarded(activity, action, run, loaded)
                }
                return@launch
            }
            onUsed(loaded)
            guarded(activity, action, run, loaded)
        }
    }

    /**
     * F11 (H3-4): the Tools tab runs its copy tools on the document's **saved** pages, and its
     * own picker shows no draft bar. So a document with unsaved page edits gets the same three
     * answers the document screen gives, before any tool runs: [R.string.w1_draft_finish_first],
     * [R.string.w1_use_saved_version] or [R.string.sheet_cancel]. [onFinishEditing] hands the
     * person back to the page edits; [run] only runs when they explicitly choose the saved
     * version, so a pre-edit crop/rotation/page delete is never exported silently.
     */
    fun confirmSavedPages(
        activity: AppCompatActivity,
        repo: ScanRepository,
        docIds: Collection<String>,
        onFinishEditing: (ScanDocument) -> Unit = {},
        run: () -> Unit
    ) {
        activity.lifecycleScope.launch {
            val draftDoc = openDraftIn(repo, docIds)
            if (draftDoc == null) {
                run()
                return@launch
            }
            ask(
                activity, draftDoc, onFinishEditing,
                activity.getString(R.string.w1_draft_action_msg, draftDoc.name)
            ) { run() }
        }
    }

    /**
     * The first of [docIds] that has an unsaved (or unreadable, which is not "no draft") page
     * draft, or null. Split out so the decision itself is testable without a screen.
     */
    internal suspend fun openDraftIn(
        repo: ScanRepository,
        docIds: Collection<String>
    ): ScanDocument? = withContext(Dispatchers.IO) {
        docIds.distinct().firstNotNullOfOrNull { id ->
            val draft = runCatching { repo.draftOf(id) }
            val open = draft.isFailure || (draft.getOrNull()?.changes ?: 0) > 0
            if (!open) null else runCatching { repo.getDocument(id) }.getOrNull()
        }
    }

    /**
     * The document still has something on this phone to export. A PDF, or at least its pages.
     * An empty PDF file counts as missing: the readers would hand over 0 bytes.
     */
    private fun hasSavedContent(doc: ScanDocument): Boolean =
        (doc.pdfFile.isFile && doc.pdfFile.length() > 0L) || doc.pageFiles.any { it.isFile }

    private fun missing(activity: AppCompatActivity, doc: ScanDocument?) {
        val res = if (doc == null) R.string.w1_doc_gone else R.string.w1_saved_missing
        Toast.makeText(activity, res, Toast.LENGTH_LONG).show()
    }

    /** The three named answers. Single-choice rows: the pick closes the sheet, then runs. */
    private fun ask(
        activity: AppCompatActivity,
        doc: ScanDocument,
        onFinishEditing: (ScanDocument) -> Unit,
        message: String,
        runSaved: () -> Unit
    ) {
        val answers = listOf(
            activity.getString(R.string.w1_draft_finish_first),
            activity.getString(R.string.w1_use_saved_version),
            activity.getString(R.string.sheet_cancel)
        )
        SwSheet.builder(activity)
            .setTitle(R.string.w1_draft_action_title)
            .setMessage(message)
            .setOptions(answers) { index, _ ->
                when (index) {
                    0 -> onFinishEditing(doc)
                    1 -> runSaved()
                    // 2 (Cancel) and any outside tap/Back commit nothing.
                }
            }
            .build()
            .show()
    }

    /**
     * The host handlers are the app's existing ones; this only turns a raced file (deleted between
     * the check above and the copy) into a spoken failure instead of a crash.
     */
    private fun guarded(
        activity: AppCompatActivity,
        action: Action,
        run: (ScanDocument) -> Unit,
        doc: ScanDocument
    ) {
        try {
            run(doc)
        } catch (_: Exception) {
            Toast.makeText(
                activity,
                activity.getString(R.string.w1_action_failed, activity.getString(action.labelRes)),
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
