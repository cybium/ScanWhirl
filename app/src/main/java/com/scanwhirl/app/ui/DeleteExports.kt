package com.scanwhirl.app.ui

import android.content.Context
import android.net.Uri
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.DownloadSaver

/**
 * T4.8 / M06 / M07: permanently deleting a document can also remove the PDF copies
 * it saved into Documents/ScanWhirl, but only copies it can **prove** it made.
 *
 * M07 ownership rule (replaces M06's title fallback, which could hand a deleted
 * document's retained `Invoice.pdf` to a later, never-exported "Invoice"):
 *  - The only candidates are signed `exportmade=` records of this exact document
 *    ([ScanRepository.provenExportsOf]). A file name, a title family and
 *    `owner_package_name` are never proof.
 *  - A file any other document (live, locked, vaulted or in Trash) also recorded
 *    is kept.
 *  - The file's current bytes must still match the fingerprint recorded when this
 *    document created it. A copy edited elsewhere since is kept.
 *  - Older copies (pre-M07 records, adopted "already saved" files, Android 9-era
 *    paths without a signature) are never counted or removed. The dialog says so.
 *  - A copy that refuses deletion (another package's row) is skipped, never a
 *    permission prompt, and reported in the result message.
 *
 * Moving a document to Trash never touches exports; only permanent deletion does.
 */
object DeleteExports {

    /** A candidate copy and what it held when this document created it. */
    data class Copy(val uri: Uri, val fingerprint: DownloadSaver.Fingerprint?)

    /** Provable copies of live document [id]. */
    fun copiesFor(repo: ScanRepository, id: String): List<Copy> =
        filtered(repo.provenExportsOf(id), repo.exportsRecordedByOthers(id))

    /** Provable copies of a document in Trash. */
    fun copiesFor(repo: ScanRepository, item: ScanRepository.TrashedDocument): List<Copy> =
        filtered(repo.provenExportsOfTrashed(item), repo.exportsRecordedByOthersThan(item))

    private fun filtered(proven: List<ScanRepository.ProvenExport>, others: Set<Uri>): List<Copy> =
        proven.filter { it.uri !in others }.map { Copy(it.uri, it.fingerprint) }.distinctBy { it.uri }

    /**
     * The permanent-delete dialog body: the optional checkbox (only when [n] > 0,
     * default checked as accepted in Q8) and a note that copies ScanWhirl cannot
     * prove are its own stay in Documents/ScanWhirl.
     */
    fun dialogBody(context: Context, n: Int): View {
        val density = context.resources.displayMetrics.density
        val pad = (24 * density).toInt()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (4 * density).toInt(), pad, 0)
            if (n > 0) addView(checkboxRow(context, n))
            addView(TextView(context).apply {
                id = R.id.deleteCopiesNote
                setText(R.string.delete_copies_unproven_note)
                setTextColor(ContextCompat.getColor(context, R.color.rv_ink_2))
                textSize = 13f
                setPadding(0, (8 * density).toInt(), 0, 0)
            })
        }
    }

    /** The checkbox row. Default **checked** (Q8): copies go with the document. */
    fun checkboxRow(context: Context, n: Int): CheckBox =
        CheckBox(context).apply {
            id = R.id.deleteCopiesBox
            text = context.resources.getQuantityString(R.plurals.delete_copies, n, n)
            isChecked = true
        }

    /**
     * Deletes every provable copy. It reads files to check fingerprints, so call
     * it off the main thread. It never throws and never blocks the document
     * delete. A refused, changed, unfingerprinted or unverifiable copy counts as
     * "could not be removed" and stays. Returns (removed, kept).
     */
    fun removeCopies(context: Context, copies: List<Copy>): Pair<Int, Int> {
        var removed = 0
        var failed = 0
        for (copy in copies) {
            val expected = copy.fingerprint
            if (expected == null) {
                failed++ // no proof of what it held: never delete
                continue
            }
            val current = try {
                DownloadSaver.fingerprintOf(context, copy.uri)
            } catch (_: Exception) {
                null
            }
            if (current == null) {
                // Gone already is the desired end state; present but unreadable is unproven.
                if (DownloadSaver.exists(context, copy.uri)) failed++ else removed++
                continue
            }
            if (current != expected) {
                failed++ // changed since it was saved: not provably ours any more
                continue
            }
            if (DownloadSaver.deleteUri(context, copy.uri)) removed++ else failed++
        }
        return removed to failed
    }

    /** "Deleted. 2 copies removed." / "…1 kept (check Files)." */
    fun message(context: Context, removed: Int, failed: Int): CharSequence =
        if (failed == 0) {
            context.resources.getQuantityString(R.plurals.deleted_copies_ok, removed, removed)
        } else {
            context.resources.getQuantityString(R.plurals.deleted_copies_partial, removed, removed, failed)
        }
}
