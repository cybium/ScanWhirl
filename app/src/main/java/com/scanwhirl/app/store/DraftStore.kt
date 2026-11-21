package com.scanwhirl.app.store

import com.scanwhirl.app.util.Durable
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
        val replacementOrig: String? = null
    )

    data class Draft(
        val baseId: String,
        val basePages: String,
        val created: Long,
        val updated: Long,
        val changes: Int,
        val next: Int,
        val entries: List<Entry>
    )

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
    fun load(id: String): Draft? {
        loadProbe?.invoke()
        if (!SAFE_ID.matches(id)) throw DraftDamaged("Bad draft id")
        val file = manifest(id)
        if (!file.isFile) return null
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
        val entries = ArrayList<Entry>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            if (line.startsWith("p=")) {
                val parts = line.removePrefix("p=").split('\t')
                if (parts.size != 4) throw DraftDamaged("Draft entry")
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
                entries += Entry(source, sha, replacement, replacementOrig)
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
        val draft = Draft(id, basePages, created, updated, changes, next, entries)
        prune(id, draft)
        return draft
    }

    /** Writes the manifest atomically (`draft.txt.tmp` + fsync + rename). */
    fun save(draft: Draft) {
        val dir = folder(draft.baseId).apply { mkdirs() }
        val text = buildString {
            append(HEADER).append('\n')
            append("base=").append(draft.baseId).append('\n')
            append("basePages=").append(draft.basePages).append('\n')
            append("created=").append(draft.created).append('\n')
            append("updated=").append(draft.updated).append('\n')
            append("changes=").append(draft.changes).append('\n')
            append("next=").append(draft.next).append('\n')
            draft.entries.forEach { entry ->
                append("p=").append(entry.source ?: "-")
                    .append('\t').append(entry.sourceSha ?: "-")
                    .append('\t').append(entry.replacement ?: "-")
                    .append('\t').append(entry.replacementOrig ?: "-")
                    .append('\n')
            }
        }
        Durable.atomicWriteText(File(dir, MANIFEST), text)
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
        val referenced = draft.entries.flatMap { listOfNotNull(it.replacement, it.replacementOrig) }.toSet()
        folder(id).listFiles { f -> REPLICA_NAME.matches(f.name) || REPLICA_ORIG_NAME.matches(f.name) }
            ?.filter { it.name !in referenced }
            ?.forEach { it.delete() }
    }

    internal companion object {
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
