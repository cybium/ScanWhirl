package com.scanwhirl.app.tools

import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.scanwhirl.app.R
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.ExportResultActivity
import com.scanwhirl.app.ui.SwDialog
import com.scanwhirl.app.ui.showSw
import com.scanwhirl.app.util.DownloadSaver
import com.scanwhirl.app.util.Sha256
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * test26 T2.4 — Lock / Unlock for saved scans AND device PDFs, moved
 * verbatim out of `MainActivity`.
 *
 * @param docs the current document list (allDocs on the Activity).
 * @param pickPdf seam for the Pro device-PDF picker: the launcher stays
 *   registered in the Activity (`pendingUriAction` + `pickProPdf`); this
 *   stores the continuation and launches the picker.
 */
class LockFlow(
    private val activity: AppCompatActivity,
    private val repo: ScanRepository,
    private val host: HomeHost,
    private val docs: () -> List<ScanDocument>,
    private val pickPdf: ((Uri) -> Unit) -> Unit
) {

    private fun promptLockOrUnlockScan(doc: ScanDocument) {
        if (doc.isLocked) {
            SwDialog.sheet(activity)
                .setTitle(doc.name)
                .setItems(
                    arrayOf(
                        activity.getString(R.string.btn_unlock_pdf),
                        activity.getString(R.string.btn_change_pw)
                    )
                ) { _, which ->
                    if (which == 0) {
                        activity.lifecycleScope.launch {
                            val progress = host.showProgress()
                            try {
                                withContext(Dispatchers.IO) { repo.unlockPdf(doc.id) }
                                progress.dismiss()
                                Toast.makeText(activity, R.string.unlock_pdf_done, Toast.LENGTH_LONG).show()
                                host.refreshList()
                            } catch (e: Exception) {
                                progress.dismiss()
                                Toast.makeText(activity, activity.getString(R.string.page_op_failed, e.message), Toast.LENGTH_LONG).show()
                            }
                        }
                    } else {
                        promptNewPasswordForScan(doc)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .showSw(isSheet = true)
        } else {
            promptNewPasswordForScan(doc)
        }
    }

    private fun promptNewPasswordForScan(doc: ScanDocument) {
        val (builder, input) = SwDialog.input(
            activity,
            hint = activity.getString(R.string.protect_hint),
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        builder
            .setTitle(R.string.protect_title)
            .setMessage(R.string.protect_note)
            .setPositiveButton(R.string.protect_apply) { _, _ ->
                val pw = input.text?.toString().orEmpty()
                if (pw.isBlank()) {
                    Toast.makeText(activity, R.string.protect_hint, Toast.LENGTH_SHORT).show()
                } else {
                    activity.lifecycleScope.launch {
                        val progress = host.showProgress()
                        try {
                            withContext(Dispatchers.IO) { repo.protectPdf(doc.id, pw) }
                            progress.dismiss()
                            Toast.makeText(activity, R.string.protect_done, Toast.LENGTH_LONG).show()
                            host.refreshList()
                        } catch (e: Exception) {
                            progress.dismiss()
                            Toast.makeText(activity, activity.getString(R.string.page_op_failed, e.message), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showSw(isSheet = false)
    }

    fun chooseLockSource() {
        SwDialog.sheet(activity)
            .setTitle(R.string.btn_lock_unlock)
            .setItems(
                arrayOf(
                    activity.getString(R.string.source_saved_scan),
                    activity.getString(R.string.lock_source_lock_device),
                    activity.getString(R.string.lock_source_unlock_device)
                )
            ) { _, which ->
                when (which) {
                    0 -> FlowUtil.pickScan(activity, R.string.btn_lock_unlock, docs()) { doc ->
                        promptLockOrUnlockScan(doc)
                    }
                    1 -> {
                        pickPdf { uri -> lockDevicePdf(uri) }
                    }
                    else -> {
                        pickPdf { uri -> unlockDevicePdf(uri) }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showSw(isSheet = true)
    }

    /**
     * Locking a device PDF honestly means importing it (pages become
     * images) and protecting the new ScanWhirl PDF. The confirmation says
     * so before anything is written.
     */
    private fun lockDevicePdf(uri: Uri) {
        SwDialog.card(activity)
            .setTitle(R.string.lock_device_title)
            .setMessage(R.string.lock_device_msg)
            .setPositiveButton(R.string.continue_label) { _, _ ->
                val (builder, input) = SwDialog.input(
                    activity,
                    hint = activity.getString(R.string.protect_hint),
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                )
                builder
                    .setTitle(R.string.protect_title)
                    .setMessage(R.string.protect_note)
                    .setPositiveButton(R.string.protect_apply) { _, _ ->
                        runLockDevicePdf(uri, input.text?.toString().orEmpty())
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .showSw(isSheet = false)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showSw(isSheet = false)
    }

    private fun runLockDevicePdf(uri: Uri, password: String) {
        if (password.isBlank()) {
            Toast.makeText(activity, R.string.protect_hint, Toast.LENGTH_SHORT).show()
            return
        }
        val progress = host.showProgress()
        activity.lifecycleScope.launch {
            try {
                val before = withContext(Dispatchers.IO) {
                    activity.contentResolver.openFileDescriptor(uri, "r")
                        ?.use { it.statSize }?.takeIf { it > 0 } ?: 0L
                }
                val name = FlowUtil.devicePdfName(repo, uri)
                val doc = withContext(Dispatchers.IO) { repo.importPdfAsScan(uri, name) }
                val locked = withContext(Dispatchers.IO) { repo.protectPdf(doc.id, password) }
                progress.dismiss()
                host.refreshList()
                Toast.makeText(activity, R.string.protect_done, Toast.LENGTH_LONG).show()
                activity.startActivity(
                    ExportResultActivity.intent(
                        activity,
                        outputPath = locked.pdfFile.path,
                        previewPath = locked.pageFiles.firstOrNull()?.path,
                        mime = "application/pdf",
                        displayName = DownloadSaver.pdfNameFor(locked.name),
                        beforeBytes = before,
                        afterBytes = locked.pdfFile.length(),
                        width = 0,
                        height = 0,
                        title = activity.getString(R.string.lock_device_title),
                        warning = activity.getString(R.string.lock_device_note),
                        docId = locked.id // T4.8: record the saved copy on this document
                    )
                )
            } catch (e: Exception) {
                progress.dismiss()
                Toast.makeText(
                    activity,
                    activity.getString(R.string.page_op_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Unlock a PDF file from storage. ScanWhirl recognises its own locks by
     * a SHA-256 index of the protected file; any other PDF stays untouched
     * with an honest explanation (we cannot break other people's passwords).
     */
    private fun unlockDevicePdf(uri: Uri) {
        val progress = host.showProgress(R.string.unlock_pdf_title)
        activity.lifecycleScope.launch {
            try {
                val hash = withContext(Dispatchers.IO) {
                    activity.contentResolver.openInputStream(uri)?.use { Sha256.of(it) }
                        ?: throw IllegalStateException("could not read the PDF")
                }
                val doc = withContext(Dispatchers.IO) { repo.findLockedDocumentByHash(hash) }
                progress.dismiss()
                if (doc == null) {
                    SwDialog.card(activity)
                        .setTitle(R.string.unlock_pdf_not_ours_title)
                        .setMessage(R.string.unlock_pdf_not_ours_msg)
                        .setPositiveButton(android.R.string.ok, null)
                        .showSw(isSheet = false)
                    return@launch
                }
                promptDeviceUnlockPassword(doc, uri)
            } catch (e: Exception) {
                progress.dismiss()
                Toast.makeText(
                    activity,
                    activity.getString(R.string.page_op_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun promptDeviceUnlockPassword(doc: ScanDocument, uri: Uri) {
        val (builder, input) = SwDialog.input(
            activity,
            hint = activity.getString(R.string.protect_hint),
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        builder
            .setTitle(R.string.unlock_pdf_title)
            .setMessage(activity.getString(R.string.unlock_pdf_msg, doc.name))
            .setPositiveButton(R.string.unlock_pdf_open) { _, _ ->
                val pw = input.text?.toString().orEmpty()
                if (repo.verifyPdfPassword(doc.id, pw)) {
                    runUnlockDevicePdf(doc, uri)
                } else {
                    Toast.makeText(activity, R.string.unlock_pdf_wrong, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showSw(isSheet = false)
    }

    /** Exports a clean (password-free) copy of the locked scan's pages. */
    private fun runUnlockDevicePdf(doc: ScanDocument, uri: Uri) {
        val progress = host.showProgress()
        activity.lifecycleScope.launch {
            try {
                val before = withContext(Dispatchers.IO) {
                    activity.contentResolver.openFileDescriptor(uri, "r")
                        ?.use { it.statSize }?.takeIf { it > 0 } ?: 0L
                }
                val clean = withContext(Dispatchers.IO) { repo.unlockedPreviewPdf(doc.id) }
                progress.dismiss()
                activity.startActivity(
                    ExportResultActivity.intent(
                        activity,
                        outputPath = clean.path,
                        previewPath = doc.pageFiles.firstOrNull()?.path,
                        mime = "application/pdf",
                        displayName = DownloadSaver.pdfNameFor("${doc.name} (unlocked)"),
                        beforeBytes = before,
                        afterBytes = clean.length(),
                        width = 0,
                        height = 0,
                        title = activity.getString(R.string.unlock_pdf_title),
                        warning = activity.getString(R.string.unlock_device_note),
                        docId = doc.id // T4.8: record the saved copy on this document
                    )
                )
            } catch (e: Exception) {
                progress.dismiss()
                Toast.makeText(
                    activity,
                    activity.getString(R.string.page_op_failed, e.message),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
