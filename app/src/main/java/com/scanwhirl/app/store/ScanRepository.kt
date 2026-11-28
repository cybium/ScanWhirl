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
import com.scanwhirl.app.util.PageOrder
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
import com.scanwhirl.app.util.SizeUnits
import com.scanwhirl.app.util.TableDetect
import com.scanwhirl.app.util.XlsxWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class ScanRepository(private val context: Context) {

    internal val docsDir: File
        get() = File(context.filesDir, "documents").apply { mkdirs() }

    internal val digitalStorage: DigitalStorage get() = DigitalStorage(context)
    internal val digitalTransaction: DigitalTransaction get() = DigitalTransaction(this)

    /** Both namespaces are disjoint. A conflicting or unsafe ID is unavailable. */
    internal fun resolveDocDir(id: String): File? {
        if (!isSafeId(id)) return null
        val raster = File(docsDir, id)
        val digitalRoot = digitalStorage.existingRoot(DigitalStorage.LIVE)
        val digital = File(digitalRoot, id)
        if (raster.exists() && digitalStorage.hasLive(id)) return null
        return when {
            digitalStorage.hasLive(id) -> digital.takeIf { DigitalStorage.owned(digitalRoot) &&
                DigitalStorage.owned(it) && it.isDirectory }
            raster.exists() -> raster.takeIf { DigitalStorage.isDirectChild(docsDir, it) && it.isDirectory }
            else -> null
        }
    }

    internal fun iterateLiveDocs(): List<File> =
        (docsDir.listFiles { f -> f.isDirectory }.orEmpty().toList() + digitalStorage.liveDirs())
            .filter { resolveDocDir(it.name)?.canonicalFile == it.canonicalFile }

    internal fun digitalIdReserved(id: String, ignoreStage: Boolean = false): Boolean =
        !DigitalStorage.safeId(id) || File(docsDir, id).exists() ||
            trashDir.listFiles().orEmpty().any { it.name == id || it.name.startsWith("$id~") } ||
            id in escrowReservedIds() || File(context.filesDir, "drafts/$id").exists() ||
            File(context.filesDir, "vault2/$id").exists() ||
            File(context.filesDir, ".vault_pending_purge/$id").exists() ||
            digitalStorage.reserves(id, ignoreStage)

    /** Unsupported digital operations must not reach a JPEG builder. */
    private fun rasterDir(id: String): File {
        if (digitalStorage.hasLive(id)) throw IOException("This operation does not yet support digital PDFs")
        return File(docsDir, id)
    }

    internal val ocrStore: OcrStore
        get() = OcrStore(docsDir, enforceLiveDocument = true)

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
         * L1lib #2 test seam: observes the thread that reads every meta.txt for tags.
         * Process-wide (several ScanRepository instances exist; the library's instance is
         * not the one a test holds). Production leaves it null.
         */
        @Volatile
        internal var listTagsProbe: ((thread: String) -> Unit)? = null

        /**
         * PVR test seam: called on the IO thread at the start of one draft page operation,
         * before it touches the manifest, so a concurrency test can hold that operation in
         * flight. Production leaves both null.
         */
        @JvmField
        @androidx.annotation.VisibleForTesting
        internal var draftFilterProbe: (() -> Unit)? = null

        @JvmField
        @androidx.annotation.VisibleForTesting
        internal var draftDeleteProbe: (() -> Unit)? = null

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
         * G1: bounded decode of a kept page before re-filtering. The old full-resolution
         * `BitmapFactory.decodeFile` could exhaust the heap on a large imported page; [decodeBoundedForRefilter]
         * caps the long side at the capture limit. Replaceable in tests to force OutOfMemoryError.
         */
        @Volatile internal var refilterDecode: (File) -> Bitmap = { decodeBoundedForRefilter(it) }

        /** Long-side cap for the refilter decode (the same ceiling a captured page can have). */
        internal const val REFILTER_MAX_LONG_SIDE = 4000

        private fun decodeBoundedForRefilter(file: File): Bitmap {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Not a decodable image" }
            var sample = 1
            // Ceiling, like ImageTools.pageSampleSize: floor division leaves an 8001 px source at
            // 4001 px after inSampleSize=2, one pixel over the cap.
            fun side(size: Int) = (size.toLong() + sample - 1) / sample
            while (side(bounds.outWidth) > REFILTER_MAX_LONG_SIDE || side(bounds.outHeight) > REFILTER_MAX_LONG_SIDE) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            return BitmapFactory.decodeFile(file.path, opts)
                ?: throw IllegalStateException("This page's kept original can't be read, so nothing was changed.")
        }

        /** G1 review P1: called under SAVE_LOCK after snapshotting an orphan, before the locked re-check. */
        @Volatile internal var orphanCleanupProbe: (() -> Unit)? = null

        /** G1 review P2: lock-record removal; false simulates a failed delete so the marker must survive. */
        @Volatile internal var removeLockFile: (File) -> Boolean = { it.delete() }

        /** G1 review: true when a startup recovery step failed; startup must not declare full success. */
        @Volatile internal var recoveryIncomplete: Boolean = false

        /** Lead decision: recursive removal of plaintext scratch; false simulates a failed delete. */
        @Volatile internal var recoveryFileDelete: (File) -> Boolean = { it.deleteRecursively() }

        /** SEC1 1.3.0: rename of a Vault original into the private purge area; false simulates failure. */
        @Volatile internal var vaultPendingMove: (File, File) -> Boolean = { from, to -> from.renameTo(to) }

        /** SEC1 1.3.0: recursive purge of a private Vault original; false simulates a partial delete. */
        @Volatile internal var vaultPendingDelete: (File) -> Boolean = { it.deleteRecursively() }

        /** SEC1 1.3.0: recursive delete in [deleteFromTrash]; false simulates a partial delete. */
        @Volatile internal var trashPurgeDelete: (File) -> Boolean = { it.deleteRecursively() }

        /** Lead decision: fault seam at the start of each startup recovery step; may throw. */
        @Volatile internal var recoveryStepFault: ((String) -> Unit)? = null

        /** Sol re-review: page/PDF hashing during recovery; a throw means UNKNOWN, never mismatch. */
        @Volatile internal var recoveryHash: (File) -> String = { Sha256.of(it) }

        /**
         * Sol final re-review: [recoveryIncomplete] is a PASS-WIDE accumulated flag. Reset it once,
         * at the start of the startup pass, never inside a later step: a step that failed earlier
         * (e.g. import staging) must stay visible after recoverOperations runs cleanly.
         */
        fun beginStartupRecoveryPass() {
            recoveryIncomplete = false
        }

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
            refilterDecode = { decodeBoundedForRefilter(it) }
            orphanCleanupProbe = null
            removeLockFile = { it.delete() }
            recoveryIncomplete = false
            recoveryFileDelete = { it.deleteRecursively() }
            vaultPendingMove = { from, to -> from.renameTo(to) }
            vaultPendingDelete = { it.deleteRecursively() }
            trashPurgeDelete = { it.deleteRecursively() }
            recoveryStepFault = null
            recoveryHash = { Sha256.of(it) }
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

        /**
         * SECURITY_JUDGE 1.3.0 finding 2: plaintext originals of documents moving
         * into the Vault live here until purged. This folder is neither [docsDir]
         * nor [trashDir], so no Trash screen or restore path can list them.
         */
        private const val VAULT_PENDING_PURGE_DIR = ".vault_pending_purge"

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
        return iterateLiveDocs().sortedByDescending { it.lastModified() }
            .mapNotNull { dir -> loadDocument(dir) }
            .filter { includeVaulted || !it.isVaulted }
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
        resolveDocDir(id)?.let(::loadDocument)

    internal fun loadDocument(dir: File): ScanDocument? {
        if (!dir.exists() || !dir.isDirectory) return null
        val parent = dir.parentFile?.canonicalFile
        if (parent == digitalStorage.existingRoot(DigitalStorage.LIVE) ||
            parent == digitalStorage.existingRoot(DigitalStorage.TRASH)) {
            return DigitalStorage.read(dir)
        }
        val meta = File(dir, "meta.txt")
        if (!meta.exists()) return null
        val lines = try { meta.readLines() } catch (_: IOException) { return null }
        if (lines.isEmpty() || lines.drop(3).any { it.startsWith("content_kind=") && it != "content_kind=raster" }) return null
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
            ?.sortedWith { a, b -> PageOrder.comparator.compare(a.first, b.first) }
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
        DocumentNames.unique(proposed, iterateLiveDocs()
            .filter { it.absoluteFile != dir.absoluteFile }
            .mapNotNull { loadDocument(it)?.name })

    /** Preserve timestamps, category, exports and unknown metadata while resolving just the title. */
    internal fun resolveNameBeforePublish(dir: File) {
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
        updateMeta(resolveDocDir(id) ?: return) { lines -> recordSeen(lines, uri, fingerprint) }
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
        updateMeta(resolveDocDir(id) ?: return) { lines ->
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
        updateMeta(resolveDocDir(id) ?: return) { lines ->
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

    fun exportRecordsOf(id: String): List<ExportRecord> = resolveDocDir(id)?.let(::exportRecordsIn).orEmpty()

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
    fun provenExportsOf(id: String): List<ProvenExport> = resolveDocDir(id)?.let { provenExportsIn(it, id) }.orEmpty()

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
    fun exportsRecordedByOthers(id: String): Set<Uri> = exportsRecordedOutside(resolveDocDir(id) ?: File(docsDir, id))

    internal fun exportsRecordedOutside(exclude: File): Set<Uri> {
        val dirs = (docsDir.listFiles { f -> f.isDirectory }.orEmpty().asList() +
            trashDir.listFiles { f -> f.isDirectory }.orEmpty().asList() +
            digitalStorage.liveDirs() + digitalStorage.dirs(DigitalStorage.TRASH))
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
        val dir = resolveDocDir(id) ?: return null
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
    //
    // L1lib3: every write to folders.txt has a proven safe order, so a crash can never
    // leave a document pointing at a name that is absent from the folder list:
    //  * createFolder        list first, no document can reference the name yet.
    //  * renameFolder        list with BOTH names, documents, then final list.
    //  * setFolder (move)    create the target folder first, then rewrite documents.
    //  * deleteFolder        documents first, then list; a tombstone completes a delete
    //                        interrupted between the two on the next startup.
    // Reconciliation is startup-only (recoverOperations), never in listDocuments(): that
    // getter is called on the main thread and must never write folders.txt.
    // ------------------------------------------------------------------

    private val foldersFile: File get() = File(context.filesDir, "library_folders.txt")
    private val deletingFile: File get() = File(context.filesDir, "library_folders_deleting.txt")

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

    /**
     * Renames a folder and every document in it. Documents are never lost.
     *
     * L1lib #6 / L1lib2 P1: folders.txt is written TWICE so it is consistent at every step:
     *  1. write the list with BOTH old and new names. From this point on a document that
     *     carries either name still resolves to a real folder, even if the process dies.
     *  2. rewrite every document from [oldName] to [newName].
     *  3. write the final list with the old name removed.
     *
     * A failure in step 1 changes nothing. A failure in steps 2 or 3 rolls the documents
     * back and restores the original list. An `Error` (simulated process death) is NOT
     * caught: the step-1 list keeps both names, so no document is orphaned. Any leftover
     * folder name found only in documents is re-added by [reconcileFolders] on startup or
     * the next library load.
     */
    fun renameFolder(oldName: String, newName: String): Boolean {
        val clean = LibraryMeta.normalizeFolder(newName) ?: return false
        synchronized(META_LOCK) {
            val all = listFolders()
            val at = all.indexOfFirst { it.equals(oldName, true) }
            if (at < 0) return false
            if (all.any { it.equals(clean, true) && !it.equals(oldName, true) }) return false

            val finalList = all.toMutableList().also { it[at] = clean }
            // Step 1: both names exist before any document points at the new one.
            writeFolders((all + clean).distinctBy { it.lowercase(Locale.getDefault()) })

            val changed = mutableListOf<File>()
            try {
                // Step 2: rewrite the documents.
                forEachDocumentDir { dir ->
                    val lines = metaLines(dir) ?: return@forEachDocumentDir
                    val meta = LibraryMeta.read(lines)
                    if (meta.folder != null && meta.folder.equals(oldName, true)) {
                        updateMeta(dir) { current ->
                            val m = LibraryMeta.read(current)
                            if (m.folder != null && m.folder.equals(oldName, true)) {
                                LibraryMeta.write(current, clean, m.tags)
                            }
                        }
                        changed += dir
                    }
                }
                // Step 3: drop the old name only after every document was rewritten.
                writeFolders(finalList)
            } catch (t: Exception) {
                // Best effort: undo documents and restore the original folder list so the
                // two stores agree again. An Error (process death) skips this on purpose.
                for (dir in changed) {
                    runCatching {
                        updateMeta(dir) { current ->
                            val m = LibraryMeta.read(current)
                            if (m.folder != null && m.folder.equals(clean, true)) {
                                LibraryMeta.write(current, oldName, m.tags)
                            }
                        }
                    }
                }
                runCatching { writeFolders(all) }
                throw t
            }
            return true
        }
    }

    /**
     * L1lib2 P1 / L1lib3 P2: startup-only. Re-add any VALID folder name still used by a
     * document but missing from folders.txt (e.g. after a process death between the two
     * rename writes). Documents are never silently moved into "All only". Never called
     * from [listDocuments]: that getter can run on the main thread and is read-only.
     */
    fun reconcileFolders() {
        synchronized(META_LOCK) {
            val used = mutableSetOf<String>()
            forEachDocumentDir { dir ->
                metaLines(dir)?.let { lines ->
                    LibraryMeta.read(lines).folder?.let { used += it }
                }
            }
            reconcileFrom(used)
        }
    }

    /**
     * Adds [folders] missing from folders.txt, applying the SAME rules as a user-created
     * folder (normalizeFolder: trimmed, whitespace-collapsed, <=40 chars). A name that is
     * blank, overlong, or not already in normal form is ignored, never written back.
     */
    private fun reconcileFrom(folders: Collection<String>) {
        val valid = folders.mapNotNull { name ->
            val clean = LibraryMeta.normalizeFolder(name)
            if (clean != null && clean == name) clean else null
        }.toSet()
        if (valid.isEmpty()) return
        synchronized(META_LOCK) {
            val known = listFolders()
            val missing = valid.filter { name -> known.none { it.equals(name, true) } }
            if (missing.isNotEmpty()) writeFolders(known + missing)
        }
    }

    /**
     * Deletes a folder; its documents move to "All" and stay in the library.
     *
     * L1lib3 P1: rewrite DOCUMENTS first, then remove the name from folders.txt. The old
     * order removed the name first, so a death before the document rewrites left documents
     * referencing a folder that startup reconciliation would faithfully re-add, resurrecting
     * a folder the user had deleted. A tombstone written before the first step lets startup
     * finish an interrupted delete instead.
     */
    fun deleteFolder(name: String) {
        val clean = LibraryMeta.normalizeFolder(name) ?: return
        synchronized(META_LOCK) {
            Durable.atomicWriteText(deletingFile, clean)
            forEachDocumentDir { dir ->
                updateMeta(dir) { lines ->
                    val meta = LibraryMeta.read(lines)
                    if (meta.folder != null && meta.folder.equals(clean, true)) {
                        LibraryMeta.write(lines, null, meta.tags)
                    }
                }
            }
            writeFolders(listFolders().filterNot { it.equals(clean, true) })
            deletingFile.delete()
        }
    }

    /** L1lib3 P1: finish a delete interrupted by process death or a disk failure. */
    private fun finishPendingDelete() {
        val raw = runCatching { deletingFile.readText().trim() }.getOrNull() ?: return
        val name = LibraryMeta.normalizeFolder(raw) ?: run {
            deletingFile.delete()
            return
        }
        synchronized(META_LOCK) {
            forEachDocumentDir { dir ->
                updateMeta(dir) { lines ->
                    val meta = LibraryMeta.read(lines)
                    if (meta.folder != null && meta.folder.equals(name, true)) {
                        LibraryMeta.write(lines, null, meta.tags)
                    }
                }
            }
            writeFolders(listFolders().filterNot { it.equals(name, true) })
            deletingFile.delete()
        }
    }

    /** Moves documents to [folder] (null = "All"). A missing folder is created. */
    fun setFolder(ids: List<String>, folder: String?) {
        val clean = LibraryMeta.normalizeFolder(folder)
        if (clean != null) createFolder(clean)
        for (id in ids.distinct()) {
            updateMeta(resolveDocDir(id) ?: continue) { lines ->
                val meta = LibraryMeta.read(lines)
                LibraryMeta.write(lines, clean, meta.tags)
            }
        }
    }

    fun setTags(id: String, tags: List<String>) {
        updateMeta(resolveDocDir(id) ?: return) { lines ->
            val meta = LibraryMeta.read(lines)
            LibraryMeta.write(lines, meta.folder, LibraryMeta.normalizeTags(tags))
        }
    }

    fun addTag(ids: List<String>, tag: String) {
        val clean = LibraryMeta.normalizeTags(listOf(tag)).firstOrNull() ?: return
        for (id in ids.distinct()) {
            updateMeta(resolveDocDir(id) ?: continue) { lines ->
                val meta = LibraryMeta.read(lines)
                LibraryMeta.write(lines, meta.folder, meta.tags + clean)
            }
        }
    }

    fun removeTag(id: String, tag: String) {
        updateMeta(resolveDocDir(id) ?: return) { lines ->
            val meta = LibraryMeta.read(lines)
            LibraryMeta.write(lines, meta.folder, meta.tags.filterNot { it.equals(tag, true) })
        }
    }

    /** Every tag used in the library, for the tag sheet's quick picks. */
    fun listTags(): List<String> {
        listTagsProbe?.invoke(Thread.currentThread().name)
        return synchronized(META_LOCK) {
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
    }

    private inline fun forEachDocumentDir(action: (File) -> Unit) {
        iterateLiveDocs().forEach { dir ->
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
            val (crossRootConflict, reservedNow) = synchronized(TRASH_LOCK) {
                val conflict = digitalStorage.reserves(target)
                conflict to (!conflict && candidate.mkdir())
            }
            if (crossRootConflict) {
                if (++attempts >= RESERVE_ATTEMPTS) throw IOException("Could not reserve a document")
                journal = SaveFormat.Journal(SaveFormat.newToken(), SaveFormat.newDocId())
                reviewDir?.let { SaveFormat.persistJournal(it, journal) }
                target = journal.target
                continue
            }
            if (reservedNow) {
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
                    val reclaimed = synchronized(TRASH_LOCK) {
                        if (digitalStorage.reserves(target)) false
                        else {
                            candidate.deleteRecursively()
                            !candidate.exists() && candidate.mkdir()
                        }
                    }
                    if (reclaimed) {
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
        } catch (e: Throwable) {
            // S1 review P2: an Error (e.g. OutOfMemoryError) is not an Exception. Its owned
            // directory must be discarded, then the failure rethrown. The test-only
            // SimulatedProcessDeath is the one Error that MUST skip cleanup: a real process
            // kill runs no cleanup and the reaper relies on the leftover (SaveFaults contract).
            if (e !is SimulatedProcessDeath) discardOwned(dir, token)
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
    /**
     * Task04: destructive result action. Provenance is checked and deletion happens during
     * ONE repository writer transaction; a changed or missing member rejects the entire batch.
     * A mixed docIds+files result may remove only verified generated files, NEVER its input docs.
     */
    enum class ResultDeleteState { REMOVED, REFUSED, PARTIAL }

    data class ResultDeleteResult(
        val state: ResultDeleteState,
        val remainingDocIds: List<String> = emptyList(),
        val remainingFiles: List<String> = emptyList()
    )

    fun removeResultOutputsIfUnchanged(
        docIds: List<String>,
        files: List<File>,
        expectedIdentity: String?,
        sourceId: String?
    ): ResultDeleteResult = withImportDiscardLock {
        if (!com.scanwhirl.app.store.resultauthority.ResultOutputAuthority.matches(
                context, this, docIds, files, expectedIdentity, sourceId
            )
        ) {
            return@withImportDiscardLock ResultDeleteResult(ResultDeleteState.REFUSED)
        }
        // The deterministic race barrier is deliberately INSIDE the same PDF/OCR/Trash/Draft
        // writer locks used by deletion and the PDF editing paths.
        com.scanwhirl.app.store.resultauthority.ResultRemovalFaults.afterVerified?.invoke()

        val candidates = if (files.isEmpty()) docIds else emptyList()
        var writeFailed = false
        candidates.forEach { id ->
            if (runCatching { deleteDocument(id) }.isFailure) writeFailed = true
        }
        files.forEach { file ->
            if (runCatching { file.delete() }.getOrDefault(false).not()) writeFailed = true
        }

        val remainingDocs = candidates.filter { getDocument(it) != null }
        val remainingFiles = files.filter { it.exists() }.map { it.path }
        val removed = !writeFailed && remainingDocs.isEmpty() && remainingFiles.isEmpty()
        if (removed && files.isNotEmpty()) {
            // One-shot: the exact producer entry is spent only after the whole verified
            // batch was unlinked. A failed/partial removal keeps it for an honest retry.
            com.scanwhirl.app.store.resultauthority.ResultOutputAuthority.consumeFileLedger(
                context, expectedIdentity ?: ""
            )
        }
        ResultDeleteResult(
            if (removed) ResultDeleteState.REMOVED else ResultDeleteState.PARTIAL,
            remainingDocs, remainingFiles
        )
    }

    fun deleteDocument(id: String) {
        synchronized(PDF_LOCK) {
            OcrStore.locked {
                synchronized(TRASH_LOCK) {
                    val dir = resolveDocDir(id) ?: return@synchronized
                    if (!dir.exists()) return@synchronized
                    com.scanwhirl.app.store.resultauthority.ResultOutputAuthority.invalidateIfTracked(context, id)
                    val root = if (digitalStorage.hasLive(id)) digitalStorage.root(DigitalStorage.TRASH) else trashDir
                    val hidden = File(root, "$PURGE_PREFIX$id-${UUID.randomUUID().toString().take(8)}")
                    if (dir.renameTo(hidden)) {
                        if (root != trashDir) {
                            Durable.syncDir(dir.parentFile!!)
                            Durable.syncDir(root)
                        }
                        trashFault(TrashStage.PURGE_HIDDEN)
                        if (!hidden.deleteRecursively()) throw IOException("Could not finish removing the copy")
                    } else {
                        if (root != trashDir) throw IOException("Could not move digital copy for removal")
                        if (!dir.deleteRecursively()) throw IOException("Could not finish removing the copy")
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
                    val src = resolveDocDir(id) ?: return@synchronized null
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
        val digital = src.parentFile?.canonicalFile == digitalStorage.existingRoot(DigitalStorage.LIVE)
        val root = if (digital) digitalStorage.root(DigitalStorage.TRASH) else trashDir
        val key = freeTrashKey(id, root)
        val dest = File(root, key)
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
        com.scanwhirl.app.store.resultauthority.ResultOutputAuthority.invalidateIfTracked(context, id)
        if (!src.renameTo(dest)) {
            marker.delete()
            throw IOException("Could not move \"$name\" to Trash")
        }
        trashFault(TrashStage.MOVED)
        Durable.syncDir(src.parentFile!!)
        Durable.syncDir(root)
        return if (digital) "digital:$key" else key
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
        val dirs = trashDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty().toList() +
            digitalStorage.dirs(DigitalStorage.TRASH).filterNot { it.name.startsWith(".") }
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
        val key = if (dir.parentFile?.canonicalFile == digitalStorage.existingRoot(DigitalStorage.TRASH)) "digital:${dir.name}" else dir.name
        return TrashedDocument(key, originalId, trashedAt, doc, previousToken)
    }

    /**
     * Restores a trashed document whole and returns it. It goes back under its
     * original id when that id is free. If a library document now uses that id, the
     * existing document is left untouched and the restored one gets a new id, so no
     * item is ever overwritten and no two documents share an id.
     */
    fun restoreFromTrash(key: String): ScanDocument {
        val restored = synchronized(TRASH_LOCK) {
            val src = trashPath(key) ?: throw IOException("Invalid Trash key")
            val trashed = trashedIn(src) ?: throw IOException("This item is no longer in Trash")
            var id = trashed.originalId
            // The original id may still be waiting in UX-007's Undo escrow; reusing it would
            // make a later Undo throw "Cannot overwrite". The allocator checks the same set.
            if (!isSafeId(id) || id in escrowReservedIds() || File(docsDir, id).exists() || digitalStorage.hasLive(id) ||
                digitalStorage.reserves(id, ignoreTrash = trashed.document.contentKind == com.scanwhirl.app.model.ContentKind.DIGITAL)) {
                id = freeLibraryId()
            }
            trashFault(TrashStage.BEFORE_RESTORE)
            val liveRoot = if (trashed.document.contentKind == com.scanwhirl.app.model.ContentKind.DIGITAL)
                digitalStorage.root(DigitalStorage.LIVE) else docsDir
            val dest = File(liveRoot, id)
            if (dest.exists()) throw IOException("Document ID reserved")
            com.scanwhirl.app.store.resultauthority.ResultOutputAuthority.invalidateIfTracked(context, trashed.originalId)
            if (id != trashed.originalId) {
                com.scanwhirl.app.store.resultauthority.ResultOutputAuthority.invalidateIfTracked(context, id)
            }
            resolveNameBeforePublish(src)
            if (!src.renameTo(dest)) throw IOException("Could not restore \"${trashed.document.name}\"")
            trashFault(TrashStage.RESTORED)
            File(dest, TRASH_MARKER).delete()
            Durable.syncDir(src.parentFile!!)
            Durable.syncDir(liveRoot)
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
            val src = trashPath(key) ?: return
            if (!src.exists() || key.startsWith(".")) return
            val originalId = trashedIn(src)?.originalId
            val hidden = File(src.parentFile, "$PURGE_PREFIX${src.name}-${UUID.randomUUID().toString().take(8)}")
            if (!src.renameTo(hidden)) throw IOException("Could not delete this item")
            trashFault(TrashStage.PURGE_HIDDEN)
            // SEC1 1.3.0: a partial recursive delete is NOT a completed deletion.
            // The hidden entry stays for recoverTrash; report failure instead of
            // claiming success while plaintext bytes are still on disk.
            if (!trashPurgeDelete(hidden)) throw IOException("Could not delete this item")
            originalId?.let { dropDerivedCaches(it) }
        }
    }

    /** SEC1 1.3.0: private, non-Trash home for plaintext originals awaiting Vault purge. */
    internal val vaultPendingPurgeDir: File
        get() = File(context.filesDir, VAULT_PENDING_PURGE_DIR).apply { mkdirs() }

    /**
     * SECURITY_JUDGE 1.3.0 finding 2: moves a library original straight into a
     * private purge area, never into the ordinary (listable, restorable) Trash.
     * Returns true only when the whole folder moved out of the Library.
     */
    fun moveOriginalToVaultPendingPurge(id: String): Boolean =
        // SEC2 N2: restore the established lock order used by moveToTrash.
        // PDF_LOCK -> OcrStore.lock -> TRASH_LOCK excludes an OCR commit from
        // validating a page and then writing plaintext into a moved directory.
        synchronized(PDF_LOCK) {
            OcrStore.locked {
                synchronized(TRASH_LOCK) {
                    val src = File(docsDir, id)
                    if (!src.isDirectory) return@synchronized false
                    val dest = File(vaultPendingPurgeDir, id)
                    if (dest.exists()) return@synchronized false
                    if (!vaultPendingMove(src, dest)) return@synchronized false
                    Durable.syncDir(docsDir)
                    dest.parentFile?.let { Durable.syncDir(it) }
                    true
                }
            }
        }

    /** True while a plaintext original is still waiting to be purged for [id]. */
    fun vaultPendingPurgeExists(id: String): Boolean = File(vaultPendingPurgeDir, id).exists()

    /**
     * SEC2 N1: every locally owned pending-purge entry, independent of any
     * Vault marker or encrypted entry. The folder's own existence is deletion
     * authority; a stale marker must never strand its plaintext.
     */
    internal fun vaultPendingPurgeIds(): List<String> =
        vaultPendingPurgeDir.listFiles { f -> f.isDirectory }?.map { it.name }.orEmpty()

    /**
     * Purges a private Vault original. Returns true only when every file is gone;
     * a partial delete leaves the folder in place for the durable-marker retry.
     */
    fun purgeVaultPendingOriginal(id: String): Boolean = synchronized(TRASH_LOCK) {
        val dir = File(vaultPendingPurgeDir, id)
        if (!dir.exists()) return@synchronized true
        val removed = vaultPendingDelete(dir)
        if (removed) {
            Durable.syncDir(vaultPendingPurgeDir)
            dropDerivedCaches(id)
        }
        removed
    }

    /** Proven exported copies of a trashed document (see [provenExportsOf]). */
    fun provenExportsOfTrashed(item: TrashedDocument): List<ProvenExport> =
        trashPath(item.key)?.let { provenExportsIn(it, item.originalId) }.orEmpty()

    /**
     * T44: every copy this Trash entry recorded, proven or not. The permanent-delete sheet names
     * the recorded copies it cannot prove so the user is never told "1 PDF copy" while a
     * different saved file stays. Reading only; never used to delete.
     */
    fun exportRecordsOfTrashed(item: TrashedDocument): List<ExportRecord> =
        trashPath(item.key)?.let(::exportRecordsIn).orEmpty()

    /** Copies recorded by any document other than this Trash entry. */
    fun exportsRecordedByOthersThan(item: TrashedDocument): Set<Uri> =
        trashPath(item.key)?.let(::exportsRecordedOutside).orEmpty()

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
            (trashDir.listFiles { f -> f.name.startsWith(PURGE_PREFIX) }.orEmpty().toList() +
                digitalStorage.dirs(DigitalStorage.TRASH).filter { it.name.startsWith(PURGE_PREFIX) }).forEach { f ->
                if (f.deleteRecursively()) finished++
            }
            (trashDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty().toList() +
                digitalStorage.dirs(DigitalStorage.TRASH).filterNot { it.name.startsWith(".") }).forEach { dir ->
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
            iterateLiveDocs().forEach { dir -> File(dir, TRASH_MARKER).delete() }
        }
        return finished
    }

    private fun trashPath(key: String): File? {
        val digital = key.startsWith("digital:")
        val name = if (digital) key.removePrefix("digital:") else key
        val base = name.substringBefore(TRASH_KEY_SEP)
        if (!isSafeId(base) || (base != name && !name.substringAfter(TRASH_KEY_SEP).matches(Regex("[0-9]+")))) return null
        val root = if (digital) digitalStorage.root(DigitalStorage.TRASH) else trashDir
        return File(root, name).takeIf { DigitalStorage.isDirectChild(root, it) }
    }

    private fun freeTrashKey(id: String, root: File = trashDir): String {
        if (!File(root, id).exists()) return id
        var n = 2
        while (File(root, "$id$TRASH_KEY_SEP$n").exists()) n++
        return "$id$TRASH_KEY_SEP$n"
    }

    /**
     * UX-007: an id waiting in the private import escrow is still reserved. Reusing it would make
     * Undo throw "Cannot overwrite" on a brand-new import. The supplier is a test seam only.
     */
    private fun escrowReservedIds(): Set<String> {
        val escrowKey = Regex("^([A-Za-z0-9_-]{1,64})--[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        return File(context.filesDir, "import_discard").listFiles()
            ?.mapNotNull { escrowKey.matchEntire(it.name)?.groupValues?.get(1) }
            ?.toSet().orEmpty()
    }

    private fun freeLibraryId(freshId: () -> String = { UUID.randomUUID().toString().take(8) }): String {
        val reserved = escrowReservedIds()
        while (true) {
            val id = freshId()
            if (id !in reserved && !digitalIdReserved(id)) return id
        }
    }

    private fun isSafeId(id: String): Boolean =
        id.isNotEmpty() && id.length <= 64 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    /** Unlocked previews built for a document must not outlive it in the library. */
    private fun dropDerivedCaches(id: String) {
        try {
            File(context.cacheDir, "preview_$id.pdf").delete()
            File(File(context.cacheDir, "documents"), "preview_$id.pdf").delete()
            val digitalPreview = File(context.cacheDir.canonicalFile, "digital_previews/$id")
            if (DigitalStorage.owned(digitalPreview)) digitalPreview.deleteRecursively()
        } catch (_: Exception) {
        }
    }

    fun renameDocument(id: String, newName: String) {
        synchronized(TRASH_LOCK) {
            val dir = resolveDocDir(id) ?: return
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
            val src = rasterDir(id)
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
            val doc = loadDocument(rasterDir(id))
                ?: throw IllegalStateException("Scan not found")
            refuseLockedSources(listOf(doc))
            if (doc.isVaulted) throw IOException(context.getString(R.string.doc_actions_vault_reason))
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
                else -> "Pages " + indices.joinToString(", ") { (it + 1).toString() }
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
            val dir = rasterDir(id)
            loadDocument(dir) ?: throw IllegalStateException("Scan not found: $id")
        }
        refuseLockedSources(sources)
        // R07 NEW-2: Vault pages are PIN-gated JPEGs. Copying them into a normal library document
        // would leave an unprotected copy behind after leaving Vault mode, exactly like extractPages
        // refuses to do (same reason string, same IOException).
        sources.firstOrNull { it.isVaulted }?.let {
            throw IOException(context.getString(R.string.doc_actions_vault_reason))
        }

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
        // PERF item 4: one native renderer (and one dup'd fd) for the whole import,
        // not pageCount + one renderer per page. withRenderer closes it on every path.
        return PdfPageRenderer.withRenderer(fd) { renderer ->
            val count = renderer.pageCount
            require(count > 0) { "PDF has no pages" }
            for (i in 0 until count) {
                val jpeg = PdfPageRenderer.renderPageJpeg(renderer, i, dpi, maxDim, quality)
                Durable.writeFile(File(dir, String.format(Locale.ROOT, PAGE_FORMAT, start + i)), jpeg)
                OpFaults.at("op:page:${start + i}")
            }
            count
        }
    }

    suspend fun deletePage(id: String, pageIndex: Int): ScanDocument =
        withContext(Dispatchers.IO) {
            val dir = rasterDir(id)
            requireEditable(dir)
            synchronized(PDF_LOCK) {
                OcrStore.locked {
                    val doc = loadDocument(dir) ?: throw IllegalStateException("Scan not found")
                    require(pageIndex in doc.pageFiles.indices) { "No such page" }
                    require(doc.pageFiles.size > 1) { "Can't delete the last page" }
                    val victim = doc.pageFiles[pageIndex]
                    val remaining = doc.pageFiles.filterIndexed { i, _ -> i != pageIndex }
                    val sizes = sourcePageSizes(remaining)
                    writePlannedPageSizes(dir, remaining, sizes)
                    OpFaults.at("geometry:prepared")
                    origOf(victim).delete()
                    victim.delete()
                    renumberPages(dir)
                    OpFaults.at("geometry:mutated")
                    ocrStore.afterDelete(id, pageIndex)
                }
                regeneratePdf(dir)
            }

            loadDocument(dir) ?: throw IllegalStateException("Scan vanished after delete")
        }

    suspend fun rotatePage(id: String, pageIndex: Int, clockwise: Boolean = true): ScanDocument =
        withContext(Dispatchers.IO) {
            val dir = rasterDir(id)
            requireEditable(dir)
            synchronized(PDF_LOCK) {
                OcrStore.locked {
                    val doc = loadDocument(dir) ?: throw IllegalStateException("Scan not found")
                    require(pageIndex in doc.pageFiles.indices) { "No such page" }
                    val file = doc.pageFiles[pageIndex]
                    val sizes = sourcePageSizes(doc.pageFiles).toMutableList()
                    // M08 P0: the page and its kept original turn together or not at all;
                    // a failure leaves both, and the committed PDF, byte-identical.
                    sizes[pageIndex] = sizes[pageIndex].let { it.second to it.first }
                    editPageAndOriginal(file, beforeCommit = { staged ->
                        val records = doc.pageFiles.mapIndexed { i, page ->
                            page.name to pageSizeRecord(
                                if (i == pageIndex) staged.first().tmp else page, sizes[i]
                            )
                        }.toMap()
                        writePageSizeRecords(dir, records)
                        OpFaults.at("geometry:prepared")
                    }) { target -> ImageTools.stageRotate90(target, clockwise) }
                    OpFaults.at("geometry:mutated")
                    ocrStore.invalidate(id, file.name)
                }
                regeneratePdf(dir)
            }
            loadDocument(dir) ?: throw IllegalStateException("Scan vanished after rotate")
        }

    /**
     * Crops one page to [rect] (full-file pixel coordinates), keeps the kept
     * original in sync so a later filter re-apply stays cropped, and
     * regenerates the PDF.
     */
    suspend fun cropPage(id: String, pageIndex: Int, rect: android.graphics.RectF): ScanDocument =
        withContext(Dispatchers.IO) {
            val dir = rasterDir(id)
            requireEditable(dir)
            // Result Undo holds PDF_LOCK during byte verification and purge. Crop must
            // use the same writer lock for the *entire* image and PDF replacement.
            synchronized(PDF_LOCK) {
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
        }

    /**
     * Stages [edit] for [page] and, when present, its kept original; only when every
     * staged copy verifies are they swapped in together ([PageReplace.commit]).
     */
    private fun editPageAndOriginal(
        page: File,
        beforeCommit: ((List<PageReplace.Staged>) -> Unit)? = null,
        edit: (File) -> PageReplace.Staged
    ) {
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
        try {
            beforeCommit?.invoke(staged)
            PageReplace.commit(staged)
        } catch (t: Throwable) {
            staged.forEach { it.discard() }
            throw t
        }
    }

    suspend fun movePage(id: String, fromIndex: Int, toIndex: Int): ScanDocument =
        withContext(Dispatchers.IO) {
            val dir = rasterDir(id)
            requireEditable(dir)
            synchronized(PDF_LOCK) {
                OcrStore.locked {
                    val doc = loadDocument(dir) ?: throw IllegalStateException("Scan not found")
                    require(fromIndex in doc.pageFiles.indices) { "No such page" }
                    require(toIndex in doc.pageFiles.indices) { "No such position" }
                    if (fromIndex != toIndex) {
                        val sizes = sourcePageSizes(doc.pageFiles).toMutableList().apply {
                            add(toIndex, removeAt(fromIndex))
                        }
                        val reordered = doc.pageFiles.toMutableList().apply {
                            add(toIndex, removeAt(fromIndex))
                        }
                        writePlannedPageSizes(dir, reordered, sizes)
                        OpFaults.at("geometry:prepared")
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
                        OpFaults.at("geometry:mutated")
                        ocrStore.afterMove(id, fromIndex, toIndex)
                    }
                }
                if (fromIndex != toIndex) regeneratePdf(dir)
            }
            loadDocument(dir) ?: throw IllegalStateException("Scan vanished after move")
        }

    internal fun pageFilesIn(dir: File): List<File> =
        if (dir.parentFile?.name in setOf(DigitalStorage.LIVE, DigitalStorage.TRASH,
                DigitalStorage.STAGING, DigitalStorage.ESCROW, DigitalStorage.DRAFTS)) emptyList() else
        dir.listFiles { f -> f.extension.equals("jpg", true) && f.length() > 0 }
            ?.sortedWith(PageOrder.comparator)
            ?: emptyList()

    /** Kept original of a filtered page: `page_001.jpg` -> `page_001.orig` (not a .jpg, never listed). */
    private fun origOf(page: File): File =
        File(page.parentFile, page.nameWithoutExtension + ".orig")

    /**
     * The one re-filter path for both saved pages and draft pages. Decode is bounded to the
     * capture cap and OutOfMemoryError (an `Error`, invisible to `catch (Exception)`) is turned
     * into an honest localized IOException. Nothing is staged or committed on failure, so the
     * original page stays byte-identical.
     */
    private fun applyRefilter(source: File, out: File, filter: ScanFilter) {
        var bmp: Bitmap? = null
        try {
            bmp = refilterDecode(source)
            val filtered = filter.applyTo(bmp)
            try {
                PageReplace.commit(PageReplace.stageJpeg(out, filtered, 92))
            } finally {
                if (filtered !== bmp) filtered.recycle()
            }
        } catch (e: OutOfMemoryError) {
            throw IOException(context.getString(R.string.refilter_out_of_memory))
        } finally {
            bmp?.recycle()
        }
    }

    /**
     * Applies [filter] to one saved page of a document — always re-processing
     * from the kept original so filters never stack, and so "Original"
     * restores the untouched scan. Creates the `.orig` sibling on first use.
     */
    suspend fun setPageFilter(id: String, pageIndex: Int, filter: ScanFilter): ScanDocument =
        withContext(Dispatchers.IO) {
            val dir = rasterDir(id)
            requireEditable(dir)
            synchronized(PDF_LOCK) {
            val doc = loadDocument(dir) ?: throw IllegalStateException("Scan not found")
            val sizes = sourcePageSizes(doc.pageFiles)
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
                applyRefilter(orig, file, filter)
            }
            writePageSizes(dir, sizes)
            regeneratePdf(dir)
            loadDocument(dir) ?: throw IllegalStateException("Scan vanished after filter")
            }
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
        validateOutput: (File) -> Unit = {},
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
                validateOutput(File(staging, "scan.pdf"))
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
                validateOutput(File(staging, "scan.pdf"))
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
        } catch (e: Throwable) {
            // CP1: an OutOfMemoryError is an Error, not an Exception, so the old Exception-only
            // catch leaked its staging folder. Release on OOM too, but leave other Errors (a real
            // or simulated process death) sealed for startup recovery exactly as before.
            if (e is Exception || e is OutOfMemoryError) OpStaging.release(staging)
            throw e
        }
        Durable.syncDir(docsDir)
        Durable.syncDir(OpStaging.root(context.filesDir))
        return loadDocument(rasterDir(id))
            ?: throw IllegalStateException("Published document could not be opened")
    }

    /** Copies one source page into [staging] as `page_%03d.jpg`, fsynced, with a cancel check. */
    private suspend fun stagePageCopy(src: File, staging: File, index: Int): File {
        currentCoroutineContext().ensureActive()
        val out = File(staging, String.format(Locale.ROOT, PAGE_FORMAT, index))
        Durable.copyFile(src, out)
        carryPageSize(src, out)
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
        keepGeometry: Set<String>,
        /** CP1: geometry already rescaled to the compressed page's pixel size, when available. */
        scaledGeo: Map<String, OcrPage> = emptyMap()
    ) {
        val texts = ocrStore.readText(sourceId)
        if (texts.isEmpty()) return
        val geo = ocrStore.readGeo(sourceId)
        val outTexts = LinkedHashMap<String, String>()
        val outGeo = LinkedHashMap<String, OcrPage>()
        for ((dst, src) in pageMap) {
            val text = texts[src] ?: continue
            outTexts[dst] = text
            if (dst in keepGeometry) {
                val scaled = scaledGeo[dst]
                if (scaled != null) outGeo[dst] = scaled
                else geo[src]?.let { outGeo[dst] = it }
            }
        }
        if (outTexts.isNotEmpty()) {
            OcrStore(OpStaging.root(context.filesDir)).putStagedAll(staging.name, outTexts, outGeo)
        }
    }

    /**
     * F11 (H3-2): writes one compressed page per source page into [staging] — one page's bytes
     * alive at a time, never a `List<ByteArray>` of the whole document — and carries the source's
     * recognised text across (H3-5). CP1 also rescales the line boxes when the pixels changed, so
     * the copy keeps a real invisible text layer (and its `text_layer.flag`) instead of losing it.
     */
    private suspend fun stageCompressedPages(
        id: String,
        doc: ScanDocument,
        staging: File,
        onPage: (done: Int, total: Int) -> Unit = { _, _ -> },
        compress: (index: Int, page: File) -> ByteArray
    ) {
        val ctx = currentCoroutineContext()
        val geo = ocrStore.readGeo(id)
        val pageMap = LinkedHashMap<String, String>()
        val keepGeometry = LinkedHashSet<String>()
        val scaledGeo = LinkedHashMap<String, OcrPage>()
        val total = doc.pageFiles.size
        doc.pageFiles.forEachIndexed { i, file ->
            ctx.ensureActive()
            onLivePageBytes?.invoke(1)
            val bytes = compress(i, file)
            val out = stagePageBytes(bytes, staging, i)
            onLivePageBytes?.invoke(0)
            pageMap[out.name] = file.name
            val layer = geo[file.name]
            if (layer != null) {
                val info = runCatching { PdfGenerator.readJpegInfo(bytes) }.getOrNull()
                if (info != null) {
                    if (info.width == layer.width && info.height == layer.height) {
                        keepGeometry += out.name
                    } else if (layer.width > 0 && layer.height > 0) {
                        val sx = info.width.toFloat() / layer.width
                        val sy = info.height.toFloat() / layer.height
                        scaledGeo[out.name] = layer.copy(
                            width = info.width,
                            height = info.height,
                            lines = layer.lines.map { line ->
                                OcrLine(
                                    text = line.text,
                                    left = (line.left * sx).roundToInt().coerceIn(0, info.width),
                                    top = (line.top * sy).roundToInt().coerceIn(0, info.height),
                                    right = (line.right * sx).roundToInt().coerceIn(0, info.width),
                                    bottom = (line.bottom * sy).roundToInt().coerceIn(0, info.height)
                                )
                            }
                        )
                        keepGeometry += out.name
                    }
                }
            }
            onPage(i + 1, total)
        }
        // CP1 review P1: a Cancel signalled from the LAST onPage callback must still stop the
        // publish. The per-page check above cannot catch it; this one can.
        ctx.ensureActive()
        carryOcr(id, staging, pageMap, keepGeometry, scaledGeo)
    }

    /**
     * F11 (H3-2): re-fits the page JPEGs already written into [staging] so the finished PDF hits
     * [target]. Each page is read, fitted and written back on its own; the whole document is
     * never in memory (the old `SizeFit.compressBytes` held every page and a second copy).
     * Internal so a test can drive it with a staging folder and count live page bytes.
     * CP1: reports per-page progress and passes a cancel predicate into the encode loop.
     */
    internal suspend fun fitStagedPagesToTarget(
        staging: File,
        target: Long,
        onPage: (done: Int, total: Int) -> Unit = { _, _ -> }
    ) {
        val ctx = currentCoroutineContext()
        val pages = pageFilesIn(staging)
        if (pages.isEmpty()) throw IOException("No pages to compress")
        val sizes = pages.map { it.length().coerceAtLeast(1L) }
        val sum = sizes.sum().coerceAtLeast(1L)
        val budget = (target - (pages.size * 600L + 1000L)).coerceAtLeast(2048L)
        pages.forEachIndexed { i, file ->
            ctx.ensureActive()
            onLivePageBytes?.invoke(1)
            val bytes = file.readBytes()
            val share = (budget * sizes[i] / sum).coerceAtLeast(1024L)
            val fitted = SizeFit.fitToShare(bytes, share, isCancelled = { !ctx.isActive })
            if (fitted !== bytes) Durable.writeFile(file, fitted)
            onLivePageBytes?.invoke(0)
            onPage(i + 1, pages.size)
        }
        // CP1 review P1: cancel from the final page must not return normally to buildAndPublish.
        ctx.ensureActive()
    }

    /** "Merged PDF <timestamp>" when no name is given. */
    private fun defaultMergedName(prefix: String): String =
        "$prefix ${SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date())}"

    // Shared page-file naming (page_000.jpg, page_001.jpg, …).
    private val PAGE_FORMAT = "page_%03d.jpg"

    /**
     * FF-G-lite: resampling tools write the SOURCE page box (points, keyed by page file name) into
     * their staged copy before publishing. [writePdfInto] reads it, so a later "Make searchable" or
     * any other PDF rebuild of that copy keeps the paper size too. Documents without the sidecar
     * (new scans, untouched imports) keep the historical pixel-derived sizing byte for byte.
     */
    private val PAGE_SIZES_FILE = "page_sizes.txt"

    private data class PageSizeRecord(
        val points: Pair<Int, Int>,
        val pixels: Pair<Int, Int>,
        val sha: String
    )

    private fun pageSizeRecord(file: File, points: Pair<Int, Int>): PageSizeRecord =
        PageSizeRecord(points, requireNotNull(PageReplace.bounds(file)) { "Unreadable page" }, Sha256.of(file))

    /** Bind the prospective names to the actual source pixels BEFORE any published page changes. */
    private fun writePlannedPageSizes(dir: File, pages: List<File>, sizes: List<Pair<Int, Int>>) {
        writePageSizeRecords(dir, pages.mapIndexed { i, page ->
            String.format(Locale.ROOT, PAGE_FORMAT, i) to pageSizeRecord(page, sizes[i])
        }.toMap())
    }

    private fun writePageSizes(dir: File, sizes: List<Pair<Int, Int>>) {
        val pages = pageFilesIn(dir)
        require(pages.size == sizes.size)
        writePageSizeMap(dir, pages.mapIndexed { i, page -> page.name to sizes[i] }.toMap())
    }

    private fun writePageSizeMap(dir: File, sizes: Map<String, Pair<Int, Int>>) {
        writePageSizeRecords(dir, sizes.mapValues { (name, points) -> pageSizeRecord(File(dir, name), points) })
    }

    private fun writePageSizeRecords(dir: File, sizes: Map<String, PageSizeRecord>) {
        val target = File(dir, PAGE_SIZES_FILE)
        val tmp = File(dir, "$PAGE_SIZES_FILE.tmp")
        val text = sizes.entries.joinToString("\n") { (name, size) ->
            "$name=${size.points.first}x${size.points.second}|${size.pixels.first}x${size.pixels.second}|${size.sha}"
        }
        try {
            Durable.writeFile(tmp, text.toByteArray(Charsets.US_ASCII))
            if (!Durable.renameSeam(tmp, target)) throw IOException("Could not replace page geometry")
            Durable.syncDir(dir)
        } finally {
            tmp.delete()
        }
    }

    /** Parsing alone never authorizes a record for use on a page. */
    private fun readPageSizeRecords(dir: File): Map<String, PageSizeRecord> {
        val file = File(dir, PAGE_SIZES_FILE)
        return try {
            val lines = OcrStore.readBoundedLines(file, 1024L * 1024)
            val result = LinkedHashMap<String, PageSizeRecord>()
            lines.forEach { line ->
                val name = line.substringBefore('=')
                require(Regex("(page_\\d+|r_\\d+)\\.jpg").matches(name))
                val fields = line.substringAfter('=').split('|')
                require(fields.size == 3) { "Unbound legacy geometry" }
                fun dimensions(value: String, limit: Int): Pair<Int, Int> {
                    val parts = value.split('x')
                    require(parts.size == 2)
                    val w = parts[0].toInt()
                    val h = parts[1].toInt()
                    require(w in 1..limit && h in 1..limit)
                    return w to h
                }
                val points = dimensions(fields[0], 14400)
                val pixels = dimensions(fields[1], 65535)
                require(Regex("[0-9a-f]{64}").matches(fields[2]))
                require(result.put(name, PageSizeRecord(points, pixels, fields[2])) == null)
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun readPageSizes(dir: File, selected: List<File>? = null): Map<String, Pair<Int, Int>>? {
        if (!File(dir, PAGE_SIZES_FILE).isFile) return null
        val records = readPageSizeRecords(dir)
        val validated = LinkedHashMap<String, PageSizeRecord>()
        (selected ?: pageFilesIn(dir)).forEach { page ->
            val pixels = PageReplace.bounds(page) ?: return@forEach
            val sha = Sha256.of(page)
            val saved = records[page.name]
            // Ordered bounds encode portrait/landscape. The digest additionally distinguishes
            // equal-bounds pages and square rotations: bounds alone cannot prove page identity.
            validated[page.name] = if (saved != null && saved.pixels == pixels && saved.sha == sha) saved
                else PageSizeRecord(PdfGenerator.sizeInPoints(pixels.first, pixels.second), pixels, sha)
        }
        val repaired = if (selected == null) validated else records + validated
        if (repaired != records) {
            // A failed repair must NEVER make the old entry usable. Return the safe values now;
            // every subsequent read validates again, including after a process restart.
            try { writePageSizeRecords(dir, repaired) } catch (_: IOException) { }
        }
        return validated.mapValues { it.value.points }
    }

    /**
     * B4: a second-stage re-encode (target fitting) changes a staged page's pixels and SHA. Re-bind
     * the ALREADY-CHOSEN points to those FINAL bytes and prove the sidecar is trusted before PDF
     * generation. A hash/pixel mismatch must never silently substitute a pixel-derived paper size:
     * when geometry cannot be verified we fail closed instead of claiming a wrong page box.
     */
    private fun rebindPageSizesVerified(
        dir: File,
        pages: List<File>,
        chosen: List<Pair<Int, Int>>
    ) {
        require(pages.isNotEmpty()) { "No pages to compress" }
        require(pages.size == chosen.size) { "Page geometry count mismatch" }
        writePageSizeMap(dir, pages.mapIndexed { i, page -> page.name to chosen[i] }.toMap())
        val final = pageFilesIn(dir)
        if (final != pages) throw IOException("Page set changed during geometry bind")
        val trusted = readPageSizes(dir, final) ?: throw IOException("Page geometry missing")
        val verified = final.map { trusted[it.name] ?: throw IOException("Page geometry unverified") }
        if (verified != chosen) throw IOException("Page geometry unverified")
    }

    /** Source page box in points, captured before a tool re-encodes/downsamples the page image. */
    private fun sourcePageSizes(files: List<File>): List<Pair<Int, Int>> {
        val saved = files.groupBy { requireNotNull(it.parentFile) }.mapValues { (dir, pages) ->
            readPageSizes(dir, pages)
        }
        return files.map { file ->
            saved[file.parentFile]?.get(file.name) ?: derivedPageSize(file)
        }
    }

    private fun derivedPageSize(file: File): Pair<Int, Int> {
        val info = PdfGenerator.readJpegInfo(file.readBytes())
        return PdfGenerator.sizeInPoints(info.width, info.height)
    }

    /** Size travels with the source file, including fresh draft replacement identities. */
    private fun carryPageSize(src: File, out: File, rotate: Boolean = false, derive: Boolean = false) {
        val size = if (derive) derivedPageSize(out) else sourcePageSizes(listOf(src)).single()
        val dir = requireNotNull(out.parentFile)
        // Preserve other records without rebinding or trusting them. Their own readers validate
        // them on use. Hashing every preceding page for every copy would be quadratic image I/O.
        val sizes = readPageSizeRecords(dir).toMutableMap()
        sizes[out.name] = pageSizeRecord(out, if (rotate) size.second to size.first else size)
        writePageSizeRecords(dir, sizes)
    }

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
        maxDim: Int?,
        rejectNotSmaller: Boolean = false
    ): ScanDocument = withContext(Dispatchers.IO) {
        val doc = loadDocument(rasterDir(id)) ?: throw IllegalStateException("Scan not found")
        refuseLockedSources(listOf(doc))
        val ctx = currentCoroutineContext()
        buildAndPublish(outputName(doc.name, "Compressed"), null, doc.pageFiles.sumOf { it.length() },
            validateOutput = { if (rejectNotSmaller) rejectGrownStagedPdf(doc, it, null) }) { staging ->
            // H3-5: the words survive compression; text and rescaled geometry are carried to the
            // copy so it is searchable right away (CP1).
            stageCompressedPages(id, doc, staging) { _, file ->
                ImageTools.recompressJpeg(
                    file.readBytes(), quality, maxDim, isCancelled = { !ctx.isActive }
                )
            }
            // FF-G-lite: pixels change, paper does not. Capture the source box before publish.
            writePageSizes(staging, sourcePageSizes(doc.pageFiles))
        }
    }

    /**
     * Compresses to a total-size target, one page at a time. The old path built a `List<ByteArray>`
     * for every page first — a 2000-page book held the whole document (hundreds of MB) in memory.
     * Here only the current page's bytes and bitmap are alive, and the recognised text is carried
     * to the copy (H3-5).
     */
    suspend fun compressScanToTarget(
        id: String,
        targetBytes: Long,
        rejectNotSmaller: Boolean = false,
        onPage: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): ScanDocument = withContext(Dispatchers.IO) {
            require(targetBytes > 0) { "Target size must be positive" }
            val doc = loadDocument(rasterDir(id)) ?: throw IllegalStateException("Scan not found")
            refuseLockedSources(listOf(doc))
            val ctx = currentCoroutineContext()
            val sizes = doc.pageFiles.map { it.length().coerceAtLeast(1L) }
            val sum = sizes.sum().coerceAtLeast(1L)
            val budget = (targetBytes - (doc.pageFiles.size * 600L + 1000L)).coerceAtLeast(2048L)
            buildAndPublish(
                outputName(doc.name, "Compressed"), null, doc.pageFiles.sumOf { it.length() },
                validateOutput = { if (rejectNotSmaller) rejectGrownStagedPdf(doc, it, targetBytes) }
            ) { staging ->
                stageCompressedPages(id, doc, staging, onPage = onPage) { i, file ->
                    val share = (budget * sizes[i] / sum).coerceAtLeast(1024L)
                    SizeFit.fitToShare(file.readBytes(), share, isCancelled = { !ctx.isActive })
                }
                // FF-G-lite: the KB fitter downsamples again; the paper box still comes from the source.
                writePageSizes(staging, sourcePageSizes(doc.pageFiles))
            }
        }

    /** Result producers use these paths: a rejected file never becomes a library document. */
    suspend fun compressScanForResult(id: String, quality: Int, maxDim: Int?): ScanDocument? =
        try { compressScan(id, quality, maxDim, rejectNotSmaller = true) }
        catch (_: NotSmaller) { null }

    suspend fun compressScanToTargetForResult(
        id: String,
        targetBytes: Long,
        onPage: (Int, Int) -> Unit = { _, _ -> }
    ): ScanDocument? = try {
        compressScanToTarget(id, targetBytes, rejectNotSmaller = true, onPage = onPage)
    } catch (_: NotSmaller) { null }

    /** Thrown when a staged compress output is not smaller than its source; nothing was published. */
    class NotSmaller(val before: Long = 0L, val target: Long? = null) : IOException()

    private fun rejectGrownStagedPdf(source: ScanDocument, stagedPdf: File, target: Long?) =
        rejectGrownStaged(source.pdfFile.length(), stagedPdf, target, source.id)

    /**
     * Staged rejection shared by library and external/device-PDF compression: measured inside
     * staging, before any publish. [sourceId] is null for an external file.
     */
    private fun rejectGrownStaged(before: Long, stagedPdf: File, target: Long?, sourceId: String?) {
        if (before <= 0L || stagedPdf.length() < before) return
        // Persist BEFORE returning to the Activity, and before any atomic publish or recovery seal.
        V2tResultReceiptStore(context).save(V2tResultReceiptStore.Receipt(
            toolId = "compress", docIds = emptyList(), files = emptyList(),
            mime = "application/pdf", note = null, targetBytes = target, beforeBytes = before,
            outputIdentity = null, receiptSourceId = sourceId, sourceKept = sourceId != null,
            chosenLocationName = null, documentsExistingName = null, undone = false,
            savedAt = System.currentTimeMillis(), outcomeClass = "NOT_SMALLER"
        ))
        OpFaults.at("compress:rejected")
        throw NotSmaller(before, target)
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
        preset: ImportDecision? = null,
        rejectNotSmallerThan: Long = 0L
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
            if (rejectNotSmallerThan > 0L) rejectGrownStaged(rejectNotSmallerThan, pdfFile, null, null)
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
            loadDocument(rasterDir(id))
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
                        SizeUnits.format(plan.estimateBytes),
                        SizeUnits.format(plan.freeBytes)
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
        /** >0: reject (throw [NotSmaller], publish nothing) an output not smaller than this. */
        rejectNotSmallerThan: Long = 0L,
        onPage: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): ScanDocument? = importPdfInternal(
        uri, name, dpi, maxDim, quality, vaulted = false,
        onPlan = null, onPage = onPage, preset = ImportDecision.Pages(indices),
        rejectNotSmallerThan = rejectNotSmallerThan
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
        /** >0: reject (throw [NotSmaller], publish nothing) an output not smaller than this. */
        rejectNotSmallerThan: Long = 0L,
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
            buildAndPublish(docName, null, 0L,
                validateOutput = { if (rejectNotSmallerThan > 0L) rejectGrownStaged(rejectNotSmallerThan, it, targetBytes, null) }
            ) { staging ->
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
                // B4: capture the chosen page-size contract BEFORE the KB fitter re-encodes the
                // rendered pages. These are the rendered 150-DPI derived points (an external PDF's
                // own MediaBox is not preserved on this base); they must not change during fitting.
                val stagedPages = pageFilesIn(staging)
                val chosenPoints = sourcePageSizes(stagedPages)
                // CP1: the re-fit pass gets the same determinate progress as the render pass.
                fitStagedPagesToTarget(staging, targetBytes, onPage = onPage)
                // B4: re-bind those SAME chosen points to the FINAL encoded bounds + SHA after the
                // re-encode and verify they are trusted before PDF generation, so a mismatch fails
                // closed instead of silently exporting a smaller pixel-derived paper size.
                rebindPageSizesVerified(staging, stagedPages, chosenPoints)
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
            val dir = rasterDir(id)
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
        val dir = rasterDir(id)
        val doc = loadDocument(dir) ?: throw IllegalStateException("Scan not found")
        if (!doc.isLocked) return doc
        if (doc.pageFiles.isEmpty()) throw IllegalStateException(context.getString(R.string.locked_no_pages))
        synchronized(PDF_LOCK) {
            if (grant != null) requireLockRecord(dir, grant)
            val work = OpStaging.create(context.filesDir)
            // Past the PDF rename the marker is the only way back to a consistent state, so it is
            // kept for the next start on any failure; before it, cleanup is a clean rollback.
            var swapped = false
            try {
                val pdf = File(dir, PdfProtection.PDF_FILE)
                val lock = File(dir, LOCK_FILE)
                val oldRecord = readPdfLock(lock)
                    ?: throw IllegalStateException("The lock record can't be read, so nothing was changed.")
                val oldPdf = Sha256.of(pdf)
                val plain = File(work, "plain.pdf")
                val layered = writePdf(dir, plain)
                Durable.syncFile(plain)
                val newPdf = Sha256.of(plain)
                Durable.atomicWriteText(
                    File(work, Unlock.MARKER),
                    Unlock.write(dir.name, oldRecord, oldPdf, newPdf)
                )
                Durable.syncDir(work)
                OpFaults.at("unlock:built")
                if (!plain.renameTo(pdf)) throw IOException("Could not unlock the PDF")
                swapped = true
                OpFaults.at("unlock:swapped")
                // The unlocked copy is durable before the lock label goes away. A failed delete
                // must never be treated as success: keep the marker (swapped=true skips cleanup)
                // and report honestly, so startup can retry instead of leaving a plain PDF
                // silently carrying a lock label.
                Durable.syncDir(dir)
                if (!removeLockFile(lock)) {
                    throw IOException(context.getString(R.string.unlock_lock_not_removed))
                }
                updateTextLayerFlag(dir, layered)
                Durable.syncDir(dir)
                File(work, Unlock.MARKER).delete()
                Durable.syncDir(work)
                OpStaging.release(work)
            } catch (e: Exception) {
                // A real kill skips this too. After the swap the marker stays so recoverOperations
                // can finish the unlock; deleting it would leave a plain PDF with a stale label.
                if (!swapped) OpStaging.release(work)
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
            val dir = rasterDir(id)
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
     * Sol re-review: "could not hash" is NOT proof of a mismatch. A failure (including an
     * OutOfMemoryError) means UNKNOWN: the marker is the only record of the new verifier, so it
     * must be kept and retried, never deleted as if the operation had been rolled back.
     */
    private enum class DigestState { VERIFIED_MATCH, VERIFIED_MISMATCH, UNKNOWN }

    private class DigestResult(val state: DigestState, val matchedNew: Boolean = false) {
        companion object {
            val MATCH_OLD = DigestResult(DigestState.VERIFIED_MATCH, false)
            val MATCH_NEW = DigestResult(DigestState.VERIFIED_MATCH, true)
            val MISMATCH = DigestResult(DigestState.VERIFIED_MISMATCH)
            val UNKNOWN = DigestResult(DigestState.UNKNOWN)
        }
    }

    private fun checkPdfDigest(pdf: File, oldHex: String, newHex: String): DigestResult = try {
        when (val digest = recoveryHash(pdf)) {
            newHex -> DigestResult.MATCH_NEW
            oldHex -> DigestResult.MATCH_OLD
            else -> DigestResult.MISMATCH
        }
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        DigestResult.UNKNOWN
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
        if (!dir.isDirectory) { // the document is truly gone: stale, nothing to repair
            File(work, PasswordSwap.MARKER).delete()
            return
        }
        val pdf = File(dir, PdfProtection.PDF_FILE)
        val lock = File(dir, LOCK_FILE)
        synchronized(PDF_LOCK) {
            when (val check = checkPdfDigest(pdf, swap.previousPdfSha256, swap.newPdfSha256)) {
                DigestResult.UNKNOWN -> {
                    // Never lose the only copy of the new verifier because a hash could not be read.
                    recoveryIncomplete = true
                    android.util.Log.w("ScanWhirl", "startup password-swap: PDF digest unknown")
                    return@synchronized
                }
                DigestResult.MATCH_NEW -> {
                    if (readPdfLock(lock) != swap.newRecord) {
                        try {
                            Durable.atomicWriteText(lock, swap.newRecord)
                            Durable.syncDir(dir)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            recoveryIncomplete = true
                            android.util.Log.w("ScanWhirl",
                                "startup password-swap: verifier write failed (${t.javaClass.simpleName})")
                            return@synchronized // keep the marker, retry next start
                        }
                    }
                    recordLockIndex(pdf, swap.id)
                }
                // MATCH_OLD or a verified MISMATCH: nothing was swapped in, resolution is complete.
            }
            File(work, PasswordSwap.MARKER).delete()
        }
    }

    /**
     * G1: finishes an unlock that died after the plain PDF was swapped in (the one destructive
     * step). Before the swap nothing changed and the marker is simply swept. After it the plain
     * copy is already durable, so the marker alone says which stale lock record must go; the
     * encrypted PDF (still named by the marker) is kept if the swap never happened.
     */
    private fun recoverUnlock(work: File) {
        val pending = Unlock.read(work) ?: return
        val dir = File(docsDir, pending.id)
        if (!dir.isDirectory) {
            File(work, Unlock.MARKER).delete()
            return
        }
        val pdf = File(dir, PdfProtection.PDF_FILE)
        val lock = File(dir, LOCK_FILE)
        synchronized(PDF_LOCK) {
            when (checkPdfDigest(pdf, pending.oldPdfSha256, pending.newPdfSha256)) {
                DigestResult.UNKNOWN -> {
                    recoveryIncomplete = true
                    android.util.Log.w("ScanWhirl", "startup unlock recovery: PDF digest unknown")
                    return@synchronized // keep the marker and its metadata for a later start
                }
                DigestResult.MATCH_NEW -> {
                    // Rolled forward: never leave a plain PDF labelled locked. If the label cannot
                    // be removed, KEEP the marker and let the sweep skip this folder for a retry.
                    if (lock.exists() && !removeLockFile(lock)) {
                        // Keep the marker and the label for a later start. No new UI: the unlock
                        // screen already reported the honest failure; startup only logs a
                        // non-sensitive marker and exposes [recoveryIncomplete] via the launcher.
                        recoveryIncomplete = true
                        android.util.Log.w("ScanWhirl", "startup unlock recovery: lock label removal failed")
                        return@synchronized
                    }
                    if (lock.exists()) Durable.syncDir(dir)
                    pruneLockIndex()
                }
                DigestResult.MATCH_OLD -> {
                    // Rolled back: the encrypted PDF is still in place, so its label must stay.
                    if (!lock.exists()) {
                        Durable.atomicWriteText(lock, pending.oldRecord)
                        Durable.syncDir(dir)
                    }
                }
                // A verified MISMATCH is a stale marker: resolved by ignoring it.
            }
            File(work, Unlock.MARKER).delete()
        }
    }

    fun protectionState(id: String): PdfProtection.State =
        if (digitalStorage.hasLive(id)) {
            if (getDocument(id) != null) PdfProtection.State.NONE else PdfProtection.State.UNREADABLE
        } else PdfProtection.state(File(docsDir, id))

    /**
     * PM-3.1c (A5): `locked.meta` sits beside a plain `scan.pdf`. With the right [password] the PDF is
     * encrypted again through the same build-and-swap as [protectPdf] and I3 holds. The RC4 key needs
     * the password, so this can never run without the user. A wrong password changes nothing.
     */
    suspend fun repairProtection(id: String, password: String): ScanDocument =
        withContext(Dispatchers.IO) {
            val dir = rasterDir(id)
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
            val dir = rasterDir(id)
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
            val dir = rasterDir(id)
            check(PdfProtection.state(dir) == PdfProtection.State.UNKNOWN_PASSWORD) { "PDF is not an unknown-password PDF" }
            val doc = loadDocument(dir) ?: throw IllegalStateException("Scan not found")
            if (doc.pageFiles.isEmpty()) throw IllegalStateException(context.getString(R.string.locked_no_pages))
            regeneratePdf(dir)
            loadDocument(dir) ?: throw IllegalStateException("Scan vanished after rebuild")
        }

    /**
     * G1: reclaims the two data-free leftovers a new-scan save can leave in documents/ when the
     * process dies before publication:
     *  - a bare reserved id directory (crash between the exclusive `mkdir` and the marker write).
     *    The ordinary reaper cannot see it because it has no owner marker, so it stayed forever;
     *  - a directory holding only our `.incomplete` / `.incomplete.tmp` marker.
     *
     * A directory with ANY other entry (pages, PDF, `txn.txt` or `meta.txt`) is never touched:
     * that is journal-linked replay material or a published document and may be the owner's only
     * copy. A marker must be provably ours; a bare empty directory carries no data at all.
     */
    private fun isDataFreeReservation(dir: File): Boolean {
        if (!dir.isDirectory) return false
        val names = dir.list()?.toSet() ?: return false
        val marker = SaveFormat.MARKER
        val markerTmp = SaveFormat.MARKER_TMP
        if (names.any { it != marker && it != markerTmp }) return false
        // A bare empty directory is a crash between mkdir and the marker write and has no
        // owner marker; any marker must nevertheless be provably ours.
        return names.isEmpty() || SaveFormat.ownerOf(dir) != null
    }

    /** Runs only while SAVE_LOCK is held: saves cannot reserve, write or publish concurrently. */
    private fun removeOrphanReservationsLocked(): Int {
        var removed = 0
        for (dir in docsDir.listFiles { it -> it.isDirectory }.orEmpty()) {
            if (!isDataFreeReservation(dir)) continue
            // Re-check immediately before the delete, under the same lock: an out-of-band write
            // between the snapshot and here must never turn into deleted user data.
            orphanCleanupProbe?.invoke()
            if (!isDataFreeReservation(dir)) continue
            if (dir.deleteRecursively()) removed++
        }
        return removed
    }

    /**
     * Sol re-review: a recovery marker carries everything needed to finish (old/new verifiers and
     * both PDF digests), and the actual replacement PDF is already inside the document folder.
     * Therefore an unresolved folder must keep ONLY its marker, never plaintext scratch. A marker
     * naming a missing document or an unrelated PDF is stale and its whole folder is deleted.
     */
    private enum class MarkerState { NONE, KEEP, STALE, UNKNOWN }

    private fun recoveryMarkerState(work: File): MarkerState {
        val (id, oldPdf, newPdf) = PasswordSwap.read(work)?.let {
            Triple(it.id, it.previousPdfSha256, it.newPdfSha256)
        } ?: Unlock.read(work)?.let {
            Triple(it.id, it.oldPdfSha256, it.newPdfSha256)
        } ?: return MarkerState.NONE
        val dir = File(docsDir, id)
        if (digitalStorage.hasLive(id)) return MarkerState.UNKNOWN
        if (!dir.isDirectory) return MarkerState.STALE // truly missing document
        return when (checkPdfDigest(File(dir, PdfProtection.PDF_FILE), oldPdf, newPdf)) {
            DigestResult.MATCH_OLD, DigestResult.MATCH_NEW -> MarkerState.KEEP
            DigestResult.MISMATCH -> MarkerState.STALE // both digests computed, neither matches
            else -> MarkerState.UNKNOWN // keep marker + metadata, retry next start
        }
    }

    /**
     * S2 item 8: a sealed Replace whose old version is already in Trash but whose replacement has
     * not been published (the base id is absent) is the user's only copy of the new version. The
     * tri-state marker logic only knows password-swap/unlock, so the staging sweep would delete it
     * after a failed roll-forward. Detect it straight from the seal so the sweep preserves it even
     * when the "replace" recovery step itself failed to run.
     */
    private fun isUnpublishedReplace(dir: File): Boolean {
        val seal = runCatching { OpStaging.readSeal(dir) }.getOrNull() ?: return false
        val replaces = seal.replaces ?: return false
        if (File(docsDir, replaces).exists()) return false
        return runCatching { listTrash() }.getOrDefault(emptyList()).any {
            it.originalId == replaces && it.previousToken == seal.token
        }
    }

    private fun pruneRecoveryStaging(work: File) {
        val markerName = when {
            PasswordSwap.read(work) != null -> PasswordSwap.MARKER
            Unlock.read(work) != null -> Unlock.MARKER
            else -> return
        }
        when (recoveryMarkerState(work)) {
            MarkerState.STALE -> {
                // S2 item 3: an unchecked stale-folder delete used to let plaintext survive while
                // startup still declared a clean pass. Leave the folder for retry and expose it.
                if (!recoveryFileDelete(work)) {
                    recoveryIncomplete = true
                    android.util.Log.w("ScanWhirl", "startup recovery: stale staging folder could not be removed")
                }
                return
            }
            MarkerState.UNKNOWN -> {
                // Recovery needs ONLY the marker (and the document's own PDF, which is not here).
                // Scratch is still removed with the same checked recursive cleanup as KEEP, so a
                // permanently unreadable document cannot keep plaintext around forever.
                recoveryIncomplete = true
            }
            else -> Unit
        }
        work.listFiles()?.forEach { file ->
            if (file.name != markerName && !recoveryFileDelete(file)) {
                recoveryIncomplete = true
            }
        }
        // Verify under the same pass: anything left (including a nested dir) is kept for retry.
        if (work.listFiles()?.any { it.name != markerName } == true) {
            recoveryIncomplete = true
            android.util.Log.w("ScanWhirl", "startup recovery: staging scratch could not be removed")
        }
    }

    /**
     * One isolated recovery step. A failing step (e.g. a disk-full folders.txt write) must never
     * abort the document and security steps or leave startup claiming a fully successful pass.
     * Cancellation is not a failure to be swallowed: it is rethrown so startup can abort cleanly.
     * Only the exception class is logged; no user data or message is written to the log.
     */
    private suspend fun recoveryStep(name: String, block: suspend () -> Unit) {
        try {
            recoveryStepFault?.invoke(name)
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Lead decision: no Error escapes startup recovery. Every other step still runs.
            recoveryIncomplete = true
            android.util.Log.w("ScanWhirl", "startup recovery step $name failed (${t.javaClass.simpleName})")
        }
    }

    /**
     * PM-2.2b startup recovery. CORE document/security steps run FIRST and each is isolated; folder
     * repair runs LAST, also isolated, so a disk-full folders.txt write cannot abort a Replace
     * roll-forward or the staging sweep. A failed folder delete keeps its tombstone for the next
     * start. [recoveryIncomplete] is exposed so startup does not declare a failed pass complete.
     *
     *  1. roll forward a sealed Replace whose base is absent and whose Trash entry carries
     *     `previous=<token>` (and whose pages/PDF still match the seal);
     *  2. finish password changes and unlocks that died after their PDF swap;
     *  3. finish every published `.publish`: discard its draft (only the matching creation) and
     *     delete the seal;
     *  4. reclaim data-free orphan save reservations (under SAVE_LOCK);
     *  5. drop interrupted discards and drafts whose base is gone from BOTH documents and Trash;
     *  6. sweep every staging folder that is not live and has no unresolved recovery marker;
     *  7. finish interrupted folder deletes, then reconcile folder names from documents.
     * Returns how many Replace operations were rolled forward.
     */
    fun recoverOperations(): Int = runBlocking { recoverOperationsSuspend() }

    /**
     * Blocking wrapper retained for tests and legacy callers only. It must NOT be called from a
     * coroutine or on the main thread: the orphan-reservation step suspends on [SAVE_LOCK], which
     * a save may hold. Production startup and legacy Home call [recoverOperationsSuspend] directly
     * from their IO coroutines, so no blocking bridge is involved there (non-reentrancy precondition).
     */
    suspend fun recoverOperationsSuspend(): Int {
        val filesDir = context.filesDir
        var rolledForward = 0
        var swept = 0
        recoveryStep("digital-storage") { rolledForward += digitalTransaction.recover() }
        // Do NOT reset recoveryIncomplete here: it accumulates across the whole startup pass.

        val stagingRoot = OpStaging.root(filesDir)
        val stagingDirs = { stagingRoot.listFiles { f -> f.isDirectory }.orEmpty() }
        // Pruning uses exactly the same live-operation exclusion as OpStaging.sweep: a marker
        // created by a running unlock/password change must never have its scratch removed. Repair
        // steps still visit every folder: a crashed Replace stays live in this process until rollback.
        val deadStagingDirs = { stagingDirs().filterNot { OpStaging.isLive(it) } }
        // S2 item 8: Replace payloads conclusively shown stale (seal vs bytes) are the only ones
        // the sweep may reclaim; every other unpublished Replace is preserved for a retry.
        val staleReplaces = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        // 1. Roll forward. listTrash() reads every Trash entry's meta.txt; hoist it out of the
        // staging loop (S staging folders × T trash entries becomes one snapshot).
        recoveryStep("replace") {
            val trashSnapshot = listTrash().filter { it.document.contentKind == com.scanwhirl.app.model.ContentKind.RASTER }
            for (dir in stagingDirs()) {
                val seal = OpStaging.readSeal(dir) ?: continue
                val replaces = seal.replaces ?: continue
                val base = File(docsDir, replaces)
                if (base.exists()) continue
                val trash = trashSnapshot.firstOrNull {
                    it.originalId == replaces && it.previousToken == seal.token
                } ?: continue
                // A committed Replace awaiting publication. Validation must be conclusive: a
                // transient hash/read failure is UNKNOWN (keep + retry), a genuine mismatch is
                // verified-stale and may be reclaimed.
                val valid = try {
                    val pages = pageFilesIn(dir)
                    pages.map { it.name } == seal.pages &&
                        SaveFormat.pagesDigest(pages) == seal.pagesSha256 &&
                        Sha256.of(File(dir, "scan.pdf")) == seal.pdfSha256
                } catch (t: Throwable) {
                    recoveryIncomplete = true
                    continue // isUnpublishedReplace keeps the intact payload for the next start
                }
                if (!valid) {
                    staleReplaces += dir.name
                    continue
                }
                synchronized(TRASH_LOCK) {
                    if (dir.renameTo(base)) {
                        OpStaging.forget(dir)
                        rolledForward++
                    } else {
                        // Failed publication: keep the complete payload and retry next start.
                        recoveryIncomplete = true
                    }
                }
            }
        }

        // 2. Core security recovery: the markers name everything needed to finish without a KDF.
        recoveryStep("password-swap") { stagingDirs().forEach { recoverPasswordSwap(it) } }
        recoveryStep("unlock") { stagingDirs().forEach { recoverUnlock(it) } }

        // 3. Finish published draft seals (create-only Save as new, or a completed Replace).
        recoveryStep("published-seals") {
            docsDir.listFiles { f -> f.isDirectory }.orEmpty().forEach { dir ->
                val seal = OpStaging.readSeal(dir) ?: return@forEach
                synchronized(DRAFT_LOCK) {
                    runCatching { drafts.load(seal.draft) }.getOrNull()?.let { draft ->
                        if (draft.created == seal.draftCreated) drafts.discard(seal.draft)
                    }
                }
                File(dir, OpStaging.SEAL).delete()
            }
        }

        // 4. G1: reclaim data-free orphan save reservations under SAVE_LOCK (suspend caller).
        recoveryStep("orphan-reservations") { SAVE_LOCK.withLock { removeOrphanReservationsLocked() } }

        // 5. Drafts: interrupted discards, and drafts whose base is gone from library and Trash.
        recoveryStep("drafts") {
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
        }

        // 6. Keep only metadata an unresolved marker truly needs, then sweep everything else.
        // Stale markers (missing document / unrelated digest) delete their whole folder here.
        recoveryStep("prune-recovery-staging") { deadStagingDirs().forEach { pruneRecoveryStaging(it) } }
        recoveryStep("staging-sweep") {
            swept = OpStaging.sweep(filesDir) { dir ->
                val state = recoveryMarkerState(dir)
                state == MarkerState.KEEP || state == MarkerState.UNKNOWN ||
                    (dir.name !in staleReplaces && isUnpublishedReplace(dir))
            }
        }

        // 7. Folder repair LAST. A failure here leaves the tombstone/folder work for the next start.
        recoveryStep("finish-folder-delete") { finishPendingDelete() }
        recoveryStep("reconcile-folders") { reconcileFolders() }

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
            val dir = rasterDir(id)
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
        val dir = rasterDir(id)
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

    /**
     * G1: the recovery marker of an interrupted unlock, kept in private [OpStaging] (never beside
     * a document). It records the locked and plain PDF digests and the old lock record. Written and
     * fsynced BEFORE the destructive rename, so a process death can finish the unlock (drop the
     * stale label) or roll back (the encrypted PDF is still there) without the password and without
     * touching the page sources.
     */
    private object Unlock {
        const val MARKER = ".unlock"
        private const val HEADER = "scanwhirl-unlock-v1"
        private val idPattern = Regex("[A-Za-z0-9_-]{1,64}")
        private val sha256Pattern = Regex("[0-9a-f]{64}")

        class Pending(
            val id: String,
            val oldRecord: String,
            val oldPdfSha256: String,
            val newPdfSha256: String
        )

        fun write(id: String, oldRecord: String, oldPdf: String, newPdf: String): String = buildString {
            append(HEADER).append('\n')
            append("id=").append(id).append('\n')
            append("old=").append(oldRecord).append('\n')
            append("pdf=").append(oldPdf).append('\n')
            append("newPdf=").append(newPdf).append('\n')
        }

        /** Any missing, torn or unexpected field returns null: an unreadable marker never guesses. */
        fun read(work: File): Pending? {
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
            val old = values["old"]?.takeIf { it.length in 1..256 } ?: return null
            val oldPdf = values["pdf"]?.takeIf { sha256Pattern.matches(it) } ?: return null
            val newPdf = values["newPdf"]?.takeIf { sha256Pattern.matches(it) } ?: return null
            return Pending(id, old, oldPdf, newPdf)
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
            val dir = rasterDir(id)
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
            val dir = rasterDir(id)
            val doc = loadDocument(dir) ?: throw IllegalStateException("Scan not found")
            synchronized(PDF_LOCK) { requireLockRecord(dir, grant) }
            val outDir = File(context.cacheDir, "documents").apply { mkdirs() }
            val preview = File(outDir, "preview_$id.pdf")
            writePdf(dir, preview)
            preview
        }

    /** Moves a scan into or out of the 4-digit PIN Hidden Vault (Pro). */
    fun setVaulted(id: String, vaulted: Boolean): ScanDocument? {
        val dir = rasterDir(id)
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
        val dir = rasterDir(id)
        if (!dir.isDirectory) return null
        return ocrStore.updateTexts(id, mapOf(pageKey to text))
    }

    /**
     * Replaces every given page's OCR text in one rewrite (the OCR Editor's
     * Save-all, P6). Returns the full updated page map, or null when the
     * document does not exist.
     */
    fun updateOcrPages(id: String, pages: Map<String, String>): Map<String, String>? {
        val dir = rasterDir(id)
        if (!dir.isDirectory) return null
        return ocrStore.updateTexts(id, pages)
    }

    /** Save only if every expected page still matches; conflicts leave the whole batch untouched. */
    fun updateOcrPagesIfUnchanged(
        id: String,
        expected: Map<String, String?>,
        pages: Map<String, String>
    ): OcrStore.UpdateResult? {
        if (digitalStorage.hasLive(id) || !File(docsDir, id).isDirectory) return null
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
            writePdfInto(dir, pdfFile, geo, forceLayers)
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
        val dir = rasterDir(id)
        if (!dir.isDirectory || File(dir, LOCK_FILE).exists()) return false
        val pages = pageFilesIn(dir)
        if (pages.isEmpty()) return false
        val geo = ocrStore.readGeo(id)
        if (geo.isEmpty()) return false
        // O1-8: never replace scan.pdf with a partial layer and then report "unavailable".
        // Only rewrite when every page has a stored layer; otherwise leave the PDF untouched.
        if (pages.any { !geo.containsKey(it.name) }) return false
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
     * geometry in [geo] when `Prefs.makeSearchable` is on, or when [forceLayers] is set for an
     * explicit operation (FF-O3 "OCR PDF" / "Make searchable") that must work independently of
     * the automatic-searchable preference. Returns true when at least one page received a layer.
     * Atomic via `.tmp` + rename inside the generator.
     */
    private fun writePdfInto(
        dir: File,
        out: File,
        geo: Map<String, OcrPage>,
        forceLayers: Boolean = false
    ): Boolean = synchronized(PDF_LOCK) {
        if (dir.parentFile?.name in setOf(DigitalStorage.LIVE, DigitalStorage.TRASH,
                DigitalStorage.STAGING, DigitalStorage.ESCROW, DigitalStorage.DRAFTS))
            throw IOException("Digital PDFs cannot be rebuilt from page images")
        val pages = pageFilesIn(dir)
        if (pages.isEmpty()) {
            false
        } else {
            val textLayers = if (forceLayers || Prefs.makeSearchable(context)) {
                pages.indices.filter {
                    geo.containsKey(String.format(Locale.ROOT, "page_%03d.jpg", it))
                }
                    .associate { i -> i to geo[String.format(Locale.ROOT, "page_%03d.jpg", i)]!! }
            } else {
                emptyMap()
            }
            // FF-G-lite: a compressed copy carries its source page box so any later rebuild
            // (Make searchable, stamp, rotate, …) cannot silently shrink the paper again.
            // Only content-bound, orientation-matching geometry can override the pixel fallback.
            val sizes = readPageSizes(dir)
            val pageSizes = sizes?.let { saved -> pages.map { saved[it.name] } }
            PdfGenerator.imagesToPdf(pages, out, textLayers, pageSizes = pageSizes).isNotEmpty()
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
        if (digitalStorage.hasLive(id)) return
        if (!Prefs.makeSearchable(context)) return
        val dir = rasterDir(id)
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
            val doc = loadDocument(rasterDir(id)) ?: throw IllegalStateException("Scan not found")
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
        val doc = loadDocument(rasterDir(id)) ?: throw IllegalStateException("Scan not found")
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
        val doc = loadDocument(rasterDir(id)) ?: throw IllegalStateException("Scan not found")
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
     * A page the recogniser could not read gets a visible, localised line in the file and is
     * passed to [onUnreadPages]; the export is never reported as complete in that case.
     */
    suspend fun exportWord(
        id: String,
        outFile: File,
        includePictures: Boolean = false,
        onUnreadPages: (List<Int>) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        val doc = loadDocument(rasterDir(id)) ?: throw IllegalStateException("Scan not found")
        refuseLockedSources(listOf(doc))
        // N2: a document that has not been read yet is not an error here. Word is a text export,
        // so the pages that have no text are read now with the app's own on-device engine (the
        // same one the Text screen and OcrIndexer use) and the result is kept, so Text shows it
        // too and the next export of any kind needs no second pass.
        val text = ocrStore.readText(id).toMutableMap()
        val unread = doc.pageFiles.filter { it.name !in text }
        val unreadPages = mutableListOf<Int>()
        if (unread.isNotEmpty()) {
            val language = com.scanwhirl.app.util.OcrLanguageSettings.get(appContext())
            for (page in unread) {
                currentCoroutineContext().ensureActive()
                // SEC2 R3: capture identity before recognition and re-check it
                // atomically with the write; a moved/purged document is refused
                // quietly, with nothing written to a recreated directory.
                val sourceHash = Sha256.of(page)
                val result = try {
                    OcrEngine.recognizeFile(page, language)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    unreadPages += doc.pageFiles.indexOf(page)
                    continue
                } catch (_: OutOfMemoryError) {
                    unreadPages += doc.pageFiles.indexOf(page)
                    continue
                }
                ocrStore.putPage(id, page.name, result.text, result.page, sourceHash)
                text[page.name] = result.text
            }
        }
        val geo = ocrStore.readGeo(id)
        val pages = doc.pageFiles.mapIndexed { index, page ->
            if (index in unreadPages) {
                // O1-1: an unreadable page is named in the file instead of silently exported blank.
                listOf(context.getString(R.string.tools_word_page_unread, index + 1))
            } else {
                wordPageLines(text[page.name] ?: "", geo[page.name])
            }
        }
        if (text.values.none { it.isNotBlank() }) {
            // T37: "Read the text first" left a Hindi user with nowhere to go. Name the setting
            // that actually decides whether the page can be read. All pages unreadable still
            // reaches this honest dead end; a partial export reports its unread pages instead.
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
        onUnreadPages(unreadPages)
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
        val doc = loadDocument(rasterDir(id)) ?: throw IllegalStateException("Scan not found")
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
        noTableNote: String? = null,
        unreadNote: String? = null
    ): ExcelExport = withContext(Dispatchers.IO) {
        val doc = loadDocument(rasterDir(id)) ?: throw IllegalStateException("Scan not found")
        refuseLockedSources(listOf(doc))
        require(doc.pageFiles.isNotEmpty()) { "This document has no pages" }
        val storedText = ocrStore.readText(id)
        val storedGeo = ocrStore.readGeo(id)
        // H4-5: a page the recogniser never read has no table claim to make, so it says so instead
        // of "no table found on this page". O1-7: the defaults are resources, never hard-coded English.
        val unreadNoteText = unreadNote ?: context.getString(R.string.tools_excel_unread_page)
        val noTableNoteText = noTableNote ?: context.getString(R.string.tools_excel_no_table)
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
                    // SEC2 R3: atomic identity re-check inside putPage; a late
                    // publish after a Vault move is refused without crashing.
                    val sourceHash = Sha256.of(page)
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
                        ocrStore.putPage(id, page.name, result.text, result.page, sourceHash)
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
                        .ifEmpty { listOf(listOf(noTableNoteText)) }
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
        val doc = loadDocument(rasterDir(id)) ?: throw IllegalStateException("Scan not found")
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
                OcrStore(OpStaging.root(context.filesDir)).putStagedAll(staging.name, texts, geo)
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
        val source = loadDocument(rasterDir(id))
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
            val sizes = sourcePageSizes(pages)
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
            writePageSizes(copyDir, sizes)
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
            carryPageSize(entryFile(id, entry), jpg, rotate = true)
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
        draftDeleteProbe?.invoke()
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
                carryPageSize(page, File(dir, jpgName))
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
            carryPageSize(entryFile(id, entry), jpg, derive = true)
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
        draftFilterProbe?.invoke()
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
                    carryPageSize(entryFile(id, entry), File(dir, "r_$n.jpg"))
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
                applyRefilter(source, File(dir, "r_$n.jpg"), filter)
                carryPageSize(entryFile(id, entry), File(dir, "r_$n.jpg"))
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
        if (indices != null && doc.isVaulted) {
            throw IOException(context.getString(R.string.doc_actions_vault_reason))
        }
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
        val doc = loadDocument(rasterDir(id))
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
            carryPageSize(entryFile(doc.id, entry), out)
            OpFaults.at("op:page:$i")
        }
        if (texts.isNotEmpty() || geo.isNotEmpty()) {
            OcrStore(OpStaging.root(context.filesDir)).putStagedAll(staging.name, texts, geo)
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
            val digital = digitalStorage.hasLive(id)
            val doc = if (digital) null else loadDocument(File(docsDir, id))
            val name = doc?.name ?: getDocument(id)?.name ?: id
            onProgress(index + 1, unique.size, name)
            currentCoroutineContext().ensureActive()
            if (doc == null) {
                results += BatchItem(id, name, ok = false, reason = if (digital) "This operation does not yet support digital PDFs" else "Document not found")
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
                    val digital = digitalStorage.hasLive(id)
                    val doc = if (digital) null else loadDocument(File(docsDir, id))
                    val name = doc?.name ?: getDocument(id)?.name ?: id
                    onProgress(index + 1, ids.size, name)
                    processed += id
                    currentCoroutineContext().ensureActive()
                    if (doc == null) {
                        failures[id] = if (digital) "This operation does not yet support digital PDFs" else "Document not found"
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
            val digital = digitalStorage.hasLive(id)
            val doc = if (digital) null else loadDocument(File(docsDir, id))
            val name = doc?.name ?: getDocument(id)?.name ?: id
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

    /**
     * UX-007's import-only escrow needs the same write locks as Trash, plus draft/metadata
     * locks, across its whole-directory renames. No policy or mutation lives in this primitive.
     */
    internal fun <T> withImportDiscardLock(action: () -> T): T = synchronized(PDF_LOCK) {
        OcrStore.locked {
            synchronized(TRASH_LOCK) {
                synchronized(DRAFT_LOCK) {
                    synchronized(META_LOCK) { action() }
                }
            }
        }
    }
}
