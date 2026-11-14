package com.scanwhirl.app.store

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.core.net.toUri
import com.scanwhirl.app.R
import com.scanwhirl.app.model.OcrPage
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.OcrStore
import com.scanwhirl.app.util.CategoryRules
import com.scanwhirl.app.util.DocxWriter
import com.scanwhirl.app.util.DownloadSaver
import com.scanwhirl.app.util.Durable
import com.scanwhirl.app.util.ImageTools
import com.scanwhirl.app.util.LongImage
import com.scanwhirl.app.util.PageRanges
import com.scanwhirl.app.util.PageReplace
import com.scanwhirl.app.util.PdfImport
import com.scanwhirl.app.util.PdfEncryptor
import com.scanwhirl.app.util.PdfGenerator
import com.scanwhirl.app.util.PdfPageRenderer
import com.scanwhirl.app.util.PdfProtection
import com.scanwhirl.app.util.PdfVerifier
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.ScanFilter
import com.scanwhirl.app.util.Sha256
import com.scanwhirl.app.util.ShareUtils
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
            trashFault = {}
            importFault = {}
        }

        /** An import staging folder untouched this long belongs to an import that died. */
        const val STALE_IMPORT_MS = 60L * 60 * 1000

        /** M07: serialises Trash moves, restores and purges across repository instances. */
        private val TRASH_LOCK = Any()

        /** Written into a Trash entry after its move: `v1 / original id / trashedAt`. */
        internal const val TRASH_MARKER = "trashed.txt"

        /** Hidden entries being permanently deleted; finished by [recoverTrash]. */
        internal const val PURGE_PREFIX = ".purge-"

        /** Separates an id from its counter when two Trash entries share an id. */
        private const val TRASH_KEY_SEP = "~"

        /** Points in a Trash operation where a test can simulate process death. */
        enum class TrashStage { BEFORE_MOVE, MOVED, BEFORE_RESTORE, RESTORED, PURGE_HIDDEN }

        /** Fault-injection seam (tests only). May throw. */
        @Volatile internal var trashFault: (TrashStage) -> Unit = {}

        /** Points in a PDF import where a test can inject a failure. */
        enum class ImportStage { COPIED, PAGE_RENDERED, BEFORE_PUBLISH }

        /** Fault-injection seam (tests only). May throw. */
        @Volatile internal var importFault: (ImportStage) -> Unit = {}
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
        updateMeta(File(docsDir, id)) { lines -> recordSeen(lines, uri, fingerprint) }
    }

    /**
     * M07 P0: records the outcome of a save of document [id].
     *
     * Every copy is listed in `exports=` so no *other* document's delete can ever
     * remove it. Only a copy this save actually **created** also gets a signed
     * provenance entry (`exportmade=`), the one proof [provenExportsOf] accepts.
     * An "already saved" result adopted a file that existed before (perhaps a
     * deleted document's retained copy), so it is recorded but never proven.
     * Re-saving unchanged bytes keeps an existing proof for that same URI.
     */
    fun addExport(id: String, saved: DownloadSaver.SavedFile) {
        val fp = saved.fingerprint
        val signature = if (saved.created && fp != null) {
            ExportProvenance.sign(context, id, saved.uri, fp)
        } else {
            null
        }
        updateMeta(File(docsDir, id)) { lines ->
            recordSeen(lines, saved.uri, fp)
            if (signature != null && fp != null) {
                val made = listLine(lines, EXPORT_MADE_KEY)
                    .filterNot { it.substringBefore(' ') == saved.uri.toString() }
                setListLine(lines, EXPORT_MADE_KEY, made + "${saved.uri} ${fp.encode()} $signature")
            }
        }
    }

    private fun recordSeen(lines: MutableList<String>, uri: Uri, fingerprint: DownloadSaver.Fingerprint?) {
        padMeta(lines)
        val recorded = listLine(lines, EXPORTS_KEY)
        setListLine(lines, EXPORTS_KEY, (recorded + uri.toString()).distinct())
        // M06: what the copy held when this document saved it (`exportsigs=` is a
        // separate line; older builds ignore it and keep reading `exports=`).
        if (fingerprint != null) {
            val sigs = listLine(lines, EXPORT_SIGS_KEY)
                .filterNot { it.substringBefore(' ') == uri.toString() }
            setListLine(lines, EXPORT_SIGS_KEY, sigs + "$uri ${fingerprint.encode()}")
        }
    }

    /** M06: forgets a recorded copy (e.g. after Undo removed it), including its proof. */
    fun removeExport(id: String, uri: Uri) {
        updateMeta(File(docsDir, id)) { lines ->
            val key = uri.toString()
            if (lines.any { it.startsWith(EXPORTS_KEY) }) {
                setListLine(lines, EXPORTS_KEY, listLine(lines, EXPORTS_KEY).filterNot { it == key })
            }
            for (k in listOf(EXPORT_SIGS_KEY, EXPORT_MADE_KEY)) {
                if (lines.any { it.startsWith(k) }) {
                    setListLine(lines, k, listLine(lines, k).filterNot { it.substringBefore(' ') == key })
                }
            }
        }
    }

    /** T4.8: PDF copies of this document recorded in Documents/ScanWhirl. */
    fun exportsOf(id: String): List<Uri> = exportRecordsOf(id).map { it.uri }

    /** A recorded copy plus, when known, the fingerprint it had when this document saved it. */
    data class ExportRecord(val uri: Uri, val fingerprint: DownloadSaver.Fingerprint?)

    fun exportRecordsOf(id: String): List<ExportRecord> = exportRecordsIn(File(docsDir, id))

    private fun metaLines(dir: File): List<String>? {
        val meta = File(dir, "meta.txt")
        if (!meta.exists()) return null
        return try { meta.readLines() } catch (_: IOException) { null }
    }

    private fun exportRecordsIn(dir: File): List<ExportRecord> {
        val lines = metaLines(dir) ?: return emptyList()
        val sigs = listLine(lines, EXPORT_SIGS_KEY).mapNotNull { entry ->
            val space = entry.indexOf(' ')
            if (space <= 0) null
            else entry.substring(0, space) to DownloadSaver.Fingerprint.decode(entry.substring(space + 1))
        }.toMap()
        return listLine(lines, EXPORTS_KEY).mapNotNull { raw ->
            runCatching { Uri.parse(raw) }.getOrNull()?.let { ExportRecord(it, sigs[raw]) }
        }
    }

    /** M07: a copy this document provably created, with the fingerprint it had then. */
    data class ProvenExport(val uri: Uri, val fingerprint: DownloadSaver.Fingerprint)

    /**
     * M07 P0: the only copies a delete of [id] may offer to remove: those whose
     * signed `exportmade=` entry verifies for this exact document id. Unsigned
     * (pre-M07) records, adopted "already saved" files and title matches are never
     * included. The caller still re-checks each file's current bytes.
     */
    fun provenExportsOf(id: String): List<ProvenExport> = provenExportsIn(File(docsDir, id), id)

    internal fun provenExportsIn(dir: File, docId: String): List<ProvenExport> {
        val lines = metaLines(dir) ?: return emptyList()
        return listLine(lines, EXPORT_MADE_KEY).mapNotNull { entry ->
            val parts = entry.split(' ')
            if (parts.size != 3) return@mapNotNull null
            val uri = runCatching { parts[0].toUri() }.getOrNull() ?: return@mapNotNull null
            val fp = DownloadSaver.Fingerprint.decode(parts[1]) ?: return@mapNotNull null
            if (ExportProvenance.verify(context, docId, uri, fp, parts[2])) ProvenExport(uri, fp) else null
        }.distinctBy { it.uri }
    }

    /**
     * M06/M07: every copy recorded by any document other than the one in
     * [exclude], including locked, vaulted and trashed ones. Deleting a document
     * must never remove a file another document also points at.
     */
    fun exportsRecordedByOthers(id: String): Set<Uri> = exportsRecordedOutside(File(docsDir, id))

    internal fun exportsRecordedOutside(exclude: File): Set<Uri> {
        val dirs = (docsDir.listFiles { f -> f.isDirectory }.orEmpty().asList() +
            trashDir.listFiles { f -> f.isDirectory }.orEmpty().asList())
            .filter { it.absoluteFile != exclude.absoluteFile }
        return dirs.flatMapTo(HashSet()) { dir -> exportRecordsIn(dir).map { it.uri } }
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

    /**
     * Permanently deletes a live document (used for legacy Vault items, which never
     * enter Trash). The directory is first renamed into a hidden purge entry, so a
     * crash mid-delete can never leave a half-deleted document visible in Home;
     * [recoverTrash] finishes the removal on the next start.
     */
    fun deleteDocument(id: String) {
        val dir = File(docsDir, id)
        synchronized(PDF_LOCK) {
            OcrStore.locked {
                synchronized(TRASH_LOCK) {
                    if (!dir.exists()) return@synchronized
                    val hidden = File(trashDir, "$PURGE_PREFIX$id-${UUID.randomUUID().toString().take(8)}")
                    if (dir.renameTo(hidden)) {
                        trashFault(TrashStage.PURGE_HIDDEN)
                        hidden.deleteRecursively()
                    } else {
                        dir.deleteRecursively()
                    }
                }
            }
        }
        dropDerivedCaches(id)
    }

    // ------------------------------------------------------------------
    // M07 P1: Trash (reversible delete)
    // ------------------------------------------------------------------

    /**
     * `filesDir/trash`: trashed documents, each a complete document directory
     * (pages, `.orig` originals, scan.pdf, meta, OCR text/geometry, flags) moved here
     * whole. It is on the same filesystem as `documents/`, so every move is one
     * atomic `rename(2)`: a document is either fully in the library or fully in
     * Trash, never split. Nothing in Home, search, Quick Sort or the OCR indexer
     * scans this directory.
     */
    internal val trashDir: File
        get() = File(context.filesDir, "trash").apply { mkdirs() }

    /** A document in Trash. [key] is its Trash entry; [originalId] the library id it had. */
    data class TrashedDocument(
        val key: String,
        val originalId: String,
        val trashedAt: Long,
        val document: ScanDocument
    )

    /** Thrown when a legacy Vault item is asked to go to Trash; it never does. */
    class VaultItemNotTrashable : IllegalStateException("Vault items are not moved to Trash")

    /**
     * Moves a live document into Trash and returns its Trash key, or null when the
     * document no longer exists. Exported copies in Documents/ScanWhirl are never
     * touched. Legacy Vault documents are refused ([VaultItemNotTrashable]) so they
     * can never surface in the ordinary Trash list.
     *
     * Order: PDF lock, then the OCR store lock, so neither a PDF regeneration nor an
     * OCR write can be half-way through the directory while it moves.
     */
    fun moveToTrash(id: String): String? {
        val key = synchronized(PDF_LOCK) {
            OcrStore.locked {
                synchronized(TRASH_LOCK) {
                    val src = File(docsDir, id)
                    val doc = loadDocument(src) ?: return@synchronized null
                    if (doc.isVaulted) throw VaultItemNotTrashable()
                    trashFault(TrashStage.BEFORE_MOVE)
                    val key = freeTrashKey(id)
                    val dest = File(trashDir, key)
                    if (!src.renameTo(dest)) throw IOException("Could not move \"${doc.name}\" to Trash")
                    trashFault(TrashStage.MOVED)
                    // Written after the move, best effort: an entry without it is still a
                    // complete trashed document (id from its name, time from the folder).
                    try {
                        Durable.atomicWriteText(
                            File(dest, TRASH_MARKER),
                            "v1\n$id\n${System.currentTimeMillis()}\n"
                        )
                    } catch (_: Exception) {
                    }
                    Durable.syncDir(docsDir)
                    Durable.syncDir(trashDir)
                    key
                }
            }
        }
        if (key != null) dropDerivedCaches(id)
        return key
    }

    /** Trashed documents, most recently trashed first. Vault items never appear. */
    fun listTrash(): List<TrashedDocument> {
        val dirs = trashDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: return emptyList()
        return dirs.mapNotNull { dir -> trashedIn(dir) }
            .filterNot { it.document.isVaulted }
            .sortedByDescending { it.trashedAt }
    }

    fun trashCount(): Int = listTrash().size

    private fun trashedIn(dir: File): TrashedDocument? {
        val doc = loadDocument(dir) ?: return null
        val marker = try {
            File(dir, TRASH_MARKER).takeIf { it.isFile }?.readLines()
        } catch (_: IOException) {
            null
        }
        val originalId = marker?.getOrNull(1)?.takeIf { isSafeId(it) } ?: dir.name.substringBefore(TRASH_KEY_SEP)
        val trashedAt = marker?.getOrNull(2)?.toLongOrNull() ?: dir.lastModified()
        return TrashedDocument(dir.name, originalId, trashedAt, doc)
    }

    /**
     * Restores a trashed document whole and returns it. It goes back under its
     * original id when that id is free. If a library document now uses that id, the
     * existing document is left untouched and the restored one gets a new id, so no
     * item is ever overwritten and no two documents share an id.
     */
    fun restoreFromTrash(key: String): ScanDocument {
        val restored = synchronized(TRASH_LOCK) {
            val src = File(trashDir, key)
            val trashed = trashedIn(src) ?: throw IOException("This item is no longer in Trash")
            var id = trashed.originalId
            if (!isSafeId(id) || File(docsDir, id).exists()) id = freeLibraryId()
            trashFault(TrashStage.BEFORE_RESTORE)
            val dest = File(docsDir, id)
            if (!src.renameTo(dest)) throw IOException("Could not restore \"${trashed.document.name}\"")
            trashFault(TrashStage.RESTORED)
            File(dest, TRASH_MARKER).delete()
            Durable.syncDir(trashDir)
            Durable.syncDir(docsDir)
            loadDocument(dest) ?: throw IOException("Restored document could not be opened")
        }
        // A restored locked document is recognised again from its saved copies.
        if (restored.isLocked) recordLockIndex(restored.pdfFile, restored.id)
        return restored
    }

    /**
     * Permanently deletes one Trash entry. It is renamed into a hidden purge entry
     * first (atomic), so a crash part-way never leaves a half-deleted document in
     * the Trash list; [recoverTrash] finishes it.
     */
    fun deleteFromTrash(key: String) {
        synchronized(TRASH_LOCK) {
            val src = File(trashDir, key)
            if (!src.exists() || key.startsWith(".")) return
            val originalId = trashedIn(src)?.originalId
            val hidden = File(trashDir, "$PURGE_PREFIX$key-${UUID.randomUUID().toString().take(8)}")
            if (!src.renameTo(hidden)) throw IOException("Could not delete this item")
            trashFault(TrashStage.PURGE_HIDDEN)
            hidden.deleteRecursively()
            originalId?.let { dropDerivedCaches(it) }
        }
    }

    /** Proven exported copies of a trashed document (see [provenExportsOf]). */
    fun provenExportsOfTrashed(item: TrashedDocument): List<ProvenExport> =
        provenExportsIn(File(trashDir, item.key), item.originalId)

    /** Copies recorded by any document other than this Trash entry. */
    fun exportsRecordedByOthersThan(item: TrashedDocument): Set<Uri> =
        exportsRecordedOutside(File(trashDir, item.key))

    /**
     * Startup recovery: finishes interrupted permanent deletes (hidden purge
     * entries) and drops a Trash marker left in a restored document by a crash
     * between the move and the marker cleanup. Never touches a visible entry.
     */
    fun recoverTrash(): Int {
        var finished = 0
        synchronized(TRASH_LOCK) {
            trashDir.listFiles { f -> f.name.startsWith(PURGE_PREFIX) }?.forEach { f ->
                if (f.deleteRecursively()) finished++
            }
            docsDir.listFiles { f -> f.isDirectory }?.forEach { dir -> File(dir, TRASH_MARKER).delete() }
        }
        return finished
    }

    private fun freeTrashKey(id: String): String {
        if (!File(trashDir, id).exists()) return id
        var n = 2
        while (File(trashDir, "$id$TRASH_KEY_SEP$n").exists()) n++
        return "$id$TRASH_KEY_SEP$n"
    }

    private fun freeLibraryId(): String {
        while (true) {
            val id = UUID.randomUUID().toString().take(8)
            if (!File(docsDir, id).exists() && !File(trashDir, id).exists()) return id
        }
    }

    private fun isSafeId(id: String): Boolean =
        id.isNotEmpty() && id.length <= 64 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    /** Unlocked previews built for a document must not outlive it in the library. */
    private fun dropDerivedCaches(id: String) {
        try {
            File(context.cacheDir, "preview_$id.pdf").delete()
            File(File(context.cacheDir, "documents"), "preview_$id.pdf").delete()
        } catch (_: Exception) {
        }
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
            if (loadDocument(src) == null) throw IllegalStateException("Scan not found")
            val name = suggestedName?.trim()?.replace('\n', ' ')?.takeIf { it.isNotBlank() }
                ?: nextDocumentName()
            // A copy keeps its original category when it has one.
            val inherited = File(src, "meta.txt").readLines().getOrNull(2)
                ?.takeIf { CategoryRules.isValid(it) }
            val files = src.listFiles { f -> f.isFile && !f.name.startsWith(".") }.orEmpty()
            buildAndPublish(name, inherited, files.sumOf { it.length() }, copyPdf = true) { s ->
                files.forEachIndexed { i, f ->
                    currentCoroutineContext().ensureActive()
                    if (f.name == "meta.txt") {
                        // T4.8: exported copies belong to the original — a duplicate starts with none.
                        val kept = f.readLines().filterNot {
                            it.startsWith(EXPORTS_KEY) || it.startsWith(EXPORT_SIGS_KEY) ||
                                it.startsWith(EXPORT_MADE_KEY)
                        }
                        Durable.writeFile(
                            File(s, "meta.txt"),
                            (kept.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8)
                        )
                    } else {
                        Durable.copyFile(f, File(s, f.name))
                    }
                    OpFaults.at("op:page:$i")
                }
            }
        }

    /**
     * test26 T5.2 (§4.5): Extract — saves the given pages as a brand-new document (new dir, pages
     * renumbered, PDF generated from the subset). The original is untouched and keeps its exported
     * copies; the category is inherited, like [duplicateDocument].
     */
    suspend fun extractPages(id: String, pageIndices: List<Int>): ScanDocument =
        withContext(Dispatchers.IO) {
            val doc = loadDocument(File(docsDir, id))
                ?: throw IllegalStateException("Scan not found")
            refuseLockedSources(listOf(doc))
            val indices = pageIndices.distinct().sorted()
            require(indices.isNotEmpty()) { "No pages selected" }
            require(indices.all { it in doc.pageFiles.indices }) { "No such page" }

            val first = indices.first() + 1
            val last = indices.last() + 1
            val name = if (first == last) "${doc.name} (page $first)"
            else "${doc.name} (pages $first–$last)"
            val chosen = indices.map { doc.pageFiles[it] }
            buildAndPublish(name, doc.category, chosen.sumOf { it.length() }) { staging ->
                chosen.forEachIndexed { i, f -> stagePageCopy(f, staging, i) }
            }
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
        refuseLockedSources(sources)

        val pages = sources.flatMap { d -> d.pageFiles.filter { it.exists() && it.length() > 0 } }
        if (pages.isEmpty()) throw IllegalStateException("No pages to merge")
        val name = suggestedName?.trim()?.replace('\n', ' ')?.takeIf { it.isNotBlank() }
            ?: "Merged scan ${SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date())}"
        buildAndPublish(name, null, pages.sumOf { it.length() }) { staging ->
            pages.forEachIndexed { i, p -> stagePageCopy(p, staging, i) }
        }
    }

    /**
     * Merges device PDFs (content Uris) in the given order. Pages are rendered
     * and written to staging one at a time — no page-byte list is ever held in
     * memory, so merging many or large PDFs cannot OOM.
     */
    suspend fun mergePdfUris(uris: List<Uri>, suggestedName: String?): ScanDocument =
        withContext(Dispatchers.IO) {
            require(uris.size >= 2) { "Pick at least 2 PDFs" }
            val name = suggestedName ?: defaultMergedName("Merged PDF")
            buildAndPublish(name, null, 0L) { staging ->
                var pageNum = 0
                for (uri in uris) {
                    // SAF PDFs are often pipes; the renderer needs a seekable fd.
                    pageNum += PdfPageRenderer.withSeekablePdf(context, uri) { fd ->
                        appendPdfPages(fd, staging, pageNum, dpi = 150f, maxDim = 2048, quality = 85)
                    }
                }
                if (pageNum == 0) throw IllegalStateException("No pages to merge")
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
            Durable.writeFile(File(dir, String.format(Locale.ROOT, PAGE_FORMAT, start + i)), jpeg)
            OpFaults.at("op:page:${start + i}")
        }
        return count
    }

    suspend fun deletePage(id: String, pageIndex: Int): ScanDocument =
        withContext(Dispatchers.IO) {
            val dir = File(docsDir, id)
            requireEditable(dir)
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
            requireEditable(dir)
            OcrStore.locked {
                val doc = loadDocument(dir) ?: throw IllegalStateException("Scan not found")
                require(pageIndex in doc.pageFiles.indices) { "No such page" }
                val file = doc.pageFiles[pageIndex]
                // M08 P0: the page and its kept original turn together or not at all;
                // a failure leaves both, and the committed PDF, byte-identical.
                editPageAndOriginal(file) { target -> ImageTools.stageRotate90(target, clockwise) }
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
            requireEditable(dir)
            OcrStore.locked {
                val doc = loadDocument(dir) ?: throw IllegalStateException("Scan not found")
                require(pageIndex in doc.pageFiles.indices) { "No such page" }
                val file = doc.pageFiles[pageIndex]
                editPageAndOriginal(file) { target -> ImageTools.stageCrop(target, rect) }
                ocrStore.invalidate(id, file.name)
            }
            regeneratePdf(dir)
            loadDocument(dir) ?: throw IllegalStateException("Scan vanished after crop")
        }

    /**
     * Stages [edit] for [page] and, when present, its kept original; only when every
     * staged copy verifies are they swapped in together ([PageReplace.commit]).
     */
    private fun editPageAndOriginal(page: File, edit: (File) -> PageReplace.Staged) {
        val orig = origOf(page).takeIf { it.exists() }
        val staged = try {
            PageReplace.stageAll(listOfNotNull({ edit(page) }, orig?.let { o -> { edit(o) } }))
        } catch (e: Exception) {
            if (orig != null && PageReplace.bounds(orig) == null) {
                throw IllegalStateException(
                    "This page's kept original can't be read, so nothing was changed.", e
                )
            }
            throw e
        }
        PageReplace.commit(staged)
    }

    suspend fun movePage(id: String, fromIndex: Int, toIndex: Int): ScanDocument =
        withContext(Dispatchers.IO) {
            val dir = File(docsDir, id)
            requireEditable(dir)
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
            requireEditable(dir)
            val doc = loadDocument(dir) ?: throw IllegalStateException("Scan not found")
            require(pageIndex in doc.pageFiles.indices) { "No such page" }
            val file = doc.pageFiles[pageIndex]
            val orig = origOf(file)
            // M08 P0: never truncate the page in place. Every new version is staged,
            // fsynced and verified first; the original is removed only after the
            // restored page has been swapped in.
            if (filter == ScanFilter.ORIGINAL) {
                if (orig.exists()) {
                    PageReplace.commit(PageReplace.stageCopy(file, orig))
                    orig.delete()
                }
            } else {
                if (!orig.exists()) PageReplace.commit(PageReplace.stageCopy(orig, file))
                val src = BitmapFactory.decodeFile(orig.path)
                    ?: throw IllegalStateException("This page's kept original can't be read, so nothing was changed.")
                val out = filter.applyTo(src)
                try {
                    PageReplace.commit(PageReplace.stageJpeg(file, out, 92))
                } finally {
                    if (out !== src) out.recycle()
                    src.recycle()
                }
            }
            regeneratePdf(dir)
            loadDocument(dir) ?: throw IllegalStateException("Scan vanished after filter")
        }

    /**
     * PM-2.1b: the one publish protocol for every tool output. [build] writes page files (and, with
     * [copyPdf], a verified `scan.pdf`) into a private staging folder; only a fully verified folder is
     * renamed into `documents/` as one new id. Any `Exception` releases the staging folder, so a
     * failure, cancel or crash never leaves a visible partial document.
     */
    private suspend fun buildAndPublish(
        name: String,
        category: String?,
        expectedBytes: Long,
        copyPdf: Boolean = false,
        build: suspend (File) -> Unit
    ): ScanDocument {
        if (expectedBytes > 0 && usableSpace(docsDir) < expectedBytes * 2 + (16L shl 20)) {
            throw IOException(context.getString(R.string.op_no_space))
        }
        val staging = OpStaging.create(context.filesDir)
        OpFaults.at("op:staged")
        var id: String
        try {
            build(staging)
            currentCoroutineContext().ensureActive()

            val pages = pageFilesIn(staging)
            val stageOcr = OcrStore(OpStaging.root(context.filesDir))
            if (copyPdf) {
                // The build copied a PDF it owns; verify the copy, never regenerate it.
                val pdf = File(staging, "scan.pdf")
                if (!pdf.isFile || pdf.length() <= 0L) throw IOException("Copy of the PDF is missing")
                if (pages.isNotEmpty()) {
                    pages.forEachIndexed { i, f ->
                        if (f.name != String.format(Locale.ROOT, PAGE_FORMAT, i)) {
                            throw IOException("Page order check failed")
                        }
                    }
                    if (PdfProtection.isEncrypted(pdf) == true) {
                        if (!File(staging, "locked.meta").exists()) {
                            throw IOException("Protection record missing")
                        }
                    } else {
                        PdfVerifier.verify(pdf, expectedPages = pages.size)
                    }
                }
                OpFaults.at("op:pdf")
            } else {
                if (pages.isEmpty()) throw IOException("No pages to publish")
                pages.forEachIndexed { i, f ->
                    if (f.name != String.format(Locale.ROOT, PAGE_FORMAT, i)) {
                        throw IOException("Page order check failed")
                    }
                }
                val geo = if (Prefs.makeSearchable(context)) stageOcr.readGeo(staging.name) else emptyMap()
                val layered = writePdfInto(staging, File(staging, "scan.pdf"), geo)
                if (layered && pages.all { geo.containsKey(it.name) }) {
                    Durable.writeFile(File(staging, "text_layer.flag"), ByteArray(0))
                }
                OpFaults.at("op:pdf")
            }

            writeMeta(staging, name, category)
            OpFaults.at("op:meta")

            // I10: the new document is either fully indexed or carries ocr_pending. The flag is
            // written directly because OcrStore.markPending refuses non-library folders.
            val texts = stageOcr.readText(staging.name)
            if (pages.any { it.name !in texts }) {
                Durable.writeFile(File(staging, "ocr_pending"), ByteArray(0))
            }

            if (!copyPdf) PdfVerifier.verify(File(staging, "scan.pdf"), expectedImages = pages)
            val meta = File(staging, "meta.txt").readLines()
            if (meta.size < 3 || meta[0].isBlank() || meta[1].toLongOrNull() == null) {
                throw IOException("Metadata check failed")
            }
            OpFaults.at("op:verified")
            Durable.syncDir(staging)

            id = synchronized(TRASH_LOCK) {
                val newId = freeLibraryId()
                OpFaults.at("op:before_rename")
                if (!staging.renameTo(File(docsDir, newId))) {
                    throw IOException(context.getString(R.string.op_publish_failed))
                }
                newId
            }
            OpStaging.forget(staging)
            OpFaults.at("op:after_rename")
        } catch (e: Exception) {
            OpStaging.release(staging)
            throw e
        }
        Durable.syncDir(docsDir)
        Durable.syncDir(OpStaging.root(context.filesDir))
        return loadDocument(File(docsDir, id))
            ?: throw IllegalStateException("Published document could not be opened")
    }

    /** Copies one source page into [staging] as `page_%03d.jpg`, fsynced, with a cancel check. */
    private suspend fun stagePageCopy(src: File, staging: File, index: Int): File {
        currentCoroutineContext().ensureActive()
        val out = File(staging, String.format(Locale.ROOT, PAGE_FORMAT, index))
        Durable.copyFile(src, out)
        OpFaults.at("op:page:$index")
        return out
    }

    /** Writes one page's bytes into [staging] as `page_%03d.jpg`, fsynced, with a cancel check. */
    private suspend fun stagePageBytes(bytes: ByteArray, staging: File, index: Int): File {
        currentCoroutineContext().ensureActive()
        val out = File(staging, String.format(Locale.ROOT, PAGE_FORMAT, index))
        Durable.writeFile(out, bytes)
        OpFaults.at("op:page:$index")
        return out
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
        pageBytes.forEachIndexed { index, bytes ->
            require(bytes.isNotEmpty()) { "Page ${index + 1} is empty" }
        }
        val docName = name?.trim()?.replace('\n', ' ')?.takeIf { it.isNotBlank() } ?: nextDocumentName()
        buildAndPublish(docName, null, pageBytes.sumOf { it.size.toLong() }) { staging ->
            pageBytes.forEachIndexed { i, bytes -> stagePageBytes(bytes, staging, i) }
        }
    }

    /** Copies a scan with pages re-encoded at [quality] (optionally downscaled to [maxDim]). */
    suspend fun compressScan(
        id: String,
        quality: Int,
        maxDim: Int?
    ): ScanDocument = withContext(Dispatchers.IO) {
        val doc = loadDocument(File(docsDir, id)) ?: throw IllegalStateException("Scan not found")
        refuseLockedSources(listOf(doc))
        buildAndPublish("${doc.name} (compressed)", null, doc.pageFiles.sumOf { it.length() }) { staging ->
            // One page's bytes are alive at a time; the source list is never materialised.
            doc.pageFiles.forEachIndexed { i, file ->
                stagePageBytes(ImageTools.recompressJpeg(file.readBytes(), quality, maxDim), staging, i)
            }
        }
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
    ): ScanDocument = importPdf(uri, name, dpi, maxDim, quality, vaulted = false)

    /**
     * M07 WP-06: the one PDF import path.
     *
     *  - The source is only ever opened for reading, copied once (bounded by
     *    [PdfImport.MAX_BYTES]) so pipe-backed providers work, and the copy is removed.
     *  - Every page is rendered into a private staging folder outside `documents/`,
     *    with one renderer for the whole file. Any page that fails fails the import:
     *    no document with silently missing pages is ever published.
     *  - The finished folder (pages, PDF, metadata, and the Vault flag when asked)
     *    is published with one atomic rename. Until then nothing is visible, so a
     *    failure, cancellation or crash leaves no phantom document; leftovers are
     *    removed by [recoverImports].
     *  - Failures are [PdfImport.ImportException]s with a [PdfImport.Reason] the UI
     *    can explain (password, damaged, too large, no space, no access…).
     *  - The new document is marked for text recognition; the caller queues it.
     */
    private suspend fun importPdf(
        uri: Uri,
        name: String?,
        dpi: Float,
        maxDim: Int,
        quality: Int,
        vaulted: Boolean
    ): ScanDocument = withContext(Dispatchers.IO) {
        val staging = File(importStagingDir, UUID.randomUUID().toString().take(12))
        if (!staging.mkdirs()) throw PdfImport.ImportException(PdfImport.Reason.SAVE_FAILED, "Could not prepare the import")
        try {
            PdfImport.withSeekableCopy(context, uri) { fd, sourceBytes ->
                importFault(ImportStage.COPIED)
                PdfImport.renderAll(fd, sourceBytes, staging, usableSpace(docsDir), dpi, maxDim, quality) {
                    importFault(ImportStage.PAGE_RENDERED)
                }
            }
            currentCoroutineContext().ensureActive()
            val pdfFile = File(staging, "scan.pdf")
            try {
                writePdf(staging, pdfFile)
            } catch (e: Exception) {
                throw PdfImport.classifyWrite(e)
            }
            if (!pdfFile.isFile || pdfFile.length() == 0L) {
                throw PdfImport.ImportException(PdfImport.Reason.NO_SPACE, "Could not write the document")
            }
            val title = name?.trim()?.replace('\n', ' ')?.takeIf { it.isNotBlank() } ?: nextDocumentName()
            try {
                writeMeta(staging, title)
                if (vaulted) Durable.atomicWriteText(File(staging, VAULT_FILE), "1")
                File(staging, "ocr_pending").writeText("")
            } catch (e: Exception) {
                throw PdfImport.classifyWrite(e)
            }
            importFault(ImportStage.BEFORE_PUBLISH)
            currentCoroutineContext().ensureActive()
            val id = synchronized(TRASH_LOCK) {
                val id = freeLibraryId()
                if (!staging.renameTo(File(docsDir, id))) {
                    throw PdfImport.ImportException(PdfImport.Reason.SAVE_FAILED, "Could not add the document")
                }
                id
            }
            Durable.syncDir(docsDir)
            loadDocument(File(docsDir, id))
                ?: throw PdfImport.ImportException(PdfImport.Reason.DAMAGED, "The imported document could not be opened")
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    /** Private staging for imports in progress (same filesystem as `documents/`). */
    private val importStagingDir: File
        get() = File(context.filesDir, "import_staging").apply { mkdirs() }

    /**
     * Startup cleanup: an import staging folder only exists while an import runs, so
     * one untouched for [STALE_IMPORT_MS] belongs to an import that died (crash,
     * power loss). It was never visible. Same for leftover seekable PDF copies.
     */
    fun recoverImports(now: Long = System.currentTimeMillis()): Int {
        var removed = 0
        val cutoff = now - STALE_IMPORT_MS
        File(context.filesDir, "import_staging").listFiles()?.forEach {
            if (it.lastModified() < cutoff && it.deleteRecursively()) removed++
        }
        File(context.cacheDir, "pdf_fd").listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
        return removed
    }

    /**
     * Imports a device PDF straight into the Hidden Vault (Pro). The Vault flag is
     * written before the document is published, so it is never visible in Home.
     */
    suspend fun importPdfIntoVault(
        uri: Uri,
        name: String?,
        dpi: Float = 150f,
        maxDim: Int = 2048,
        quality: Int = 85
    ): ScanDocument = importPdf(uri, name, dpi, maxDim, quality, vaulted = true)

    private val LOCK_FILE = PdfProtection.LOCK_FILE
    private val VAULT_FILE = "vault.flag"

    /** T4.8: prefix of the optional meta.txt line listing exported copies. */
    private val EXPORTS_KEY = "exports="

    /** M06: `exportsigs=<uri> <size>:<sha256>|…` fingerprints of recorded copies. */
    private val EXPORT_SIGS_KEY = "exportsigs="

    /** M07: `exportmade=<uri> <size>:<sha256> <hmac>|…` signed proof this document created a copy. */
    private val EXPORT_MADE_KEY = "exportmade="
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
            if (!PdfProtection.isEncodable(password)) {
                throw IllegalArgumentException(context.getString(R.string.password_charset))
            }
            val dir = File(docsDir, id)
            val doc = loadDocument(dir) ?: throw IllegalStateException("Scan not found")
            if (doc.isLocked) throw IllegalStateException("Already protected; use Change password")
            encryptInto(dir, doc, password, "protect")
            recordLockIndex(File(dir, PdfProtection.PDF_FILE), id)
            loadDocument(dir)!!
        }

    /**
     * Builds the encrypted PDF in a private [OpStaging] folder and swaps it in by rename (never a copy
     * onto `scan.pdf`; no plaintext temporary ever sits in the document folder). Order is the contract:
     *
     *  1. plain PDF and encrypted PDF are built and verified in staging;
     *  2. `locked.meta` is written (fsynced, atomic);
     *  3. the encrypted PDF is renamed over `scan.pdf`.
     *
     * A crash between 2 and 3 leaves `locked.meta` beside the previous PDF: detectable and honest
     * (PM-3.1c). The opposite order would leave an encrypted PDF the app does not know is locked.
     * [op] names the fault points (`<op>:encrypted`, `<op>:lock_written`). Cleanup catches
     * [Exception] only, so a simulated (or real) process death skips it and [recoverOperations] sweeps.
     */
    private fun encryptInto(dir: File, doc: ScanDocument, password: String, op: String) =
        synchronized(PDF_LOCK) {
            val work = OpStaging.create(context.filesDir)
            try {
                val plain = File(work, "plain.pdf")
                if (doc.pageFiles.isNotEmpty()) {
                    writePdf(dir, plain)
                } else {
                    require(doc.pdfFile.exists() && doc.pdfFile.length() > 0L) { "PDF missing" }
                    Durable.copyFile(doc.pdfFile, plain)
                }
                val enc = File(work, "enc.pdf")
                PdfEncryptor.encrypt(plain, enc, password)
                Durable.syncFile(enc)
                if (PdfProtection.isEncrypted(enc) != true) throw IOException("Protection could not be verified")
                OpFaults.at("$op:encrypted")
                val lock = File(dir, LOCK_FILE)
                val previousLock = if (lock.exists()) lock.readText() else null
                Durable.atomicWriteText(lock, Prefs.sha256("pdf_lock:$password"))
                OpFaults.at("$op:lock_written")
                if (!enc.renameTo(File(dir, PdfProtection.PDF_FILE))) {
                    // Put the previous lock record back (protect: none, so the file is removed).
                    if (previousLock == null) lock.delete() else Durable.atomicWriteText(lock, previousLock)
                    throw IOException("Could not protect the PDF")
                }
                Durable.syncDir(dir)
                OpStaging.release(work)
            } catch (e: Exception) {
                OpStaging.release(work)
                throw e
            }
        }

    /**
     * Removes password protection after checking the current [password]. The clean PDF is rebuilt from
     * the stored page JPEGs in [OpStaging] and swapped in by rename, then the lock record is deleted.
     */
    suspend fun unlockPdf(id: String, password: String): ScanDocument =
        withContext(Dispatchers.IO) {
            val dir = File(docsDir, id)
            val doc = loadDocument(dir) ?: throw IllegalStateException("Scan not found")
            if (!doc.isLocked) return@withContext doc
            if (!verifyPdfPassword(id, password)) {
                throw PdfProtection.WrongPassword(context.getString(R.string.unlock_pdf_wrong))
            }
            if (doc.pageFiles.isEmpty()) throw IllegalStateException(context.getString(R.string.locked_no_pages))
            synchronized(PDF_LOCK) {
                val work = OpStaging.create(context.filesDir)
                try {
                    val plain = File(work, "plain.pdf")
                    val layered = writePdf(dir, plain)
                    OpFaults.at("unlock:built")
                    if (!plain.renameTo(File(dir, PdfProtection.PDF_FILE))) {
                        throw IOException("Could not unlock the PDF")
                    }
                    OpFaults.at("unlock:swapped")
                    File(dir, LOCK_FILE).delete()
                    updateTextLayerFlag(dir, layered)
                    Durable.syncDir(dir)
                    OpStaging.release(work)
                } catch (e: Exception) {
                    OpStaging.release(work)
                    throw e
                }
            }
            pruneLockIndex()
            loadDocument(dir) ?: throw IllegalStateException("Scan vanished after unlock")
        }

    /**
     * Changes the password of a locked document after checking [current]. Same build-and-swap as
     * [protectPdf] (fault points `chpw:encrypted`, `chpw:lock_written`); `locked.meta` is overwritten
     * with the new hash.
     *
     * Known limitation: a crash between the lock write and the swap leaves the NEW hash beside the
     * old-password PDF. I3 still holds (both say locked), but only the old password opens the PDF.
     */
    suspend fun changePdfPassword(id: String, current: String, new: String): ScanDocument =
        withContext(Dispatchers.IO) {
            val dir = File(docsDir, id)
            val doc = loadDocument(dir) ?: throw IllegalStateException("Scan not found")
            if (!verifyPdfPassword(id, current)) {
                throw PdfProtection.WrongPassword(context.getString(R.string.unlock_pdf_wrong))
            }
            require(new.isNotBlank()) { "Password must not be empty" }
            if (!PdfProtection.isEncodable(new)) {
                throw IllegalArgumentException(context.getString(R.string.password_charset))
            }
            if (doc.pageFiles.isEmpty()) throw IllegalStateException(context.getString(R.string.locked_no_pages))
            encryptInto(dir, doc, new, "chpw")
            recordLockIndex(File(dir, PdfProtection.PDF_FILE), id)
            loadDocument(dir)!!
        }

    fun protectionState(id: String): PdfProtection.State = PdfProtection.state(File(docsDir, id))

    /**
     * PM-3.1c (A5): `locked.meta` sits beside a plain `scan.pdf`. With the right [password] the PDF is
     * encrypted again through the same build-and-swap as [protectPdf] and I3 holds. The RC4 key needs
     * the password, so this can never run without the user. A wrong password changes nothing.
     */
    suspend fun repairProtection(id: String, password: String): ScanDocument =
        withContext(Dispatchers.IO) {
            val dir = File(docsDir, id)
            check(PdfProtection.state(dir) == PdfProtection.State.NEEDS_REPAIR) { "Protection does not need repair" }
            if (!verifyPdfPassword(id, password)) {
                throw PdfProtection.WrongPassword(context.getString(R.string.unlock_pdf_wrong))
            }
            // A legacy lock made with a non-Latin password verifies (the hash is UTF-8) but the PDF
            // password is ISO-8859-1, so encrypting would lock the file with a password nobody can type.
            // Refuse; the user can choose Remove lock instead.
            if (!PdfProtection.isEncodable(password)) {
                throw IllegalArgumentException(context.getString(R.string.password_charset))
            }
            val doc = loadDocument(dir) ?: throw IllegalStateException("Scan not found")
            encryptInto(dir, doc, password, "repair")
            recordLockIndex(File(dir, PdfProtection.PDF_FILE), id)
            loadDocument(dir) ?: throw IllegalStateException("Scan vanished after repair")
        }

    /**
     * PM-3.1c: the user chose to drop a lock label that has no protected PDF behind it. Only
     * `locked.meta` is deleted, and only after the right [password].
     */
    suspend fun removeProtectionLabel(id: String, password: String): ScanDocument =
        withContext(Dispatchers.IO) {
            val dir = File(docsDir, id)
            check(PdfProtection.state(dir) == PdfProtection.State.NEEDS_REPAIR) { "Protection does not need repair" }
            if (!verifyPdfPassword(id, password)) {
                throw PdfProtection.WrongPassword(context.getString(R.string.unlock_pdf_wrong))
            }
            synchronized(PDF_LOCK) {
                File(dir, LOCK_FILE).delete()
                Durable.syncDir(dir)
            }
            pruneLockIndex()
            loadDocument(dir) ?: throw IllegalStateException("Scan not found")
        }

    /**
     * PM-3.1c: the PDF is encrypted with a password nobody here knows and there is no `locked.meta`.
     * Rebuilds a plain PDF from the stored pages. Allowed because no lock record exists, so the
     * generator's choke does not refuse; the swap is atomic.
     */
    suspend fun rebuildPlainPdf(id: String): ScanDocument =
        withContext(Dispatchers.IO) {
            val dir = File(docsDir, id)
            check(PdfProtection.state(dir) == PdfProtection.State.UNKNOWN_PASSWORD) { "PDF is not an unknown-password PDF" }
            val doc = loadDocument(dir) ?: throw IllegalStateException("Scan not found")
            if (doc.pageFiles.isEmpty()) throw IllegalStateException(context.getString(R.string.locked_no_pages))
            regeneratePdf(dir)
            loadDocument(dir) ?: throw IllegalStateException("Scan vanished after rebuild")
        }

    /** Startup recovery for [OpStaging]: removes working folders left by a dead process. Returns the count. */
    fun recoverOperations(): Int = OpStaging.sweep(context.filesDir)

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
        requireUnlocked(dir) // A3 backstop: a locked document's PDF is never rebuilt in plaintext
        val pdfFile = File(dir, "scan.pdf")
        val pages = pageFilesIn(dir)
        val layered = if (pages.isNotEmpty()) writePdf(dir, pdfFile) else false
        updateTextLayerFlag(dir, layered)
    }

    /** `text_layer.flag` exists exactly when every page of [dir] received an invisible text layer. */
    private fun updateTextLayerFlag(dir: File, layered: Boolean) {
        val id = dir.name
        val pages = pageFilesIn(dir)
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

    /** A3: refuses any rebuild of a locked document's PDF. */
    private fun requireUnlocked(dir: File) {
        if (File(dir, LOCK_FILE).exists()) {
            throw PdfProtection.DocumentLocked(context.getString(R.string.locked_edit_refused))
        }
    }

    /**
     * A3: refuses page edits on a locked document. Today the same check as [requireUnlocked]; two names
     * on purpose, so PM-2.2b can add the draft-lease check here only (a PDF refresh after OCR does not
     * change pages and must stay allowed while a draft is open).
     */
    private fun requireEditable(dir: File) = requireUnlocked(dir)

    /** F13: tools cannot use a locked source yet (their output would be an unprotected copy). */
    private fun refuseLockedSources(sources: List<ScanDocument>) {
        sources.firstOrNull { it.isLocked }?.let { src ->
            throw PdfProtection.DocumentLocked(context.getString(R.string.locked_source_refused, src.name))
        }
    }

    /**
     * Writes [out] from [dir]'s page files, with an invisible text layer built from the OCR
     * geometry in [geo] when `Prefs.makeSearchable` is on. Returns true when at least one page
     * received a layer. Atomic via `.tmp` + rename inside the generator.
     */
    private fun writePdfInto(dir: File, out: File, geo: Map<String, OcrPage>): Boolean = synchronized(PDF_LOCK) {
        val pages = pageFilesIn(dir)
        if (pages.isEmpty()) {
            false
        } else {
            val textLayers = if (Prefs.makeSearchable(context)) {
                pages.indices.filter { geo.containsKey("page_%03d.jpg".format(it)) }
                    .associate { i -> i to geo["page_%03d.jpg".format(i)]!! }
            } else {
                emptyMap()
            }
            PdfGenerator.imagesToPdf(pages, out, textLayers).isNotEmpty()
        }
    }

    /** Writes [out] from [dir]'s page files, reading the document's own OCR geometry. */
    private fun writePdf(dir: File, out: File): Boolean = writePdfInto(
        dir,
        out,
        if (Prefs.makeSearchable(context)) ocrStore.readGeo(dir.name) else emptyMap()
    )

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
        // M07: Trash counts too, so restoring an item never duplicates a fresh default name.
        val docDirs = (docsDir.listFiles { f -> f.isDirectory && !SaveFormat.isIncomplete(f) }.orEmpty().asList() +
            trashDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty().asList())
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

    // ------------------------------------------------------------------
    // L4 tools: split, convert, clean (all publish through buildAndPublish)
    // ------------------------------------------------------------------

    /** The image formats "PDF to images" can write. */
    enum class ImageExportFormat { JPG, PNG }

    /**
     * Splits a document into one new document per range (PM-2.1c publish protocol). Each output
     * keeps the category; a failure publishes nothing for the part that failed.
     */
    suspend fun splitByRange(id: String, ranges: List<PageRanges.Span>): List<ScanDocument> =
        withContext(Dispatchers.IO) {
            val doc = loadDocument(File(docsDir, id)) ?: throw IllegalStateException("Scan not found")
            refuseLockedSources(listOf(doc))
            require(ranges.isNotEmpty()) { "No page ranges" }
            ranges.forEach {
                require(it.from in 1..doc.pageFiles.size && it.to <= doc.pageFiles.size) { "No such page" }
            }
            ranges.map { span ->
                val chosen = span.indices().map { doc.pageFiles[it] }
                val name = "${doc.name} (${PageRanges.label(span)})"
                buildAndPublish(name, doc.category, chosen.sumOf { it.length() }) { staging ->
                    chosen.forEachIndexed { i, f -> stagePageCopy(f, staging, i) }
                }
            }
        }

    /**
     * Exports a document's pages as images: one JPG/PNG when there is a single page and [zip] is
     * false, otherwise one ZIP whose entries are written one page at a time (no page list in
     * memory). [quality] 100 keeps the stored JPEG untouched; lower values re-encode it.
     * [pageIndices] selects pages (0-based); null exports every page.
     * [outFile] is written atomically and returned.
     */
    suspend fun pdfToImages(
        id: String,
        format: ImageExportFormat,
        zip: Boolean,
        outFile: File,
        quality: Int = 100,
        pageIndices: List<Int>? = null
    ): File = withContext(Dispatchers.IO) {
        val doc = loadDocument(File(docsDir, id)) ?: throw IllegalStateException("Scan not found")
        refuseLockedSources(listOf(doc))
        require(doc.pageFiles.isNotEmpty()) { "This document has no pages" }
        val chosen = pageIndices?.distinct()?.sorted() ?: doc.pageFiles.indices.toList()
        require(chosen.isNotEmpty()) { "Choose at least one page" }
        require(chosen.all { it in doc.pageFiles.indices }) { "No such page" }
        val asZip = zip || chosen.size > 1
        val tmp = File(outFile.parentFile, outFile.name + ".part")
        tmp.parentFile?.mkdirs()
        try {
            if (!asZip) {
                Durable.writeFile(tmp, pageImageBytes(doc.pageFiles[chosen.first()], format, quality))
            } else {
                FileOutputStream(tmp).use { raw ->
                    java.util.zip.ZipOutputStream(raw).use { archive ->
                        chosen.forEach { index ->
                            currentCoroutineContext().ensureActive()
                            val extension = if (format == ImageExportFormat.JPG) "jpg" else "png"
                            archive.putNextEntry(
                                java.util.zip.ZipEntry(
                                    String.format(Locale.ROOT, "page_%03d.%s", index + 1, extension)
                                )
                            )
                            archive.write(pageImageBytes(doc.pageFiles[index], format, quality))
                            archive.closeEntry()
                        }
                    }
                }
                Durable.syncFile(tmp)
            }
            if (!tmp.renameTo(outFile)) throw IOException("Could not save the images")
            outFile
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    private fun pageImageBytes(page: File, format: ImageExportFormat, quality: Int): ByteArray =
        if (format == ImageExportFormat.JPG) {
            val bytes = page.readBytes()
            if (quality >= 100) bytes else ImageTools.recompressJpeg(bytes, quality, null)
        } else {
            val bitmap = BitmapFactory.decodeFile(page.path)
                ?: throw IllegalStateException("Page ${page.name} can't be read")
            try {
                ImageTools.encodePng(bitmap)
            } finally {
                bitmap.recycle()
            }
        }

    /**
     * Stitches the document's pages into one or more long JPEGs (E20: 1080 px wide, at most
     * [LongImage.MAX_PART_HEIGHT] px tall per part, aspect ratio kept, never squashed). Parts are
     * written one at a time to [outDir]; a failure deletes whatever this call already wrote.
     * @return the written files, in page order.
     */
    suspend fun exportLongImage(
        id: String,
        outDir: File,
        baseName: String? = null
    ): List<File> = withContext(Dispatchers.IO) {
        val doc = loadDocument(File(docsDir, id)) ?: throw IllegalStateException("Scan not found")
        refuseLockedSources(listOf(doc))
        require(doc.pageFiles.isNotEmpty()) { "This document has no pages" }
        if (!outDir.exists() && !outDir.mkdirs()) throw IOException("Could not prepare the images")
        val base = ShareUtils.sanitizeName(
            baseName?.trim()?.takeIf { it.isNotBlank() } ?: doc.name
        ).substringBeforeLast('.').ifBlank { "Scan" }
        val written = mutableListOf<File>()
        val context = currentCoroutineContext()
        try {
            var total = 0
            LongImage.stitchEach(doc.pageFiles) { index, count, bytes ->
                context.ensureActive()
                total = count
                val name = if (count == 1) "$base - long.jpg" else "$base - long ${index + 1} of $count.jpg"
                val out = File(outDir, name)
                val tmp = File(outDir, name + ".part")
                try {
                    Durable.writeFile(tmp, bytes)
                    if (!tmp.renameTo(out)) throw IOException("Could not save $name")
                } catch (e: Exception) {
                    tmp.delete()
                    throw e
                }
                written += out
            }
            check(written.size == total) { "The long image was not written completely" }
            written
        } catch (e: Exception) {
            written.forEach { it.delete() }
            throw e
        }
    }

    /**
     * Writes a `.docx` from the document's recognised text (one paragraph per OCR line, page
     * break between pages). Never rewrites the document itself.
     */
    suspend fun exportWord(id: String, outFile: File): File = withContext(Dispatchers.IO) {
        val doc = loadDocument(File(docsDir, id)) ?: throw IllegalStateException("Scan not found")
        refuseLockedSources(listOf(doc))
        val text = ocrStore.readText(id)
        require(text.isNotEmpty()) { "Read the text first, then convert it to Word." }
        val pages = doc.pageFiles.map { page ->
            (text[page.name] ?: "").lines().dropLastWhile { it.isBlank() }
        }
        require(pages.any { lines -> lines.any { it.isNotBlank() } }) {
            "Read the text first, then convert it to Word."
        }
        DocxWriter.write(outFile, pages)
        outFile
    }

    /**
     * Creates a metadata-free copy: every page is decoded and re-encoded, which drops EXIF,
     * maker notes and embedded thumbnails, and the PDF is regenerated by the generator (which
     * writes no document information). The original is untouched.
     */
    suspend fun removeMetadata(id: String): ScanDocument = withContext(Dispatchers.IO) {
        val doc = loadDocument(File(docsDir, id)) ?: throw IllegalStateException("Scan not found")
        refuseLockedSources(listOf(doc))
        require(doc.pageFiles.isNotEmpty()) { "This document has no pages" }
        buildAndPublish("${doc.name} (clean)", doc.category, doc.pageFiles.sumOf { it.length() }) { staging ->
            doc.pageFiles.forEachIndexed { i, page ->
                val bitmap = BitmapFactory.decodeFile(page.path)
                    ?: throw IllegalStateException("Page ${page.name} can't be read")
                val bytes = try {
                    ImageTools.encodeJpeg(bitmap, 92)
                } finally {
                    bitmap.recycle()
                }
                stagePageBytes(bytes, staging, i)
            }
        }
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
