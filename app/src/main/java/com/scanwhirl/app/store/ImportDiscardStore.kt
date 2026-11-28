package com.scanwhirl.app.store

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.scanwhirl.app.ui.pagegrid.PageThumbLoader
import com.scanwhirl.app.util.Durable
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * UX-007: finite, private Undo escrow, never Library Trash and never a source URI.
 *
 * The manifest is durable BEFORE any rename. STAGING rolls back; PENDING alone may expire;
 * RESTORING finishes an accepted Undo even after its deadline; PURGING finishes deletion.
 * Each payload directory is moved whole, under the repository's existing write locks.
 * A transaction marker travels with the document so recovery never overwrites an unrelated ID.
 * Only validated children of filesDir/documents, drafts and import_discard can be moved.
 *
 * The manifest carries BOTH a monotonic (elapsedRealtime) and a wall-clock window. Inside one
 * boot the monotonic deadline is authoritative, so a wall-clock adjustment cannot change a
 * promised Undo. Across a reboot elapsedRealtime restarts: the first recovery persists an anchor
 * (wall time then + a conservatively bounded remaining window, plus the new boot's monotonic
 * reading). Later waits use that monotonic anchor, so even a clock that keeps moving backwards
 * cannot keep an expired escrow alive. v1/v2 manifests are read and upgraded on first recovery.
 */
class ImportDiscardStore(
    context: Context,
    private val repo: ScanRepository = ScanRepository(context),
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val boot: () -> Int = {
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }
            .getOrDefault(-1)
    },
    private val wall: () -> Long = System::currentTimeMillis
) {
    private val app = context.applicationContext
    private val root get() = File(app.filesDir, "import_discard").apply { mkdirs() }

    data class Token(val key: String, val id: String, val deadline: Long)
    private data class Record(
        val phase: String, val start: Long, val deadline: Long, val boot: Int,
        val wallStart: Long, val wallDeadline: Long,
        val anchorBoot: Int = -1, val anchorElapsed: Long = -1,
        val anchorWall: Long = -1, val anchorWindow: Long = -1
    )
    enum class Stage {
        MANIFEST_WRITTEN, DOCUMENT_MOVED, DRAFT_MOVED, COMMITTED,
        RESTORE_STARTED, DOCUMENT_RESTORED, DRAFT_RESTORED, PURGE_STARTED
    }

    companion object {
        private const val MARKER = ".import-discard"
        private val ID = Regex("[A-Za-z0-9_-]{1,64}")
        private val KEY = Regex("([A-Za-z0-9_-]{1,64})--[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        @Volatile internal var fault: (Stage) -> Unit = {}
    }

    private fun tx(key: String): File? =
        key.takeIf { KEY.matches(it) }?.let { File(root, it) }
            ?.takeIf { it.canonicalFile.parentFile == root.canonicalFile }

    private fun idOf(dir: File): String = requireNotNull(KEY.matchEntire(dir.name)).groupValues[1]
    private fun active(id: String) = File(repo.docsDir, id)
    private fun draft(id: String) = File(app.filesDir, "drafts/$id")

    private fun write(dir: File, record: Record) {
        Durable.atomicWriteText(File(dir, "manifest"),
            "v3\n${record.phase}\n${idOf(dir)}\n${record.start}\n${record.deadline}\n" +
                "${record.boot}\n${record.wallStart}\n${record.wallDeadline}\n" +
                "${record.anchorBoot}\n${record.anchorElapsed}\n${record.anchorWall}\n" +
                "${record.anchorWindow}\n")
        Durable.syncDir(dir)
    }

    private fun read(dir: File): Record? = runCatching {
        val lines = File(dir, "manifest").readLines()
        require(lines.size >= 6 && lines[2] == idOf(dir))
        require(lines[1] in listOf("STAGING", "PENDING", "RESTORING", "PURGING"))
        val start = lines[3].toLong()
        val deadline = lines[4].toLong()
        val window = deadline - start
        require(start >= 0 && window > 0 && window <= Int.MAX_VALUE)
        val bootNo = lines[5].toInt()
        when (lines[0]) {
            // A v1 escrow has no wall window: the manifest's own creation time is the only
            // durable wall fact. If even that is missing, keep it restorable via the anchor.
            "v1" -> {
                require(lines.size == 6)
                val created = File(dir, "manifest").lastModified()
                val wallStart = if (created > 0) created else -1L
                val wallDeadline = if (wallStart > 0) wallStart + window else -1L
                Record(lines[1], start, deadline, bootNo, wallStart, wallDeadline)
            }
            "v2" -> {
                require(lines.size == 8)
                val wallStart = lines[6].toLong()
                val wallDeadline = lines[7].toLong()
                require(wallStart >= 0 && wallDeadline > wallStart && wallDeadline - wallStart <= Int.MAX_VALUE)
                Record(lines[1], start, deadline, bootNo, wallStart, wallDeadline)
            }
            "v3" -> {
                require(lines.size == 12)
                val wallStart = lines[6].toLong()
                val wallDeadline = lines[7].toLong()
                require(wallStart >= 0 && wallDeadline > wallStart && wallDeadline - wallStart <= Int.MAX_VALUE)
                val anchorBoot = lines[8].toInt()
                val anchorElapsed = lines[9].toLong()
                val anchorWall = lines[10].toLong()
                val anchorWindow = lines[11].toLong()
                require(anchorBoot == -1 || (anchorElapsed >= 0 && anchorWall >= 0 &&
                    anchorWindow >= 0 && anchorWindow <= window))
                Record(lines[1], start, deadline, bootNo, wallStart, wallDeadline,
                    anchorBoot, anchorElapsed, anchorWall, anchorWindow)
            }
            else -> error("Unknown import escrow manifest")
        }
    }.getOrNull()

    /** The monotonic clock only counts while this boot lasts; after a reboot use the anchor. */
    private fun sameBoot(record: Record) = record.boot == boot() && elapsed() >= record.start

    private fun anchoredHere(record: Record) = record.anchorBoot == boot() && record.anchorWindow >= 0

    private fun window(record: Record) = record.deadline - record.start

    /** min of the original wall deadline and the bounded deadline inherited from an anchor. */
    private fun effectiveWallDeadline(record: Record): Long {
        val creation = if (record.wallDeadline > 0) record.wallDeadline else Long.MAX_VALUE
        val anchor = if (record.anchorWindow >= 0) record.anchorWall + record.anchorWindow
                     else Long.MAX_VALUE
        return minOf(creation, anchor)
    }

    private fun remaining(record: Record): Long = when {
        anchoredHere(record) ->
            (record.anchorElapsed + record.anchorWindow - elapsed()).coerceAtLeast(0)
        sameBoot(record) ->
            (record.deadline - elapsed()).coerceAtLeast(0)
        else ->
            (effectiveWallDeadline(record) - wall())
                .coerceAtMost(window(record)).coerceAtLeast(0)
    }

    private fun expired(record: Record) = remaining(record) <= 0

    /** First recovery after a boot change: persist a bounded, monotonic deadline for this boot. */
    private fun anchor(dir: File, record: Record): Record {
        if (anchoredHere(record) || sameBoot(record)) return record
        val now = wall()
        val left = (effectiveWallDeadline(record) - now)
            .coerceAtMost(window(record)).coerceAtLeast(0)
        val updated = record.copy(
            anchorBoot = boot(), anchorElapsed = elapsed(),
            anchorWall = now, anchorWindow = left
        )
        write(dir, updated)
        return updated
    }

    private fun deadlineOf(record: Record) = when {
        anchoredHere(record) -> record.anchorElapsed + record.anchorWindow
        sameBoot(record) -> record.deadline
        else -> effectiveWallDeadline(record)
    }

    fun remainingMs(token: Token): Long = repo.withImportDiscardLock {
        val dir = tx(token.key) ?: return@withImportDiscardLock 0
        val record = read(dir) ?: return@withImportDiscardLock 0
        if (record.phase != "PENDING") 0 else remaining(record)
    }

    fun discard(id: String, undoMs: Int): Token = repo.withImportDiscardLock {
        require(ID.matches(id))
        require(undoMs >= 8_000)
        val doc = repo.getDocument(id) ?: throw IOException("Import is no longer available")
        check(!doc.isVaulted && repo.exportRecordsOf(id).isEmpty() &&
            hasNoDraftEdits(id)) { "An import already used must be kept" }
        requireOwnedTree(active(id))
        if (draft(id).exists()) requireOwnedTree(draft(id))
        val dir = File(root, "$id--${UUID.randomUUID()}")
        if (!dir.mkdir()) throw IOException("Could not stage import")
        val start = elapsed()
        val wallStart = wall()
        val record = Record("STAGING", start, start + undoMs, boot(), wallStart, wallStart + undoMs)
        var committed = record
        try {
            write(dir, record)
            Durable.syncDir(root)
            fault(Stage.MANIFEST_WRITTEN)
            Durable.atomicWriteText(File(active(id), MARKER), dir.name)
            // Draft first: interruption can never leave an orphan active draft for the
            // normal startup reaper to mistake for abandoned work.
            if (draft(id).exists()) move(draft(id), File(dir, "draft"))
            fault(Stage.DRAFT_MOVED)
            move(active(id), File(dir, "document"))
            fault(Stage.DOCUMENT_MOVED)
            clearCaches(id, File(dir, "document"))
            repo.ocrStore.pendingQueue()
            val readyAt = elapsed()
            val readyWall = wall()
            committed = Record("PENDING", readyAt, readyAt + undoMs, boot(),
                readyWall, readyWall + undoMs)
            write(dir, committed)
        } catch (e: Exception) {
            // No successful receipt is delivered until PENDING is durable. Failure never
            // falls back to deletion; even a failed rollback retains a recoverable escrow.
            runCatching { restorePayload(dir) }
            throw e
        }
        // PENDING is durable: a later fault is NOT a failed discard. Swallowing it keeps the
        // success receipt in the UI, which starts expiry; recovery still completes on restart.
        runCatching { fault(Stage.COMMITTED) }
        Token(dir.name, id, committed.deadline)
    }

    /** Reconciles interrupted work and returns every unexpired durable Undo token. Off main. */
    fun recover(): List<Token> = repo.withImportDiscardLock {
        val pending = mutableListOf<Token>()
        root.listFiles()?.filter { it.isDirectory && KEY.matches(it.name) }?.forEach { dir ->
            // Never follow an unexpected symlink outside this private transaction tree.
            if (tx(dir.name) == null || dir.canonicalFile != File(root.canonicalFile, dir.name)) return@forEach
            val record = read(dir)
            when {
                record == null || record.phase == "STAGING" || record.phase == "RESTORING" ->
                    restorePayload(dir)
                record.phase == "PURGING" -> purge(dir)
                else -> {
                    val current = if (record.phase == "PENDING" &&
                        !sameBoot(record) && !anchoredHere(record)) anchor(dir, record) else record
                    if (expired(current)) purge(dir)
                    else pending += Token(dir.name, idOf(dir), deadlineOf(current))
                }
            }
        }
        pending
    }

    fun pending(): List<Token> = recover()

    /** Single-use Undo. Expiry and Undo are serialized by the same process-wide locks. */
    fun restore(key: String): String? = repo.withImportDiscardLock {
        val dir = tx(key)?.takeIf { it.isDirectory } ?: return@withImportDiscardLock null
        val record = read(dir) ?: return@withImportDiscardLock null
        if (record.phase != "PENDING") return@withImportDiscardLock null
        if (remaining(record) <= 0) {
            // Same boot: the monotonic deadline really passed. After reboot: the persisted
            // wall deadline really passed. Neither case may resurrect an expired import.
            purge(dir)
            return@withImportDiscardLock null
        }
        write(dir, record.copy(phase = "RESTORING"))
        fault(Stage.RESTORE_STARTED)
        val id = idOf(dir)
        restorePayload(dir)
        repo.ocrStore.pendingQueue()
        id
    }

    private fun restorePayload(dir: File) {
        val id = idOf(dir)
        val document = File(dir, "document")
        val dest = active(id)
        val hadPayload = document.exists()
        if (document.exists()) {
            if (dest.exists()) throw IOException("Cannot overwrite an existing document")
            move(document, dest)
        }
        if (!dest.isDirectory) throw IOException("Import is not available for recovery")
        // R01 F1: a same-titled document can be created while the Undo receipt is live (the
        // escrow is invisible to uniqueDocumentName). Resolve the title under the escrow's
        // write lock, while the document is still hidden from docsDir readers, exactly as the
        // Trash restore does — otherwise two different documents share an identical title.
        repo.resolveNameBeforePublish(dest)
        val marker = File(dest, MARKER)
        if (!hadPayload && File(dir, "draft").exists() &&
            (!marker.isFile || marker.readText() != dir.name)) {
            throw IOException("Cannot attach a draft to an unrelated document")
        }
        fault(Stage.DOCUMENT_RESTORED)
        val savedDraft = File(dir, "draft")
        if (savedDraft.exists()) {
            if (draft(id).exists()) throw IOException("Cannot overwrite an existing draft")
            draft(id).parentFile!!.mkdirs()
            move(savedDraft, draft(id))
        }
        fault(Stage.DRAFT_RESTORED)
        // If a previous restore moved the document, its token proves this is OUR item.
        if (marker.isFile && marker.readText() == dir.name && !marker.delete()) {
            throw IOException("Could not finish restoring import")
        }
        if (!dir.deleteRecursively()) throw IOException("Could not finish restoring import")
        Durable.syncDir(root)
    }

    private fun purge(dir: File) {
        val record = read(dir) ?: return
        if (record.phase != "PURGING") write(dir, record.copy(phase = "PURGING"))
        fault(Stage.PURGE_STARTED)
        clearCaches(idOf(dir), File(dir, "document"))
        // Payload first, manifest last: death during removal leaves a durable PURGING intent.
        for (name in listOf("document", "draft")) {
            val payload = File(dir, name)
            if (payload.exists()) requireOwnedTree(payload)
            if (payload.exists() && !payload.deleteRecursively()) throw IOException("Could not finish discard")
        }
        if (!dir.deleteRecursively()) throw IOException("Could not finish discard")
        Durable.syncDir(root)
    }

    private fun move(from: File, to: File) {
        if (from.canonicalFile != File(requireNotNull(from.parentFile).canonicalFile, from.name) ||
            to.exists() || !from.renameTo(to)) {
            throw IOException("Could not move import")
        }
        from.parentFile?.let { Durable.syncDir(it) }
        to.parentFile?.let { Durable.syncDir(it) }
    }

    private fun requireOwnedTree(dir: File) {
        val canonicalRoot = dir.canonicalFile
        if (canonicalRoot != File(requireNotNull(dir.parentFile).canonicalFile, dir.name)) {
            throw IOException("Unexpected link in import")
        }
        dir.walkTopDown().forEach {
            if (it.canonicalFile != File(canonicalRoot, it.relativeTo(dir).path)) {
                throw IOException("Unexpected link in import")
            }
        }
    }

    private fun hasNoDraftEdits(id: String): Boolean {
        val manifest = File(draft(id), "draft.txt")
        if (!manifest.exists()) return true
        // DraftStore.load also prunes orphan replacements. Discard's guard must NOT mutate
        // any original bytes before escrow, so inspect only the versioned identity/change
        // header here. Unknown/damaged headers fail closed; payload stays opaque and exact.
        val lines = manifest.readLines()
        return lines.firstOrNull() == "scanwhirl-draft-v1" &&
            lines.filter { it.startsWith("base=") } == listOf("base=$id") &&
            lines.filter { it.startsWith("changes=") } == listOf("changes=0")
    }

    private fun clearCaches(id: String, payload: File) {
        payload.walkTopDown().filter { it.isFile }.forEach {
            PageThumbLoader.invalidate(File(active(id), it.relativeTo(payload).path))
        }
        listOf(File(app.cacheDir, "preview_$id.pdf"), File(app.cacheDir, "preview_$id.fp"),
            File(app.cacheDir, "documents/preview_$id.pdf")).forEach {
            if (it.exists() && !it.delete()) throw IOException("Could not clear import preview")
        }
    }
}
