package com.scanwhirl.app.store

import android.content.Context
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.util.ReplaceFeature
import java.io.IOException

/** Test seam for process death at each journal boundary. */
object ReplaceFaults {
    @Volatile
    var hook: (String) -> Unit = {}

    fun at(step: String) = hook(step)

    internal fun reset() {
        hook = {}
    }
}

/**
 * Lane CR — orchestrates one Replace original operation.
 *
 * Nothing happens unless [ReplaceFeature.isEnabled] is true (ships OFF). The
 * journal is fsynced before the identity-gated move; every step after that is
 * recoverable. No path permanently deletes a document.
 */
class ReplaceCoordinator(private val context: Context, private val repo: ScanRepository) {

    private val journal = ReplaceJournal(context)

    suspend fun replace(
        sourceId: String,
        outputId: String,
        operation: String,
        sourceName: String
    ): Result<String> {
        if (!ReplaceFeature.isEnabled()) return Result.failure(IOException("Replace is disabled"))
        if (sourceId == outputId) return Result.failure(IOException("Not two documents"))
        return ReplaceJournal.singleFlight(sourceId) {
            runCatching {
                if (!repo.replaceIsEligible(sourceId, outputId, operation)) {
                    throw IOException("This result is not eligible for Replace")
                }
                val sourceIdentity = repo.replaceDocIdentity(sourceId)
                    ?: throw IOException("The source is gone")
                val outputIdentity = repo.replaceDocIdentity(outputId)
                    ?: throw IOException("The output is gone")
                val opId = "replace-$sourceId-${System.currentTimeMillis()}-" +
                    sourceIdentity.take(8)
                val now = System.currentTimeMillis()
                val entry = ReplaceJournal.Entry(
                    opId = opId, sourceId = sourceId, outputId = outputId,
                    operation = operation, sourceIdentity = sourceIdentity,
                    outputIdentity = outputIdentity, sourceName = sourceName,
                    state = ReplaceJournal.STATE_INTENT, trashKey = null,
                    createdAt = now, updatedAt = now
                )
                // WRITE-AHEAD: nothing destructive before this fsynced write returns.
                journal.write(entry)
                ReplaceFaults.at("before-move")
                val key = repo.replaceMoveSourceToTrash(sourceId, sourceIdentity, opId)
                ReplaceFaults.at("after-move")
                journal.write(entry.copy(
                    state = ReplaceJournal.STATE_COMMITTING, trashKey = key,
                    updatedAt = System.currentTimeMillis()
                ))
                ReplaceFaults.at("after-committing")
                journal.write(entry.copy(
                    state = ReplaceJournal.STATE_DONE, trashKey = key,
                    updatedAt = System.currentTimeMillis()
                ))
                ReplaceFaults.at("after-done")
                key
            }
        }
    }

    suspend fun undo(opId: String): Result<ScanDocument> = runCatching {
        if (!ReplaceFeature.isEnabled()) throw IOException("Replace is disabled")
        val entry = journal.load(opId) ?: throw IOException("No such replace")
        val key = entry.trashKey ?: throw IOException("Nothing to undo")
        val restored = repo.replaceRestoreOriginal(key, entry.sourceIdentity)
        journal.write(entry.copy(state = ReplaceJournal.STATE_UNDO,
            updatedAt = System.currentTimeMillis()))
        restored
    }

    suspend fun undoByTrashKey(key: String): Result<ScanDocument> {
        val entry = journal.findByTrashKey(key)
            ?: return Result.failure(IOException("No such replace"))
        return undo(entry.opId)
    }

    fun journalForTrashKey(key: String): ReplaceJournal.Entry? = journal.findByTrashKey(key)

    fun recoverBeforeExpiry(): Int = journal.recoverBeforeExpiry(repo)
}
