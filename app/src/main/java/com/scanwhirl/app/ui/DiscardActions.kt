package com.scanwhirl.app.ui

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.store.ImportDiscardStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One Library host per activity. The notice is reconstructed from disk, never consumed in memory. */
class DiscardActions(
    private val activity: AppCompatActivity,
    private val root: View,
    private val anchor: View,
    private val onChanged: () -> Unit
) {
    private var bar: Snackbar? = null
    private var load: Job? = null
    private var viewExpiry: Job? = null
    private var key: String? = null

    fun showPending() {
        if (load?.isActive == true) return
        load = activity.lifecycleScope.launch {
            val store = ImportDiscardStore(activity)
            val token = withContext(Dispatchers.IO) { runCatching { store.pending().firstOrNull() }.getOrNull() }
                ?: return@launch
            if (activity.isFinishing || activity.isDestroyed || (key == token.key && bar?.isShown == true)) {
                return@launch
            }
            val remaining = withContext(Dispatchers.IO) { store.remainingMs(token) }
            if (remaining <= 0) return@launch
            bar?.dismiss()
            key = token.key
            val notice = Snackbar.make(root, R.string.u1_import_discarded, Snackbar.LENGTH_INDEFINITE)
                .setAnchorView(anchor)
                .setAction(R.string.undo) {
                    activity.lifecycleScope.launch {
                        val restored = withContext(Dispatchers.IO) {
                            runCatching { store.restore(token.key) }.getOrNull()
                        }
                        if (restored != null) {
                            TrashActions.queueIndexing(activity, ScanWhirlApp.from(activity).repository, listOf(restored))
                            onChanged()
                            key = null
                            bar = null
                            showPending()
                        } else {
                            Snackbar.make(root, R.string.u1_import_restore_failed, Snackbar.LENGTH_LONG)
                                .setAnchorView(anchor).show()
                        }
                    }
                }
            bar = notice
            notice.show()
            scheduleExpiry(activity, token)
            // Activity rotation cancels only its view timer, not the durable transaction.
            viewExpiry?.cancel()
            viewExpiry = activity.lifecycleScope.launch {
                delay(remaining)
                if (bar === notice) {
                    notice.dismiss()
                    bar = null
                    key = null
                    showPending()
                }
            }
        }
    }

    companion object {
        /** App-scope cleanup survives rotation; cold-start recovery handles process death. */
        fun scheduleExpiry(context: android.content.Context, token: ImportDiscardStore.Token) {
            val app = ScanWhirlApp.from(context)
            app.appScope.launch(Dispatchers.IO) {
                val store = ImportDiscardStore(app)
                // remainingMs is wall-aware after a reboot: a zero window means finish now,
                // not "wait forever". recover() purges expired escrows and keeps live ones.
                val remaining = store.remainingMs(token)
                if (remaining > 0) delay(remaining)
                runCatching { store.recover() }
            }
        }
    }
}
