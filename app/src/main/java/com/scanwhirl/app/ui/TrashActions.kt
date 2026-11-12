package com.scanwhirl.app.ui

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.SavedSnackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * M07 P1: the shared "Move to Trash" + Undo behaviour for Home (one card or a
 * selection) and the document screen.
 *
 * Moving to Trash is immediate and reversible, so it asks nothing first; the
 * snackbar names what moved and offers Undo for [SavedSnackbar.ACTION_DURATION_MS].
 * Exports in Documents/ScanWhirl are never touched by a move. Legacy Vault items
 * are never moved (the caller uses permanent delete inside the authenticated Vault).
 */
object TrashActions {

    /** What one move did. [keys] are the Trash entries Undo restores. */
    data class Moved(val keys: List<String>, val names: List<String>, val failed: Int)

    /**
     * A move made on a screen that is closing (the document screen): Home shows its
     * Undo snackbar on the next resume. In-memory only, like HomeReveal.
     */
    @Volatile private var pendingNotice: Moved? = null

    fun post(moved: Moved) {
        pendingNotice = moved
    }

    fun takePending(): Moved? = pendingNotice.also { pendingNotice = null }

    /** Moves [ids] to Trash (call off the main thread). Vault items are skipped and counted as failed. */
    fun moveAll(repo: ScanRepository, ids: List<String>): Moved {
        val keys = mutableListOf<String>()
        val names = mutableListOf<String>()
        var failed = 0
        for (id in ids) {
            val name = repo.getDocument(id)?.name
            try {
                val key = repo.moveToTrash(id)
                if (key != null) {
                    keys += key
                    names += name.orEmpty()
                }
            } catch (_: Exception) {
                failed++
            }
        }
        return Moved(keys, names, failed)
    }

    /** Restores every key (call off the main thread). Returns the restored documents' ids. */
    fun restoreAll(repo: ScanRepository, keys: List<String>): Pair<List<String>, Int> {
        val restored = mutableListOf<String>()
        var failed = 0
        for (key in keys) {
            try {
                restored += repo.restoreFromTrash(key).id
            } catch (_: Exception) {
                failed++
            }
        }
        return restored to failed
    }

    /** "Moved “Invoice” to Trash  [Undo]". [onChanged] refreshes the screen after Undo. */
    fun showMoved(
        activity: AppCompatActivity,
        root: View,
        anchor: View?,
        moved: Moved,
        onChanged: () -> Unit
    ) {
        val res = activity.resources
        val text: CharSequence = when {
            moved.keys.isEmpty() -> activity.getString(R.string.trash_move_failed)
            moved.keys.size == 1 && moved.failed == 0 ->
                activity.getString(R.string.trash_moved_one, moved.names.first())
            moved.failed == 0 ->
                res.getQuantityString(R.plurals.trash_moved_many, moved.keys.size, moved.keys.size)
            else -> res.getQuantityString(
                R.plurals.trash_moved_partial, moved.keys.size, moved.keys.size, moved.failed
            )
        }
        val bar = Snackbar.make(root, text, Snackbar.LENGTH_LONG)
            .setDuration(SavedSnackbar.ACTION_DURATION_MS)
        if (anchor != null && anchor.isShown) bar.setAnchorView(anchor)
        if (moved.keys.isNotEmpty()) {
            bar.setAction(R.string.undo) {
                val app = activity.applicationContext
                activity.lifecycleScope.launch {
                    val repo = ScanRepository(app)
                    val (restored, failed) = withContext(Dispatchers.IO) { restoreAll(repo, moved.keys) }
                    queueIndexing(activity, repo, restored)
                    onChanged()
                    val msg = if (failed == 0) {
                        res.getQuantityString(R.plurals.trash_restored, restored.size, restored.size)
                    } else {
                        activity.getString(R.string.trash_restore_failed)
                    }
                    Snackbar.make(root, msg, Snackbar.LENGTH_SHORT).also { s ->
                        if (anchor != null && anchor.isShown) s.setAnchorView(anchor)
                    }.show()
                }
            }
        }
        bar.show()
        root.announceForAccessibility(text)
    }

    /** A restored document that still needs text recognition goes back in the queue. */
    fun queueIndexing(activity: AppCompatActivity, repo: ScanRepository, ids: List<String>) {
        val indexer = (activity.application as? ScanWhirlApp)?.indexer ?: return
        for (id in ids) {
            if (repo.ocrStore.isPending(id)) indexer.enqueue(id)
        }
    }
}
