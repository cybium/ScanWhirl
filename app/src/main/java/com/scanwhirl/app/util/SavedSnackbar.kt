package com.scanwhirl.app.util

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.scanwhirl.app.R
import com.scanwhirl.app.store.ScanRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * T4.7: "Saved to Documents/ScanWhirl" confirmation with Open and, when
 * there is something to undo, Undo. Material's Snackbar supports a single
 * action button, so both buttons live in a custom layout added to the snackbar
 * view (the built-in message text is hidden).
 */
object SavedSnackbar {

    /** M06: how long an export result with Open/Undo stays up. */
    const val ACTION_DURATION_MS = 6000

    /** [onUndo] null hides Undo. */
    fun show(
        root: View,
        message: CharSequence,
        onOpen: () -> Unit,
        onUndo: (() -> Unit)?
    ): Snackbar {
        // M06: two-line outcome + actions need longer than LENGTH_LONG (~2.75 s) to read and act on.
        val snackbar = Snackbar.make(root, "", Snackbar.LENGTH_LONG).setDuration(ACTION_DURATION_MS)
        val layout = snackbar.view
        layout.findViewById<View>(com.google.android.material.R.id.snackbar_text)
            ?.visibility = View.GONE
        val custom = LayoutInflater.from(root.context)
            .inflate(R.layout.snackbar_saved, layout as ViewGroup, false)
        custom.findViewById<TextView>(R.id.snackMessage).text = message
        custom.findViewById<View>(R.id.snackOpen).setOnClickListener {
            onOpen()
            snackbar.dismiss()
        }
        val undo = custom.findViewById<View>(R.id.snackUndo)
        if (onUndo == null) {
            undo.visibility = View.GONE
        } else {
            undo.setOnClickListener {
                onUndo()
                snackbar.dismiss()
            }
        }
        layout.addView(custom)
        snackbar.show()
        return snackbar
    }

    /**
     * M06: the truthful confirmation for a Documents export.
     *  - New file: [createdMessage] plus Open/Undo. Undo removes only that new file.
     *  - Name already taken by a different file: "Saved as a new copy … earlier
     *    file kept", plus Open/Undo, where Undo again removes only the new copy.
     *  - Identical copy already there: "Already saved", Open only. Nothing was
     *    created, so there is nothing to undo, and an earlier export is never
     *    deleted.
     * [docId] (when the export belongs to a document) has its record updated on Undo.
     */
    fun showExport(
        activity: AppCompatActivity,
        root: View,
        saved: DownloadSaver.SavedFile,
        @StringRes createdMessage: Int,
        docId: String?
    ): Snackbar {
        val message = when {
            !saved.created -> activity.getString(R.string.export_already_saved, saved.name)
            saved.keptEarlier -> activity.getString(R.string.export_saved_new_copy, saved.name)
            else -> activity.getString(createdMessage, saved.name)
        }
        val app = activity.applicationContext
        val onUndo: (() -> Unit)? = if (!saved.created) null else {
            {
                activity.lifecycleScope.launch {
                    val result = withContext(NonCancellable + Dispatchers.IO) {
                        DownloadSaver.undoSave(app, saved).also { r ->
                            if (r == DownloadSaver.UndoResult.REMOVED && docId != null) {
                                try {
                                    ScanRepository(app).removeExport(docId, saved.uri)
                                } catch (_: Exception) {
                                    // A stale record is harmless: delete-with-document re-checks the file.
                                }
                            }
                        }
                    }
                    val text = when (result) {
                        DownloadSaver.UndoResult.REMOVED ->
                            activity.getString(R.string.export_undo_removed, saved.name)
                        DownloadSaver.UndoResult.KEPT_CHANGED ->
                            activity.getString(R.string.export_undo_kept_changed, saved.name)
                        else -> activity.getString(R.string.export_undo_failed, saved.name)
                    }
                    Toast.makeText(app, text, Toast.LENGTH_LONG).show()
                }
                Unit
            }
        }
        return show(
            root,
            message,
            onOpen = { PdfOpener.open(activity, saved.uri) },
            onUndo = onUndo
        )
    }
}
