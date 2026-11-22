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
                val sourceIdentity = auth.sourceFingerprint
                val outputIdentity = auth.outputFingerprint
                val opId = "replace-${java.util.UUID.randomUUID()}"
                val now = System.currentTimeMillis()
                val entry = ReplaceJournal.Entry(
                    opId = opId, sourceId = sourceId, outputId = outputId,
                    operation = operation, sourceIdentity = sourceIdentity,
                    outputIdentity = outputIdentity, sourceName = sourceName,
                    state = ReplaceJournal.STATE_INTENT, trashKey = null,
                    createdAt = now, updatedAt = now, authId = authId
                )
                repo.replaceCommit(entry, journal) { authorizations.consume(authId) }
            }
        }
    }

    /** Council Undo swap: restore the original and move the unchanged output to Trash. */
    suspend fun undo(opId: String): Result<ReplaceUndoOutcome> = runCatching {
        if (!ReplaceFeature.isEnabled()) throw IOException("Replace is disabled")
        repo.replaceUndo(opId, journal)
    }

    suspend fun undoByTrashKey(key: String): Result<ReplaceUndoOutcome> {
        val entry = journal.findByTrashKey(key)
            ?: return Result.failure(IOException("No such replace"))
        return undo(entry.opId)
    }

    fun journalForTrashKey(key: String): ReplaceJournal.Entry? = journal.findByTrashKey(key)

    fun journalForAuthorization(id: String): ReplaceJournal.Entry? =
        journal.all().firstOrNull { it.authId == id }

    fun recoverBeforeExpiry(): Int = journal.recoverBeforeExpiry(repo)

}
