package com.scanwhirl.app.ui

import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.scanwhirl.app.R
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.tools.LockFlow
import com.scanwhirl.app.util.PdfProtection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * PM-3.1c (decision A5, invariant I3): a lock record without a protected PDF, or a protected PDF
 * without a lock record, is shown to the user and never exported silently. Repair needs the user's
 * password (the RC4 key cannot be derived from the stored hash), so nothing here is automatic.
 */
object ProtectionDialogs {

    /**
     * Runs [proceed] only when the document's protection is consistent. For `NEEDS_REPAIR` and
     * `UNKNOWN_PASSWORD` the repair dialog is shown and [proceed] is NOT run ([onResolved] runs after a
     * successful repair, so the caller can refresh; the export is not resumed). `UNREADABLE` proceeds,
     * as before: Share already reports a missing PDF with its own message.
     */
    fun guardExport(
        activity: AppCompatActivity,
        repo: ScanRepository,
        doc: ScanDocument,
        onResolved: () -> Unit = {},
        proceed: () -> Unit
    ) {
        when (repo.protectionState(doc.id)) {
            PdfProtection.State.NEEDS_REPAIR,
            PdfProtection.State.UNKNOWN_PASSWORD -> showFor(activity, repo, doc, onResolved)
            PdfProtection.State.NONE,
            PdfProtection.State.LOCKED,
            PdfProtection.State.UNREADABLE -> proceed()
        }
    }

    /** Shows the repair dialog that fits the document's state; does nothing for any other state. */
    fun showFor(activity: AppCompatActivity, repo: ScanRepository, doc: ScanDocument, onResolved: () -> Unit) {
        when (repo.protectionState(doc.id)) {
            PdfProtection.State.NEEDS_REPAIR -> showNeedsRepair(activity, repo, doc, onResolved)
            PdfProtection.State.UNKNOWN_PASSWORD -> showUnknownPassword(activity, repo, doc, onResolved)
            else -> Unit
        }
    }

    private fun showNeedsRepair(
        activity: AppCompatActivity,
        repo: ScanRepository,
        doc: ScanDocument,
        onResolved: () -> Unit
    ) {
        com.scanwhirl.app.ui.kit.SwSheet.builder(activity)
            .setTitle(R.string.protection_repair_title)
            .setMessage(activity.getString(R.string.protection_repair_msg, doc.name))
            .setPrimary(R.string.btn_protect_again) {
                LockFlow.promptCurrentPassword(activity, doc.name) { pw ->
                    launchRepair(activity, onResolved, toast = R.string.protection_repaired) {
                        repo.repairProtection(doc.id, pw)
                    }
                }
            }
            .setSecondary(R.string.btn_remove_lock) {
                LockFlow.promptCurrentPassword(activity, doc.name) { pw ->
                    launchRepair(activity, onResolved, toast = null) {
                        repo.removeProtectionLabel(doc.id, pw)
                    }
                }
            }
            .build()
            .show()
    }

    private fun showUnknownPassword(
        activity: AppCompatActivity,
        repo: ScanRepository,
        doc: ScanDocument,
        onResolved: () -> Unit
    ) {
        com.scanwhirl.app.ui.kit.SwSheet.builder(activity)
            .setTitle(R.string.protection_repair_title)
            .setMessage(R.string.protection_unknown_msg)
            .setPrimary(R.string.btn_rebuild_pdf) {
                launchRepair(activity, onResolved, toast = null) { repo.rebuildPlainPdf(doc.id) }
            }
            .setSecondary(R.string.protection_not_now)
            .build()
            .show()
    }

    private fun launchRepair(
        activity: AppCompatActivity,
        onResolved: () -> Unit,
        toast: Int?,
        work: suspend () -> Unit
    ) {
        activity.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { work() }
                if (toast != null) Toast.makeText(activity, toast, Toast.LENGTH_LONG).show()
                onResolved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: PdfProtection.WrongPassword) {
                Toast.makeText(activity, R.string.unlock_pdf_wrong, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(activity, activity.getString(R.string.page_op_failed, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }
}
