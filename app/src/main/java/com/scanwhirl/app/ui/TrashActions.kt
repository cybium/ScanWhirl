package com.scanwhirl.app.ui

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.View
import android.view.accessibility.AccessibilityManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.store.ScanRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * M07 P1: the shared "Move to Trash" + Undo behaviour for Home (one card or a
 * selection) and the document screen.
 *
 * Moving to Trash is immediate and reversible, so it asks nothing first; the
 * snackbar names what moved and offers Undo for [UNDO_DURATION_MS].
 * Exports in Documents/ScanWhirl are never touched by a move. Legacy Vault items
 * are never moved (the caller uses permanent delete inside the authenticated Vault).
 */
object TrashActions {

    /**
     * T32 (phone build 3): how long the move's Undo stays up. Two taps ~3.5-4 s
     * after the move missed a 6 s bar, and Undo is the only in-app way back
     * without opening Trash, so the window is at least 8 s - a safety net the
     * device measurement supports. Longer when the system "time to take action"
     * accessibility timeout asks for more (see [undoDurationMs]).
     *
     * SavedSnackbar's export result (Open/Undo) is a different bar and keeps its
     * own ACTION_DURATION_MS = 6 s: it is not this code path.
     */
    const val UNDO_DURATION_MS = 8000

    /**
     * The real window: never below [UNDO_DURATION_MS], and never below the
     * platform's recommended timeout (Android 10+ "time to take action"), which
     * is what a user with a motor/vision accessibility setting expects.
     */
    internal fun undoDurationMs(recommendedTimeoutMs: Int?): Int =
        max(UNDO_DURATION_MS, recommendedTimeoutMs ?: 0)

    internal fun undoDurationMs(activity: Activity): Int = undoDurationMs(recommendedTimeout(activity))

    /** The system's recommended timeout, or null when unavailable (API < 29, no service). */
    private fun recommendedTimeout(activity: Activity): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val manager = activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return null
        return try {
            manager.getRecommendedTimeoutMillis(
                UNDO_DURATION_MS,
                AccessibilityManager.FLAG_CONTENT_CONTROLS or AccessibilityManager.FLAG_CONTENT_TEXT
            )
        } catch (_: Exception) {
            null
        }
    }

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
            .setDuration(undoDurationMs(recommendedTimeout(activity)))
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
