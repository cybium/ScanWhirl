package com.scanwhirl.app.ui

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.DownloadSaver
import java.io.File

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
 * T44 (phone build 5): the sheet used to promise "1 saved PDF copy" for any proven
 * copy (a PowerPoint export included) and only counted unproven copies. That is not
 * enough to act on: it now names the file(s) it will remove and names the recorded
 * copies it must leave, so the checkbox can never stand for a different file.
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
     * T44: copies this document recorded in Documents/ScanWhirl that the app must not
     * remove — unsigned (older or adopted) records and files another document also
     * records. The sheet names them instead of pretending they are gone.
     */
    fun unprovenFor(repo: ScanRepository, id: String): List<Uri> =
        unproven(repo.exportRecordsOf(id).map { it.uri }, repo.provenExportsOf(id).map { it.uri }, repo.exportsRecordedByOthers(id))

    /** T44: the same for a document in Trash. */
    fun unprovenFor(repo: ScanRepository, item: ScanRepository.TrashedDocument): List<Uri> =
        unproven(
            repo.exportRecordsOfTrashed(item).map { it.uri },
            repo.provenExportsOfTrashed(item).map { it.uri },
            repo.exportsRecordedByOthersThan(item)
        )

    private fun unproven(recorded: List<Uri>, proven: List<Uri>, others: Set<Uri>): List<Uri> =
        recorded.distinct().filter { it !in proven && it !in others }

    /**
     * The permanent-delete dialog body: the optional checkbox (only when [copies] is
     * not empty, default checked as accepted in Q8) naming every file it will remove,
     * and a note naming the recorded copies that stay (with the reason).
     */
    fun dialogBody(context: Context, copies: List<Copy>, unproven: List<Uri> = emptyList()): View {
        val density = context.resources.displayMetrics.density
        val pad = (24 * density).toInt()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (4 * density).toInt(), pad, 0)
            if (copies.isNotEmpty()) addView(checkboxRow(context, copies))
            addView(TextView(context).apply {
                id = R.id.deleteCopiesNote
                setText(unprovenNote(context, unproven))
                setTextColor(ContextCompat.getColor(context, R.color.rv_ink_2))
                textSize = 13f
                setPadding(0, (8 * density).toInt(), 0, 0)
            })
        }
    }

    /**
     * T44: the note under the box. When the app can read the names of the recorded copies
     * it cannot prove, it lists them (a count alone leaves the user hunting in Files);
     * otherwise it keeps the plain "copies ScanWhirl cannot prove stay" sentence.
     */
    private fun unprovenNote(context: Context, unproven: List<Uri>): CharSequence {
        val present = unproven.filter { DownloadSaver.exists(context, it) }
        val names = present.mapNotNull { displayName(context, it) }
        return if (present.isNotEmpty() && names.size == present.size) {
            context.resources.getQuantityString(
                R.plurals.delete_copies_unproven_named,
                present.size,
                joined(context, names)
            )
        } else {
            context.getString(R.string.delete_copies_unproven_note)
        }
    }

    /**
     * The checkbox row. Default **checked** (Q8): copies go with the document.
     * T44: it names the file(s) it removes, because a proven copy is not always a PDF
     * (a Word/PowerPoint export is recorded the same way) and a count alone can stand
     * for a different file than the one the user has in mind.
     */
    fun checkboxRow(context: Context, copies: List<Copy>): CheckBox =
        CheckBox(context).apply {
            id = R.id.deleteCopiesBox
            val names = copies.mapNotNull { displayName(context, it.uri) }
            text = if (names.size == copies.size && names.isNotEmpty()) {
                context.getString(R.string.delete_copies_named, joined(context, names))
            } else {
                context.resources.getQuantityString(R.plurals.delete_copies, copies.size, copies.size)
            }
            isChecked = true
        }

    /** "A.pdf" / "A.pdf, B.pdf" / "A.pdf, B.pdf, C.pdf and 2 more". */
    private fun joined(context: Context, names: List<String>): String {
        val shown = names.take(3)
        return if (names.size <= 3) {
            shown.joinToString(", ")
        } else {
            context.getString(R.string.delete_copies_names_more, shown.joinToString(", "), names.size - 3)
        }
    }

    /**
     * The file's display name, or null when the provider cannot answer. Reading only:
     * a name is shown, never used as proof of ownership.
     */
    private fun displayName(context: Context, uri: Uri): String? = try {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            uri.path?.let { File(it).name }?.takeIf { it.isNotBlank() }
        } else {
            context.contentResolver
                .query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
                }
                ?.takeIf { it.isNotBlank() }
        }
    } catch (_: Exception) {
        null
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
