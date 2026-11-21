package com.scanwhirl.app.store

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.core.net.toUri
import com.scanwhirl.app.R
import com.scanwhirl.app.model.OcrLine
import com.scanwhirl.app.model.OcrPage
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.OcrStore
import com.scanwhirl.app.tools.SizeFit
import com.scanwhirl.app.util.CategoryRules
import com.scanwhirl.app.util.DocxWriter
import com.scanwhirl.app.util.DownloadSaver
import com.scanwhirl.app.util.Durable
import com.scanwhirl.app.util.ExportNames
import com.scanwhirl.app.util.ImageTools
import com.scanwhirl.app.util.LongImage
import com.scanwhirl.app.util.OcrEngine
import com.scanwhirl.app.util.OcrBox
import com.scanwhirl.app.util.OutputNames
import com.scanwhirl.app.util.PageRanges
import com.scanwhirl.app.util.PageReplace
import com.scanwhirl.app.util.PageStamp
import com.scanwhirl.app.util.PdfImport
import com.scanwhirl.app.util.PdfDecryptor
import com.scanwhirl.app.util.PdfImportBudget
import com.scanwhirl.app.util.PdfEncryptor
import com.scanwhirl.app.util.PdfGenerator
import com.scanwhirl.app.util.PdfPageRenderer
import com.scanwhirl.app.util.PdfProtection
import com.scanwhirl.app.util.PdfVerifier
import com.scanwhirl.app.util.PptxWriter
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.ReadingOrder
import com.scanwhirl.app.util.ScanFilter
import com.scanwhirl.app.util.Sha256
import com.scanwhirl.app.util.TableDetect
import com.scanwhirl.app.util.XlsxWriter
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
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class ScanRepository(private val context: Context) {

    internal val docsDir: File
        get() = File(context.filesDir, "documents").apply { mkdirs() }

    internal val ocrStore: OcrStore
        get() = OcrStore(docsDir)

    companion object {
        /** Process-wide lock for PDF regeneration - several ScanRepository instances exist at once. */
        val PDF_LOCK = Any()

        /**
         * Test seam (performance audit L1-1): called with the reading thread's name
         * at the start of every library listing, so a test can prove no disk read
         * happens on the main thread. Production leaves it null.
         */
        @Volatile
        internal var readProbe: ((thread: String) -> Unit)? = null

        /**
         * Serialises new-scan save transactions and the abandoned-save reaper, so a
         * cleanup can never race an active transaction (several ScanRepository
         * instances exist at once, hence process-wide).
         */
        private val SAVE_LOCK = Mutex()

        /** Serialises metadata read-modify-write cycles (no lost updates between instances). */
        private val META_LOCK = Any()

        /** PM-2.2b: serialises every draft read/write and prune across repository instances. */
        private val DRAFT_LOCK = Any()

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

        /**
         * The in-memory decrypt step of a protected-PDF import. Replaceable in tests so the
         * heap-exhaustion path can be exercised without allocating 100 MB in the JVM.
         */
        @Volatile internal var decryptPdf: (ByteArray, String) -> ByteArray = PdfDecryptor::decrypt

        /**
         * Test seam (task S4B): called on the thread that runs the PDF-lock PBKDF2, so a test can
         * prove the 1 300 000-iteration derivation never happens on the main thread.
         */
        @Volatile internal var lockDerivationProbe: (() -> Unit)? = null

        internal fun resetTestSeams() {
            usableSpace = ::probeUsableSpace
            trashFault = {}
            importFault = {}
            decryptPdf = PdfDecryptor::decrypt
            lockDerivationProbe = null
        }

        /** An import staging folder untouched this long belongs to an import that died. */
        const val STALE_IMPORT_MS = 60L * 60 * 1000

        /** M07: serialises Trash moves, restores and purges across repository instances. */
        private val TRASH_LOCK = Any()

        /** Written into a Trash entry after its move: `v1 / original id / trashedAt`. */
        internal const val TRASH_MARKER = "trashed.txt"

        /**
         * How long a Trash entry stays restorable. The UI promises 30 days (`shell_trash_note`);
         * [recoverTrash] deletes an older entry for good on the next start.
         */
        internal const val TRASH_RETENTION_MS = 30L * 24 * 60 * 60 * 1000

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
        readProbe?.invoke(Thread.currentThread().name)
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
        val organised = LibraryMeta.read(lines)
        val pdf = File(dir, "scan.pdf")
        var totalSize = 0L
        val pages = dir.listFiles { f -> f.extension.equals("jpg", true) || f.extension.equals("jpeg", true) }
            ?.mapNotNull { f -> f.length().takeIf { it > 0 }?.let { f to it } }
            ?.sortedBy { it.first.nameWithoutExtension }
            ?.onEach { totalSize += it.second }
            ?.map { it.first }
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
            folder = organised.folder,
            tags = organised.tags,
            revision = revision,
            isSearchable = isSearchable,
            cachedSize = if (pdf.exists()) pdf.length() else totalSize,
            cachedModified = revision
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

    /**
     * Call only under TRASH_LOCK, held through the publish rename/marker unlink or rename edit.
     * That existing process-wide lock serialises names across scans, imports, tools and restores.
     * Legacy Vault items in documents/ reserve names (as outputName already does), so returning
     * an item cannot collide. The separate encrypted Vault is not read or unlocked for naming.
     * Trash and incomplete saves do not reserve names; they resolve afresh before publication.
     */
    private fun uniqueDocumentName(dir: File, proposed: String): String =
        DocumentNames.unique(proposed, docsDir.listFiles { f -> f.isDirectory }.orEmpty()
            .filter { it.absoluteFile != dir.absoluteFile }
            .mapNotNull { loadDocument(it)?.name })

    /** Preserve timestamps, category, exports and unknown metadata while resolving just the title. */
    private fun resolveNameBeforePublish(dir: File) {
        updateMeta(dir) { lines ->
            if (lines.isNotEmpty()) lines[0] = uniqueDocumentName(dir, lines[0])
        }
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

    // ------------------------------------------------------------------
    // Library organisation: folders and tags (v2 UI: 03_tools_hub)
    // ------------------------------------------------------------------

    private val foldersFile: File get() = File(context.filesDir, "library_folders.txt")

    /** Folder names created explicitly (empty folders survive a restart), sorted. */
    fun listFolders(): List<String> = synchronized(META_LOCK) {
        if (!foldersFile.exists()) return emptyList()
        runCatching { foldersFile.readLines() }.getOrDefault(emptyList())
            .mapNotNull { LibraryMeta.normalizeFolder(it) }
            .distinctBy { it.lowercase(Locale.getDefault()) }
            .sortedBy { it.lowercase(Locale.getDefault()) }
    }

    private fun writeFolders(names: List<String>) {
        Durable.atomicWriteText(
            foldersFile,
            names.joinToString("\n") + if (names.isEmpty()) "" else "\n"
        )
    }

    /** Creates an explicit (possibly empty) folder. False when blank or already there. */
    fun createFolder(name: String): Boolean {
        val clean = LibraryMeta.normalizeFolder(name) ?: return false
        synchronized(META_LOCK) {
            val all = listFolders()
            if (all.any { it.equals(clean, true) }) return false
            writeFolders(all + clean)
            return true
        }
    }

    /** Renames a folder and every document in it. Documents are never lost. */
    fun renameFolder(oldName: String, newName: String): Boolean {
        val clean = LibraryMeta.normalizeFolder(newName) ?: return false
        synchronized(META_LOCK) {
            val all = listFolders()
            val at = all.indexOfFirst { it.equals(oldName, true) }
            if (at < 0) return false
            if (all.any { it.equals(clean, true) && !it.equals(oldName, true) }) return false
            writeFolders(all.toMutableList().also { it[at] = clean })
            forEachDocumentDir { dir ->
                updateMeta(dir) { lines ->
                    val meta = LibraryMeta.read(lines)
                    if (meta.folder != null && meta.folder.equals(oldName, true)) {
                        LibraryMeta.write(lines, clean, meta.tags)
                    }
                }
            }
            return true
        }
    }

    /** Deletes a folder; its documents move to "All" and stay in the library. */
    fun deleteFolder(name: String) {
        synchronized(META_LOCK) {
            writeFolders(listFolders().filterNot { it.equals(name, true) })
            forEachDocumentDir { dir ->
                updateMeta(dir) { lines ->
                    val meta = LibraryMeta.read(lines)
                    if (meta.folder != null && meta.folder.equals(name, true)) {
                        LibraryMeta.write(lines, null, meta.tags)
                    }
                }
            }
        }
    }

    /** Moves documents to [folder] (null = "All"). A missing folder is created. */
    fun setFolder(ids: List<String>, folder: String?) {
        val clean = LibraryMeta.normalizeFolder(folder)
        if (clean != null) createFolder(clean)
        for (id in ids.distinct()) {
            updateMeta(File(docsDir, id)) { lines ->
                val meta = LibraryMeta.read(lines)
                LibraryMeta.write(lines, clean, meta.tags)
            }
        }
    }

    fun setTags(id: String, tags: List<String>) {
        updateMeta(File(docsDir, id)) { lines ->
            val meta = LibraryMeta.read(lines)
            LibraryMeta.write(lines, meta.folder, LibraryMeta.normalizeTags(tags))
        }
    }

    fun addTag(ids: List<String>, tag: String) {
        val clean = LibraryMeta.normalizeTags(listOf(tag)).firstOrNull() ?: return
        for (id in ids.distinct()) {
            updateMeta(File(docsDir, id)) { lines ->
                val meta = LibraryMeta.read(lines)
                LibraryMeta.write(lines, meta.folder, meta.tags + clean)
            }
        }
    }

    fun removeTag(id: String, tag: String) {
        updateMeta(File(docsDir, id)) { lines ->
            val meta = LibraryMeta.read(lines)
            LibraryMeta.write(lines, meta.folder, meta.tags.filterNot { it.equals(tag, true) })
        }
    }

    /** Every tag used in the library, for the tag sheet's quick picks. */
    fun listTags(): List<String> = synchronized(META_LOCK) {
        val seen = LinkedHashSet<String>()
        forEachDocumentDir { dir ->
            metaLines(dir)?.let { lines ->
                LibraryMeta.read(lines).tags.forEach { tag ->
                    if (seen.none { it.equals(tag, true) }) seen += tag
                }
            }
        }
        seen.sortedBy { it.lowercase(Locale.getDefault()) }
    }

    private inline fun forEachDocumentDir(action: (File) -> Unit) {
        docsDir.listFiles { f -> f.isDirectory }?.forEach { dir ->
            if (!SaveFormat.isIncomplete(dir)) action(dir)
        }
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
        val doc = synchronized(TRASH_LOCK) {
            resolveNameBeforePublish(dir)
            if (!File(dir, SaveFormat.MARKER).delete()) {
                throw IOException("Could not publish the new document. Try saving again.")
            }
            Durable.syncDir(dir)
            Durable.syncDir(docsDir) // best effort; durability of the entry itself is not guaranteed
            loadDocument(dir) ?: throw IllegalStateException("Saved document could not be reopened")
        }
        SaveFaults.at("after_publish")

        // Do not take the OCR lock while holding TRASH_LOCK (the global order is OCR -> Trash).
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

    /** PM-2.2b: the page drafts, one manifest folder per document id. */
    internal val drafts: DraftStore
        get() = DraftStore(context.filesDir)

    /** A document in Trash. [key] is its Trash entry; [originalId] the library id it had.
     *  [previousToken] names the publish staging folder that Replace moved here (PM-2.2b). */
    data class TrashedDocument(
        val key: String,
        val originalId: String,
        val trashedAt: Long,
        val document: ScanDocument,
        val previousToken: String? = null
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
                    trashUnderLocks(src, id, null)
                }
            }
        }
        if (key != null) dropDerivedCaches(id)
        return key
    }

    /**
     * The move itself, with every lock already held (PM-2.2b extracted it for Replace). The marker
     * gains a fourth line `previous=<token>` only when a draft Replace moved this entry, so startup
     * recovery can tell "the user deleted it" from "a Replace died after the move".
     */
    private fun trashUnderLocks(src: File, id: String, previousToken: String?): String {
        val name = loadDocument(src)?.name ?: id
        trashFault(TrashStage.BEFORE_MOVE)
        val key = freeTrashKey(id)
        val dest = File(trashDir, key)
        // F1 (hunt H1-2): the marker is written INSIDE the document before the move, so the
        // one rename carries the trash time with it. A crash right after the move can no longer
        // leave an entry whose only date is the folder's old modification time (which expiry
        // would read as "trashed months ago" and purge). Best effort: an entry without a marker
        // gets "first seen now" from [recoverTrash] and is never expired on a guessed date. A
        // marker left in a Library folder by a failed move is stripped by [recoverTrash].
        val marker = File(src, TRASH_MARKER)
        try {
            Durable.atomicWriteText(marker, trashMarkerText(id, System.currentTimeMillis(), previousToken))
        } catch (_: Exception) {
        }
        if (!src.renameTo(dest)) {
            marker.delete()
            throw IOException("Could not move \"$name\" to Trash")
        }
        trashFault(TrashStage.MOVED)
        Durable.syncDir(docsDir)
        Durable.syncDir(trashDir)
        return key
    }

    private fun trashMarkerText(id: String, trashedAt: Long, previousToken: String?): String = buildString {
        append("v1\n")
        append(id).append('\n')
        append(trashedAt).append('\n')
        if (previousToken != null) append("previous=").append(previousToken).append('\n')
    }

    /**
     * F1 (hunt H1-2): the trash time this entry's own marker recorded, or null when the marker is
     * missing, unreadable or not a v1 marker. Only a recorded time may ever expire an entry.
     */
    private fun recordedTrashTime(dir: File): Long? {
        val lines = try {
            File(dir, TRASH_MARKER).takeIf { it.isFile }?.readLines()
        } catch (_: IOException) {
            null
        } ?: return null
        if (lines.firstOrNull() != "v1") return null
        return lines.getOrNull(2)?.toLongOrNull()?.takeIf { it > 0 }
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
        val previousToken = marker?.firstOrNull { it.startsWith("previous=") }
            ?.removePrefix("previous=")?.takeIf { it.isNotBlank() }
        return TrashedDocument(dir.name, originalId, trashedAt, doc, previousToken)
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
            resolveNameBeforePublish(src)
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

    /**
     * T44: every copy this Trash entry recorded, proven or not. The permanent-delete sheet names
     * the recorded copies it cannot prove so the user is never told "1 PDF copy" while a
     * different saved file stays. Reading only; never used to delete.
     */
    fun exportRecordsOfTrashed(item: TrashedDocument): List<ExportRecord> =
        exportRecordsIn(File(trashDir, item.key))

    /** Copies recorded by any document other than this Trash entry. */
    fun exportsRecordedByOthersThan(item: TrashedDocument): Set<Uri> =
        exportsRecordedOutside(File(trashDir, item.key))

    /**
     * Startup recovery: finishes interrupted permanent deletes (hidden purge
     * entries), expires Trash entries older than [TRASH_RETENTION_MS], and drops a
     * Trash marker left in a restored document by a crash between the move and the
     * marker cleanup. Entries a Replace moved here (`previous=`) are never expired:
     * they belong to an interrupted Replace and [recoverOperations] puts them back.
     */
    fun recoverTrash(now: Long = System.currentTimeMillis()): Int {
        var finished = 0
        synchronized(TRASH_LOCK) {
            trashDir.listFiles { f -> f.name.startsWith(PURGE_PREFIX) }?.forEach { f ->
                if (f.deleteRecursively()) finished++
            }
            trashDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }?.forEach { dir ->
                val trashed = trashedIn(dir) ?: return@forEach
                val recorded = recordedTrashTime(dir)
                if (recorded == null) {
                    // F1 (hunt H1-2): no trustworthy date (legacy entry, crash, damaged marker).
                    // The restore window starts now; if even that cannot be recorded the entry is
                    // kept, never purged on the folder's old modification time.
                    try {
                        Durable.atomicWriteText(
                            File(dir, TRASH_MARKER),
                            trashMarkerText(trashed.originalId, now, trashed.previousToken)
                        )
                    } catch (_: Exception) {
                    }
                    return@forEach
                }
                if (trashed.previousToken == null && now - recorded >= TRASH_RETENTION_MS) {
                    if (dir.deleteRecursively()) {
                        finished++
                        dropDerivedCaches(trashed.originalId)
                    }
                }
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
        synchronized(TRASH_LOCK) {
            val dir = File(docsDir, id)
            updateMeta(dir) { lines ->
                while (lines.size < 2) lines.add("")
                val proposed = newName.trim().replace('\n', ' ').ifBlank { lines[0] }
                lines[0] = uniqueDocumentName(dir, proposed)
            }
        }
    }

    /** Copies a document (pages + PDF) into a brand-new one. Used by "Save as new". */
    /** The unique name for a tool output, stacking-free ([OutputNames]). */
    fun outputName(baseName: String, toolLabel: String): String =
        OutputNames.forTool(baseName, toolLabel, listDocuments(includeVaulted = true).map { it.name })

    private fun existingDocumentNames(): List<String> =
        listDocuments(includeVaulted = true).map { it.name }

    suspend fun duplicateDocument(id: String, suggestedName: String?): ScanDocument =
        withContext(Dispatchers.IO) {
            val src = File(docsDir, id)
            if (loadDocument(src) == null) throw IllegalStateException("Scan not found")
            val sourceDoc = loadDocument(src)
            val name = suggestedName?.trim()?.replace('\n', ' ')?.takeIf { it.isNotBlank() }
                ?: OutputNames.forTool(
                    sourceDoc?.name ?: "Scan", "Copy", existingDocumentNames()
                )
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
            // A gap means "Pages 1-10" would claim pages that were never picked. Name the actual
            // selection instead; a contiguous run keeps the familiar range label.
            val label = when {
                indices.size == 1 -> "Page $first"
                last - first == indices.size - 1 -> "Pages $first-$last"
                else -> "Pages " + indices.joinToString(", ") { it + 1 }
            }
            val name = OutputNames.forTool(doc.name, label, existingDocumentNames())
            val chosen = indices.map { doc.pageFiles[it] }
            buildAndPublish(name, doc.category, chosen.sumOf { it.length() }) { staging ->
                val pageMap = LinkedHashMap<String, String>()
                chosen.forEachIndexed { i, f ->
                    pageMap[stagePageCopy(f, staging, i).name] = f.name
                }
                // H3-5: the pages are copied byte for byte, so their recognised text and line
                // geometry belong to the new document too — searchable before the app restarts.
                carryOcr(id, staging, pageMap, pageMap.keys)
            }
        }

    /** "Doc (page 3)" or "Doc (pages 1–4)" — the one name formula for extract (PM-2.2c). */
    internal fun extractedName(name: String, indices: List<Int>): String {
        val first = indices.first() + 1
        val last = indices.last() + 1
        return if (first == last) "$name (page $first)" else "$name (pages $first–$last)"
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
            ?: OutputNames.forTool(sources.first().name, "Merged", existingDocumentNames())
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

    /** PM-2.2b: what a draft publish promises (see [buildAndPublish]). */
    private class SealSpec(
        val draftId: String,
        val draftCreated: Long,
        val replaces: String?,
        val basePages: String?
    )

    /** The base is safely in Trash and the new version is sealed: startup recovery rolls forward. */
    internal class PublishPending(message: String) : IOException(message)

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
        seal: SealSpec? = null,
        forceTextLayers: Boolean = false,
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
                val geo = if (forceTextLayers || Prefs.makeSearchable(context)) stageOcr.readGeo(staging.name) else emptyMap()
                val layered = writePdfInto(staging, File(staging, "scan.pdf"), geo)
                if (layered && pages.all { geo.containsKey(it.name) }) {
                    Durable.writeFile(File(staging, "text_layer.flag"), ByteArray(0))
                }
                OpFaults.at("op:pdf")
            }

            // Replace copies the base meta.txt byte for byte; writing it again would change the
            // category and the created timestamp the user did not edit (PM-2.2b §5.3).
            if (seal?.replaces == null) {
                writeMeta(staging, name, category)
                OpFaults.at("op:meta")
            }

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

            if (seal != null) {
                OpStaging.writeSeal(
                    staging,
                    OpStaging.Seal(
                        token = staging.name,
                        draft = seal.draftId,
                        draftCreated = seal.draftCreated,
                        replaces = seal.replaces,
                        pages = pages.map { it.name },
                        pagesSha256 = SaveFormat.pagesDigest(pages),
                        pdfSha256 = Sha256.of(File(staging, "scan.pdf"))
                    )
                )
                OpFaults.at("op:sealed")
            }

            if (seal?.replaces != null) {
                // Replace: two renames under PDF → OCR → Trash (F6). Recovery rolls forward a death
                // between them; a user-deleted base is never resurrected (no `previous` marker).
                id = synchronized(PDF_LOCK) {
                    OcrStore.locked {
                        synchronized(TRASH_LOCK) {
                            val base = File(docsDir, seal.replaces)
                            val baseDoc = loadDocument(base)
                                ?: throw DraftStore.DraftStale(context.getString(R.string.draft_stale))
                            if (baseDoc.isLocked) {
                                throw PdfProtection.DocumentLocked(
                                    context.getString(R.string.locked_edit_refused)
                                )
                            }
                            if (SaveFormat.pagesDigest(baseDoc.pageFiles) != seal.basePages) {
                                throw DraftStore.DraftStale(context.getString(R.string.draft_stale))
                            }
                            val key = trashUnderLocks(base, seal.replaces, staging.name)
                            OpFaults.at("replace:base_trashed")
                            if (!staging.renameTo(base)) {
                                if (File(trashDir, key).renameTo(base)) {
                                    File(base, TRASH_MARKER).delete()
                                    throw IOException(context.getString(R.string.op_publish_failed))
                                }
                                // The old version is safe in Trash and the new one is sealed:
                                // leave staging for startup recovery to roll forward.
                                OpStaging.forget(staging)
                                throw PublishPending(context.getString(R.string.op_publish_failed))
                            }
                            seal.replaces
                        }
                    }
                }
                OpStaging.forget(staging)
                OpFaults.at("op:after_rename")
            } else {
                id = synchronized(TRASH_LOCK) {
                    val newId = freeLibraryId()
                    OpFaults.at("op:before_rename")
                    resolveNameBeforePublish(staging)
                    if (!staging.renameTo(File(docsDir, newId))) {
                        throw IOException(context.getString(R.string.op_publish_failed))
                    }
                    newId
                }
                OpStaging.forget(staging)
                OpFaults.at("op:after_rename")
            }
        } catch (e: PublishPending) {
            // Recovery must find the sealed staging folder: do not release it.
            throw e
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

    /**
     * F11 (H3-5) test seam: called with the number of page byte arrays this compress holds at
     * that instant. The streaming paths never exceed one, however many pages the document has;
     * the all-at-once `SizeFit.compressPages` would report the whole document.
     */
    @androidx.annotation.VisibleForTesting
    internal var onLivePageBytes: ((Int) -> Unit)? = null

    /**
     * F11 (H3-5): carries the source document's recognised text onto [staging] pages, so a
     * Compress/Split/Extract output is searchable the moment it is published instead of only
     * after a restart and a background re-recognition. Compressing changes pixels, not words;
     * splitting/extracting only renumbers pages.
     *
     * [pageMap] is output page name -> source page name; [keepGeometry] names the output pages
     * whose pixels kept the source's exact size. Geometry is dropped elsewhere on purpose: line
     * boxes from a downscaled page would be misplaced, and the PDF writer only layers a page
     * whose geometry matches its JPEG.
     */
    private fun carryOcr(
        sourceId: String,
        staging: File,
        pageMap: Map<String, String>,
        keepGeometry: Set<String>
    ) {
        val texts = ocrStore.readText(sourceId)
        if (texts.isEmpty()) return
        val geo = ocrStore.readGeo(sourceId)
        val outTexts = LinkedHashMap<String, String>()
        val outGeo = LinkedHashMap<String, OcrPage>()
        for ((dst, src) in pageMap) {
            val text = texts[src] ?: continue
            outTexts[dst] = text
            if (dst in keepGeometry) geo[src]?.let { outGeo[dst] = it }
        }
        if (outTexts.isNotEmpty()) {
            OcrStore(OpStaging.root(context.filesDir)).putAll(staging.name, outTexts, outGeo)
        }
    }

    /**
     * F11 (H3-2): writes one compressed page per source page into [staging] — one page's bytes
     * alive at a time, never a `List<ByteArray>` of the whole document — and carries the source's
     * recognised text across (H3-5).
     */
    private suspend fun stageCompressedPages(
        id: String,
        doc: ScanDocument,
        staging: File,
        compress: (index: Int, page: File) -> ByteArray
    ) {
        val geo = ocrStore.readGeo(id)
        val pageMap = LinkedHashMap<String, String>()
        val keepGeometry = LinkedHashSet<String>()
        doc.pageFiles.forEachIndexed { i, file ->
            currentCoroutineContext().ensureActive()
            onLivePageBytes?.invoke(1)
            val bytes = compress(i, file)
            val out = stagePageBytes(bytes, staging, i)
            onLivePageBytes?.invoke(0)
            pageMap[out.name] = file.name
            val layer = geo[file.name]
            if (layer != null) {
                val info = runCatching { PdfGenerator.readJpegInfo(bytes) }.getOrNull()
                if (info != null && info.width == layer.width && info.height == layer.height) {
                    keepGeometry += out.name
                }
            }
        }
        carryOcr(id, staging, pageMap, keepGeometry)
    }

    /**
     * F11 (H3-2): re-fits the page JPEGs already written into [staging] so the finished PDF hits
     * [target]. Each page is read, fitted and written back on its own; the whole document is
     * never in memory (the old `SizeFit.compressBytes` held every page and a second copy).
     * Internal so a test can drive it with a staging folder and count live page bytes.
     */
    internal suspend fun fitStagedPagesToTarget(staging: File, target: Long) {
        val pages = pageFilesIn(staging)
        if (pages.isEmpty()) throw IOException("No pages to compress")
        val sizes = pages.map { it.length().coerceAtLeast(1L) }
        val sum = sizes.sum().coerceAtLeast(1L)
        val budget = (target - (pages.size * 600L + 1000L)).coerceAtLeast(2048L)
        pages.forEachIndexed { i, file ->
            currentCoroutineContext().ensureActive()
            onLivePageBytes?.invoke(1)
            val bytes = file.readBytes()
            val share = (budget * sizes[i] / sum).coerceAtLeast(1024L)
            val fitted = SizeFit.fitToShare(bytes, share)
            if (fitted !== bytes) Durable.writeFile(file, fitted)
            onLivePageBytes?.invoke(0)
        }
    }

    /** "Merged PDF <timestamp>" when no name is given. */
    private fun defaultMergedName(prefix: String): String =
        "$prefix ${SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date())}"

    // Shared page-file naming (page_000.jpg, page_001.jpg, …).
    private val PAGE_FORMAT = "page_%03d.jpg"

    /** Long side of a page image embedded in an exported Word file. */
    private val WORD_PICTURE_MAX_PX = 1600

    /** Long side of a page image placed on a PowerPoint slide. */
    private val PPTX_PICTURE_MAX_PX = 2000

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
        // H3-6: an explicitly named copy is still a new document. Ask for the same unique name
        // the tool-output rule gives, so two "… (compressed)" runs are distinguishable.
        val uniqueName = OutputNames.uniqueName(docName, existingDocumentNames())
        buildAndPublish(uniqueName, null, pageBytes.sumOf { it.size.toLong() }) { staging ->
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
        buildAndPublish(outputName(doc.name, "Compressed"), null, doc.pageFiles.sumOf { it.length() }) { staging ->
            // H3-5: the words survive compression; text (and geometry where the pixels kept their
            // size) is carried to the copy so it is searchable right away.
            stageCompressedPages(id, doc, staging) { _, file ->
                ImageTools.recompressJpeg(file.readBytes(), quality, maxDim)
            }
        }
    }

    /**
     * Compresses to a total-size target, one page at a time. The old path built a `List<ByteArray>`
     * for every page first — a 2000-page book held the whole document (hundreds of MB) in memory.
     * Here only the current page's bytes and bitmap are alive, and the recognised text is carried
     * to the copy (H3-5).
     */
    suspend fun compressScanToTarget(id: String, targetBytes: Long): ScanDocument =
        withContext(Dispatchers.IO) {
            require(targetBytes > 0) { "Target size must be positive" }
            val doc = loadDocument(File(docsDir, id)) ?: throw IllegalStateException("Scan not found")
            refuseLockedSources(listOf(doc))
            val sizes = doc.pageFiles.map { it.length().coerceAtLeast(1L) }
            val sum = sizes.sum().coerceAtLeast(1L)
            val budget = (targetBytes - (doc.pageFiles.size * 600L + 1000L)).coerceAtLeast(2048L)
            buildAndPublish(
                outputName(doc.name, "Compressed"), null, doc.pageFiles.sumOf { it.length() }
            ) { staging ->
                stageCompressedPages(id, doc, staging) { i, file ->
                    val share = (budget * sizes[i] / sum).coerceAtLeast(1024L)
                    SizeFit.fitToShare(file.readBytes(), share)
                }
            }
        }

    /**
     * Imports a password-protected PDF by decrypting a temporary copy first: the source file is
     * only read, the plaintext copy lives in `cacheDir` and is deleted before returning. Pages are
     * then rendered through the normal [importPdfAsScan] path, so the new document is a regular
     * ScanWhirl scan (and the original PDF is untouched).
     *
     * Throws [PdfDecryptor.WrongPassword] for a wrong password, [PdfDecryptor.Unsupported] for
     * formats this offline decoder cannot open, and [PdfImport.ImportException] (`LOW_MEMORY`) for
     * a file this phone cannot hold in memory while it decrypts: a [OutOfMemoryError] would be an
     * `Error` and kill the process, so it is reported as that normal failure instead.
     */
    suspend fun importProtectedPdfAsScan(
        uri: Uri,
        name: String?,
        password: String
    ): ScanDocument = withContext(Dispatchers.IO) {
        val clean = File(context.cacheDir, "decrypted-${UUID.randomUUID()}.pdf")
        try {
            PdfImport.withSeekableCopy(context, uri) { fd, size ->
                // The whole file is held in memory to be decrypted (and copied twice more
                // inside PdfDecryptor), so a file the heap cannot carry is refused before it
                // is read instead of throwing an OutOfMemoryError that kills the process.
                if (size > PdfDecryptor.maxInMemoryBytes()) {
                    throw PdfImport.ImportException(
                        PdfImport.Reason.LOW_MEMORY,
                        "This protected PDF is too large to unlock in memory"
                    )
                }
                try {
                    val raw = ParcelFileDescriptor.AutoCloseInputStream(fd.dup()).use { it.readBytes() }
                    Durable.writeFile(clean, decryptPdf(raw, password))
                } catch (e: OutOfMemoryError) {
                    // An Error, so the callers' `catch (e: Exception)` would miss it: report the
                    // same friendly failure a too-large file gets, with nothing published.
                    throw PdfImport.ImportException(
                        PdfImport.Reason.LOW_MEMORY,
                        "This protected PDF is too large to unlock in memory",
                        e
                    )
                }
            }
            importPdfAsScan(Uri.fromFile(clean), name)
        } finally {
            clean.delete()
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
    /** How a planned PDF import continues after the pre-check sheet. */
    sealed interface ImportDecision {
        data object All : ImportDecision
        data class Pages(val indices: List<Int>) : ImportDecision
        data object Cancel : ImportDecision
    }

    /**
     * Imports a device PDF after a pre-check. [onPlan] is called with the page count, the size
     * estimate and free space when the PDF is big (>50 pages or >25% of free space); it runs
     * while the seekable copy is open. Pages are rendered one at a time and the document is
     * published only when every chosen page is written. A cancel returns null and leaves nothing.
     */
    suspend fun importPdfAsScanPlanned(
        uri: Uri,
        name: String?,
        dpi: Float = 150f,
        maxDim: Int = 2048,
        quality: Int = 85,
        onPlan: suspend (PdfImportBudget.Plan) -> ImportDecision,
        onPage: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): ScanDocument? = importPdfInternal(uri, name, dpi, maxDim, quality, vaulted = false, onPlan, onPage)

    private suspend fun importPdf(
        uri: Uri,
        name: String?,
        dpi: Float,
        maxDim: Int,
        quality: Int,
        vaulted: Boolean
    ): ScanDocument = importPdfInternal(uri, name, dpi, maxDim, quality, vaulted, null, null)
        ?: throw IllegalStateException("Import cancelled")

    private suspend fun importPdfInternal(
        uri: Uri,
        name: String?,
        dpi: Float,
        maxDim: Int,
        quality: Int,
        vaulted: Boolean,
        onPlan: (suspend (PdfImportBudget.Plan) -> ImportDecision)?,
        onPage: ((Int, Int) -> Unit)?,
        preset: ImportDecision? = null
    ): ScanDocument? = withContext(Dispatchers.IO) {
        val staging = File(importStagingDir, UUID.randomUUID().toString().take(12))
        if (!staging.mkdirs()) throw PdfImport.ImportException(PdfImport.Reason.SAVE_FAILED, "Could not prepare the import")
        try {
            val rendered = withPlannedPdfPages(
                uris = listOf(uri),
                dpi = dpi,
                maxDim = maxDim,
                quality = quality,
                preset = preset,
                onCopied = { importFault(ImportStage.COPIED) },
                onPlan = onPlan ?: { ImportDecision.All }
            ) { planned ->
                val pdf = planned.first()
                if (pdf.indices.isEmpty()) {
                    false
                } else {
                    PdfImport.renderPages(
                        pdf.source, staging, pdf.indices, usableSpace(docsDir)
                    ) { done, total ->
                        importFault(ImportStage.PAGE_RENDERED)
                        onPage?.invoke(done, total)
                    }
                    true
                }
            } ?: false
            if (!rendered) {
                staging.deleteRecursively()
                return@withContext null
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
                resolveNameBeforePublish(staging)
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

    /** One opened PDF in a planned batch, with the pages the user chose (0-based). */
    data class PlannedPdf(
        val source: PdfImport.PageSource,
        val indices: List<Int>,
        val pageCount: Int
    )

    /** Counts and estimates a device PDF without rendering it (for Compress's own renderer). */
    suspend fun inspectDevicePdf(uri: Uri, maxDim: Int = 2048): PdfImportBudget.Plan =
        withContext(Dispatchers.IO) {
            PdfImport.withSeekableCopy(context, uri) { fd, _ ->
                PdfImportBudget.plan(
                    PdfImport.pageCount(fd),
                    usableSpace(docsDir),
                    PdfImportBudget.pageBytes(maxDim)
                )
            }
        }

    /**
     * The one huge-PDF gate for every path that renders a device PDF into pages: copies each PDF
     * once, counts pages, applies the shared budget/refusal, asks once (when needed), then runs
     * [block] with every source open and the selected indices. Returns null when the user cancels.
     */
    suspend fun <T> withPlannedPdfPages(
        uris: List<Uri>,
        dpi: Float = 150f,
        maxDim: Int = 2048,
        quality: Int = 85,
        preset: ImportDecision? = null,
        onCopied: (() -> Unit)? = null,
        onPlan: suspend (PdfImportBudget.Plan) -> ImportDecision = { ImportDecision.All },
        block: suspend (List<PlannedPdf>) -> T
    ): T? = withContext(Dispatchers.IO) {
        require(uris.isNotEmpty()) { "Pick a PDF" }
        openPlannedPdfs(uris, 0, emptyList(), dpi, maxDim, quality, preset, onCopied, onPlan, block)
    }

    private suspend fun <T> openPlannedPdfs(
        uris: List<Uri>,
        index: Int,
        opened: List<Pair<PdfImport.PageSource, Int>>,
        dpi: Float,
        maxDim: Int,
        quality: Int,
        preset: ImportDecision?,
        onCopied: (() -> Unit)?,
        onPlan: suspend (PdfImportBudget.Plan) -> ImportDecision,
        block: suspend (List<PlannedPdf>) -> T
    ): T? {
        if (index == uris.size) {
            val totalPages = opened.sumOf { it.second }
            val plan = PdfImportBudget.plan(
                totalPages, usableSpace(docsDir), PdfImportBudget.pageBytes(maxDim)
            )
            if (plan.tooLittleSpace) {
                throw PdfImport.ImportException(
                    PdfImport.Reason.NO_SPACE,
                    context.getString(
                        R.string.tools_import_no_space,
                        plan.estimateBytes / (1024 * 1024),
                        plan.freeBytes / (1024 * 1024)
                    ),
                    pages = totalPages
                )
            }
            val decision = preset ?: onPlan(plan)
            if (decision == ImportDecision.Cancel) return null
            val chosen: List<List<Int>> = when (decision) {
                ImportDecision.All -> opened.map { (_, count) -> (0 until count).toList() }
                is ImportDecision.Pages -> {
                    val flat = decision.indices.distinct().sorted()
                    var cursor = 0
                    opened.map { (_, count) ->
                        val pages = flat.filter { it in cursor until cursor + count }.map { it - cursor }
                        cursor += count
                        pages
                    }
                }
                ImportDecision.Cancel -> return null
            }
            if (chosen.all { it.isEmpty() }) return null
            return block(opened.mapIndexed { i, (source, count) ->
                PlannedPdf(source, chosen[i], count)
            })
        }
        return PdfImport.withSeekableCopy(context, uris[index]) { fd, _ ->
            onCopied?.invoke()
            val count = PdfImport.pageCount(fd)
            val source = PdfImport.PdfRendererSource(fd, dpi, maxDim, quality)
            source.use {
                openPlannedPdfs(
                    uris, index + 1, opened + (source to count),
                    dpi, maxDim, quality, preset, onCopied, onPlan, block
                )
            }
        }
    }

    /**
     * Merges device PDFs after the pre-check; pages are rendered one at a time into private
     * staging and published once. Cancelling returns null and leaves nothing.
     */
    suspend fun mergePdfUrisPlanned(
        uris: List<Uri>,
        suggestedName: String?,
        onPlan: suspend (PdfImportBudget.Plan) -> ImportDecision,
        onPage: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): ScanDocument? {
        require(uris.size >= 2) { "Pick at least 2 PDFs" }
        val name = suggestedName ?: defaultMergedName("Merged PDF")
        return withPlannedPdfPages(uris, onPlan = onPlan) { planned ->
            val total = planned.sumOf { it.indices.size }
            var done = 0
            buildAndPublish(name, null, 0L) { staging ->
                for (pdf in planned) {
                    if (pdf.indices.isEmpty()) continue
                    PdfImport.renderPages(pdf.source, staging, pdf.indices, usableSpace(docsDir)) { pageDone, _ ->
                        OpFaults.at("op:page:${done + pageDone - 1}")
                        onPage(done + pageDone, total)
                    }
                    done += pdf.indices.size
                }
                if (done == 0) throw IllegalStateException("No pages to merge")
            }
        }
    }

    /**
     * Imports one device PDF with pre-chosen pages (Compress has already shown the gate), without
     * asking a second time. Returns null for an empty selection.
     */
    suspend fun importPdfAsScanSelected(
        uri: Uri,
        name: String?,
        dpi: Float = 150f,
        maxDim: Int = 2048,
        quality: Int = 85,
        indices: List<Int>,
        onPage: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): ScanDocument? = importPdfInternal(
        uri, name, dpi, maxDim, quality, vaulted = false,
        onPlan = null, onPage = onPage, preset = ImportDecision.Pages(indices)
    )

    /**
     * F11 (H3-2): compresses a picked PDF to a total-size target without ever holding the whole
     * document in memory. Pages are rendered one at a time into private staging (the shared
     * huge-PDF gate and space check apply), then each page is re-fitted from disk, and the
     * verified folder is published once. Only a cancel publishes nothing.
     *
     * [sourceName] is the picked file's name; the copy is named through [outputName], so two
     * runs on the same file never share a name (H3-6, the same rule as a saved scan).
     */
    suspend fun importPdfToTarget(
        uri: Uri,
        sourceName: String?,
        indices: List<Int>,
        targetBytes: Long,
        dpi: Float = 150f,
        maxDim: Int = 2048,
        quality: Int = 85,
        onPage: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): ScanDocument? {
        require(targetBytes > 0) { "Target size must be positive" }
        require(indices.isNotEmpty()) { "No pages selected" }
        val base = sourceName?.trim()?.replace('\n', ' ')
            ?.takeIf { it.isNotBlank() } ?: nextDocumentName()
        val docName = outputName(base, "Compressed")
        return withPlannedPdfPages(
            uris = listOf(uri),
            dpi = dpi,
            maxDim = maxDim,
            quality = quality,
            preset = ImportDecision.Pages(indices)
        ) { planned ->
            buildAndPublish(docName, null, 0L) { staging ->
                val total = planned.sumOf { it.indices.size }
                var done = 0
                for (pdf in planned) {
                    if (pdf.indices.isEmpty()) continue
                    PdfImport.renderPages(pdf.source, staging, pdf.indices, usableSpace(docsDir)) { pageDone, _ ->
                        OpFaults.at("op:page:${done + pageDone - 1}")
                        onPage(done + pageDone, total)
                    }
                    done += pdf.indices.size
                }
                if (done == 0) throw IllegalStateException("No pages to compress")
                fitStagedPagesToTarget(staging, targetBytes)
            }
        }
    }

    /** Vault import with the shared huge-PDF gate. */
    suspend fun importPdfIntoVaultPlanned(
        uri: Uri,
        name: String?,
        onPlan: suspend (PdfImportBudget.Plan) -> ImportDecision,
        onPage: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): ScanDocument? = importPdfInternal(
        uri, name, 150f, 2048, 85, vaulted = true,
        onPlan = onPlan, onPage = onPage, preset = null
    )

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
            if (drafts.exists(id)) {
                throw DraftStore.DraftOpen(context.getString(R.string.draft_open_refused))
            }
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
                Durable.atomicWriteText(lock, PdfLockVerifier.create(password))
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
            if (!verifyPdfPassword(id, password)) {
                throw PdfProtection.WrongPassword(context.getString(R.string.unlock_pdf_wrong))
            }
            unlockVerified(id)
        }

    /**
     * Removes protection after [checkPdfPassword] accepted the password: the KDF is not run a second
     * time, and the lock record the grant was made for must still be the one on disk
     * (compare-and-swap under [PDF_LOCK]), so an authorization never outlives the record it checked.
     */
    suspend fun unlockPdf(id: String, grant: PdfPasswordGrant): ScanDocument =
        withContext(Dispatchers.IO) { unlockVerified(id, grant) }

    private fun unlockVerified(id: String, grant: PdfPasswordGrant? = null): ScanDocument {
        val dir = File(docsDir, id)
        val doc = loadDocument(dir) ?: throw IllegalStateException("Scan not found")
        if (!doc.isLocked) return doc
        if (doc.pageFiles.isEmpty()) throw IllegalStateException(context.getString(R.string.locked_no_pages))
        synchronized(PDF_LOCK) {
            if (grant != null) requireLockRecord(dir, grant)
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
        return loadDocument(dir) ?: throw IllegalStateException("Scan vanished after unlock")
    }

    /**
     * Changes the password of a locked document after checking [current] (one PBKDF2 for the check,
     * one for the new verifier, both off the caller's thread).
     *
     * Crash safety: the PDF swap and the verifier write are covered by a [PasswordSwap] marker in
     * private staging, repaired by [recoverOperations] at the next start. The destructive step is
     * the PDF rename; after it the marker alone can finish the job, so a process death can no longer
     * leave the new verifier beside the old PDF. Fault points `chpw:encrypted`, `chpw:marker`,
     * `chpw:swapped`.
     */
    suspend fun changePdfPassword(id: String, current: String, new: String): ScanDocument {
        val grant = checkPdfPassword(id, current)
            ?: throw PdfProtection.WrongPassword(context.getString(R.string.unlock_pdf_wrong))
        return changePdfPassword(id, grant, new)
    }

    /** Same, after [checkPdfPassword] accepted [grant]'s password: the current password is not re-derived. */
    suspend fun changePdfPassword(id: String, grant: PdfPasswordGrant, new: String): ScanDocument =
        withContext(Dispatchers.IO) {
            require(new.isNotBlank()) { "Password must not be empty" }
            if (!PdfProtection.isEncodable(new)) {
                throw IllegalArgumentException(context.getString(R.string.password_charset))
            }
            val dir = File(docsDir, id)
            val doc = loadDocument(dir) ?: throw IllegalStateException("Scan not found")
            if (!doc.isLocked) throw IllegalStateException("Not protected")
            if (doc.pageFiles.isEmpty()) throw IllegalStateException(context.getString(R.string.locked_no_pages))
            swapPasswordVerified(dir, doc, grant, new)
            recordLockIndex(File(dir, PdfProtection.PDF_FILE), id)
            loadDocument(dir)!!
        }

    /**
     * The verified password change: build and verify the new PDF in private staging, then swap it in
     * under a marker that names the old and the new verifier and both PDF digests.
     *
     *  1. `enc.pdf` is built, verified and fsynced in [OpStaging];
     *  2. the marker is written and fsynced (the only place the new verifier survives a kill);
     *  3. `enc.pdf` is renamed over `scan.pdf` — the one destructive step;
     *  4. `locked.meta` is replaced with the new verifier;
     *  5. the marker is deleted and the staging folder released.
     *
     * A death before 3 leaves the old PDF and the old verifier (the marker is swept; nothing changed).
     * A death after 3 is rolled forward by [recoverOperations], which reads the new verifier from the
     * marker alone.
     */
    private fun swapPasswordVerified(
        dir: File,
        doc: ScanDocument,
        grant: PdfPasswordGrant,
        new: String
    ) = synchronized(PDF_LOCK) {
        require(grant.docId == dir.name) { "Authorization is for another document" }
        val lock = File(dir, LOCK_FILE)
        val current = if (lock.exists()) readPdfLock(lock) else null
        // Compare-and-swap: the password the owner proved is still the one on disk.
        if (current == null || current != grant.lockRecord) {
            throw PdfProtection.WrongPassword(context.getString(R.string.unlock_pdf_wrong))
        }
        val work = OpStaging.create(context.filesDir)
        // Past the PDF rename the marker is the only way back to a consistent verifier, so it is
        // kept for the next start on any failure; before it, cleanup is a clean rollback.
        var swapped = false
        try {
            val plain = File(work, "plain.pdf")
            if (doc.pageFiles.isNotEmpty()) {
                writePdf(dir, plain)
            } else {
                require(doc.pdfFile.exists() && doc.pdfFile.length() > 0L) { "PDF missing" }
                Durable.copyFile(doc.pdfFile, plain)
            }
            val enc = File(work, "enc.pdf")
            PdfEncryptor.encrypt(plain, enc, new)
            Durable.syncFile(enc)
            if (PdfProtection.isEncrypted(enc) != true) throw IOException("Protection could not be verified")
            OpFaults.at("chpw:encrypted")

            val newRecord = PdfLockVerifier.create(new)
            val pdf = File(dir, PdfProtection.PDF_FILE)
            val previousDigest = Sha256.of(pdf)
            val newDigest = Sha256.of(enc)
            Durable.atomicWriteText(
                File(work, PasswordSwap.MARKER),
                PasswordSwap.write(dir.name, current, newRecord, previousDigest, newDigest)
            )
            Durable.syncDir(work)
            OpFaults.at("chpw:marker")

            if (!enc.renameTo(pdf)) throw IOException("Could not change the PDF password")
            swapped = true
            Durable.syncDir(dir)
            OpFaults.at("chpw:swapped")

            Durable.atomicWriteText(lock, newRecord)
            Durable.syncDir(dir)
            File(work, PasswordSwap.MARKER).delete()
            Durable.syncDir(work)
            OpStaging.release(work)
        } catch (e: Exception) {
            // A real kill skips this too. After the swap the marker stays so [recoverOperations]
            // can finish the change; deleting it there would leave the new PDF with the old
            // verifier and no way to repair it.
            if (!swapped) OpStaging.release(work)
            throw e
        }
    }

    /**
     * Finishes a password change that died after the PDF swap (see [swapPasswordVerified]).
     * Only the destructive step needs repair: once `scan.pdf` is the new file, the marker alone
     * names the verifier that must replace the old one. A death before the swap changed nothing
     * (the marker, staging and old verifier are swept).
     */
    private fun recoverPasswordSwap(work: File) {
        val swap = PasswordSwap.read(work) ?: return
        val dir = File(docsDir, swap.id)
        if (!dir.isDirectory) return
        val pdf = File(dir, PdfProtection.PDF_FILE)
        val lock = File(dir, LOCK_FILE)
        synchronized(PDF_LOCK) {
            val digest = runCatching { Sha256.of(pdf) }.getOrNull()
            if (digest == swap.newPdfSha256) {
                if (readPdfLock(lock) != swap.newRecord) {
                    Durable.atomicWriteText(lock, swap.newRecord)
                    Durable.syncDir(dir)
                }
                recordLockIndex(pdf, swap.id)
            }
        }
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

    /**
     * PM-2.2b startup recovery, in order:
     *  1. roll forward a sealed Replace whose base is absent and whose Trash entry carries
     *     `previous=<token>` (and whose pages/PDF still match the seal);
     *  2. finish every published `.publish`: discard its draft (only the matching creation) and
     *     delete the seal;
     *  3. drop interrupted discards and drafts whose base is gone from BOTH documents and Trash;
     *  4. sweep every staging folder that is not live in this process.
     * Returns how many Replace operations were rolled forward.
     */
    fun recoverOperations(): Int {
        val filesDir = context.filesDir
        var rolledForward = 0

        // 1. Roll forward.
        val stagingRoot = OpStaging.root(filesDir)
        for (dir in stagingRoot.listFiles { f -> f.isDirectory }.orEmpty()) {
            val seal = OpStaging.readSeal(dir) ?: continue
            val replaces = seal.replaces ?: continue
            val base = File(docsDir, replaces)
            if (base.exists()) continue
            val trash = listTrash().firstOrNull {
                it.originalId == replaces && it.previousToken == seal.token
            } ?: continue
            val pages = pageFilesIn(dir)
            if (pages.map { it.name } != seal.pages) continue
            if (SaveFormat.pagesDigest(pages) != seal.pagesSha256) continue
            if (Sha256.of(File(dir, "scan.pdf")) != seal.pdfSha256) continue
            synchronized(TRASH_LOCK) {
                if (dir.renameTo(base)) {
                    OpStaging.forget(dir)
                    rolledForward++
                }
            }
        }

        // 1b. Finish a password change that died between its PDF swap and its verifier write: the
        // marker names the new verifier, so no second KDF run and no plaintext is needed.
        for (dir in stagingRoot.listFiles { f -> f.isDirectory }.orEmpty()) {
            recoverPasswordSwap(dir)
        }

        // 2. Finish published draft seals (create-only Save as new, or a completed Replace).
        docsDir.listFiles { f -> f.isDirectory }.orEmpty().forEach { dir ->
            val seal = OpStaging.readSeal(dir) ?: return@forEach
            synchronized(DRAFT_LOCK) {
                runCatching { drafts.load(seal.draft) }.getOrNull()?.let { draft ->
                    if (draft.created == seal.draftCreated) drafts.discard(seal.draft)
                }
            }
            File(dir, OpStaging.SEAL).delete()
        }

        // 3. Drafts: interrupted discards, and drafts whose base is gone from library and Trash.
        // Only a manifest we can read is judged: an unknown/damaged folder is never deleted here.
        synchronized(DRAFT_LOCK) {
            drafts.removeDiscardLeftovers()
            val trashIds = listTrash().map { it.originalId }.toSet()
            drafts.ids().forEach { id ->
                val draft = runCatching { drafts.load(id) }.getOrNull() ?: return@forEach
                if (!File(docsDir, draft.baseId).exists() && draft.baseId !in trashIds) {
                    drafts.discard(id)
                }
            }
        }

        // 4. Everything else not live in this process (including a seal that failed a check).
        val swept = OpStaging.sweep(filesDir)
        return rolledForward + swept
    }

    /**
     * A lock-record-bound proof that [checkPdfPassword] accepted a document's password, together
     * with the exact lock record it checked. [unlockPdf] and [changePdfPassword] re-check that record
     * under [PDF_LOCK] instead of deriving the KDF again, so one attempt derives once.
     *
     * It carries no consumption state: the grant stays valid exactly as long as the record it names
     * is still the one on disk (so it may be used more than once, e.g. to build the same preview
     * twice) and is refused as soon as a password change or an unlock has replaced that record.
     */
    class PdfPasswordGrant internal constructor(
        val docId: String,
        internal val lockRecord: String?
    )

    /**
     * Checks [password] for document [id] on a background dispatcher, upgrading an old verifier only
     * after a successful check (the migration is a compare-and-swap under [PDF_LOCK]). Returns the
     * grant a follow-up [unlockPdf] / [changePdfPassword] can act on without deriving again, or null
     * when the password is wrong or the lock is unreadable.
     *
     * Cancelling the calling coroutine (the owner dismissed the sheet) stops the migration: a
     * cancelled check leaves the record exactly as it was.
     */
    suspend fun checkPdfPassword(id: String, password: String): PdfPasswordGrant? =
        withContext(Dispatchers.IO) {
            val dir = File(docsDir, id)
            val lockFile = File(dir, LOCK_FILE)
            if (!lockFile.exists()) {
                return@withContext if (PdfProtection.state(dir) == PdfProtection.State.NONE) {
                    PdfPasswordGrant(id, null)
                } else null
            }
            val stored = readPdfLock(lockFile) ?: return@withContext null
            val coroutine = currentCoroutineContext()
            val effective = checkStoredLock(dir, lockFile, stored, password) {
                coroutine.ensureActive()
            } ?: return@withContext null
            PdfPasswordGrant(id, effective)
        }

    /** Verifies a lock, upgrading an old verifier only after a successful password check. */
    fun verifyPdfPassword(id: String, password: String): Boolean {
        val dir = File(docsDir, id)
        val lockFile = File(dir, LOCK_FILE)
        if (!lockFile.exists()) return PdfProtection.state(dir) == PdfProtection.State.NONE
        val stored = readPdfLock(lockFile) ?: return false
        return checkStoredLock(dir, lockFile, stored, password) != null
    }

    /**
     * The single verifier check. Returns the lock record that is valid from now on (the upgraded one
     * after a migration) or null when the password does not match.
     *
     * [beforeMigration] runs inside [PDF_LOCK] just before the legacy record is replaced, so a
     * cancelled check can abort before any byte changes.
     */
    private fun checkStoredLock(
        dir: File,
        lockFile: File,
        stored: String,
        password: String,
        beforeMigration: () -> Unit = {}
    ): String? {
        if (!PdfLockVerifier.isLegacy(stored)) return if (PdfLockVerifier.verify(stored, password)) stored else null
        if (!MessageDigest.isEqual(stored.toByteArray(), Prefs.sha256("pdf_lock:$password").toByteArray())) {
            return null
        }
        // A stale label on a plaintext file is upgraded by Repair or deleted by Remove lock.
        // Do not mutate it just because a check succeeded (repair itself can still refuse).
        if (PdfProtection.isEncodable(password) && PdfProtection.state(dir) == PdfProtection.State.LOCKED) {
            try {
                val upgraded = PdfLockVerifier.create(password)
                synchronized(PDF_LOCK) {
                    // A concurrent password change or unlock wins; never reintroduce an old lock.
                    if (readPdfLock(lockFile) != stored) return null
                    beforeMigration()
                    Durable.atomicWriteText(lockFile, upgraded)
                    Durable.syncDir(dir)
                }
                return upgraded
            } catch (_: IOException) {
                // Full/unwritable storage must not prevent an existing lock from being opened.
                // The old record stays valid and the next successful check retries migration.
            }
        }
        return stored
    }

    /** The lock record a grant was made for is still the one on disk (compare-and-swap). */
    private fun requireLockRecord(dir: File, grant: PdfPasswordGrant) {
        require(grant.docId == dir.name) { "Authorization is for another document" }
        val lock = File(dir, LOCK_FILE)
        val record = if (lock.exists()) readPdfLock(lock) else null
        if (record != grant.lockRecord) {
            throw PdfProtection.WrongPassword(context.getString(R.string.unlock_pdf_wrong))
        }
    }

    private fun readPdfLock(file: File): String? =
        if (file.length() !in 1L..256L) null else runCatching { file.readText().trim() }.getOrNull()

    /**
     * The recovery marker of an interrupted password change, kept in private [OpStaging] (never
     * beside a document). It records the verifier before and after the change and the digest of
     * both possible `scan.pdf` bodies, which is enough to finish the change after a process death
     * without the password and without touching the page sources.
     */
    private object PasswordSwap {
        const val MARKER = ".password-swap"
        private const val HEADER = "scanwhirl-password-swap-v1"
        private val idPattern = Regex("[A-Za-z0-9_-]{1,64}")
        private val sha256Pattern = Regex("[0-9a-f]{64}")

        class Swap(
            val id: String,
            val oldRecord: String,
            val newRecord: String,
            val previousPdfSha256: String,
            val newPdfSha256: String
        )

        fun write(
            id: String,
            oldRecord: String,
            newRecord: String,
            previousPdfSha256: String,
            newPdfSha256: String
        ): String = buildString {
            append(HEADER).append('\n')
            append("id=").append(id).append('\n')
            append("old=").append(oldRecord).append('\n')
            append("new=").append(newRecord).append('\n')
            append("pdf=").append(previousPdfSha256).append('\n')
            append("newPdf=").append(newPdfSha256).append('\n')
        }

        /** Any missing, torn or unexpected field returns null: an unreadable marker never guesses. */
        fun read(work: File): Swap? {
            val file = File(work, MARKER)
            if (file.length() !in 1L..2048L) return null
            val lines = runCatching { file.readLines() }.getOrNull() ?: return null
            if (lines.firstOrNull() != HEADER) return null
            val values = HashMap<String, String>()
            for (line in lines.drop(1)) {
                val eq = line.indexOf('=')
                if (eq <= 0) return null
                values[line.substring(0, eq)] = line.substring(eq + 1)
            }
            val id = values["id"]?.takeIf { idPattern.matches(it) } ?: return null
            val oldRecord = values["old"]?.takeIf { it.length in 1..256 } ?: return null
            val newRecord = values["new"]?.takeIf { it.length in 1..256 } ?: return null
            val previous = values["pdf"]?.takeIf { sha256Pattern.matches(it) } ?: return null
            val next = values["newPdf"]?.takeIf { sha256Pattern.matches(it) } ?: return null
            return Swap(id, oldRecord, newRecord, previous, next)
        }
    }

    private object PdfLockVerifier {
        // A platform-independent record, including backups restored to API 24/25. The JCA
        // SHA-256 variant is unavailable there. HMAC-SHA1 is not affected by SHA-1 collisions.
        private const val FORMAT = "pbkdf2-sha1-v1"
        private const val ITERATIONS = 1_300_000
        private const val SALT_BYTES = 16
        private const val KEY_BYTES = 20
        private val random = SecureRandom()
        private val legacy = Regex("[0-9a-f]{64}")

        fun isLegacy(stored: String) = legacy.matches(stored)

        fun create(password: String): String {
            val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
            return "$FORMAT:${encode(salt)}:${encode(derive(password, salt))}"
        }

        fun verify(stored: String, password: String): Boolean {
            if (!PdfProtection.isEncodable(password)) return false
            val parts = stored.split(':', limit = 4)
            if (parts.size != 3 || parts[0] != FORMAT) return false
            val salt = decode(parts[1], SALT_BYTES) ?: return false
            val expected = decode(parts[2], KEY_BYTES) ?: return false
            return MessageDigest.isEqual(expected, derive(password, salt))
        }

        private fun derive(password: String, salt: ByteArray): ByteArray {
            lockDerivationProbe?.invoke()
            // ASCII Base64 gives the same UTF-8 password semantics across JCA providers.
            val spec = PBEKeySpec(encode(password.toByteArray(Charsets.UTF_8)).toCharArray(), salt, ITERATIONS, KEY_BYTES * 8)
            return try {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        }

        private fun encode(bytes: ByteArray) =
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

        private fun decode(value: String, size: Int): ByteArray? =
            runCatching { android.util.Base64.decode(value, android.util.Base64.NO_WRAP) }.getOrNull()
                ?.takeIf { it.size == size && encode(it) == value }
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

    /**
     * Same preview after [checkPdfPassword] accepted the password. The lock record must still be the
     * one the grant covers, so a password change made in between is never bypassed. The grant is not
     * consumed: calling this again with the same grant is fine while the record is unchanged.
     */
    suspend fun unlockedPreviewPdf(id: String, grant: PdfPasswordGrant): File =
        withContext(Dispatchers.IO) {
            val dir = File(docsDir, id)
            val doc = loadDocument(dir) ?: throw IllegalStateException("Scan not found")
            synchronized(PDF_LOCK) { requireLockRecord(dir, grant) }
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

    /** Recognised line geometry per page key (empty when none was stored). */
    fun ocrGeometry(id: String): Map<String, com.scanwhirl.app.model.OcrPage> = ocrStore.readGeo(id)

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

    /** Save only if every expected page still matches; conflicts leave the whole batch untouched. */
    fun updateOcrPagesIfUnchanged(
        id: String,
        expected: Map<String, String?>,
        pages: Map<String, String>
    ): OcrStore.UpdateResult? {
        if (!File(docsDir, id).isDirectory) return null
        return ocrStore.updatePagesIfUnchanged(id, expected, pages)
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
    private fun regeneratePdf(dir: File, forceLayers: Boolean = false) = synchronized(PDF_LOCK) {
        requireUnlocked(dir) // A3 backstop: a locked document's PDF is never rebuilt in plaintext
        val pdfFile = File(dir, "scan.pdf")
        val pages = pageFilesIn(dir)
        // Read the geometry once for both the write and the flag; the old code re-read the whole
        // map once per page (and used indexOf per page), which was O(pages²) on a 2000-page book.
        val geo = if (forceLayers || Prefs.makeSearchable(context)) {
            ocrStore.readGeo(dir.name)
        } else {
            emptyMap()
        }
        val layered = if (pages.isNotEmpty()) {
            writePdfInto(dir, pdfFile, geo)
        } else false
        updateTextLayerFlag(dir, layered, geo)
    }

    /**
     * v2 UI: 03_tools_hub — "Make searchable": rebuild this document's PDF with
     * the invisible OCR text layer already stored for it. False when the PDF is
     * locked, has no pages, or its text has not been recognised yet (the Tools
     * sheet then says so instead of pretending).
     */
    fun makeSearchable(id: String): Boolean {
        val dir = File(docsDir, id)
        if (!dir.isDirectory || File(dir, LOCK_FILE).exists()) return false
        if (pageFilesIn(dir).isEmpty()) return false
        if (ocrStore.readGeo(id).isEmpty()) return false
        regeneratePdf(dir, forceLayers = true)
        return File(dir, "text_layer.flag").exists()
    }

    /** `text_layer.flag` exists exactly when every page of [dir] received an invisible text layer. */
    private fun updateTextLayerFlag(
        dir: File,
        layered: Boolean,
        geo: Map<String, OcrPage> = emptyMap()
    ) {
        val id = dir.name
        val pages = pageFilesIn(dir)
        val searchable = layered && pages.withIndex().all { (index, _) ->
            geo.containsKey(String.format(Locale.ROOT, "page_%03d.jpg", index))
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
    private fun requireEditable(dir: File) {
        requireUnlocked(dir)
        // PM-2.2b lease (I7): while a draft exists the base pages must stay byte-identical.
        if (drafts.exists(dir.name)) {
            throw DraftStore.DraftOpen(context.getString(R.string.draft_open_refused))
        }
    }

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
                pages.indices.filter {
                    geo.containsKey(String.format(Locale.ROOT, "page_%03d.jpg", it))
                }
                    .associate { i -> i to geo[String.format(Locale.ROOT, "page_%03d.jpg", i)]!! }
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

    /** App context for callers that need per-app settings (OCR language) without owning one. */
    internal fun appContext(): Context = context

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
            // Owner F9: parts are "<document name> part 1", "part 2", ... in page order.
            ranges.sortedBy { it.from }.mapIndexed { index, span ->
                val chosen = span.indices().map { doc.pageFiles[it] }
                val name = OutputNames.forSplitPart(
                    doc.name,
                    index + 1,
                    existingDocumentNames(),
                    context.getString(com.scanwhirl.app.R.string.split_part_word)
                )
                buildAndPublish(name, doc.category, chosen.sumOf { it.length() }) { staging ->
                    val pageMap = LinkedHashMap<String, String>()
                    chosen.forEachIndexed { i, f ->
                        pageMap[stagePageCopy(f, staging, i).name] = f.name
                    }
                    // H3-5: a byte-identical page keeps its recognised text and line geometry.
                    carryOcr(id, staging, pageMap, pageMap.keys)
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
        // H2-7: only a known extension is removed ("12.05.2025 Rent receipt" stays whole).
        val base = ExportNames.baseName(baseName?.trim()?.takeIf { it.isNotBlank() } ?: doc.name)
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
     * No recognised text. The setup screen turns this into a sheet that names the text-language
     * setting and opens it, instead of leaving a Hindi scan at "Read the text first".
     */
    class NoTextForExportException(message: String) : IllegalStateException(message)

    /**
     * Writes a `.docx` from the document's recognised text (one paragraph per OCR line, page
     * break between pages). With [includePictures] each page starts with its own page image
     * (fitted to the text width) followed by that page's text. Never rewrites the document.
     */
    suspend fun exportWord(
        id: String,
        outFile: File,
        includePictures: Boolean = false
    ): File = withContext(Dispatchers.IO) {
        val doc = loadDocument(File(docsDir, id)) ?: throw IllegalStateException("Scan not found")
        refuseLockedSources(listOf(doc))
        // N2: a document that has not been read yet is not an error here. Word is a text export,
        // so the pages that have no text are read now with the app's own on-device engine (the
        // same one the Text screen and OcrIndexer use) and the result is kept, so Text shows it
        // too and the next export of any kind needs no second pass.
        val text = ocrStore.readText(id).toMutableMap()
        val unread = doc.pageFiles.filter { it.name !in text }
        if (unread.isNotEmpty()) {
            val language = com.scanwhirl.app.util.OcrLanguageSettings.get(appContext())
            for (page in unread) {
                currentCoroutineContext().ensureActive()
                val result = try {
                    OcrEngine.recognizeFile(page, language)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Unreadable page: it stays "not read" and exports as an empty page, exactly
                    // like a page the recognizer found no text on.
                    continue
                } catch (_: OutOfMemoryError) {
                    continue
                }
                ocrStore.putPage(id, page.name, result.text, result.page)
                text[page.name] = result.text
            }
        }
        val geo = ocrStore.readGeo(id)
        val pages = doc.pageFiles.map { page ->
            wordPageLines(text[page.name] ?: "", geo[page.name])
        }
        if (pages.none { lines -> lines.any { it.isNotBlank() } }) {
            // T37: "Read the text first" left a Hindi user with nowhere to go. Name the setting
            // that actually decides whether the page can be read.
            throw NoTextForExportException(context.getString(R.string.tools_word_no_text))
        }
        if (includePictures) {
            val context = currentCoroutineContext()
            DocxWriter.writeWithPictures(
                outFile,
                doc.pageFiles.size,
                pageLines = { index -> pages[index] },
                pagePicture = { index ->
                    context.ensureActive()
                    wordPageJpeg(doc.pageFiles[index])
                }
            )
        } else {
            DocxWriter.write(outFile, pages)
        }
        outFile
    }

    /**
     * T38: the paragraphs one page contributes to the Word file. [stored] is the recognised text
     * (the long-standing source: one paragraph per line). When the page's stored geometry still
     * belongs to that text, the lines are put in true reading order ([ReadingOrder]), so a
     * two-column page or a sidebar is read column by column instead of interleaved. A page whose
     * geometry is missing, no longer matches the text (the user edited it) or shows no confident
     * gutter keeps exactly the stored order: never worse than before.
     */
    private fun wordPageLines(stored: String, ocr: com.scanwhirl.app.model.OcrPage?): List<String> {
        val today = stored.lines().dropLastWhile { it.isBlank() }
        if (ocr == null) return today
        val boxes = ocr.lines.filter { it.text.isNotBlank() }
        val textLines = today.filter { it.isNotBlank() }
        if (boxes.isEmpty() || boxes.size != textLines.size) return today
        // The stored text wins on every edit: geometry that belongs to different text is stale.
        for (index in boxes.indices) {
            if (boxes[index].text.trim() != textLines[index].trim()) return today
        }
        val lines = boxes.map {
            ReadingOrder.LineBox(it.text, OcrBox(it.left, it.top, it.right, it.bottom))
        }
        val ordered = ReadingOrder.order(lines)
        if (ordered == lines) return today
        return ReadingOrder.toTextLines(ordered)
    }

    /** A page for an embedded Word picture: at most 1600 px on the long side, JPEG 90. */
    private fun wordPageJpeg(page: File): ByteArray {
        val bitmap = ImageTools.decodeSampledFile(page, WORD_PICTURE_MAX_PX)
        return try {
            ImageTools.encodeJpeg(bitmap, 90)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Writes a minimal valid `.pptx`: one slide per page, each slide a picture of the page
     * fitted to the slide; the page's OCR text goes in the speaker notes. Slide size follows
     * the first page's displayed aspect (A4, portrait or landscape). A page stored sideways
     * (a PDF page with `/Rotate` 90 or 270, which the importer renders without turning) is
     * turned upright before it becomes the slide picture; see [slideUprightRotation].
     * Pages are encoded one at a time.
     */
    suspend fun exportPowerPoint(id: String, outFile: File): File = withContext(Dispatchers.IO) {
        val doc = loadDocument(File(docsDir, id)) ?: throw IllegalStateException("Scan not found")
        refuseLockedSources(listOf(doc))
        require(doc.pageFiles.isNotEmpty()) { "This document has no pages" }
        val text = ocrStore.readText(id)
        val geometry = ocrStore.readGeo(id)
        val firstSize = ImageTools.imageSize(doc.pageFiles.first().readBytes())
            ?: throw IllegalStateException("Page ${doc.pageFiles.first().name} can't be read")
        val rotations = doc.pageFiles.map { page -> slideUprightRotation(page, geometry[page.name]) }
        val context = currentCoroutineContext()
        PptxWriter.write(
            outFile,
            doc.pageFiles.size,
            portrait = slidePortrait(firstSize.first, firstSize.second, rotations.first()),
            pageJpeg = { index ->
                context.ensureActive()
                slidePageJpeg(doc.pageFiles[index], rotations[index])
            },
            pageNotes = { index -> text[doc.pageFiles[index].name] }
        )
        outFile
    }

    /**
     * Clockwise degrees (0/90/180/270) that make a slide page read the way a PDF reader shows
     * it. The importer renders the page pixels without applying the PDF page's `/Rotate`, so a
     * page that was turned on its side is stored sideways. A stored EXIF orientation is the
     * explicit case. Otherwise the stored OCR line boxes decide: a turned line has a tall box,
     * and its text starts on a shared margin. That margin is the box top for a page stored
     * turned clockwise (so it goes back counter-clockwise, 270) and the box bottom for the
     * other way (90). No geometry means no guess: the page is left exactly as stored.
     */
    private fun slideUprightRotation(page: File, geometry: OcrPage?): Int {
        val exif = OcrEngine.exifRotation(page)
        if (exif % 360 != 0) return exif
        return geometry?.let { sidewaysRotation(it) } ?: 0
    }

    /**
     * 270 when the recognised lines' tops share one margin, 90 when their bottoms do, 0 when
     * the lines are upright or the evidence is mixed. Only a page whose lines are mostly
     * taller than wide is considered, so ordinary text is never turned.
     */
    internal fun sidewaysRotation(page: OcrPage): Int {
        val lines = page.lines.filter {
            it.text.isNotBlank() && it.right > it.left && it.bottom > it.top
        }
        if (lines.size < 3) return 0
        val tall = lines.count { (it.bottom - it.top).toFloat() >= (it.right - it.left) * 1.25f }
        if (tall * 2 < lines.size) return 0
        val topSpread = spread(lines.map { it.top })
        val bottomSpread = spread(lines.map { it.bottom })
        return when {
            bottomSpread > topSpread * 2 + 8 -> 270
            topSpread > bottomSpread * 2 + 8 -> 90
            else -> 0
        }
    }

    /** Interquartile range: one centred title or indented line cannot decide the margin. */
    private fun spread(values: List<Int>): Int {
        if (values.size < 2) return 0
        val sorted = values.sorted()
        return sorted[(sorted.size * 3) / 4] - sorted[sorted.size / 4]
    }

    /** True when the page, after its upright turn, is taller than it is wide. */
    private fun slidePortrait(width: Int, height: Int, rotation: Int): Boolean =
        if (rotation % 180 == 0) height >= width else width >= height

    /**
     * The workbook a To Excel run wrote, plus how many pages carried a table. [file] is null when
     * [tablesOnly] found no table on any page: nothing is written then, so the caller can offer
     * the all-text export instead of a workbook full of "no table" notes.
     */
    data class ExcelExport(
        val file: File?,
        val tablePages: Int,
        val totalPages: Int,
        /** Page indices (0-based) the recogniser could not read; their sheets say so honestly. */
        val unreadPages: List<Int> = emptyList()
    )

    /**
     * Writes a `.xlsx` workbook: one sheet per page ("Page 1", ...). A page whose stored OCR
     * geometry forms a table becomes a cell grid; other pages export one line per cell.
     * [tablesOnly] keeps only the pages with a table (and writes nothing at all when no page has
     * one). Pages with no stored OCR are recognised now with the app's existing on-device engine,
     * in the user's Text-language setting, and the result is kept (as To Word does). A page whose
     * stored text has no geometry (typed in the Text screen, or corrected after recognition)
     * exports exactly that text and is never re-read.
     */
    suspend fun exportExcel(
        id: String,
        outFile: File,
        tablesOnly: Boolean = false,
        noTableNote: String = "No table found on this page.",
        unreadNote: String? = null
    ): ExcelExport = withContext(Dispatchers.IO) {
        val doc = loadDocument(File(docsDir, id)) ?: throw IllegalStateException("Scan not found")
        refuseLockedSources(listOf(doc))
        require(doc.pageFiles.isNotEmpty()) { "This document has no pages" }
        val storedText = ocrStore.readText(id)
        val storedGeo = ocrStore.readGeo(id)
        // H4-5: a page the recogniser never read has no table claim to make, so it says so instead
        // of "no table found on this page".
        val unreadNoteText = unreadNote ?: context.getString(R.string.tools_excel_unread_page)
        val unreadPages = mutableListOf<Int>()
        val pages = ArrayList<OcrPage>(doc.pageFiles.size)
        for ((index, page) in doc.pageFiles.withIndex()) {
            currentCoroutineContext().ensureActive()
            val geo = storedGeo[page.name]
            val text = storedText[page.name]
            pages += when {
                // Geometry is dropped by the store whenever the text is edited, so a page that still
                // has geometry is the page the user sees.
                geo != null -> geo
                // H4-2: text without geometry is the user's own entry or correction. Export it and
                // never replace it with a fresh read of the image.
                text != null -> textOnlyOcrPage(text)
                else -> {
                    val result = try {
                        OcrEngine.recognizeFile(page, com.scanwhirl.app.util.OcrLanguageSettings.get(appContext()))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    } catch (_: OutOfMemoryError) {
                        null
                    }
                    if (result == null) {
                        unreadPages += index
                        OcrPage(0, 0, emptyList())
                    } else {
                        // H4-2: keep what was read, exactly like To Word, so the Text screen shows
                        // it and the next export needs no second English pass.
                        ocrStore.putPage(id, page.name, result.text, result.page)
                        result.page
                    }
                }
            }
        }
        val tables = pages.map { TableDetect.detect(it) }
        val tablePages = tables.count { it != null }
        if (tablesOnly && tablePages == 0) {
            return@withContext ExcelExport(
                file = null, tablePages = 0, totalPages = pages.size, unreadPages = unreadPages
            )
        }
        val sheets = pages.mapIndexedNotNull { index, ocr ->
            val table = tables[index]
            when {
                table != null -> XlsxWriter.Sheet("Page ${index + 1}", table.rows)
                tablesOnly -> null
                index in unreadPages -> XlsxWriter.Sheet("Page ${index + 1}", listOf(listOf(unreadNoteText)))
                else -> XlsxWriter.Sheet(
                    "Page ${index + 1}",
                    ocr.lines.filter { it.text.isNotBlank() }.map { listOf(it.text) }
                        .ifEmpty { listOf(listOf(noTableNote)) }
                )
            }
        }
        XlsxWriter.write(outFile, sheets)
        ExcelExport(
            file = outFile, tablePages = tablePages, totalPages = pages.size, unreadPages = unreadPages
        )
    }

    /** A page known only by its stored text: one line per cell, no geometry to build a table from. */
    private fun textOnlyOcrPage(text: String): OcrPage =
        OcrPage(0, 0, text.lines().dropLastWhile { it.isBlank() }.map { OcrLine(it, 0, 0, 0, 0) })

    /** A page for a slide, turned upright by [uprightRotation]: at most 2000 px, JPEG 90. */
    private fun slidePageJpeg(page: File, uprightRotation: Int): ByteArray {
        val bitmap = ImageTools.decodeSampledFile(page, PPTX_PICTURE_MAX_PX)
        val turned = if (uprightRotation % 360 == 0) {
            bitmap
        } else {
            Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height,
                android.graphics.Matrix().apply { postRotate(uprightRotation.toFloat()) }, true
            )
        }
        return try {
            ImageTools.encodeJpeg(turned, 90)
        } finally {
            if (turned !== bitmap) turned.recycle()
            bitmap.recycle()
        }
    }

    /**
     * Creates a metadata-free copy: every page is decoded and re-encoded, which drops EXIF,
     * maker notes and embedded thumbnails, and the PDF is regenerated by the generator (which
     * writes no document information). Recognised text and its geometry are carried over, so a
     * searchable document stays searchable and a selectable PDF keeps its text. The original is
     * untouched.
     */
    suspend fun removeMetadata(id: String): ScanDocument = withContext(Dispatchers.IO) {
        val doc = loadDocument(File(docsDir, id)) ?: throw IllegalStateException("Scan not found")
        refuseLockedSources(listOf(doc))
        require(doc.pageFiles.isNotEmpty()) { "This document has no pages" }
        val texts = ocrStore.readText(id)
        val geo = ocrStore.readGeo(id)
        val hadTextLayer = File(File(docsDir, id), "text_layer.flag").exists()
        buildAndPublish(
            outputName(doc.name, "Clean"),
            doc.category,
            doc.pageFiles.sumOf { it.length() },
            forceTextLayers = hadTextLayer || Prefs.makeSearchable(context)
        ) { staging ->
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
            if (texts.isNotEmpty()) {
                OcrStore(OpStaging.root(context.filesDir)).putAll(staging.name, texts, geo)
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

    /**
     * L5 Edit & sign: bakes [edits] (vector marks per page) into a **new copy** and returns it.
     *
     * Order matters for data safety:
     *  1. the source is only read; a locked source is refused (its password cannot re-encrypt);
     *  2. [duplicateDocument] makes the copy, so a failure can never leave the library half-edited;
     *  3. each page is decoded once (≤ 3000 px), marked and swapped through [PageReplace];
     *  4. every marked page loses its kept `.orig` (a filter must never restore the unmarked
     *     pixels); a page carrying a redaction also loses its OCR text/geometry and is re-queued
     *     for recognition — the old text and unredacted pixels are not in the copy;
     *  5. the PDF is regenerated and verified before the copy is handed back.
     *
     * Any failure deletes the unfinished copy and leaves the source byte-identical.
     */
    suspend fun stampDocument(
        id: String,
        edits: List<PageStamp.PageEdits>,
        suggestedName: String?
    ): ScanDocument = withContext(Dispatchers.IO) {
        val source = loadDocument(File(docsDir, id))
            ?: throw IllegalStateException(context.getString(R.string.stamp_not_found))
        refuseLockedSources(listOf(source))

        val effective = edits.mapNotNull { edit ->
            val marks = edit.marks.filterNot { mark ->
                mark is PageStamp.Mark.Text && mark.text.isBlank()
            }
            if (marks.isEmpty()) null else PageStamp.PageEdits(edit.pageIndex, marks)
        }
        require(effective.isNotEmpty()) { context.getString(R.string.stamp_no_marks) }
        effective.forEach { edit ->
            require(edit.pageIndex in source.pageFiles.indices) {
                context.getString(R.string.stamp_no_page)
            }
        }

        val marks = effective.flatMap { it.marks }
        val existingNames = listDocuments(includeVaulted = true).map { it.name }
        val copy = duplicateDocument(
            id,
            suggestedName?.trim()?.takeIf { it.isNotBlank() }
                ?: OutputNames.forTool(source.name, PageStamp.outputLabel(marks), existingNames)
        )
        val copyDir = File(docsDir, copy.id)
        try {
            val pages = pageFilesIn(copyDir)
            effective.groupBy { it.pageIndex }.toSortedMap().forEach { (index, pageEdits) ->
                val page = pages[index]
                val marks = pageEdits.flatMap { it.marks }
                PageStamp.applyPage(page, marks, index + 1, pages.size)
                // H3-3: any burned-in mark makes the kept `.orig` stale. Leaving it beside a
                // marked page let a later filter (even Original) rebuild the page from pixels
                // that never had the signature/watermark. Redaction additionally drops the
                // recognised text, because the old text described the hidden pixels.
                File(page.parentFile, page.nameWithoutExtension + ".orig").delete()
                if (marks.any { it is PageStamp.Mark.Redact }) {
                    // I9/E10: no unredacted pixels and no recognised text survive in the copy.
                    ocrStore.invalidate(copy.id, page.name)
                }
            }
            regeneratePdf(copyDir)
            PdfVerifier.verify(
                File(copyDir, PdfProtection.PDF_FILE),
                expectedImages = pageFilesIn(copyDir)
            )
            loadDocument(copyDir)
                ?: throw IOException(context.getString(R.string.stamp_copy_failed))
        } catch (t: Throwable) {
            try {
                deleteDocument(copy.id)
            } catch (_: Exception) {
                // The error below is the one worth showing; an incomplete copy is recovered by
                // the existing trash sweep, and it is never offered as a finished document.
            }
            throw t
        }
    }

    // ------------------------------------------------------------------
    // PM-2.2b/2.3b: page drafts (manifest over the immutable base)
    // ------------------------------------------------------------------

    /** Result of [replaceWithDraft]: the same document id and the Trash key of its old version. */
    internal data class ReplaceResult(val document: ScanDocument, val trashKey: String)

    /** One manifest read for the viewer: the document, its visible pages and its draft. */
    internal data class DraftState(
        val document: ScanDocument?,
        val pages: List<File>,
        val draft: DraftStore.Draft?,
        /** Set when the manifest is damaged; the viewer shows the base pages and Discard only. */
        val damage: DraftStore.DraftDamaged? = null
    )

    /**
     * The draft of [id], or null. Never writes: opening a draft is O(1) (G14). A damaged manifest
     * throws [DraftStore.DraftDamaged] rather than returning a partial draft.
     */
    internal suspend fun draftOf(id: String): DraftStore.Draft? = withContext(Dispatchers.IO) {
        synchronized(DRAFT_LOCK) { drafts.load(id) }
    }

    /**
     * Everything the viewer needs in **one** manifest parse and one document load. The old
     * `draftPages(id) to draftOf(id)` pair read and parsed the same manifest twice (L5-2).
     */
    internal suspend fun draftState(id: String): DraftState = withContext(Dispatchers.IO) {
        val document = getDocument(id)
        try {
            val draft = synchronized(DRAFT_LOCK) { drafts.load(id) }
            DraftState(document, pagesOf(id, document, draft), draft)
        } catch (damage: DraftStore.DraftDamaged) {
            DraftState(document, document?.pageFiles.orEmpty(), null, damage)
        }
    }

    /** Pages as the draft sees them (replacement files where an edit wrote one), or the base pages. */
    internal suspend fun draftPages(id: String): List<File> = withContext(Dispatchers.IO) {
        synchronized(DRAFT_LOCK) {
            val draft = drafts.load(id)
            if (draft == null) getDocument(id)?.pageFiles.orEmpty()
            else pagesOf(id, null, draft)
        }
    }

    private fun pagesOf(
        id: String,
        document: ScanDocument?,
        draft: DraftStore.Draft?
    ): List<File> {
        if (draft == null) return document?.pageFiles.orEmpty()
        return draft.entries.map { entry ->
            entry.replacement?.let { drafts.fileOf(id, it) }
                ?: File(File(docsDir, id), requireNotNull(entry.source) { "Draft page has no source" })
        }
    }

    /** Rotates one draft page into a fresh `r_<n>` (and its kept original), then commits the manifest. */
    internal suspend fun draftRotate(
        id: String,
        index: Int,
        clockwise: Boolean = true
    ): DraftStore.Draft = withContext(Dispatchers.IO) {
        synchronized(DRAFT_LOCK) {
            val doc = draftEditDoc(id)
            val draft = draftFor(doc)
            require(index in draft.entries.indices) { "No such page" }
            val entry = draft.entries[index]
            val n = draft.next
            val dir = drafts.folderOf(id).apply { mkdirs() }
            val jpg = File(dir, "r_$n.jpg")
            Durable.copyFile(entryFile(id, entry), jpg)
            PageReplace.commit(ImageTools.stageRotate90(jpg, clockwise))
            var origName: String? = null
            entryOrig(id, entry)?.let { orig ->
                val outOrig = File(dir, "r_$n.orig")
                Durable.copyFile(orig, outOrig)
                PageReplace.commit(ImageTools.stageRotate90(outOrig, clockwise))
                origName = "r_$n.orig"
            }
            OpFaults.at("draft:replacement_written")
            storeDraft(
                draft.copy(
                    updated = System.currentTimeMillis(),
                    changes = draft.changes + 1,
                    next = n + 1,
                    entries = draft.entries.toMutableList().also {
                        it[index] = entry.copy(
                            replacement = "r_$n.jpg",
                            replacementOrig = origName
                        )
                    }
                )
            )
        }
    }

    /** Hides one draft page. The last page can never be deleted. */
    internal suspend fun draftDelete(id: String, index: Int): DraftStore.Draft = withContext(Dispatchers.IO) {
        synchronized(DRAFT_LOCK) {
            val doc = draftEditDoc(id)
            val draft = draftFor(doc)
            require(index in draft.entries.indices) { "No such page" }
            require(draft.entries.size > 1) { "Can't delete the last page" }
            storeDraft(
                draft.copy(
                    updated = System.currentTimeMillis(),
                    changes = draft.changes + 1,
                    entries = draft.entries.toMutableList().also { it.removeAt(index) }
                )
            )
        }
    }

    /** Moves a draft page; `from == to` writes nothing. */
    internal suspend fun draftMove(id: String, from: Int, to: Int): DraftStore.Draft = withContext(Dispatchers.IO) {
        synchronized(DRAFT_LOCK) {
            val doc = draftEditDoc(id)
            val draft = draftFor(doc)
            require(from in draft.entries.indices && to in draft.entries.indices) { "No such page" }
            if (from == to) return@withContext draft
            storeDraft(
                draft.copy(
                    updated = System.currentTimeMillis(),
                    changes = draft.changes + 1,
                    entries = draft.entries.toMutableList().also { it.add(to, it.removeAt(from)) }
                )
            )
        }
    }

    /**
     * Inserts copies of [pages] at [position] (0 = start, entries.size = end). The files are
     * copied into the draft folder and never moved, so the source document is untouched (I7).
     * A `.orig` sibling is copied too, keeping filter-from-original behaviour.
     */
    internal suspend fun draftInsertPages(
        id: String,
        pages: List<File>,
        position: Int
    ): DraftStore.Draft = withContext(Dispatchers.IO) {
        synchronized(DRAFT_LOCK) {
            val doc = draftEditDoc(id)
            val draft = draftFor(doc)
            require(pages.isNotEmpty()) { "No pages to add" }
            require(pages.all { it.isFile && it.length() > 0L }) { "A page could not be read" }
            val dir = drafts.folderOf(id).apply { mkdirs() }
            var n = draft.next
            val inserted = ArrayList<DraftStore.Entry>(pages.size)
            pages.forEach { page ->
                val jpgName = "r_$n.jpg"
                Durable.copyFile(page, File(dir, jpgName))
                var origName: String? = null
                val orig = File(page.parentFile, page.nameWithoutExtension + ".orig")
                    .takeIf { it.isFile }
                if (orig != null) {
                    val oName = "r_$n.orig"
                    Durable.copyFile(orig, File(dir, oName))
                    origName = oName
                }
                inserted += DraftStore.Entry(null, null, jpgName, origName)
                n++
            }
            OpFaults.at("draft:replacement_written")
            val at = position.coerceIn(0, draft.entries.size)
            storeDraft(
                draft.copy(
                    updated = System.currentTimeMillis(),
                    changes = draft.changes + 1,
                    next = n,
                    entries = draft.entries.toMutableList().also { it.addAll(at, inserted) }
                )
            )
        }
    }

    /** Crops one draft page (and its kept original) into fresh `r_<n>` files. */
    internal suspend fun draftCrop(
        id: String,
        index: Int,
        rect: android.graphics.RectF
    ): DraftStore.Draft = withContext(Dispatchers.IO) {
        synchronized(DRAFT_LOCK) {
            val doc = draftEditDoc(id)
            val draft = draftFor(doc)
            require(index in draft.entries.indices) { "No such page" }
            val entry = draft.entries[index]
            val n = draft.next
            val dir = drafts.folderOf(id).apply { mkdirs() }
            val jpg = File(dir, "r_$n.jpg")
            Durable.copyFile(entryFile(id, entry), jpg)
            PageReplace.commit(ImageTools.stageCrop(jpg, rect))
            var origName: String? = null
            entryOrig(id, entry)?.let { orig ->
                val outOrig = File(dir, "r_$n.orig")
                Durable.copyFile(orig, outOrig)
                PageReplace.commit(ImageTools.stageCrop(outOrig, rect))
                origName = "r_$n.orig"
            }
            OpFaults.at("draft:replacement_written")
            storeDraft(
                draft.copy(
                    updated = System.currentTimeMillis(),
                    changes = draft.changes + 1,
                    next = n + 1,
                    entries = draft.entries.toMutableList().also {
                        it[index] = entry.copy(
                            replacement = "r_$n.jpg",
                            replacementOrig = origName
                        )
                    }
                )
            )
        }
    }

    /**
     * Applies [filter] to one draft page, always from the unfiltered version (never stacked).
     * `ORIGINAL` removes the replacement; when the kept original is the base page itself the entry
     * becomes a pure base reference, so OCR carry-over works again (PM-2.3b §5.3).
     */
    internal suspend fun draftFilter(
        id: String,
        index: Int,
        filter: ScanFilter
    ): DraftStore.Draft = withContext(Dispatchers.IO) {
        synchronized(DRAFT_LOCK) {
            val doc = draftEditDoc(id)
            val draft = draftFor(doc)
            require(index in draft.entries.indices) { "No such page" }
            val entry = draft.entries[index]
            val unfiltered = entry.replacementOrig?.let { drafts.fileOf(id, it) }
                ?: if (entry.replacement == null) baseOrig(id, entry) else null
            val dir = drafts.folderOf(id).apply { mkdirs() }

            if (filter == ScanFilter.ORIGINAL) {
                if (unfiltered == null) return@withContext draft
                val pureReference = entry.replacementOrig != null &&
                    entry.sourceSha != null && Sha256.of(unfiltered) == entry.sourceSha
                val newEntry: DraftStore.Entry
                val newNext: Int
                if (pureReference) {
                    newEntry = entry.copy(replacement = null, replacementOrig = null)
                    newNext = draft.next
                } else {
                    val n = draft.next
                    Durable.copyFile(unfiltered, File(dir, "r_$n.jpg"))
                    OpFaults.at("draft:replacement_written")
                    newEntry = entry.copy(replacement = "r_$n.jpg", replacementOrig = null)
                    newNext = n + 1
                }
                return@withContext storeDraft(
                    draft.copy(
                        updated = System.currentTimeMillis(),
                        changes = draft.changes + 1,
                        next = newNext,
                        entries = draft.entries.toMutableList().also { it[index] = newEntry }
                    )
                )
            } else {
                val source = unfiltered
                    ?: entry.replacement?.let { drafts.fileOf(id, it) }
                    ?: File(File(docsDir, id), requireNotNull(entry.source) { "Draft page has no source" })
                val n = draft.next
                val newOrig = entry.replacementOrig ?: run {
                    val outOrig = File(dir, "r_$n.orig")
                    Durable.copyFile(source, outOrig)
                    "r_$n.orig"
                }
                val bmp = BitmapFactory.decodeFile(source.path)
                    ?: throw IllegalStateException(
                        "This page's kept original can't be read, so nothing was changed."
                    )
                val out = filter.applyTo(bmp)
                try {
                    PageReplace.commit(PageReplace.stageJpeg(File(dir, "r_$n.jpg"), out, 92))
                } finally {
                    if (out !== bmp) out.recycle()
                    bmp.recycle()
                }
                OpFaults.at("draft:replacement_written")
                storeDraft(
                    draft.copy(
                        updated = System.currentTimeMillis(),
                        changes = draft.changes + 1,
                        next = n + 1,
                        entries = draft.entries.toMutableList().also {
                            it[index] = entry.copy(
                                replacement = "r_$n.jpg",
                                replacementOrig = newOrig
                            )
                        }
                    )
                )
            }
        }
    }

    /** Deletes one draft (never the base). */
    internal suspend fun discardDraft(id: String) {
        withContext(Dispatchers.IO) {
            synchronized(DRAFT_LOCK) { drafts.discard(id) }
        }
    }

    /**
     * Builds a **new copy** from the draft (name defaults to `draft_edited_name`), or extracts
     * [indices] of its pages. A changed base page fails as [DraftStore.DraftStale] and keeps the
     * draft. The draft is discarded only for the full-copy case, after the copy is published.
     */
    internal suspend fun saveDraftAsNew(
        id: String,
        name: String?,
        indices: List<Int>? = null
    ): ScanDocument = withContext(Dispatchers.IO) {
        val doc = draftEditDoc(id)
        val draft = synchronized(DRAFT_LOCK) { drafts.load(id) }
            ?: throw DraftStore.DraftStale(context.getString(R.string.draft_stale))
        val selected = indices?.distinct()?.sorted() ?: draft.entries.indices.toList()
        require(selected.isNotEmpty()) { "No pages selected" }
        require(selected.all { it in draft.entries.indices }) { "No such page" }
        val outName = name?.trim()?.replace('\n', ' ')?.takeIf { it.isNotBlank() }
            ?: if (indices == null) {
                context.getString(R.string.draft_edited_name, doc.name)
            } else {
                extractedName(doc.name, selected)
            }
        val seal = if (indices == null) SealSpec(id, draft.created, null, null) else null
        val copy = buildAndPublish(outName, doc.category, 0, seal = seal) { staging ->
            stageDraftPages(doc, draft, selected, staging)
        }
        if (indices == null) {
            synchronized(DRAFT_LOCK) { drafts.discard(id) }
            File(File(docsDir, copy.id), OpStaging.SEAL).delete()
        }
        copy
    }

    /**
     * Replaces the original with the draft under the SAME id (A1 Replace): the base is moved to Trash
     * with `previous=<token>` and the sealed staging takes its place in one rename. A death between
     * the two renames is rolled forward by [recoverOperations]; the previous version stays in Trash.
     */
    internal suspend fun replaceWithDraft(id: String): ReplaceResult = withContext(Dispatchers.IO) {
        val doc = draftEditDoc(id)
        val draft = synchronized(DRAFT_LOCK) { drafts.load(id) }
            ?: throw DraftStore.DraftStale(context.getString(R.string.draft_stale))
        require(draft.changes > 0) { "No unsaved page edits" }
        if (SaveFormat.pagesDigest(doc.pageFiles) != draft.basePages) {
            throw DraftStore.DraftStale(context.getString(R.string.draft_stale))
        }
        val published = buildAndPublish(
            doc.name,
            doc.category,
            0,
            seal = SealSpec(id, draft.created, id, draft.basePages)
        ) { staging ->
            stageDraftPages(doc, draft, draft.entries.indices.toList(), staging)
            // meta.txt (name/category/created), the Vault flag and a legacy transaction record are
            // adopted byte for byte: Replace must not lose what the user did outside the draft.
            listOf("meta.txt", "vault.flag", "txn.txt").forEach { name ->
                val file = File(File(docsDir, id), name)
                if (file.isFile) Durable.copyFile(file, File(staging, name))
            }
        }
        synchronized(DRAFT_LOCK) { drafts.discard(id) }
        File(File(docsDir, published.id), OpStaging.SEAL).delete()
        dropDerivedCaches(id)
        val trashKey = listTrash()
            .firstOrNull { it.originalId == id && it.previousToken != null }?.key.orEmpty()
        ReplaceResult(published, trashKey)
    }

    // ------------------------------------------------------------------ draft internals

    /** The base, refusing a locked document before any draft folder is created. */
    private fun draftEditDoc(id: String): ScanDocument {
        val doc = loadDocument(File(docsDir, id))
            ?: throw DraftStore.DraftStale(context.getString(R.string.draft_stale))
        if (doc.isLocked) {
            throw PdfProtection.DocumentLocked(context.getString(R.string.locked_edit_refused))
        }
        return doc
    }

    /** The existing draft, or a fresh manifest over the current base pages (no page copies). */
    private fun draftFor(doc: ScanDocument): DraftStore.Draft =
        drafts.load(doc.id) ?: freshDraft(doc)

    private fun freshDraft(doc: ScanDocument): DraftStore.Draft {
        require(doc.pageFiles.isNotEmpty()) { "This document has no pages" }
        val now = System.currentTimeMillis()
        return DraftStore.Draft(
            baseId = doc.id,
            basePages = SaveFormat.pagesDigest(doc.pageFiles),
            created = now,
            updated = now,
            changes = 0,
            next = 0,
            entries = doc.pageFiles.map { DraftStore.Entry(it.name, Sha256.of(it)) }
        )
    }

    /** Manifest replace is the commit point; the folder entry is synced before the fault point. */
    private fun storeDraft(draft: DraftStore.Draft): DraftStore.Draft {
        drafts.save(draft)
        Durable.syncDir(drafts.folderOf(draft.baseId))
        OpFaults.at("draft:saved")
        return draft
    }

    private fun entryFile(id: String, entry: DraftStore.Entry): File =
        entry.replacement?.let { drafts.fileOf(id, it) }
            ?: File(File(docsDir, id), requireNotNull(entry.source) { "Draft page has no source" })

    private fun entryOrig(id: String, entry: DraftStore.Entry): File? =
        entry.replacementOrig?.let { drafts.fileOf(id, it) }
            ?: if (entry.replacement == null) baseOrig(id, entry) else null

    private fun baseOrig(id: String, entry: DraftStore.Entry): File? {
        val source = entry.source ?: return null
        return origOf(File(File(docsDir, id), source)).takeIf { it.exists() }
    }

    /** Copies the chosen draft pages into [staging] and carries base OCR for untouched pages. */
    private suspend fun stageDraftPages(
        doc: ScanDocument,
        draft: DraftStore.Draft,
        selected: List<Int>,
        staging: File
    ) {
        val baseText = ocrStore.readText(doc.id)
        val baseGeo = ocrStore.readGeo(doc.id)
        val texts = LinkedHashMap<String, String>()
        val geo = LinkedHashMap<String, OcrPage>()
        selected.forEachIndexed { i, index ->
            currentCoroutineContext().ensureActive()
            val entry = draft.entries[index]
            val out = File(staging, String.format(Locale.ROOT, PAGE_FORMAT, i))
            if (entry.replacement != null) {
                Durable.copyFile(drafts.fileOf(doc.id, entry.replacement), out)
                entry.replacementOrig?.let {
                    Durable.copyFile(drafts.fileOf(doc.id, it), origOf(out))
                }
            } else {
                val source = requireNotNull(entry.source) { "Draft page has no source" }
                val src = File(File(docsDir, doc.id), source)
                if (!src.isFile) throw DraftStore.DraftStale(context.getString(R.string.draft_stale))
                Durable.copyFile(src, out)
                if (Sha256.of(out) != entry.sourceSha) {
                    throw DraftStore.DraftStale(context.getString(R.string.draft_stale))
                }
                origOf(src).takeIf { it.exists() }?.let { Durable.copyFile(it, origOf(out)) }
                baseText[source]?.let { texts[out.name] = it }
                baseGeo[source]?.let { geo[out.name] = it }
            }
            OpFaults.at("op:page:$i")
        }
        if (texts.isNotEmpty() || geo.isNotEmpty()) {
            OcrStore(OpStaging.root(context.filesDir)).putAll(staging.name, texts, geo)
        }
    }

    // ------------------------------------------------------------------
    // Batch tools: one action over many documents
    // ------------------------------------------------------------------

    /** One action a batch can run over many documents. */
    sealed interface BatchAction {
        data class Compress(
            val quality: Int,
            val maxDim: Int?,
            val targetBytes: Long? = null
        ) : BatchAction

        data object ExportPdf : BatchAction
        data class PdfToImages(val format: ImageExportFormat, val oneZip: Boolean) : BatchAction
        data object ToWord : BatchAction
        data object RemoveMetadata : BatchAction
        data class Protect(val password: String) : BatchAction
    }

    /** What happened to one document in a batch. Completed documents stay published. */
    data class BatchItem(
        val docId: String,
        val name: String,
        val ok: Boolean,
        val reason: String? = null,
        val outputId: String? = null,
        val files: List<File> = emptyList(),
        val savedName: String? = null
    )

    /**
     * Runs [action] over [ids], one document at a time. Every output goes through the same publish
     * path as the single-document tools; a failure on one document is recorded (reason) and the
     * batch continues. Cancellation stops the batch, keeps completed outputs, and the publish
     * path discards the document in progress (nothing partial). [onProgress] is called with the
     * 1-based document number before it is processed.
     */
    suspend fun runBatch(
        ids: List<String>,
        action: BatchAction,
        onProgress: (number: Int, total: Int, name: String) -> Unit = { _, _, _ -> },
        cancelled: () -> Boolean = { false }
    ): List<BatchItem> = withContext(Dispatchers.IO) {
        val unique = ids.distinct()
        if (unique.isEmpty()) return@withContext emptyList()
        val outDir = File(context.cacheDir, "documents/batch/${UUID.randomUUID().toString().take(8)}").apply { mkdirs() }
        val takenNames = mutableSetOf<String>()
        if (action is BatchAction.PdfToImages && action.oneZip) {
            return@withContext runOneZipBatch(unique, action.format, outDir, onProgress, cancelled)
        }
        val results = mutableListOf<BatchItem>()
        for ((index, id) in unique.withIndex()) {
            if (cancelled()) break
            currentCoroutineContext().ensureActive()
            val doc = loadDocument(File(docsDir, id))
            val name = doc?.name ?: id
            onProgress(index + 1, unique.size, name)
            currentCoroutineContext().ensureActive()
            if (doc == null) {
                results += BatchItem(id, name, ok = false, reason = "Document not found")
                continue
            }
            results += try {
                when (action) {
                    is BatchAction.Compress -> {
                        if (action.targetBytes != null) {
                            val out = compressScanToTarget(doc.id, action.targetBytes)
                            BatchItem(id, name, true, outputId = out.id)
                        } else {
                            val out = compressScan(doc.id, action.quality, action.maxDim)
                            BatchItem(id, name, true, outputId = out.id)
                        }
                    }
                    BatchAction.ExportPdf -> {
                        val saved = DownloadSaver.savePdfFile(
                            context, doc.pdfFile, DownloadSaver.pdfNameFor(doc.name)
                        )
                        runCatching { addExport(doc.id, saved) }
                        BatchItem(id, name, true, savedName = saved.name)
                    }
                    is BatchAction.PdfToImages -> {
                        val zipName = uniqueName(
                            "${ExportNames.baseName(doc.name)} - images.zip", takenNames
                        )
                        val out = File(outDir, zipName)
                        pdfToImages(doc.id, action.format, zip = true, outFile = out)
                        BatchItem(id, name, true, files = listOf(out))
                    }
                    BatchAction.ToWord -> {
                        val out = File(outDir, uniqueName(ExportNames.fileName(doc.name, "docx"), takenNames))
                        exportWord(doc.id, out)
                        BatchItem(id, name, true, files = listOf(out))
                    }
                    BatchAction.RemoveMetadata -> {
                        val out = removeMetadata(doc.id)
                        BatchItem(id, name, true, outputId = out.id)
                    }
                    is BatchAction.Protect -> {
                        val out = protectPdf(doc.id, action.password)
                        BatchItem(id, name, true, outputId = out.id)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                BatchItem(id, name, ok = false, reason = e.message ?: "Could not finish")
            }
            if (cancelled()) break
        }
        results
    }

    /** One aggregate image ZIP for all documents; one document fails alone, cancel leaves nothing. */
    private suspend fun runOneZipBatch(
        ids: List<String>,
        format: ImageExportFormat,
        outDir: File,
        onProgress: (Int, Int, String) -> Unit,
        cancelled: () -> Boolean
    ): List<BatchItem> {
        val out = File(outDir, "ScanWhirl images.zip")
        val failures = mutableMapOf<String, String>()
        val processed = mutableListOf<String>()
        val folders = ExportNames.Allocator()
        try {
            java.util.zip.ZipOutputStream(FileOutputStream(out)).use { aggregate ->
                for ((index, id) in ids.withIndex()) {
                    if (cancelled()) break
                    currentCoroutineContext().ensureActive()
                    val doc = loadDocument(File(docsDir, id))
                    val name = doc?.name ?: id
                    onProgress(index + 1, ids.size, name)
                    processed += id
                    currentCoroutineContext().ensureActive()
                    if (doc == null) {
                        failures[id] = "Document not found"
                        continue
                    }
                    val temp = File(outDir, "one_${doc.id}.zip")
                    try {
                        pdfToImages(doc.id, format, zip = true, outFile = temp)
                        java.util.zip.ZipFile(temp).use { zip ->
                            // H2-6: two documents with the same name get their own folder.
                            val folder = folders.folder(ExportNames.baseName(doc.name))
                            zip.entries().toList().forEach { entry ->
                                aggregate.putNextEntry(java.util.zip.ZipEntry("$folder/${entry.name}"))
                                zip.getInputStream(entry).use { it.copyTo(aggregate) }
                                aggregate.closeEntry()
                            }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failures[id] = e.message ?: "Could not export the images"
                    } finally {
                        temp.delete()
                    }
                }
            }
            if (out.length() == 0L) {
                out.delete()
                return processed.map { BatchItem(it, it, false, reason = "No images were written") }
            }
            Durable.syncFile(out)
        } catch (e: kotlinx.coroutines.CancellationException) {
            out.delete()
            throw e
        } catch (e: Exception) {
            out.delete()
            throw e
        }
        return processed.map { id ->
            val doc = loadDocument(File(docsDir, id))
            val name = doc?.name ?: id
            val reason = failures[id]
            if (reason == null) {
                BatchItem(id, name, true, files = listOf(out))
            } else {
                BatchItem(id, name, false, reason = reason)
            }
        }
    }

    private fun uniqueName(candidate: String, taken: MutableSet<String>): String {
        var name = candidate
        var n = 2
        while (!taken.add(name.lowercase(Locale.ROOT))) {
            val dot = candidate.lastIndexOf('.')
            name = if (dot > 0) {
                "${candidate.substring(0, dot)} ($n)${candidate.substring(dot)}"
            } else {
                "$candidate ($n)"
            }
            n++
        }
        return name
    }
}
