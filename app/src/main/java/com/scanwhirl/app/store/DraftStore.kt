package com.scanwhirl.app.store

import com.scanwhirl.app.util.Durable
import com.scanwhirl.app.util.Sha256
import com.scanwhirl.app.model.ScanDocument
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * PM-2.2b — the durable page draft, a small manifest over the document's immutable base.
 *
 * `filesDir/drafts/<docId>/draft.txt` lists the base page each output page comes from and its
 * SHA-256, plus an optional `r_<n>.jpg` / `r_<n>.orig` replacement that an edit wrote. Nothing is
 * copied when a draft opens; only an edited page gets a replacement file. Every edit writes a
 * fresh `r_<n>` (names are never reused) and fsyncs it before the manifest is replaced atomically,
 * so a crash leaves either the previous manifest or the new one, never a half-written one.
 *
 * All use happens under `ScanRepository.DRAFT_LOCK`; this class is deliberately lock-free so the
 * repository can keep the lock order (PDF → OCR → Trash) intact.
 */
internal class DraftStore(filesDir: File) {

    class DraftOpen(message: String) : IOException(message)
    class DraftStale(message: String) : IOException(message)
    class DraftDamaged(message: String) : IOException(message)

    /**
     * One output page: either a base page (name + SHA-256) or an inserted page (both null) that
     * only exists as a draft replacement. [replacement] is required for an inserted page.
     */
    data class Entry(
        val source: String?,
        val sourceSha: String?,
        val replacement: String? = null,
        val replacementOrig: String? = null,
        val pageId: String = source ?: UUID.randomUUID().toString()
    )

    data class Draft(
        val baseId: String,
        val basePages: String,
        val created: Long,
        val updated: Long,
        val changes: Int,
        val next: Int,
        val entries: List<Entry>,
        val moved: Set<String> = emptySet(),
        val selection: Set<String> = emptySet(),
        val history: List<Snapshot> = emptyList(),
        val revision: Long = 0,
        /** In-memory baseline; copy() carries it through the repository's raster edits. */
        val checkpoint: Snapshot? = null
    )

    data class Snapshot(
        val entries: List<Entry>,
        val changes: Int,
        val moved: Set<String>,
        val selection: Set<String>
    )

    private fun Draft.snapshot() = Snapshot(entries, changes, moved, selection)

    private val root = File(filesDir, DIR)

    private fun folder(id: String) = File(root, id)
    private fun manifest(id: String) = File(folder(id), MANIFEST)

    fun folderOf(id: String): File = folder(id)

    fun fileOf(id: String, name: String): File = File(folder(id), name)

    fun exists(id: String): Boolean = folder(id).isDirectory

    /** Draft ids in the drafts folder (discard leftovers excluded). */
    fun ids(): List<String> = root.listFiles { f -> f.isDirectory && !f.name.startsWith(DISCARD_PREFIX) }
        .orEmpty().map { it.name }

    /**
     * Reads the manifest, validates it and prunes unreferenced `r_*` files. Returns null when no
     * draft exists. A damaged draft throws [DraftDamaged]; a partial one is never returned.
     */
    fun load(id: String): Draft? = synchronized(LOCK) {
        loadProbe?.invoke()
        if (!SAFE_ID.matches(id)) throw DraftDamaged("Bad draft id")
        val file = manifest(id)
        if (!file.isFile) return@synchronized null
        val lines = try {
            // SEC1 1.3.0: a hostile backup cannot make draft recovery read unbounded text.
            OcrStore.readBoundedLines(file, MAX_MANIFEST_BYTES)
        } catch (_: Exception) {
            throw DraftDamaged("Draft manifest unreadable")
        }
        if (lines.firstOrNull() != HEADER) throw DraftDamaged("Draft manifest header")
        var base: String? = null
        var basePages: String? = null
        var created = 0L
        var updated = 0L
        var changes = -1
        var next = -1
        var revision = 0L
        var metadata: JSONObject? = null
        val entries = ArrayList<Entry>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            if (line.startsWith("p=")) {
                val parts = line.removePrefix("p=").split('\t')
                if (parts.size !in 4..5) throw DraftDamaged("Draft entry")
                val inserted = parts[0] == "-"
                val source: String?
                val sha: String?
                if (inserted) {
                    source = null
                    sha = null
                    if (parts[1] != "-") throw DraftDamaged("Inserted page sha")
                } else {
                    source = parts[0]
                    if (!SOURCE_NAME.matches(source)) throw DraftDamaged("Draft source name")
                    sha = parts[1]
                    if (!SHA.matches(sha)) throw DraftDamaged("Draft source sha")
                }
                val replacement = parts[2].takeIf { it != "-" }?.also {
                    if (!REPLICA_NAME.matches(it)) throw DraftDamaged("Draft replacement name")
                }
                val replacementOrig = parts[3].takeIf { it != "-" }?.also {
                    if (!REPLICA_ORIG_NAME.matches(it)) throw DraftDamaged("Draft original name")
                }
                if (inserted && replacement == null) throw DraftDamaged("Inserted page has no file")
                val pageId = parts.getOrNull(4) ?: source ?: replacement!!
                if (!SAFE_ID.matches(pageId) && !SOURCE_NAME.matches(pageId) && !REPLICA_NAME.matches(pageId)) {
                    throw DraftDamaged("Draft page identity")
                }
                entries += Entry(source, sha, replacement, replacementOrig, pageId)
                continue
            }
            val eq = line.indexOf('=')
            if (eq <= 0) throw DraftDamaged("Draft manifest line")
            val key = line.substring(0, eq)
            val value = line.substring(eq + 1)
            when (key) {
                "base" -> base = value
                "basePages" -> basePages = value
                "created" -> created = value.toLongOrNull() ?: throw DraftDamaged("Draft created")
                "updated" -> updated = value.toLongOrNull() ?: throw DraftDamaged("Draft updated")
                "changes" -> changes = value.toIntOrNull() ?: throw DraftDamaged("Draft changes")
                "next" -> next = value.toIntOrNull() ?: throw DraftDamaged("Draft next")
                "revision" -> revision = value.toLongOrNull()?.takeIf { it >= 0 }
                    ?: throw DraftDamaged("Draft revision")
                "organize" -> metadata = try { JSONObject(value) } catch (_: Exception) {
                    throw DraftDamaged("Draft organize state")
                }
                else -> Unit
            }
        }
        if (base != id) throw DraftDamaged("Draft base does not match its folder")
        if (basePages == null || !SHA.matches(basePages)) throw DraftDamaged("Draft base digest")
        if (created <= 0L || updated < created) throw DraftDamaged("Draft times")
        if (changes < 0 || next < 0) throw DraftDamaged("Draft counters")
        if (entries.isEmpty()) throw DraftDamaged("Draft has no pages")
        entries.forEach { entry ->
            listOfNotNull(entry.replacement, entry.replacementOrig).forEach { name ->
                if (!File(folder(id), name).isFile) throw DraftDamaged("Draft replacement missing")
            }
        }
        val moved = metadata?.optJSONArray("moved").strings()
        val selection = metadata?.optJSONArray("selection").strings()
        val history = ArrayList<Snapshot>()
        try {
            val snapshots = metadata?.optJSONArray("undo") ?: JSONArray()
            for (i in 0 until snapshots.length()) {
                val s = snapshots.getJSONObject(i)
                val restored = s.getJSONArray("entries")
                val savedEntries = (0 until restored.length()).map { j ->
                    val e = restored.getJSONObject(j)
                    Entry(e.nullable("source"), e.nullable("sha"), e.nullable("replacement"),
                        e.nullable("orig"), e.getString("id")).also { validateEntry(id, it) }
                }
                if (savedEntries.isEmpty()) throw DraftDamaged("Empty undo")
                history += Snapshot(savedEntries, s.getInt("changes").also {
                    if (it < 0) throw DraftDamaged("Undo changes")
                }, s.optJSONArray("moved").strings(), s.optJSONArray("selection").strings())
            }
        } catch (e: Exception) {
            throw DraftDamaged("Draft undo state")
        }
        val snapshot = Snapshot(entries, changes, moved, selection)
        val draft = Draft(id, basePages, created, updated, changes, next, entries,
            moved, selection, history, revision, snapshot)
        prune(id, draft)
        draft
    }

    private fun JSONObject.nullable(key: String): String? =
        if (isNull(key)) null else getString(key)

    private fun JSONArray?.strings(): Set<String> =
        if (this == null) emptySet() else (0 until length()).map { getString(it) }.toSet()

    private fun validateEntry(id: String, e: Entry) {
        if (e.source != null && (!SOURCE_NAME.matches(e.source) || e.sourceSha?.let(SHA::matches) != true)) {
            throw DraftDamaged("Undo source")
        }
        if (e.source == null && (e.sourceSha != null || e.replacement == null)) throw DraftDamaged("Undo inserted page")
        if (!SAFE_ID.matches(e.pageId) && !SOURCE_NAME.matches(e.pageId) && !REPLICA_NAME.matches(e.pageId)) {
            throw DraftDamaged("Undo identity")
        }
        listOf(e.replacement to REPLICA_NAME, e.replacementOrig to REPLICA_ORIG_NAME).forEach { (name, pattern) ->
            if (name != null && (!pattern.matches(name) || !fileOf(id, name).isFile)) {
                throw DraftDamaged("Undo replacement")
            }
        }
    }

    /** Writes the manifest atomically (`draft.txt.tmp` + fsync + rename). */
    fun save(draft: Draft) = synchronized(LOCK) {
        // Compare-and-swap also protects the repository's existing load/copy/save callers
        // from a concurrent organized edit. Never let an older manifest overwrite newer work.
        val existing = manifest(draft.baseId)
        val diskRevision = if (existing.isFile) OcrStore.readBoundedLines(existing, MAX_MANIFEST_BYTES)
            .firstOrNull { it.startsWith("revision=") }?.substringAfter('=')?.toLongOrNull() ?: 0L else 0L
        if (diskRevision != draft.revision) throw DraftStale("Draft changed during the edit")
        val history = if (draft.checkpoint != null && draft.checkpoint.entries != draft.entries)
            draft.history + draft.checkpoint else draft.history
        val dir = folder(draft.baseId).apply { mkdirs() }
        val text = buildString {
            append(HEADER).append('\n')
            append("base=").append(draft.baseId).append('\n')
            append("basePages=").append(draft.basePages).append('\n')
            append("created=").append(draft.created).append('\n')
            append("updated=").append(draft.updated).append('\n')
            append("changes=").append(draft.changes).append('\n')
            append("next=").append(draft.next).append('\n')
            append("revision=").append(diskRevision + 1).append('\n')
            val state = JSONObject().put("moved", JSONArray(draft.moved.toList()))
                .put("selection", JSONArray(draft.selection.toList()))
                .put("undo", JSONArray(history.map { s ->
                    JSONObject().put("changes", s.changes)
                        .put("moved", JSONArray(s.moved.toList()))
                        .put("selection", JSONArray(s.selection.toList()))
                        .put("entries", JSONArray(s.entries.map { e ->
                            JSONObject().put("source", e.source ?: JSONObject.NULL)
                                .put("sha", e.sourceSha ?: JSONObject.NULL)
                                .put("replacement", e.replacement ?: JSONObject.NULL)
                                .put("orig", e.replacementOrig ?: JSONObject.NULL).put("id", e.pageId)
                        }))
                }))
            append("organize=").append(state.toString()).append('\n')
            draft.entries.forEach { entry ->
                append("p=").append(entry.source ?: "-")
                    .append('\t').append(entry.sourceSha ?: "-")
                    .append('\t').append(entry.replacement ?: "-")
                    .append('\t').append(entry.replacementOrig ?: "-")
                    .append('\t').append(entry.pageId)
                    .append('\n')
            }
        }
        if (text.toByteArray(Charsets.UTF_8).size > MAX_MANIFEST_BYTES) {
            throw IOException("Draft history is full")
        }
        Durable.atomicWriteText(File(dir, MANIFEST), text)
    }

    /** Called only for an explicit edit, never on open. Raster writers keep their safety checks. */
    fun prepareOrganize(doc: ScanDocument, selectedPaths: Set<String>): Draft = synchronized(LOCK) {
        checkEditable(doc)
        val current = load(doc.id) ?: run {
            val now = System.currentTimeMillis()
            Draft(doc.id, SaveFormat.pagesDigest(doc.pageFiles), now, now, 0, 0,
                doc.pageFiles.map { Entry(it.name, Sha256.of(it)) })
        }
        if (SaveFormat.pagesDigest(doc.pageFiles) != current.basePages) throw DraftStale("Base changed")
        val selection = current.entries.filter { fileFor(doc, it).path in selectedPaths }.map { it.pageId }.toSet()
        // Save selection in the checkpoint so Undo restores exactly the pre-edit selection.
        save(current.copy(selection = selection, checkpoint = null))
        load(doc.id)!!
    }

    fun fileFor(doc: ScanDocument, entry: Entry): File =
        entry.replacement?.let { fileOf(doc.id, it) }
            ?: File(doc.pageFiles.first().parentFile, requireNotNull(entry.source))

    /** Group reorder/delete is one durable manifest replacement and one history entry. */
    fun organizeOrder(
        doc: ScanDocument, expected: List<File>, order: List<File>,
        explicitlyMoved: List<File>, selectedPaths: Set<String>
    ): Draft = synchronized(LOCK) {
        checkEditable(doc)
        val d = prepareOrganize(doc, selectedPaths)
        val byPath = d.entries.associateBy { fileFor(doc, it).path }
        if (expected.map { it.path } != d.entries.map { fileFor(doc, it).path }) {
            throw DraftStale("Displayed pages changed")
        }
        require(order.isNotEmpty()) { "Can't delete the last page" }
        require(order.distinct().size == order.size && order.all { it.path in byPath }) { "Invalid order" }
        val entries = order.map { byPath.getValue(it.path) }
        if (entries == d.entries) return@synchronized d
        val moved = d.moved + explicitlyMoved.mapNotNull { byPath[it.path]?.pageId }
        save(d.copy(entries = entries, changes = d.changes + 1,
            updated = System.currentTimeMillis(), moved = moved))
        load(doc.id)!!
    }

    /** Collapse a multi-page raster operation to one undo unit, retaining all prior page files. */
    fun finishOrganizeBatch(doc: ScanDocument, before: Draft): Draft = synchronized(LOCK) {
        val after = load(doc.id) ?: throw DraftDamaged("Draft missing")
        save(after.copy(history = before.history + before.snapshot(), checkpoint = null,
            changes = before.changes + 1))
        load(doc.id)!!
    }

    fun undoOrganize(doc: ScanDocument): Draft? = synchronized(LOCK) {
        checkEditable(doc)
        val d = load(doc.id) ?: return@synchronized null
        if (SaveFormat.pagesDigest(doc.pageFiles) != d.basePages) throw DraftStale("Base changed")
        val previous = d.history.lastOrNull() ?: return@synchronized d
        // Never rewind next: replacement names are never reused, including after Undo.
        save(d.copy(entries = previous.entries, changes = previous.changes, moved = previous.moved,
            selection = previous.selection, history = d.history.dropLast(1), checkpoint = null,
            updated = System.currentTimeMillis()))
        load(doc.id)
    }

    private fun checkEditable(doc: ScanDocument) {
        if (doc.isLocked || doc.isVaulted) throw IOException("Protected document")
    }

    /** Deletes one draft. The folder is renamed aside first, so recovery can finish it. */
    fun discard(id: String) {
        val dir = folder(id)
        if (!dir.exists()) return
        val hidden = File(root, "$DISCARD_PREFIX$id-${UUID.randomUUID().toString().take(8)}")
        if (dir.renameTo(hidden)) {
            hidden.deleteRecursively()
        } else {
            dir.deleteRecursively()
        }
    }

    /** Deletes hidden folders left by an interrupted discard. */
    fun removeDiscardLeftovers() {
        root.listFiles { f -> f.isDirectory && f.name.startsWith(DISCARD_PREFIX) }
            ?.forEach { it.deleteRecursively() }
    }

    private fun prune(id: String, draft: Draft) {
        val referenced = (draft.entries + draft.history.flatMap { it.entries })
            .flatMap { listOfNotNull(it.replacement, it.replacementOrig) }.toSet()
        folder(id).listFiles { f -> REPLICA_NAME.matches(f.name) || REPLICA_ORIG_NAME.matches(f.name) }
            ?.filter { it.name !in referenced }
            ?.forEach { it.delete() }
    }

    internal companion object {
        private val LOCK = Any()
        /** Test seam: counts manifest parses. Null in production (L5-2 measurement). */
        internal var loadProbe: (() -> Unit)? = null

        const val DIR = "drafts"
        /** SEC1 1.3.0: shared with restore-time rejection of oversized manifests. */
        internal const val MAX_MANIFEST_BYTES = 4L * 1024 * 1024
        private const val MANIFEST = "draft.txt"
        private const val HEADER = "scanwhirl-draft-v1"
        private const val DISCARD_PREFIX = ".discard-"
        private val SAFE_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
        private val SOURCE_NAME = Regex("^page_\\d{3}\\.jpg$")
        private val REPLICA_NAME = Regex("^r_\\d+\\.jpg$")
        private val REPLICA_ORIG_NAME = Regex("^r_\\d+\\.orig$")
        private val SHA = Regex("^[0-9a-f]{64}$")
    }
}
