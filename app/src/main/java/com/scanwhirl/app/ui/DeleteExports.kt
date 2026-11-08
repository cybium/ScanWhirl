package com.scanwhirl.app.ui

import android.content.Context
import android.net.Uri
import android.widget.CheckBox
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.DownloadSaver
import java.io.File

/**
 * T4.8 (complaint #18, N11, Q8): deleting a document can also remove the PDF
 * copies it saved into Documents/ScanWhirl.
 *
 * Only two sources are ever touched: what the document recorded itself in
 * `meta.txt` (`exports=`), and exact legacy name matches
 * (`<name>.pdf` / `<name> (n).pdf`) that MediaStore proves this package owns.
 * Copies that refuse deletion (another package's row) are skipped — never a
 * permission prompt — and reported in the confirmation message.
 */
object DeleteExports {

    /** Recorded exports + exact-name legacy copies, deduplicated. */
    fun copiesFor(
        context: Context,
        repo: ScanRepository,
        id: String,
        docName: String
    ): List<Uri> {
        val recorded = repo.exportsOf(id)
        val legacy = DownloadSaver.findCopies(context, docName)
        return (recorded + legacy).distinct()
    }

    /**
     * The dialog's checkbox row, only added when [n] > 0. Default **checked**
     * (Q8): the expected behaviour is that the copies go with the document.
     */
    fun checkboxRow(context: Context, n: Int): CheckBox {
        val density = context.resources.displayMetrics.density
        return CheckBox(context).apply {
            id = R.id.deleteCopiesBox
            text = context.resources.getQuantityString(R.plurals.delete_copies, n, n)
            isChecked = true
            setPadding(
                (24 * density).toInt(),
                (8 * density).toInt(),
                (24 * density).toInt(),
                (4 * density).toInt()
            )
        }
    }

    /**
     * Deletes every copy. Never throws and never blocks the document delete:
     * a `SecurityException` (row owned by another package / older install)
     * counts as "could not be removed". Returns (removed, failed).
     */
    fun removeCopies(context: Context, copies: List<Uri>): Pair<Int, Int> {
        var removed = 0
        var failed = 0
        for (uri in copies) {
            try {
                if (uri.scheme == "file") {
                    val file = uri.path?.let { File(it) }
                    if (file == null || (file.exists() && !file.delete())) failed++ else removed++
                } else {
                    context.contentResolver.delete(uri, null, null)
                    removed++
                }
            } catch (_: Exception) {
                // SecurityException / RecoverableSecurityException: skip, no prompt.
                failed++
            }
        }
        return removed to failed
    }

    /** "Deleted. 2 copies removed." / "…1 could not be removed (delete it in Files)." */
    fun message(context: Context, removed: Int, failed: Int): CharSequence =
        if (failed == 0) {
            context.resources.getQuantityString(
                R.plurals.deleted_copies_ok, removed, removed
            )
        } else {
            context.resources.getQuantityString(
                R.plurals.deleted_copies_partial, removed, removed, failed
            )
        }
}
