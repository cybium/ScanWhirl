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

/** Outcome of a council-style Undo swap, used for the honest receipt. */
data class ReplaceUndoOutcome(
    val restoredId: String,
    val outputTrashed: Boolean,
    val outputKeptChanged: Boolean,
    val outputTrashKey: String?
)

/**
 * Lane CR — orchestrates one Replace original operation.
 * Ships OFF via [ReplaceFeature]; all authorizations are result-bound and single-use.
 */
class ReplaceCoordinator(private val context: Context, private val repo: ScanRepository) {

    private val journal = ReplaceJournal(context)
    private val authorizations = ReplaceAuthorizationStore(context)

    /** Legacy callers/tests: issue a fresh authorization for this exact pair. */
    suspend fun replace(
        sourceId: String, outputId: String, operation: String, sourceName: String
    ): Result<String> {
        val sourceFp = repo.replaceDocIdentity(sourceId)
            ?: return Result.failure(IOException("The source is gone"))
        val outputFp = repo.replaceDocIdentity(outputId)
            ?: return Result.failure(IOException("The output is gone"))
        val id = "auth-$sourceId-$outputId-${System.nanoTime()}"
        authorizations.issue(id, sourceId, outputId, operation, sourceFp, outputFp)
        return replace(id, sourceId, outputId, operation, sourceName)
    }

    /** Authoritative path: the authorization was issued when the result was produced. */
    suspend fun replace(
        authId: String,
        sourceId: String,
        outputId: String,
        operation: String,
        sourceName: String
    ): Result<String> {
        if (!ReplaceFeature.isEnabled()) return Result.failure(IOException("Replace is disabled"))
        if (sourceId == outputId) return Result.failure(IOException("Not two documents"))
        return ReplaceJournal.singleFlight(sourceId) {
            runCatching {
                val auth = authorizations.load(authId)
                    ?: throw IOException("No replace authorization")
                if (auth.consumed || auth.sourceId != sourceId || auth.outputId != outputId ||
                    auth.toolId != operation
                ) {
                    throw IOException("Stale or replayed authorization")
                }
                // Revalidate and consume inside the repository's commit lock.
                if (!repo.replaceCommitGuard(
                        sourceId, outputId, auth.sourceFingerprint, auth.outputFingerprint, operation
                    ) { authorizations.consume(authId) }
                ) {
                    throw IOException("The result no longer matches its authorization")
                }
                val sourceIdentity = auth.sourceFingerprint
                val outputIdentity = auth.outputFingerprint
                val opId = "replace-$sourceId-${System.currentTimeMillis()}-$sourceIdentity"
                val now = System.currentTimeMillis()
                val entry = ReplaceJournal.Entry(
                    opId = opId, sourceId = sourceId, outputId = outputId,
                    operation = operation, sourceIdentity = sourceIdentity,
                    outputIdentity = outputIdentity, sourceName = sourceName,
                    state = ReplaceJournal.STATE_INTENT, trashKey = null,
                    createdAt = now, updatedAt = now, authId = authId
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

    /** Council Undo swap: restore the original and move the unchanged output to Trash. */
    suspend fun undo(opId: String): Result<ReplaceUndoOutcome> = runCatching {
        if (!ReplaceFeature.isEnabled()) throw IOException("Replace is disabled")
        val entry = journal.load(opId) ?: throw IOException("No such replace")
        val key = entry.trashKey ?: throw IOException("Nothing to undo")
        journal.write(entry.copy(state = ReplaceJournal.STATE_UNDOING,
            updatedAt = System.currentTimeMillis()))
        ReplaceFaults.at("before-restore")
        val restored = repo.replaceRestoreOriginal(key, entry.sourceIdentity)
        ReplaceFaults.at("after-restore")
        val outputNow = repo.replaceDocIdentity(entry.outputId)
        val changed = outputNow != entry.outputIdentity
        var outputKey: String? = null
        if (!changed) {
            ReplaceFaults.at("before-output-trash")
            outputKey = repo.replaceMoveOutputToTrash(entry.outputId, entry.outputIdentity, opId)
            ReplaceFaults.at("after-output-trash")
        }
        journal.write(entry.copy(
            state = ReplaceJournal.STATE_UNDONE, trashKey = null,
            undoOutputKey = outputKey, undoOutputChanged = changed,
            updatedAt = System.currentTimeMillis()
        ))
        ReplaceUndoOutcome(restored.id, outputKey != null, changed, outputKey)
    }

    suspend fun undoByTrashKey(key: String): Result<ReplaceUndoOutcome> {
        val entry = journal.findByTrashKey(key)
            ?: return Result.failure(IOException("No such replace"))
        return undo(entry.opId)
    }

    fun journalForTrashKey(key: String): ReplaceJournal.Entry? = journal.findByTrashKey(key)

    fun recoverBeforeExpiry(): Int = journal.recoverBeforeExpiry(repo)

    /** Used by the result screen to issue the durable, single-use authorization. */
    fun authorizeResult(
        id: String, sourceId: String, outputId: String, toolId: String
    ): ReplaceAuthorizationStore.Authorization? {
        val sourceFp = repo.replaceDocIdentity(sourceId) ?: return null
        val outputFp = repo.replaceDocIdentity(outputId) ?: return null
        return authorizations.issue(id, sourceId, outputId, toolId, sourceFp, outputFp)
    }
}
