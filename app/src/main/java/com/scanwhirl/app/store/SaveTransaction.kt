package com.scanwhirl.app.store

import com.scanwhirl.app.util.Durable
import com.scanwhirl.app.util.Sha256
import java.io.File
import java.io.IOException
import java.security.SecureRandom

/**
 * On-disk vocabulary of the transactional new-scan save (WP-05).
 *
 * Document visibility rule (every reader applies it, see [ScanRepository.loadDocument]):
 *
 *     visible  <=>  meta.txt exists  AND  no `.incomplete` marker
 *
 * A save reserves `documents/<id>` with an exclusive `mkdir`, creates the
 * `.incomplete` marker FIRST, builds pages, PDF, `txn.txt` and finally
 * `meta.txt` inside it, verifies everything, and publishes by unlinking the
 * marker (the single commit point). Nothing else ever makes it visible.
 */
internal object SaveFormat {
    const val MARKER = ".incomplete"
    const val MARKER_TMP = ".incomplete.tmp"
    const val TXN = "txn.txt"
    const val JOURNAL = "save_txn.txt"

    private const val MARKER_HEADER = "scanwhirl-incomplete-v1"
    private const val JOURNAL_HEADER = "scanwhirl-save-journal-v1"
    private const val TXN_HEADER = "scanwhirl-txn-v1"

    private val random = SecureRandom()

    private fun hex(bytes: Int): String {
        val b = ByteArray(bytes)
        random.nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    /** 128-bit unguessable transaction token. */
    fun newToken(): String = hex(16)

    /** 64-bit document id (legacy ids are 8 hex characters and keep working unchanged). */
    fun newDocId(): String = hex(8)

    private val TOKEN_RE = Regex("^[0-9a-f]{32}$")
    private val ID_RE = Regex("^[0-9a-f]{8,32}$")

    fun isToken(s: String?) = s != null && TOKEN_RE.matches(s)
    fun isDocId(s: String?) = s != null && ID_RE.matches(s)

    /** True once the transaction wrote its provenance record or metadata: past the "disposable partial output" stage. */
    fun reachedProvenance(dir: File): Boolean = File(dir, TXN).exists() || File(dir, "meta.txt").exists()

    /** True while a save still owns [dir] (the directory must not be shown or indexed). */
    fun isIncomplete(dir: File): Boolean = File(dir, MARKER).exists()

    // -- marker ---------------------------------------------------------------

    /** Creates the marker via tmp + fsync + rename so its token is complete or absent, never torn. */
    fun createMarker(dir: File, token: String, now: Long) {
        val tmp = File(dir, MARKER_TMP)
        Durable.writeFile(tmp, "$MARKER_HEADER\ntoken=$token\nstarted=$now\n".toByteArray())
        if (!tmp.renameTo(File(dir, MARKER))) throw IOException("Could not mark the new document as incomplete")
    }

    class MarkerInfo(val token: String, val startedAt: Long)

    private fun parseMarker(file: File): MarkerInfo? {
        val lines = runCatching { file.readLines() }.getOrNull() ?: return null
        if (lines.firstOrNull() != MARKER_HEADER) return null
        val token = lines.firstOrNull { it.startsWith("token=") }?.removePrefix("token=")
        if (!isToken(token)) return null
        val started = lines.firstOrNull { it.startsWith("started=") }?.removePrefix("started=")?.toLongOrNull()
        return MarkerInfo(token!!, started ?: file.lastModified())
    }

    /**
     * Positive ownership proof for a hidden directory: either the committed-to-disk
     * marker, or — when the crash hit between `mkdir` and the marker rename — a
     * directory that contains nothing except our own complete `.incomplete.tmp`.
     */
    fun ownerOf(dir: File): MarkerInfo? {
        val marker = File(dir, MARKER)
        if (marker.exists()) return parseMarker(marker)
        val names = dir.list() ?: return null
        if (names.size == 1 && names[0] == MARKER_TMP) return parseMarker(File(dir, MARKER_TMP))
        return null
    }

    // -- journal (lives in the review staging directory) ------------------------

    class Journal(val token: String, var target: String)

    fun loadJournal(reviewDir: File): Journal? {
        val file = File(reviewDir, JOURNAL)
        if (!file.isFile) return null
        val lines = runCatching { file.readLines() }.getOrNull() ?: return null
        if (lines.firstOrNull() != JOURNAL_HEADER) return null
        val token = lines.firstOrNull { it.startsWith("token=") }?.removePrefix("token=")
        val target = lines.firstOrNull { it.startsWith("target=") }?.removePrefix("target=")
        if (!isToken(token) || !isDocId(target)) return null
        return Journal(token!!, target!!)
    }

    /** Durably records the token and chosen target BEFORE any directory is reserved. */
    fun persistJournal(reviewDir: File, journal: Journal) {
        Durable.atomicWriteText(
            File(reviewDir, JOURNAL),
            "$JOURNAL_HEADER\ntoken=${journal.token}\ntarget=${journal.target}\n"
        )
        Durable.syncDir(reviewDir) // best effort
    }

    // -- provenance record (lives inside the document) --------------------------

    class Txn(
        val token: String,
        val pages: Int,
        val pagesSha256: String,
        val pdfSha256: String,
        val pdfBytes: Long,
        /**
         * Identity of the REVIEW this transaction was built from (see [Identity]). Null only in
         * a txn.txt written before this field existed: such a document can never be proven to be
         * the save of the current review, so replay treats it as a conflict instead of a match.
         */
        val sourceSha256: String? = null,
        val filtersSha256: String? = null
    ) {
        fun serialize() = buildString {
            append("$TXN_HEADER\ntoken=$token\npages=$pages\npagesSha256=$pagesSha256\n")
            append("pdfSha256=$pdfSha256\npdfBytes=$pdfBytes\n")
            if (sourceSha256 != null) append("sourceSha256=$sourceSha256\n")
            if (filtersSha256 != null) append("filtersSha256=$filtersSha256\n")
        }

        /** True when [id] is exactly the review this transaction saved. */
        fun matches(id: Identity) = sourceSha256 == id.source && filtersSha256 == id.filters
    }

    fun readTxn(dir: File): Txn? {
        val file = File(dir, TXN)
        if (!file.isFile) return null
        val lines = runCatching { file.readLines() }.getOrNull() ?: return null
        if (lines.firstOrNull() != TXN_HEADER) return null
        fun v(key: String) = lines.firstOrNull { it.startsWith("$key=") }?.removePrefix("$key=")
        val token = v("token")
        val pages = v("pages")?.toIntOrNull()
        val pagesSha = v("pagesSha256")
        val pdfSha = v("pdfSha256")
        val pdfBytes = v("pdfBytes")?.toLongOrNull()
        if (!isToken(token) || pages == null || pages <= 0 || pagesSha == null || pdfSha == null || pdfBytes == null) return null
        return Txn(token!!, pages, pagesSha, pdfSha, pdfBytes, v("sourceSha256"), v("filtersSha256"))
    }

    /**
     * Durable identity of a review's inputs: what a save is a save OF.
     *
     *  - [source]: the ordered SHA-256 of every staged page's bytes (content AND order);
     *  - [filters]: the effective filter of every page, in the same order.
     *
     * Deliberately NOT part of it: title and category. They are not persisted in the review
     * staging (a resumed review re-derives a default title and an auto-suggested category),
     * and they stay freely editable on the saved document. Letting a resumed review overwrite
     * them would clobber a user's choice with a default; letting them veto a replay would
     * duplicate a document over a rename. The committed document's metadata always wins.
     *
     * Derived from source bytes, never from the saved pages or PDF, so legitimate post-save
     * work (OCR text layer, PDF regeneration) cannot change it.
     */
    class Identity(val source: String, val filters: String)

    fun identity(pageSha256: List<String>, filterIds: List<String>): Identity = Identity(
        source = Sha256.of("scanwhirl-source-v1\n${pageSha256.size}\n${pageSha256.joinToString("\n")}".toByteArray()),
        filters = Sha256.of("scanwhirl-filters-v1\n${filterIds.size}\n${filterIds.joinToString("\n")}".toByteArray())
    )

    /** Digest over the ordered per-page digests: identifies page content and order. */
    fun pagesDigest(pages: List<File>): String =
        Sha256.of(pages.joinToString("\n") { Sha256.of(it) }.toByteArray())
}

/**
 * Deterministic fault seam for tests. Production code calls [at] with a step name
 * between transaction states; tests throw from it to simulate ENOSPC, cancellation
 * or — by throwing [SimulatedProcessDeath], which is an Error and therefore skips
 * all cleanup — a process kill at that exact point.
 *
 * Steps: journaled, reserved, marked, page:<i>, pdf, txn, meta, verified,
 * before_publish, after_publish.
 */
internal object SaveFaults {
    @Volatile var hook: (String) -> Unit = {}
    fun at(step: String) = hook(step)
    fun reset() { hook = {} }
}

/** Test-only stand-in for an abrupt process death: not an Exception, so no cleanup handler runs. */
internal class SimulatedProcessDeath(val step: String) : Error("simulated process death at $step")

/** What [ScanRepository.saveScan] does when the review no longer matches an earlier save of it. */
enum class SavedCopyPolicy {
    /** Refuse with [SavedReviewConflictException]: nothing is saved, replaced or discarded. */
    ASK,

    /**
     * The owner explicitly chose a separate new document: a NEW transaction token and target
     * are persisted in the review journal and the earlier document is left untouched.
     */
    SAVE_AS_NEW
}

/**
 * A document from an earlier save of this very review already exists, but it is NOT a faithful
 * save of what the review contains now. It is neither returned as a success nor replaced.
 */
class SavedReviewConflictException(
    val documentId: String,
    val reason: Reason
) : java.io.IOException(
    when (reason) {
        Reason.REVIEW_CHANGED ->
            "An earlier save of this scan exists, but its pages, order or filters have changed since. " +
                "Your changes are not in that document."
        Reason.SAVED_COPY_CHANGED ->
            "An earlier save of this scan exists, but that document was changed or can no longer be verified."
    }
) {
    enum class Reason { REVIEW_CHANGED, SAVED_COPY_CHANGED }
}
