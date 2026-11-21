package com.scanwhirl.app.store

import android.content.Context
import com.scanwhirl.app.util.Durable
import java.io.File

/**
 * L3-01 / UX-002 — durable journal for the "Keep the original?" decision.
 *
 * One journal per result operation, written atomically before any storage move:
 * the source id, output id, operation identity and the source fingerprint are
 * captured together with the choice and (once known) the Trash key. A process
 * death at any boundary is repaired by [reconcile] on the next open: it either
 * finishes a move that was journalled but not performed, finds a Trash entry
 * whose bytes still match, or retries a restore that never completed.
 *
 * "pending" is only the UI's preselection. It is never acted on: storage changes
 * happen only for an explicit "replace" / "keep_both" decision. This is what
 * keeps the app from silently replacing an original on an abandoned result.
 */
class ToolResultChoiceStore(private val context: Context) {

    data class Entry(
        val operationId: String,
        val sourceId: String,
        val outputId: String,
        val operation: String,
        val sourceFingerprint: String,
        /** "pending" | "replace" | "keep_both" */
        val decision: String,
        /** the preselected button, never a storage instruction */
        val preferred: String,
        val trashKey: String?,
        val updatedAt: Long
    )

    private fun fileFor(operationId: String): File {
        val safe = operationId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(80)
        require(safe.isNotEmpty()) { "An operation id is required" }
        return File(File(context.filesDir, "tool_result_choices").apply { mkdirs() }, "$safe.journal")
    }

    /** Atomic: a death during the write leaves either the old journal or the new one. */
    fun save(entry: Entry) {
        Durable.atomicWriteText(fileFor(entry.operationId), buildString {
            append(HEADER).append('\n')
            append("operationId=").append(entry.operationId).append('\n')
            append("sourceId=").append(entry.sourceId).append('\n')
            append("outputId=").append(entry.outputId).append('\n')
            append("operation=").append(entry.operation).append('\n')
            append("fingerprint=").append(entry.sourceFingerprint).append('\n')
            append("decision=").append(entry.decision).append('\n')
            append("preferred=").append(entry.preferred).append('\n')
            append("trashKey=").append(entry.trashKey ?: "-").append('\n')
            append("updatedAt=").append(entry.updatedAt).append('\n')
        })
    }

    fun load(operationId: String): Entry? {
        val file = fileFor(operationId)
        if (!file.isFile) return null
        val lines = runCatching { file.readLines() }.getOrNull() ?: return null
        if (lines.firstOrNull() != HEADER) return null
        val v = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val at = line.indexOf('=')
            if (at > 0) v[line.substring(0, at)] = line.substring(at + 1)
        }
        val id = v["operationId"]?.takeIf { it.isNotBlank() } ?: return null
        val source = v["sourceId"]?.takeIf { it.isNotBlank() } ?: return null
        val output = v["outputId"]?.takeIf { it.isNotBlank() } ?: return null
        val fp = v["fingerprint"]?.takeIf { it.length == 64 } ?: return null
        return Entry(
            operationId = id,
            sourceId = source,
            outputId = output,
            operation = v["operation"].orEmpty(),
            sourceFingerprint = fp,
            decision = v["decision"] ?: "pending",
            preferred = v["preferred"] ?: "keep_both",
            trashKey = v["trashKey"]?.takeIf { it != "-" && it.isNotBlank() },
            updatedAt = v["updatedAt"]?.toLongOrNull() ?: 0L
        )
    }

    /**
     * Repairs a decision interrupted at any journal boundary. Idempotent: a
     * second call after success is a no-op. Failures that cannot be proven are
     * left for the next open and never reported as success.
     */
    fun reconcile(repo: ScanRepository, operationId: String): Entry? {
        var entry = load(operationId) ?: return null
        if (entry.decision == "replace") {
            if (entry.trashKey != null) {
                // A recorded key whose entry no longer matches means the original
                // is back or was changed elsewhere. Do not claim it is in Trash.
                if (repo.findTrashedToolSource(entry.sourceId, entry.sourceFingerprint) == null) {
                    entry = entry.copy(trashKey = null)
                    save(entry)
                }
            } else if (repo.getDocument(entry.sourceId) != null) {
                // Death after the journal write, before the move.
                try {
                    val key = repo.moveToolSourceToTrash(entry.sourceId, entry.sourceFingerprint)
                    entry = entry.copy(trashKey = key)
                    save(entry)
                } catch (_: Exception) {
                    // Retry later; the original is still in the library.
                }
            } else {
                // Death after the rename, before the key was journalled.
                val key = repo.findTrashedToolSource(entry.sourceId, entry.sourceFingerprint)
                if (key != null) {
                    entry = entry.copy(trashKey = key)
                    save(entry)
                }
            }
        } else if (entry.decision == "keep_both") {
            val sourceBack = repo.getDocument(entry.sourceId) != null &&
                repo.toolSourceFingerprint(entry.sourceId) == entry.sourceFingerprint
            if (sourceBack) {
                // Death after the restore rename: the original is already back,
                // byte-identical. Only the journalled key is stale now.
                if (entry.trashKey != null) {
                    entry = entry.copy(trashKey = null)
                    save(entry)
                }
            } else {
                val key = entry.trashKey ?: repo.findTrashedToolSource(entry.sourceId, entry.sourceFingerprint)
                if (key != null) {
                    try {
                        repo.restoreToolSource(key, entry.sourceFingerprint)
                        entry = entry.copy(trashKey = null)
                        save(entry)
                    } catch (_: Exception) {
                        // It stays in Trash, unchanged, for a later retry.
                    }
                }
            }
        }
        return entry
    }

    private companion object {
        const val HEADER = "scanwhirl-tool-choice-v1"
    }
}
