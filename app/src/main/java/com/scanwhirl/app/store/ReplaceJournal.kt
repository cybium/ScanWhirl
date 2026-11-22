package com.scanwhirl.app.store

import android.content.Context
import com.scanwhirl.app.util.Durable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Lane CR — write-ahead journal for "Replace original" (COUNCIL §5).
 *
 * An INTENT entry is fsynced BEFORE any document is moved. Only after the
 * identity-gated move does the state advance to COMMITTING, then DONE. A death
 * at any boundary is repaired by [recoverBeforeExpiry], which runs before
 * Trash expiry and keeps pending entries out of the 30-day purge (the Trash
 * marker carries `previous=<opId>`). The journal never authorizes a permanent
 * delete: it only decides whether the original is in the library or in Trash.
 */
class ReplaceJournal(private val context: Context) {

    data class Entry(
        val opId: String,
        val sourceId: String,
        val outputId: String,
        val operation: String,
        val sourceIdentity: String,
        val outputIdentity: String,
        val sourceName: String,
        val state: String,
        val trashKey: String?,
        val createdAt: Long,
        val updatedAt: Long,
        val authId: String? = null,
        val undoOutputKey: String? = null,
        val undoOutputChanged: Boolean = false
    )

    private fun dir(): File = File(context.filesDir, "replace").apply { mkdirs() }

    private fun fileFor(opId: String): File {
        val safe = opId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(80)
        require(safe.isNotEmpty()) { "operation id required" }
        return File(dir(), "$safe.journal")
    }

    /** Atomic + fsynced. Nothing destructive may happen before this returns. */
    fun write(entry: Entry) {
        Durable.atomicWriteText(fileFor(entry.opId), buildString {
            append(HEADER).append('\n')
            append("opId=").append(enc(entry.opId)).append('\n')
            append("sourceId=").append(enc(entry.sourceId)).append('\n')
            append("outputId=").append(enc(entry.outputId)).append('\n')
            append("operation=").append(enc(entry.operation)).append('\n')
            append("sourceIdentity=").append(entry.sourceIdentity).append('\n')
            append("outputIdentity=").append(entry.outputIdentity).append('\n')
            append("sourceName=").append(enc(entry.sourceName)).append('\n')
            append("state=").append(entry.state).append('\n')
            append("trashKey=").append(enc(entry.trashKey)).append('\n')
            append("createdAt=").append(entry.createdAt).append('\n')
            append("updatedAt=").append(entry.updatedAt).append('\n')
            append("authId=").append(enc(entry.authId)).append('\n')
            append("undoOutputKey=").append(enc(entry.undoOutputKey)).append('\n')
            append("undoOutputChanged=").append(entry.undoOutputChanged).append('\n')
        })
    }

    fun load(opId: String): Entry? = parse(fileFor(opId))

    fun all(): List<Entry> = dir().listFiles { f -> f.isFile && f.name.endsWith(".journal") }
        .orEmpty().mapNotNull { parse(it) }

    fun findBySource(sourceId: String): Entry? = all().firstOrNull { it.sourceId == sourceId }

    fun findByTrashKey(key: String): Entry? = all().firstOrNull { it.trashKey == key }

    fun delete(opId: String) {
        fileFor(opId).delete()
    }

    private fun parse(file: File): Entry? {
        if (!file.isFile) return null
        val lines = runCatching { file.readLines() }.getOrNull() ?: return null
        if (lines.firstOrNull() != HEADER) return null
        val v = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val at = line.indexOf('=')
            if (at > 0) v[line.substring(0, at)] = line.substring(at + 1)
        }
        val id = dec(v["opId"]) ?: return null
        return Entry(
            opId = id,
            sourceId = dec(v["sourceId"]) ?: return null,
            outputId = dec(v["outputId"]) ?: return null,
            operation = dec(v["operation"]).orEmpty(),
            sourceIdentity = v["sourceIdentity"].orEmpty(),
            outputIdentity = v["outputIdentity"].orEmpty(),
            sourceName = dec(v["sourceName"]).orEmpty(),
            state = v["state"] ?: STATE_INTENT,
            trashKey = dec(v["trashKey"]),
            createdAt = v["createdAt"]?.toLongOrNull() ?: 0L,
            updatedAt = v["updatedAt"]?.toLongOrNull() ?: 0L,
            authId = dec(v["authId"]),
            undoOutputKey = dec(v["undoOutputKey"]),
            undoOutputChanged = v["undoOutputChanged"]?.toBooleanStrictOrNull() ?: false
        )
    }

    /**
     * Reconcile journals BEFORE Trash expiry. Pending entries get a fresh
     * `previous=` marker so recoverTrash skips them; a move that never happened
     * is rolled back by dropping its journal; a completed move is only ever
     * confirmed, never repeated.
     */
    fun recoverBeforeExpiry(repo: ScanRepository): Int {
        var touched = 0
        for (entry in all()) {
            when (entry.state) {
                STATE_INTENT -> {
                    if (repo.getDocument(entry.sourceId) != null) {
                        // No move happened: nothing to roll back.
                        delete(entry.opId)
                    } else {
                        // Death after the move but before the key was journalled.
                        val moved = repo.replaceTrashKeyForOp(entry.opId)
                        if (moved != null && repo.trashHasIdentity(moved, entry.sourceIdentity, entry.opId)) {
                            write(entry.copy(state = STATE_DONE, trashKey = moved,
                                updatedAt = System.currentTimeMillis()))
                            touched++
                        }
                    }
                }
                STATE_COMMITTING -> {
                    val key = entry.trashKey
                    if (key == null || repo.trashHasIdentity(key, entry.sourceIdentity, entry.opId)) {
                        if (key != null) touched++
                    } else if (repo.getDocument(entry.sourceId) != null) {
                        delete(entry.opId) // move never happened
                    }
                }
                STATE_DONE -> if (repo.getDocument(entry.sourceId) != null && entry.trashKey == null) {
                    write(entry.copy(state = STATE_UNDO, updatedAt = System.currentTimeMillis()))
                }
                STATE_UNDOING, STATE_UNDO -> {
                    // An interrupted Undo: the original is back, the output either
                    // went to Trash or was kept because it had changed. Either way
                    // both documents still exist, so only the journal needs finishing.
                    write(entry.copy(state = STATE_UNDONE, updatedAt = System.currentTimeMillis()))
                }
            }
        }
        prune(repo)
        return touched
    }

    fun backdate(opId: String, time: Long) {
        load(opId)?.let { write(it.copy(createdAt = time, updatedAt = time)) }
    }

    /** Keep the journal bounded: done entries past the Trash window and undone entries go. */
    fun prune(repo: ScanRepository) {
        val cutoff = System.currentTimeMillis() - TRASH_WINDOW_MS
        all().forEach { entry ->
            when (entry.state) {
                STATE_UNDONE, STATE_UNDO -> delete(entry.opId)
                STATE_DONE, STATE_COMMITTING -> if (entry.createdAt in 1 until cutoff) {
                    // Let the normal Trash purge reclaim the old entry once the
                    // journal is no longer needed to protect it.
                    entry.trashKey?.let { repo.replaceReleaseExpiryGuard(it) }
                    delete(entry.opId)
                }
            }
        }
    }

    private fun enc(value: String?): String = value.orEmpty()
        .replace("%", "%25").replace("=", "%3D").replace("\n", "%0A")

    private fun dec(value: String?): String? = value
        ?.replace("%0A", "\n")
        ?.replace("%3D", "=")
        ?.replace("%25", "%")

    companion object {
        const val STATE_INTENT = "INTENT"
        const val STATE_COMMITTING = "COMMITTING"
        const val STATE_DONE = "DONE"
        const val STATE_UNDO = "UNDO"
        const val STATE_UNDOING = "UNDOING"
        const val STATE_UNDONE = "UNDONE"
        const val TRASH_WINDOW_MS = 30L * 24 * 60 * 60 * 1000
        const val HEADER = "scanwhirl-replace-v1"

        /** Single-flight per source document: no two Replace ops can touch one id. */
        private val flights = ConcurrentHashMap<String, Mutex>()

        suspend fun <T> singleFlight(sourceId: String, action: suspend () -> T): T {
            val mutex = flights.getOrPut(sourceId) { Mutex() }
            return try {
                mutex.withLock { action() }
            } finally {
                flights.remove(sourceId, mutex)
            }
        }
    }
}
