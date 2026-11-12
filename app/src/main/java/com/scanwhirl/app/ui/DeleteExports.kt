package com.scanwhirl.app.ui

import android.content.Context
import android.net.Uri
import android.widget.CheckBox
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.DownloadSaver

/**
 * T4.8 (complaint #18, N11, Q8): deleting a document can also remove the PDF
 * copies it saved into Documents/ScanWhirl.
 *
 * M06 ownership rules: a copy is removed only when this document can prove it is
 * *its* copy.
 *  - Only two sources are considered: what the document recorded itself in
 *    `meta.txt` (`exports=`), and exact legacy name matches
 *    (`<name>.pdf` / `<name> (n).pdf`) that MediaStore proves this package owns.
 *  - A file another document also recorded is kept. Two documents can point at
 *    one file after an identical save, or after an older build rewrote a
 *    same-name copy in place.
 *  - Legacy name matching is skipped when another document's name falls in the
 *    same family. "Invoice (2).pdf" could be that document's copy.
 *  - A recorded fingerprint must still match. A copy edited elsewhere since it was
 *    saved (annotated, replaced) is kept.
 *  - Pre-Android-10 records without a fingerprint are kept. A public file path is
 *    no proof of ownership.
 *  - A copy that refuses deletion (another package's row) is skipped, never a
 *    permission prompt, and is reported in the confirmation message.
 */
object DeleteExports {

    /** A candidate copy and, when recorded, what it held when it was saved. */
    data class Copy(val uri: Uri, val fingerprint: DownloadSaver.Fingerprint?)

    /** Provable copies of [id]: recorded exports + unambiguous exact-name legacy rows. */
    fun copiesFor(
        context: Context,
        repo: ScanRepository,
        id: String,
        docName: String
    ): List<Copy> {
        val others = repo.exportsRecordedByOthers(id)
        val recorded = repo.exportRecordsOf(id)
            .filter { it.uri !in others }
            .filter { it.fingerprint != null || it.uri.scheme != "file" }
            .map { Copy(it.uri, it.fingerprint) }
        val family = DownloadSaver.familyRegex(DownloadSaver.pdfNameFor(docName))
        val ambiguous = repo.listDocuments(includeVaulted = true).any { other ->
            other.id != id && family.matches(DownloadSaver.pdfNameFor(other.name))
        }
        val legacy = if (ambiguous) {
            emptyList()
        } else {
            DownloadSaver.findCopies(context, docName)
                .filter { it !in others }
                .map { Copy(it, null) }
        }
        return (recorded + legacy).distinctBy { it.uri }
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
     * Deletes every provable copy. It reads files to check fingerprints, so call
     * it off the main thread. It never throws and never blocks the document
     * delete. A refused, changed or unverifiable copy counts as "could not be
     * removed". Returns (removed, failed).
     */
    fun removeCopies(context: Context, copies: List<Copy>): Pair<Int, Int> {
        var removed = 0
        var failed = 0
        for (copy in copies) {
            val expected = copy.fingerprint
            if (expected != null) {
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
            }
            if (DownloadSaver.deleteUri(context, copy.uri)) removed++ else failed++
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
