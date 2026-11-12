package com.scanwhirl.app.store

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.scanwhirl.app.model.OcrPage
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.OcrStore
import com.scanwhirl.app.util.CategoryRules
import com.scanwhirl.app.util.DownloadSaver
import com.scanwhirl.app.util.Durable
import com.scanwhirl.app.util.ImageTools
import com.scanwhirl.app.util.OcrEngine
import com.scanwhirl.app.util.PdfEncryptor
import com.scanwhirl.app.util.PdfGenerator
import com.scanwhirl.app.util.PdfPageRenderer
import com.scanwhirl.app.util.PdfVerifier
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.ScanFilter
import com.scanwhirl.app.util.Sha256
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class ScanRepository(private val context: Context) {

    internal val docsDir: File
        get() = File(context.filesDir, "documents").apply { mkdirs() }

    internal val ocrStore: OcrStore
        get() = OcrStore(docsDir)

    companion object {
        /** Process-wide lock for PDF regeneration — several ScanRepository instances exist at once. */
        val PDF_LOCK = Any()

        /**
         * Serialises new-scan save transactions and the abandoned-save reaper, so a
         * cleanup can never race an active transaction (several ScanRepository
         * instances exist at once, hence process-wide).
         */
        private val SAVE_LOCK = Mutex()

        /** Serialises metadata read-modify-write cycles (no lost updates between instances). */
        private val META_LOCK = Any()

        /** A hidden save directory untouched for this long is considered abandoned. */
        const val ABANDONED_SAVE_AGE_MS = 24L * 60 * 60 * 1000

        private const val RESERVE_ATTEMPTS = 8
        private const val SPACE_MARGIN_BYTES = 1L shl 20

        /**
         * Advisory free-space probe. `usableSpace` can over- or under-estimate (quota, reserved
         * blocks, clearable cache), so it only gates an early, friendly failure — real ENOSPC is
         * still handled by every write and fsync.
         */
        @android.annotation.SuppressLint("UsableSpace")
        private fun probeUsableSpace(dir: File): Long = dir.usableSpace

        /** Free-space probe (advisory only); replaceable in tests. */
        @Volatile internal var usableSpace: (File) -> Long = ::probeUsableSpace

        internal fun resetTestSeams() {
            usableSpace = ::probeUsableSpace
        }
    }

    fun listDocuments(includeVaulted: Boolean = false): List<ScanDocument> {
        return docsDir.listFiles { f -> f.isDirectory }
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { dir -> loadDocument(dir) }
            ?.filter { includeVaulted || !it.isVaulted }
            ?: emptyList()
    }

    /** Documents hidden inside the 4-digit PIN Pro Vault. */
    fun listVaultDocuments(): List<ScanDocument> {
        return docsDir.listFiles { f -> f.isDirectory }
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { dir -> loadDocument(dir) }
            ?.filter { it.isVaulted }
            ?: emptyList()
    }

    /** Finds a document by ID regardless of whether it is in the Vault. */
    fun getDocument(id: String): ScanDocument? =
        loadDocument(File(docsDir, id))

    internal fun loadDocument(dir: File): ScanDocument? {
        if (!dir.exists() || !dir.isDirectory) return null
        val meta = File(dir, "meta.txt")
        if (!meta.exists()) return null
        val lines = try { meta.readLines() } catch (_: IOException) { return null }
        if (lines.isEmpty()) return null
        // Visibility rule (WP-05): meta.txt is read FIRST, the marker SECOND. meta.txt is
        // only ever written after the save's marker exists and its content is complete,
        // so "meta present + marker absent" can only mean a published document.
        if (SaveFormat.isIncomplete(dir)) return null
        val name = lines[0].ifBlank { "Untitled scan" }
        val createdAt = lines.getOrNull(1)?.toLongOrNull() ?: dir.lastModified()
        val category = lines.getOrNull(2)?.takeIf { CategoryRules.isValid(it) }
            ?: CategoryRules.OTHER
        val pdf = File(dir, "scan.pdf")
        val pages = dir.listFiles { f -> f.extension.equals("jpg", true) || f.extension.equals("jpeg", true) }
            ?.filter { it.length() > 0 }
            ?.sortedBy { it.nameWithoutExtension }
            ?: emptyList()
        if (!pdf.exists() && pages.isEmpty()) return null
        val isLocked = File(dir, LOCK_FILE).exists()
        val isVaulted = File(dir, VAULT_FILE).exists()

        // Revision: max lastModified of meta.txt, scan.pdf, locked.meta, vault.flag, page files.
        val allFileTimes = listOf(meta, pdf, File(dir, LOCK_FILE), File(dir, VAULT_FILE))
            .filter { it.exists() }
            .map { it.lastModified() }
            .plus(pages.map { it.lastModified() })
        val revision = if (allFileTimes.isEmpty()) 0L else allFileTimes.maxOrNull() ?: 0L

        // isSearchable: text_layer.flag exists.
        val isSearchable = File(dir, "text_layer.flag").exists()

        return ScanDocument(
            id = dir.name,
            name = name,
            createdAt = createdAt,
            pageCount = pages.size,
            pdfFile = pdf,
            pageFiles = pages,
            isLocked = isLocked,
            isVaulted = isVaulted,
            category = category,
            revision = revision,
            isSearchable = isSearchable
        )
    }

    /**
     * Writes `name / createdAt / category` (meta.txt). The category is
     * guessed from the name unless one is given, so every document lands on
     * a globe ring without the user having to tag anything.
     */
    private fun writeMeta(dir: File, name: String, category: String? = null) = synchronized(META_LOCK) {
        val cat = category?.takeIf { CategoryRules.isValid(it) }
            ?: CategoryRules.classify(name)
        val meta = File(dir, "meta.txt")
        // Everything after the three fixed lines (T4.8 `exports=` and any unknown
        // lines written by other builds) survives a rewrite.
        val extras = if (meta.exists()) meta.readLines().drop(3).dropLastWhile { it.isBlank() } else emptyList()
        Durable.atomicWriteText(meta, buildString {
            append(name).append('\n')
            append(System.currentTimeMillis()).append('\n')
            append(cat).append('\n')
            extras.forEach { append(it).append('\n') }
        })
    }

    /**
     * Atomic read-modify-write of meta.txt: the whole file is rewritten to a
     * synced `.tmp` and renamed over the original, so a crash leaves either the
     * old or the new content, never a torn file. Line 2 (createdAt), the
     * category and unknown lines pass through untouched unless [change] edits them.
     * Returns false when the document has no meta.txt.
     */
    private fun updateMeta(dir: File, change: (MutableList<String>) -> Unit): Boolean = synchronized(META_LOCK) {
        val meta = File(dir, "meta.txt")
        if (!meta.exists()) return false
        val lines = meta.readLines().toMutableList()
        change(lines)
        Durable.atomicWriteText(meta, lines.joinToString("\n") + "\n")
        true
    }

    private fun padMeta(lines: MutableList<String>) {
        while (lines.size < 3) lines.add(if (lines.size == 1) "" else CategoryRules.OTHER)
    }

    /**
     * T4.8 (complaint #18): records a PDF copy this document saved into
     * Documents/ScanWhirl so deleting the document can remove it too.
     * Stored as an optional 4th meta line `exports=<uri1>|<uri2>` — old
     * builds ignore unknown lines, and setCategory/renameDocument rewrite
     * the whole file line-by-line, so it survives those.
     */
    fun addExport(id: String, uri: Uri, fingerprint: DownloadSaver.Fingerprint? = null) {
        updateMeta(File(docsDir, id)) { lines ->
            padMeta(lines)
            val recorded = listLine(lines, EXPORTS_KEY)
            setListLine(lines, EXPORTS_KEY, (recorded + uri.toString()).distinct())
            // M06: what the copy held when this document saved it, so a later
            // delete can prove the file is still that copy (`exportsigs=` is a
            // separate line; older builds ignore it and keep reading `exports=`).
            if (fingerprint != null) {
                val sigs = listLine(lines, EXPORT_SIGS_KEY)
                    .filterNot { it.substringBefore(' ') == uri.toString() }
                setListLine(lines, EXPORT_SIGS_KEY, sigs + "$uri ${fingerprint.encode()}")
            }
        }
    }

    /** M06: forgets a recorded copy (e.g. after Undo removed it). */
    fun removeExport(id: String, uri: Uri) {
        updateMeta(File(docsDir, id)) { lines ->
            val key = uri.toString()
            if (lines.any { it.startsWith(EXPORTS_KEY) }) {
                setListLine(lines, EXPORTS_KEY, listLine(lines, EXPORTS_KEY).filterNot { it == key })
            }
            if (lines.any { it.startsWith(EXPORT_SIGS_KEY) }) {
                setListLine(
                    lines, EXPORT_SIGS_KEY,
                    listLine(lines, EXPORT_SIGS_KEY).filterNot { it.substringBefore(' ') == key }
                )
            }
        }
    }

    /** T4.8: PDF copies of this document recorded in Documents/ScanWhirl. */
    fun exportsOf(id: String): List<Uri> = exportRecordsOf(id).map { it.uri }

    /** A recorded copy plus, when known, the fingerprint it had when this document saved it. */
    data class ExportRecord(val uri: Uri, val fingerprint: DownloadSaver.Fingerprint?)

    fun exportRecordsOf(id: String): List<ExportRecord> {
        val meta = File(File(docsDir, id), "meta.txt")
        if (!meta.exists()) return emptyList()
        val lines = try { meta.readLines() } catch (_: IOException) { return emptyList() }
        val sigs = listLine(lines, EXPORT_SIGS_KEY).mapNotNull { entry ->
            val space = entry.indexOf(' ')
            if (space <= 0) null
            else entry.substring(0, space) to DownloadSaver.Fingerprint.decode(entry.substring(space + 1))
        }.toMap()
        return listLine(lines, EXPORTS_KEY).mapNotNull { raw ->
            runCatching { Uri.parse(raw) }.getOrNull()?.let { ExportRecord(it, sigs[raw]) }
        }
    }

    /**
     * M06: every copy recorded by a document other than [id], including locked
     * and vaulted ones. Deleting [id] must never remove a file another
     * document also points at (identical saves, or a same-name copy an older
     * build rewrote in place).
     */
    fun exportsRecordedByOthers(id: String): Set<Uri> {
        val dirs = docsDir.listFiles { f -> f.isDirectory && f.name != id } ?: return emptySet()
        return dirs.flatMapTo(HashSet()) { exportsOf(it.name) }
    }

    private fun listLine(lines: List<String>, key: String): List<String> =
        lines.firstOrNull { it.startsWith(key) }
            ?.removePrefix(key)
            ?.split("|")
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    private fun setListLine(lines: MutableList<String>, key: String, values: List<String>) {
        val entry = lines.indexOfFirst { it.startsWith(key) }
        val line = key + values.joinToString("|")
        if (entry >= 0) lines[entry] = line else lines.add(line)
    }

    /** User-chosen category override (editor → "Categorize"). */
    fun setCategory(id: String, category: String): ScanDocument? {
        require(CategoryRules.isValid(category)) { "Unknown category: $category" }
        val dir = File(docsDir, id)
        val changed = updateMeta(dir) { lines ->
            padMeta(lines)
            lines[2] = category
        }
        if (!changed) return null
        return loadDocument(dir)
    }

    /**
     * Bulk "Move to category" (Home selection mode). One pass per document;
     * missing/locked-away ids are skipped rather than failing the batch.
     */
    fun setCategory(ids: List<String>, category: String) {
        ids.forEach { id -> setCategory(id, category) }
    }

    /**
     * Transactional new-scan save (WP-05). A scan becomes a visible document only
     * when ALL pages, a structurally verified PDF and consistent metadata exist.
     *
     * State machine (see [SaveFormat]):
     *
     *   journaled → reserved (exclusive `mkdir`) → marked (`.incomplete`) → pages →
     *   pdf → txn.txt → meta.txt → verified → **published (unlink `.incomplete`)**
     *
     * The unlink is the single commit point. Before it nothing is visible to any
     * reader; after it nothing is ever deleted by this call. Existing directories
     * are never replaced: `mkdir` fails on any existing path, including an empty one.
     *
     * [reviewDir] is the review staging folder. When given, a stable token and the
     * chosen target id are persisted there BEFORE anything is reserved, so a retry
     * after process death reuses the same identity. The staging pages are only
     * read, never modified or deleted — the caller deletes them after this returns.
     *
     * Replay (R1/R2). What sits at the journalled target decides the outcome, and it is
     * judged against the review's [SaveFormat.Identity] (staged page bytes, order, filters):
     *
     *  - published by this token AND identical review → returned as-is (idempotent, no
     *    regeneration; title and category of the saved document are never rewritten);
     *  - published by this token but the review changed, or the saved copy was edited or
     *    no longer verifies → [SavedReviewConflictException]; nothing is saved or touched.
     *    The owner can then choose [SavedCopyPolicy.SAVE_AS_NEW], which journals a brand new
     *    token + target before building and leaves the earlier document alone;
     *  - hidden, owned, fully prepared and verified for this same review → published as is
     *    (journal-linked recovery, no rebuild);
     *  - hidden and owned but complete and verified for a DIFFERENT (superseded) review → kept
     *    untouched; the changed review is saved to a new target persisted in the journal;
     *  - hidden and owned but incomplete or unverifiable → rebuilt in place from the review
     *    staging, which is the authoritative source;
     *  - anything else (foreign token, unprovable marker, unknown) → never touched.
     *
     * [pdfUri] is retained for source compatibility and is ignored: a foreign PDF
     * cannot be tied to the saved pages, so the PDF is always generated from them.
     */
    suspend fun saveScan(
        imageUris: List<Uri>,
        @Suppress("UNUSED_PARAMETER") pdfUri: Uri?,
        suggestedName: String?,
        filter: ScanFilter = ScanFilter.ORIGINAL,
        pageFilters: Map<Int, ScanFilter>? = null,
        category: String? = null,
        reviewDir: File? = null,
        ifSavedCopyDiffers: SavedCopyPolicy = SavedCopyPolicy.ASK
    ): ScanDocument = withContext(Dispatchers.IO) {
        require(imageUris.isNotEmpty()) { "Choose at least one PNG, JPEG or WebP image." }
        SAVE_LOCK.withLock {
            runSaveTransaction(imageUris, suggestedName, filter, pageFilters, category, reviewDir, ifSavedCopyDiffers)
        }
    }

    private suspend fun runSaveTransaction(
        imageUris: List<Uri>,
        suggestedName: String?,
        filter: ScanFilter,
        pageFilters: Map<Int, ScanFilter>?,
        category: String?,
        reviewDir: File?,
        policy: SavedCopyPolicy
    ): ScanDocument {
        val effective: (Int) -> ScanFilter = { i -> pageFilters?.get(i) ?: filter }
        val filterIds = imageUris.indices.map { effective(it).id }

        // 1. Identity: token + target id are durable BEFORE any directory is reserved.
        var journal = reviewDir?.let { SaveFormat.loadJournal(it) }
            ?: SaveFormat.Journal(SaveFormat.newToken(), SaveFormat.newDocId())
                .also { fresh -> reviewDir?.let { SaveFormat.persistJournal(it, fresh) } }
        SaveFaults.at("journaled")
        checkFreeSpace(imageUris)

        // The review's identity is only needed when something already sits at the target;
        // a fresh save derives it from the very bytes it normalizes instead.
        var inputIdentity: SaveFormat.Identity? = null
        val identityNow = { inputIdentity ?: identityOfInputs(imageUris, filterIds).also { inputIdentity = it } }

        // 2. Exclusive reservation. Never replaces, deletes or repurposes a foreign directory.
        var owned: File? = null
        var attempts = 0
        var target = journal.target
        while (owned == null) {
            currentCoroutineContext().ensureActive()
            val candidate = File(docsDir, target)
            if (candidate.mkdir()) {
                owned = candidate
                break
            }
            if (!candidate.exists()) throw IOException("Could not create a new document. Try saving again.")
            when (val state = classifyExisting(candidate, journal.token, identityNow)) {
                is Existing.Committed -> {
                    // Replay of a save that already published, for an unchanged review: same
                    // document, no duplicate, nothing rewritten.
                    val doc = loadDocument(candidate)
                        ?: throw IllegalStateException("Saved document could not be reopened")
                    markPendingAfterPublish(doc)
                    return doc
                }
                is Existing.Prepared -> return publishPrepared(candidate)
                is Existing.OwnedStale -> {
                    // Our own incomplete / unverifiable / superseded attempt (marker carries our
                    // token); the review staging still holds the source, so rebuild it.
                    candidate.deleteRecursively()
                    if (!candidate.exists() && candidate.mkdir()) {
                        owned = candidate
                        break
                    }
                }
                is Existing.Conflict -> {
                    if (policy == SavedCopyPolicy.ASK) throw SavedReviewConflictException(candidate.name, state.reason)
                    // Explicit owner choice: a NEW identity, durable before anything is reserved.
                    // The earlier document keeps its own token and is never touched.
                    journal = SaveFormat.Journal(SaveFormat.newToken(), SaveFormat.newDocId())
                    reviewDir?.let { SaveFormat.persistJournal(it, journal) }
                    target = journal.target
                    continue
                }
                is Existing.Foreign -> Unit // never touched
            }
            if (++attempts >= RESERVE_ATTEMPTS) {
                throw IOException("Could not reserve a new document. Try saving again.")
            }
            // Genuine collision: persist the new target BEFORE retrying.
            target = SaveFormat.newDocId()
            journal.target = target
            reviewDir?.let { SaveFormat.persistJournal(it, journal) }
        }
        SaveFaults.at("reserved")
        val dir: File = checkNotNull(owned)
        val token = journal.token

        // 3. Build everything inside the hidden directory. Any ordinary failure or
        //    cancellation removes only this directory (positively owned via its token).
        try {
            SaveFormat.createMarker(dir, token, System.currentTimeMillis())
            Durable.syncDir(dir)
            Durable.syncDir(docsDir) // best effort: see Durable
            SaveFaults.at("marked")

            val pageFiles = ArrayList<File>(imageUris.size)
            val sourceDigests = ArrayList<String>(imageUris.size)
            imageUris.forEachIndexed { index, uri ->
                currentCoroutineContext().ensureActive()
                val normalized = try {
                    val bytes = context.contentResolver.openInputStream(uri)?.use(ImageTools::readPageBytes)
                        ?: throw IllegalArgumentException("Cannot read this image. Choose it again.")
                    sourceDigests.add(Sha256.of(bytes)) // identity = the exact bytes this save consumed
                    ImageTools.normalizePage(bytes, effective(index))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw IllegalArgumentException("Page ${index + 1}: ${e.message}", e)
                }
                val file = File(dir, String.format(Locale.ROOT, PAGE_FORMAT, index))
                Durable.writeFile(file, normalized)
                if (file.length() != normalized.size.toLong()) {
                    throw IOException("Page ${index + 1} was not written completely")
                }
                pageFiles.add(file)
                SaveFaults.at("page:$index")
            }
            Durable.syncDir(dir)

            currentCoroutineContext().ensureActive()
            val pdfFile = File(dir, "scan.pdf")
            // Throws (never swallowed) when the PDF cannot be written, synced or verified.
            PdfGenerator.imagesToPdf(pageFiles, pdfFile)
            SaveFaults.at("pdf")

            val review = SaveFormat.identity(sourceDigests, filterIds)
            val txn = SaveFormat.Txn(
                token = token,
                pages = pageFiles.size,
                pagesSha256 = SaveFormat.pagesDigest(pageFiles),
                pdfSha256 = Sha256.of(pdfFile),
                pdfBytes = pdfFile.length(),
                sourceSha256 = review.source,
                filtersSha256 = review.filters
            )
            Durable.atomicWriteText(File(dir, SaveFormat.TXN), txn.serialize())
            SaveFaults.at("txn")

            val name = suggestedName?.trim()?.replace('\n', ' ')?.takeIf { it.isNotBlank() }
                ?: nextDocumentName()
            writeMeta(dir, name, category) // atomic + synced; written LAST (content is complete)
            SaveFaults.at("meta")
            Durable.syncDir(dir)

            verifyPrepared(dir, txn)
            SaveFaults.at("verified")
            SaveFaults.at("before_publish")
        } catch (e: Exception) {
            discardOwned(dir, token)
            throw e
        }

        return publishPrepared(dir)
    }

    /**
     * COMMIT: one atomic unlink of the marker of a verified, prepared directory. Nothing here
     * ever deletes the document — if the unlink fails the fully prepared directory is left
     * exactly as it is, so a later replay can publish it instead of rebuilding it.
     */
    private fun publishPrepared(dir: File): ScanDocument {
        if (!File(dir, SaveFormat.MARKER).delete()) {
            throw IOException("Could not publish the new document. Try saving again.")
        }
        Durable.syncDir(dir)
        Durable.syncDir(docsDir) // best effort; durability of the entry itself is not guaranteed
        SaveFaults.at("after_publish")

        val doc = loadDocument(dir) ?: throw IllegalStateException("Saved document could not be reopened")
        markPendingAfterPublish(doc)
        return doc
    }

    private sealed interface Existing {
        /** Published by our token for exactly this review. */
        object Committed : Existing

        /** Hidden, owned, verified, prepared for exactly this review: publish it as is. */
        object Prepared : Existing

        /** Hidden and ours, but incomplete or unverifiable: rebuild in place from the review staging. */
        object OwnedStale : Existing

        /** Published by our token, yet not a faithful save of this review: the owner must choose. */
        class Conflict(val reason: SavedReviewConflictException.Reason) : Existing

        /** Somebody else's (or unprovable): never touched. */
        object Foreign : Existing
    }

    /** What already sits at a path `mkdir` refused: ours (committed / prepared / abandoned) or somebody else's. */
    private fun classifyExisting(dir: File, token: String, identity: () -> SaveFormat.Identity): Existing {
        val owner = SaveFormat.ownerOf(dir)
        if (owner != null) {
            if (owner.token != token) return Existing.Foreign
            val txn = SaveFormat.readTxn(dir) ?: return Existing.OwnedStale // never reached provenance
            if (txn.token != token) return Existing.Foreign // contradictory provenance: not ours to judge
            if (!isVerifiedPrepared(dir, txn)) return Existing.OwnedStale
            // Complete, verified output of this transaction. Same review → publish it as is.
            // A DIFFERENT review → keep it untouched (it is complete data, and after a lost
            // unlink it may even have been a document the owner saw) and save the changed
            // review to a new, journalled target instead.
            return if (txn.matches(identity())) Existing.Prepared else Existing.Foreign
        }
        if (SaveFormat.isIncomplete(dir)) return Existing.Foreign // marker we cannot prove is ours
        val txn = SaveFormat.readTxn(dir) ?: return Existing.Foreign
        if (txn.token != token || !File(dir, "meta.txt").isFile) return Existing.Foreign
        if (!isVerifiedCommit(dir, txn)) return Existing.Conflict(SavedReviewConflictException.Reason.SAVED_COPY_CHANGED)
        return if (txn.matches(identity())) Existing.Committed
        else Existing.Conflict(SavedReviewConflictException.Reason.REVIEW_CHANGED)
    }

    /**
     * True only when the published [dir] is internally consistent with its own provenance:
     * page count and ordered page digest match, and the PDF is either byte-identical to the
     * recorded hash or (after legitimate regeneration, e.g. a text layer added by indexing)
     * still passes structural verification with the same page count. Says nothing about WHICH
     * review it was built from — that is [SaveFormat.Txn.matches].
     */
    private fun isVerifiedCommit(dir: File, txn: SaveFormat.Txn): Boolean {
        if (!File(dir, "meta.txt").isFile) return false
        val pages = pageFilesIn(dir)
        if (pages.size != txn.pages) return false
        if (SaveFormat.pagesDigest(pages) != txn.pagesSha256) return false
        val pdf = File(dir, "scan.pdf")
        if (!pdf.isFile) return false
        return try {
            if (pdf.length() == txn.pdfBytes && Sha256.of(pdf) == txn.pdfSha256) {
                true
            } else {
                PdfVerifier.verify(pdf, expectedPages = txn.pages)
                true
            }
        } catch (_: IOException) {
            false
        }
    }

    /**
     * Content half of the journal-linked recovery gate for a HIDDEN directory: pages, bounded PDF
     * verification, hashes and metadata — exactly what publication itself requires. Ownership
     * (marker token == journal token == txn token) and review identity are checked by the caller.
     */
    private fun isVerifiedPrepared(dir: File, txn: SaveFormat.Txn): Boolean = try {
        verifyPrepared(dir, txn)
        true
    } catch (_: Exception) {
        false
    }

    /** Streams every page's bytes through SHA-256 (no whole-file allocation) to identify the review. */
    private fun identityOfInputs(imageUris: List<Uri>, filterIds: List<String>): SaveFormat.Identity {
        val digests = imageUris.mapIndexed { index, uri ->
            try {
                context.contentResolver.openInputStream(uri)?.use { Sha256.of(it) }
                    ?: throw IllegalArgumentException("Cannot read this image. Choose it again.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw IllegalArgumentException("Page ${index + 1}: ${e.message}", e)
            }
        }
        return SaveFormat.identity(digests, filterIds)
    }

    /**
     * The verified committed document a review staging folder already produced, if any —
     * used to offer "Open saved document" instead of saving a second copy. It must be the
     * save of exactly THIS staging (page bytes, order and persisted filters); a staging that
     * was edited since is not offered as saved. Read-only; never deletes anything.
     */
    fun committedDocumentFor(reviewDir: File): ScanDocument? {
        val journal = SaveFormat.loadJournal(reviewDir) ?: return null
        val dir = File(docsDir, journal.target)
        if (!dir.isDirectory || SaveFormat.isIncomplete(dir)) return null
        val identity = PendingScans.identity(reviewDir) ?: return null
        val txn = SaveFormat.readTxn(dir) ?: return null
        if (txn.token != journal.token || !txn.matches(identity)) return null
        return if (isVerifiedCommit(dir, txn)) loadDocument(dir) else null
    }

    /** Independent re-read of the prepared (still hidden) document before it may be published. */
    private fun verifyPrepared(dir: File, txn: SaveFormat.Txn) {
        val pages = pageFilesIn(dir)
        if (pages.size != txn.pages) throw IOException("Saved page count ${pages.size} != ${txn.pages}")
        pages.forEachIndexed { i, f ->
            if (f.name != String.format(Locale.ROOT, PAGE_FORMAT, i)) throw IOException("Page order check failed")
        }
        if (SaveFormat.pagesDigest(pages) != txn.pagesSha256) throw IOException("Saved pages do not match what was written")
        val pdf = File(dir, "scan.pdf")
        PdfVerifier.verify(pdf, expectedImages = pages)
        if (Sha256.of(pdf) != txn.pdfSha256) throw IOException("Saved PDF changed after it was written")
        val meta = File(dir, "meta.txt").readLines()
        if (meta.size < 3 || meta[0].isBlank() || meta[1].toLongOrNull() == null) throw IOException("Metadata check failed")
        if (SaveFormat.readTxn(dir)?.token != txn.token) throw IOException("Provenance check failed")
    }

    /** Removes [dir] only if it is still a hidden directory this transaction owns. */
    private fun discardOwned(dir: File, token: String) {
        if (SaveFormat.ownerOf(dir)?.token == token) dir.deleteRecursively()
    }

    /** OCR/indexing is queued only after a verified publish and can never undo or fail the save. */
    private fun markPendingAfterPublish(doc: ScanDocument) {
        try {
            if (!File(doc.pdfFile.parentFile, "ocr.txt").exists()) ocrStore.markPending(doc.id)
        } catch (_: Exception) {
            // The indexer backfill finds the document later; the saved PDF stays valid.
        }
    }

    /** Advisory: the extra bytes this save writes (pages + PDF ≈ 2x the staged pages). */
    private fun checkFreeSpace(imageUris: List<Uri>) {
        val sources = imageUris.sumOf { uri ->
            if (uri.scheme == "file") uri.path?.let { File(it).length() } ?: 0L else 0L
        }
        if (sources <= 0L) return // content:// sizes are unknown; real ENOSPC is still handled
        val needed = sources * 2 + SPACE_MARGIN_BYTES
        if (usableSpace(docsDir) < needed) {
            throw IOException("Not enough storage space to save this scan. Free some space and try again.")
        }
    }

    /**
     * Deletes ONLY disposable hidden save output: a directory whose marker proves it belongs
     * to a save transaction, is older than [minAgeMs], and never reached the provenance stage
     * (no `txn.txt` and no `meta.txt`) — i.e. a partial build that holds nothing the review
     * staging cannot rebuild.
     *
     * Never deleted, however old the marker:
     *  - an owned directory that reached `txn.txt` or `meta.txt`. That is either a fully
     *    prepared document (pages + verified PDF + provenance + metadata, e.g. a crash just
     *    before publication or a marker that reappeared after power loss) or provenance we
     *    cannot verify; both are kept as they are, and a journal-linked replay of the same
     *    unchanged review publishes the prepared one ([saveScan]). The reaper itself never
     *    publishes: it cannot know the review's filters, nor that the owner did not discard it;
     *  - published and legacy documents, directories without a provable marker (foreign or
     *    unknown, even empty), and review staging.
     *
     * It never removes a `.incomplete` marker. Takes the same lock as [saveScan], so it cannot
     * run against an active transaction.
     */
    suspend fun reapAbandonedSaves(
        minAgeMs: Long = ABANDONED_SAVE_AGE_MS,
        now: Long = System.currentTimeMillis()
    ): Int = withContext(Dispatchers.IO) {
        SAVE_LOCK.withLock {
            var removed = 0
            docsDir.listFiles { f -> f.isDirectory }?.forEach { dir ->
                val owner = SaveFormat.ownerOf(dir) ?: return@forEach
                if (now - owner.startedAt < minAgeMs) return@forEach
                if (SaveFormat.reachedProvenance(dir)) return@forEach // complete or unverifiable: preserved
                if (dir.deleteRecursively()) removed++
            }
            removed
        }
    }

    fun deleteDocument(id: String) {
        val dir = File(docsDir, id)
        OcrStore.locked { dir.deleteRecursively() }
    }

    fun renameDocument(id: String, newName: String) {
        updateMeta(File(docsDir, id)) { lines ->
            while (lines.size < 2) lines.add("")
            lines[0] = newName.trim().replace('\n', ' ').ifBlank { lines[0] }
        }
    }

    /** Copies a document (pages + PDF) into a brand-new one. Used by "Save as new". */
    suspend fun duplicateDocument(id: String, suggestedName: String?): ScanDocument =
        withContext(Dispatchers.IO) {
            val src = File(docsDir, id)
            if (!src.exists()) throw IllegalStateException("Scan not found")
            val newId = UUID.randomUUID().toString().take(8)
            val dest = File(docsDir, newId).apply { mkdirs() }
            src.listFiles()?.forEach { f ->
                if (f.isFile) f.copyTo(File(dest, f.name), overwrite = true)
            }
            val name = suggestedName?.trim()?.replace('\n', ' ')?.takeIf { it.isNotBlank() }
                ?: nextDocumentName()
            // A copy keeps its original category when it has one.
            val inherited = src.listFiles { f -> f.name == "meta.txt" }
                ?.firstOrNull()?.readLines()?.getOrNull(2)
                ?.takeIf { CategoryRules.isValid(it) }
            // T4.8: exported copies belong to the original — a duplicate
            // starts with none of its own.
            val destMeta = File(dest, "meta.txt")
            if (destMeta.exists()) {
                destMeta.writeText(
                    destMeta.readLines()
                        .filterNot { it.startsWith(EXPORTS_KEY) || it.startsWith(EXPORT_SIGS_KEY) }
                        .joinToString("\n")
                )
            }
            writeMeta(dest, name, inherited)
            loadDocument(dest) ?: throw IllegalStateException("Copy could not be opened")
        }

    /**
     * test26 T5.2 (§4.5): Extract — saves the given pages as a brand-new
     * document (new dir, pages renumbered, PDF regenerated from the subset).
     * The original is untouched and keeps its exported copies; the category is
     * inherited, like [duplicateDocument].
     */
    suspend fun extractPages(id: String, pageIndices: List<Int>): ScanDocument =
        withContext(Dispatchers.IO) {
            val doc = loadDocument(File(docsDir, id))
                ?: throw IllegalStateException("Scan not found")
            val indices = pageIndices.distinct().sorted()
            require(indices.isNotEmpty()) { "No pages selected" }
            require(indices.all { it in doc.pageFiles.indices }) { "No such page" }

            val (newId, dir) = newDocDir()
            indices.forEachIndexed { i, srcIdx ->
                doc.pageFiles[srcIdx].copyTo(
                    File(dir, String.format(Locale.ROOT, PAGE_FORMAT, i)),
                    overwrite = true
                )
            }
            val first = indices.first() + 1
            val last = indices.last() + 1
            val name = if (first == last) "${doc.name} (page $first)"
            else "${doc.name} (pages $first–$last)"
            finalizeDoc(newId, dir, name, doc.category)
        }

    suspend fun mergeDocuments(
        sourceIds: List<String>,
        suggestedName: String?
    ): ScanDocument = withContext(Dispatchers.IO) {
        require(sourceIds.size >= 2) { "Need at least 2 scans to merge" }

        val sources = sourceIds.map { id ->
            val dir = File(docsDir, id)
            loadDocument(dir) ?: throw IllegalStateException("Scan not found: $id")
        }

        val id = UUID.randomUUID().toString().take(8)
        val dir = File(docsDir, id).apply { mkdirs() }

        var pageNum = 0
        for (doc in sources) {
            for (page in doc.pageFiles) {
                if (!page.exists() || page.length() == 0L) continue
                val out = File(dir, String.format(Locale.ROOT, "page_%03d.jpg", pageNum))
                page.copyTo(out, overwrite = true)
                pageNum++
            }
        }
        if (pageNum == 0) {
            dir.deleteRecursively()
            throw IllegalStateException("No pages to merge")
        }

        val name = suggestedName?.trim()?.replace('\n', ' ')?.takeIf { it.isNotBlank() }
            ?: "Merged scan ${SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date())}"

        writeMeta(dir, name)

        val pdfFile = File(dir, "scan.pdf")
        try {
            PdfGenerator.imagesToPdf(pageFilesIn(dir), pdfFile)
        } catch (e: Exception) {
            pdfFile.delete()
        }

        loadDocument(dir)!!
    }

    /**
     * Merges device PDFs (content Uris) in the given order. Pages are rendered
     * and written to disk one at a time — no page-byte list is ever held in
     * memory, so merging many or large PDFs cannot OOM.
     */
    suspend fun mergePdfUris(uris: List<Uri>, suggestedName: String?): ScanDocument =
        withContext(Dispatchers.IO) {
            require(uris.size >= 2) { "Pick at least 2 PDFs" }
            val (id, dir) = newDocDir()
            try {
                var pageNum = 0
                for (uri in uris) {
                    // SAF PDFs are often pipes; the renderer needs a seekable fd.
                    pageNum += PdfPageRenderer.withSeekablePdf(context, uri) { fd ->
                        appendPdfPages(fd, dir, pageNum, dpi = 150f, maxDim = 2048, quality = 85)
                    }
                }
                if (pageNum == 0) throw IllegalStateException("No pages to merge")
                finalizeDoc(id, dir, suggestedName ?: defaultMergedName("Merged PDF"))
            } catch (e: Exception) {
                dir.deleteRecursively()
                throw e
            }
        }

    /**
     * Renders every page of the open PDF into [dir], continuing at [start].
     * Returns how many pages were appended.
     */
    private fun appendPdfPages(
        fd: ParcelFileDescriptor,
        dir: File,
        start: Int,
        dpi: Float,
        maxDim: Int,
        quality: Int
    ): Int {
        val count = PdfPageRenderer.pageCount(fd)
        require(count > 0) { "PDF has no pages" }
        for (i in 0 until count) {
            val jpeg = PdfPageRenderer.renderPageJpeg(fd, i, dpi, maxDim, quality)
            File(dir, String.format(Locale.ROOT, PAGE_FORMAT, start + i)).writeBytes(jpeg)
        }
        return count
    }

    suspend fun deletePage(id: String, pageIndex: Int): ScanDocument =
        withContext(Dispatchers.IO) {
            val dir = File(docsDir, id)
            OcrStore.locked {
                val doc = loadDocument(dir) ?: throw IllegalStateException("Scan not found")
                require(pageIndex in doc.pageFiles.indices) { "No such page" }
                require(doc.pageFiles.size > 1) { "Can't delete the last page" }
                val victim = doc.pageFiles[pageIndex]
                origOf(victim).delete()
                victim.delete()
                renumberPages(dir)
                ocrStore.afterDelete(id, pageIndex)
            }
            regeneratePdf(dir)

            loadDocument(dir) ?: throw IllegalStateException("Scan vanished after delete")
        }

    suspend fun rotatePage(id: String, pageIndex: Int, clockwise: Boolean = true): ScanDocument =
        withContext(Dispatchers.IO) {
            val dir = File(docsDir, id)
            OcrStore.locked {
                val doc = loadDocument(dir) ?: throw IllegalStateException("Scan not found")
                require(pageIndex in doc.pageFiles.indices) { "No such page" }
                val file = doc.pageFiles[pageIndex]
                rotateFile90(file, clockwise)
                // Keep the original aligned for later filter re-application.
                origOf(file).takeIf { it.exists() }?.let { rotateFile90(it, clockwise) }
                ocrStore.invalidate(id, file.name)
            }

            regeneratePdf(dir)
            loadDocument(dir) ?: throw IllegalStateException("Scan vanished after rotate")
        }

    /**
     * Crops one page to [rect] (full-file pixel coordinates), keeps the kept
     * original in sync so a later filter re-apply stays cropped, and
     * regenerates the PDF.
     */
    suspend fun cropPage(id: String, pageIndex: Int, rect: android.graphics.RectF): ScanDocument =
        withContext(Dispatchers.IO) {
            val dir = File(docsDir, id)
            OcrStore.locked {
                val doc = loadDocument(dir) ?: throw IllegalStateException("Scan not found")
                require(pageIndex in doc.pageFiles.indices) { "No such page" }
                val file = doc.pageFiles[pageIndex]
                ImageTools.cropTo(file, rect)
                origOf(file).takeIf { it.exists() }?.let { ImageTools.cropTo(it, rect) }
                ocrStore.invalidate(id, file.name)
            }
            regeneratePdf(dir)
            loadDocument(dir) ?: throw IllegalStateException("Scan vanished after crop")
        }

    private fun rotateFile90(file: File, clockwise: Boolean = true) = ImageTools.rotate90(file, clockwise)

    suspend fun movePage(id: String, fromIndex: Int, toIndex: Int): ScanDocument =
        withContext(Dispatchers.IO) {
            val dir = File(docsDir, id)
            OcrStore.locked {
                val doc = loadDocument(dir) ?: throw IllegalStateException("Scan not found")
                require(fromIndex in doc.pageFiles.indices) { "No such page" }
                require(toIndex in doc.pageFiles.indices) { "No such position" }
                if (fromIndex != toIndex) {
                    val reordered = doc.pageFiles.toMutableList().apply {
                        add(toIndex, removeAt(fromIndex))
                    }
                    val tmpNames = mutableListOf<File>()
                    reordered.forEachIndexed { i, f ->
                        val tmp = File(dir, String.format(Locale.ROOT, "tmp_%03d.jpg", i))
                        f.renameTo(tmp)
                        origOf(f).takeIf { it.exists() }?.renameTo(origOf(tmp))
                        tmpNames.add(tmp)
                    }
                    tmpNames.forEachIndexed { i, tmp ->
                        val target = File(dir, String.format(Locale.ROOT, "page_%03d.jpg", i))
                        tmp.renameTo(target)
                        origOf(tmp).takeIf { it.exists() }?.renameTo(origOf(target))
                    }
                    ocrStore.afterMove(id, fromIndex, toIndex)
                }
            }
            if (fromIndex != toIndex) regeneratePdf(dir)
            loadDocument(dir) ?: throw IllegalStateException("Scan vanished after move")
        }

    private fun pageFilesIn(dir: File): List<File> =
        dir.listFiles { f -> f.extension.equals("jpg", true) && f.length() > 0 }
            ?.sortedBy { it.nameWithoutExtension }
            ?: emptyList()

    /** Kept original of a filtered page: `page_001.jpg` -> `page_001.orig` (not a .jpg, never listed). */
    private fun origOf(page: File): File =
        File(page.parentFile, page.nameWithoutExtension + ".orig")

    /**
     * Applies [filter] to one saved page of a document — always re-processing
     * from the kept original so filters never stack, and so "Original"
     * restores the untouched scan. Creates the `.orig` sibling on first use.
     */
    suspend fun setPageFilter(id: String, pageIndex: Int, filter: ScanFilter): ScanDocument =
        withContext(Dispatchers.IO) {
            val dir = File(docsDir, id)
            val doc = loadDocument(dir) ?: throw IllegalStateException("Scan not found")
            require(pageIndex in doc.pageFiles.indices) { "No such page" }
            val file = doc.pageFiles[pageIndex]
            val orig = origOf(file)
            if (filter == ScanFilter.ORIGINAL) {
                if (orig.exists()) {
                    orig.copyTo(file, overwrite = true)
                    orig.delete()
                }
            } else {
                if (!orig.exists()) file.copyTo(orig, overwrite = true)
                val src = BitmapFactory.decodeFile(orig.path)
                    ?: throw IllegalStateException("Unreadable page")
                val out = filter.applyTo(src)
                FileOutputStream(file).use { o -> out.compress(Bitmap.CompressFormat.JPEG, 92, o) }
                if (out !== src) out.recycle()
                src.recycle()
            }
            regeneratePdf(dir)
            loadDocument(dir) ?: throw IllegalStateException("Scan vanished after filter")
        }

    /** Fresh document directory (never finalised: no meta yet). */
    private fun newDocDir(): Pair<String, File> {
        val id = UUID.randomUUID().toString().take(8)
        return id to File(docsDir, id).apply { mkdirs() }
    }

    /** Writes the name/meta, generates the PDF, and returns the listed document. */
    private fun finalizeDoc(
        id: String,
        dir: File,
        suggestedName: String?,
        category: String? = null
    ): ScanDocument {
        val name = suggestedName?.trim()?.replace('\n', ' ')?.takeIf { it.isNotBlank() }
            ?: nextDocumentName()
        writeMeta(dir, name, category)
        val pdfFile = File(dir, "scan.pdf")
        try {
            writePdf(dir, pdfFile) // PDF is always written; layers may be empty
        } catch (_: Exception) {
            pdfFile.delete()
        }
        return loadDocument(dir) ?: throw IllegalStateException("Scan could not be saved").also { ocrStore.markPending(id) }
    }

    /** "Merged PDF <timestamp>" when no name is given. */
    private fun defaultMergedName(prefix: String): String =
        "$prefix ${SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date())}"

    // Shared page-file naming (page_000.jpg, page_001.jpg, …).
    private val PAGE_FORMAT = "page_%03d.jpg"

    /** Creates a brand-new scan document from raw page JPEG bytes. */
    suspend fun createDocument(
        name: String?,
        pageBytes: List<ByteArray>
    ): ScanDocument = withContext(Dispatchers.IO) {
        require(pageBytes.isNotEmpty()) { "No pages" }

        val (id, dir) = newDocDir()

        pageBytes.forEachIndexed { index, bytes ->
            require(bytes.isNotEmpty()) { "Page ${index + 1} is empty" }
            File(dir, String.format(Locale.ROOT, PAGE_FORMAT, index)).writeBytes(bytes)
        }

        finalizeDoc(id, dir, name)
    }

    /** Copies a scan with pages re-encoded at [quality] (optionally downscaled to [maxDim]). */
    suspend fun compressScan(
        id: String,
        quality: Int,
        maxDim: Int?
    ): ScanDocument = withContext(Dispatchers.IO) {
        val doc = loadDocument(File(docsDir, id)) ?: throw IllegalStateException("Scan not found")
        val compressed = doc.pageFiles.map { file ->
            ImageTools.recompressJpeg(file.readBytes(), quality, maxDim)
        }
        createDocument("${doc.name} (compressed)", compressed)
    }

    /**
     * Imports an external PDF file as a new scan (rendered on-device).
     * [dpi]/[maxDim]/[quality] control the rasterization, i.e. the compression
     * level. Pages are rendered and written one at a time so large PDFs
     * cannot OOM.
     */
    suspend fun importPdfAsScan(
        uri: Uri,
        name: String?,
        dpi: Float = 150f,
        maxDim: Int = 2048,
        quality: Int = 85
    ): ScanDocument = withContext(Dispatchers.IO) {
        val (id, dir) = newDocDir()
        try {
            // Copying once makes every provider (Downloads, Drive, chat apps)
            // readable by PdfRenderer instead of failing "not seekable".
            PdfPageRenderer.withSeekablePdf(context, uri) { fd ->
                appendPdfPages(fd, dir, 0, dpi = dpi, maxDim = maxDim, quality = quality)
            }
            finalizeDoc(id, dir, name)
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
    }

    /** Imports a device PDF straight into the Hidden Vault (Pro). */
    suspend fun importPdfIntoVault(
        uri: Uri,
        name: String?,
        dpi: Float = 150f,
        maxDim: Int = 2048,
        quality: Int = 85
    ): ScanDocument {
        val doc = importPdfAsScan(uri, name, dpi, maxDim, quality)
        return setVaulted(doc.id, true) ?: doc
    }

    private val LOCK_FILE = "locked.meta"
    private val VAULT_FILE = "vault.flag"

    /** T4.8: prefix of the optional meta.txt line listing exported copies. */
    private val EXPORTS_KEY = "exports="

    /** M06: `exportsigs=<uri> <size>:<sha256>|…` fingerprints of recorded copies. */
    private val EXPORT_SIGS_KEY = "exportsigs="
    private val LOCK_INDEX_FILE = "locked_pdfs.txt"

    /**
     * Rebuilds the document's PDF with the password-protection extra applied
     * (Pro). The page images are untouched; only the PDF wrapper changes.
     *
     * The finished encrypted PDF's SHA-256 is recorded in an app-private
     * index, so if the user later picks that saved/shared copy from storage
     * ScanWhirl can recognise its own lock and offer an honest "Unlock PDF".
     */
    suspend fun protectPdf(id: String, password: String): ScanDocument =
        withContext(Dispatchers.IO) {
            require(password.isNotBlank()) { "Password must not be empty" }
            val dir = File(docsDir, id)
            val doc = loadDocument(dir) ?: throw IllegalStateException("Scan not found")
            val src = File(dir, "plain.pdf")
            if (doc.pageFiles.isNotEmpty()) {
                writePdf(dir, src) // plain PDF is always written for encryption
            } else {
                require(doc.pdfFile.exists() && doc.pdfFile.length() > 0L) { "PDF missing" }
                doc.pdfFile.copyTo(src, overwrite = true)
            }
            val protected = File(dir, "protected.pdf")
            try {
                PdfEncryptor.encrypt(src, protected, password)
                protected.copyTo(doc.pdfFile, overwrite = true)
                File(dir, LOCK_FILE).writeText(Prefs.sha256("pdf_lock:$password"))
                recordLockIndex(doc.pdfFile, id)
            } finally {
                src.delete()
                protected.delete()
            }
            loadDocument(dir) ?: throw IllegalStateException("Scan vanished after protection")
        }

    /**
     * Removes password protection from a document by regenerating its clean
     * PDF from the stored page JPEGs and clearing the lock marker.
     */
    suspend fun unlockPdf(id: String): ScanDocument =
        withContext(Dispatchers.IO) {
            val dir = File(docsDir, id)
            loadDocument(dir) ?: throw IllegalStateException("Scan not found")
            File(dir, LOCK_FILE).delete()
            regeneratePdf(dir)
            pruneLockIndex()
            loadDocument(dir) ?: throw IllegalStateException("Scan vanished after unlock")
        }

    /** Checks a user-entered password against the stored lock hash. */
    fun verifyPdfPassword(id: String, password: String): Boolean {
        val lockFile = File(File(docsDir, id), LOCK_FILE)
        if (!lockFile.exists()) return true
        val stored = lockFile.readText().trim()
        return stored == Prefs.sha256("pdf_lock:$password")
    }

    // ------------------------------------------------------------------
    // Password-lock index (recognises ScanWhirl-locked PDFs later)
    // ------------------------------------------------------------------

    private fun recordLockIndex(pdf: File, docId: String) {
        try {
            val hash = Sha256.of(pdf)
            File(context.filesDir, LOCK_INDEX_FILE)
                .appendText("$hash\t$docId\n")
        } catch (_: Exception) {
            // Indexing is a convenience; a failed write must never break locking.
        }
    }

    /** Finds the still-locked ScanWhirl document that produced [hash]. */
    fun findLockedDocumentByHash(hash: String): ScanDocument? {
        val index = File(context.filesDir, LOCK_INDEX_FILE)
        if (!index.exists()) return null
        return index.readLines().asReversed().firstNotNullOfOrNull { line ->
            val parts = line.split('\t')
            if (parts.size != 2 || parts[0] != hash) return@firstNotNullOfOrNull null
            val doc = getDocument(parts[1]) ?: return@firstNotNullOfOrNull null
            if (doc.isLocked) doc else null
        }
    }

    /** Drops index entries whose document is gone or no longer locked. */
    private fun pruneLockIndex() {
        try {
            val index = File(context.filesDir, LOCK_INDEX_FILE)
            if (!index.exists()) return
            val kept = index.readLines().filter { line ->
                val parts = line.split('\t')
                parts.size == 2 && getDocument(parts[1])?.isLocked == true
            }
            index.writeText(kept.joinToString("\n").let { if (it.isBlank()) "" else "$it\n" })
        } catch (_: Exception) {
        }
    }

    /**
     * Builds a temporary unencrypted PDF in cacheDir so the in-app PdfRenderer
     * can display a locked scan after the user enters their password.
     */
    suspend fun unlockedPreviewPdf(id: String): File =
        withContext(Dispatchers.IO) {
            val dir = File(docsDir, id)
            val doc = loadDocument(dir) ?: throw IllegalStateException("Scan not found")
            val outDir = File(context.cacheDir, "documents").apply { mkdirs() }
            val preview = File(outDir, "preview_$id.pdf")
            writePdf(dir, preview)
            preview
        }

    /** Moves a scan into or out of the 4-digit PIN Hidden Vault (Pro). */
    fun setVaulted(id: String, vaulted: Boolean): ScanDocument? {
        val dir = File(docsDir, id)
        if (!dir.exists()) return null
        val flag = File(dir, VAULT_FILE)
        if (vaulted) {
            flag.writeText("1")
        } else {
            flag.delete()
        }
        return loadDocument(dir)
    }

    /** OCR text of a document, when it exists: lines keyed by the page file name. */
    fun ocrDoc(id: String): Map<String, String> = ocrStore.readText(id)

    /** True when this document has OCR text saved already. */
    fun hasOcr(id: String): Boolean = File(File(docsDir, id), "ocr.txt").exists()

    /**
     * Replaces one page's OCR text (the OCR Editor's Save, P6) and rewrites
     * `ocr.txt` through [OcrStore.updateTexts]. Returns
     * the full updated page map, or null when the document does not exist.
     */
    fun updateOcrPage(id: String, pageKey: String, text: String): Map<String, String>? {
        val dir = File(docsDir, id)
        if (!dir.isDirectory) return null
        return ocrStore.updateTexts(id, mapOf(pageKey to text))
    }

    /**
     * Replaces every given page's OCR text in one rewrite (the OCR Editor's
     * Save-all, P6). Returns the full updated page map, or null when the
     * document does not exist.
     */
    fun updateOcrPages(id: String, pages: Map<String, String>): Map<String, String>? {
        val dir = File(docsDir, id)
        if (!dir.isDirectory) return null
        return ocrStore.updateTexts(id, pages)
    }

    /**
     * Runs OCR over every page (background) and stores text + geometry through
     * [OcrStore]. Returns the number of pages processed. A page the recognizer
     * rejects is stored with empty text and no geometry.
     */
    suspend fun buildOcr(id: String): Int = withContext(Dispatchers.IO) {
        val dir = File(docsDir, id)
        val doc = loadDocument(dir) ?: throw IllegalStateException("Scan not found")
        var count = 0
        for (page in doc.pageFiles) {
            try {
                val result = OcrEngine.recognizeFile(page)
                ocrStore.putPage(id, page.name, result.text, result.page)
                count++
            } catch (_: Exception) {
                // Page unreadable: store empty text, no geometry, honest.
                ocrStore.putPage(id, page.name, "", null)
            }
        }
        count
    }

    private fun renumberPages(dir: File) {
        pageFilesIn(dir).forEachIndexed { i, f ->
            val target = File(dir, String.format(Locale.ROOT, "page_%03d.jpg", i))
            if (f != target) {
                val tmp = File(dir, String.format(Locale.ROOT, "renum_%03d.jpg", i))
                f.renameTo(tmp)
                origOf(f).takeIf { it.exists() }?.renameTo(origOf(tmp))
                tmp.renameTo(target)
                origOf(tmp).takeIf { it.exists() }?.renameTo(origOf(target))
            }
        }
    }

    /** Writes the document's PDF through [writePdf], then maintains `text_layer.flag`. */
    private fun regeneratePdf(dir: File) = synchronized(PDF_LOCK) {
        val pdfFile = File(dir, "scan.pdf")
        val id = dir.name
        val pages = pageFilesIn(dir)
        val layered = if (pages.isNotEmpty()) writePdf(dir, pdfFile) else false
        val searchable = layered && pages.all {
            ocrStore.readGeo(id).containsKey("page_%03d.jpg".format(pages.indexOf(it)))
        }
        val flag = File(dir, "text_layer.flag")
        if (searchable) {
            flag.writeText("")
        } else {
            flag.delete()
        }
    }

    /**
     * Writes [out] from [dir]'s page files, with an invisible text layer built from
     * `OcrStore.readGeo(id)` when `Prefs.makeSearchable` is on. Returns true when at
     * least one page received a layer. Atomic via `.tmp` + rename inside the generator.
     */
    private fun writePdf(dir: File, out: File): Boolean = synchronized(PDF_LOCK) {
        val pages = pageFilesIn(dir)
        if (pages.isEmpty()) {
            false
        } else {
            val id = dir.name
            val textLayers = if (Prefs.makeSearchable(context)) {
                val geo = ocrStore.readGeo(id)
                pages.indices.filter { geo.containsKey("page_%03d.jpg".format(it)) }
                    .associate { i -> i to geo["page_%03d.jpg".format(i)]!! }
            } else {
                emptyMap()
            }
            PdfGenerator.imagesToPdf(pages, out, textLayers).isNotEmpty()
        }
    }

    /**
     * Regenerates the document's PDF with a text layer after indexing completes.
     * No-op when searchable PDFs are off, when the document is locked, or when the
     * directory is gone — the lock must never be dropped.
     */
    fun refreshPdfAfterIndex(id: String) {
        if (!Prefs.makeSearchable(context)) return
        val dir = File(docsDir, id)
        if (!dir.exists() || !dir.isDirectory) return
        if (File(dir, LOCK_FILE).exists()) return
        regeneratePdf(dir)
    }

    /** Next default scan name: "Document 1", "Document 2", …
     *  Starts at max(existing Document-N)+1 so the sequence is predictable
     *  and already-deleted numbers are not reused.  Then advances further
     *  if that name is already taken by a document that was explicitly
     *  renamed to "Document N", making it collision-proof in all cases. */
    fun nextDocumentName(): String {
        val docDirs = docsDir.listFiles { f -> f.isDirectory && !SaveFormat.isIncomplete(f) } ?: emptyArray()
        val existingNames = docDirs.mapNotNull { dir ->
            val meta = File(dir, "meta.txt")
            if (meta.exists()) meta.readLines().firstOrNull() else null
        }.toHashSet()
        val pattern = Regex("^Document (\\d+)$")
        val maxN = existingNames.mapNotNull { pattern.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: 0
        var n = maxN + 1
        while (existingNames.contains("Document $n")) n++
        return "Document $n"
    }

    fun displayNameOf(uri: Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                return cursor.getString(idx)
            }
        }
        return null
    }
}
