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
import com.scanwhirl.app.ui.protect.ProtectSheet
import com.scanwhirl.app.ui.protect.TryCooldown
import com.scanwhirl.app.ui.protect.UnlockSheet
import com.scanwhirl.app.ui.showSw
import com.scanwhirl.app.util.DownloadSaver
import com.scanwhirl.app.util.PdfProtection
import com.scanwhirl.app.util.Sha256
import com.scanwhirl.app.util.ShareUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lock / Unlock for saved scans AND device PDFs (v2: Protect PDF + Unlock sheet).
 *
 * The two mockup screens live in `ui/protect`: [ProtectSheet] (`05_protect_pdf`) and
 * [UnlockSheet] (`09_unlock_sheet`). Everything below is behaviour: which document, which
 * repository call, and what the user sees when it fails.
 *
 * @param docs the current document list (allDocs on the Activity).
 * @param pickPdf seam for the device-PDF picker: the launcher stays registered in the Activity
 *   (`pendingUriAction` + `pickPdf`); this stores the continuation and launches the picker.
 */
class LockFlow(
    private val activity: AppCompatActivity,
    private val repo: ScanRepository,
    private val host: HomeHost,
    private val docs: () -> List<ScanDocument>,
    private val pickPdf: ((Uri) -> Unit) -> Unit
) {

    /** Wrong-password pacing survives closing and reopening a sheet, per document, this process. */
    private fun cooldownFor(id: String): TryCooldown = cooldowns.getOrPut(id) { TryCooldown() }

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
                    if (which == 0) promptUnlockScan(doc) else promptChangePassword(doc)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .showSw(isSheet = true)
        } else {
            promptNewPasswordForScan(doc)
        }
    }

    /** Mockup 09: the tries-left sheet. "Open" unlocks the stored document in place. */
    private fun promptUnlockScan(doc: ScanDocument) {
        UnlockSheet.show(
            activity = activity,
            documentName = doc.name,
            verify = { candidate -> repo.verifyPdfPassword(doc.id, candidate) },
            cooldown = cooldownFor(doc.id),
            vaultAvailable = false, // L2's Vault password store is not merged yet
            onUnlocked = { password -> runUnlockScan(doc, password) },
            onRemoveProtection = { password -> runUnlockScan(doc, password) }
        )
    }

    private fun runUnlockScan(doc: ScanDocument, password: String) {
        activity.lifecycleScope.launch {
            val progress = host.showProgress()
            try {
                withContext(Dispatchers.IO) { repo.unlockPdf(doc.id, password) }
                progress.dismiss()
                Toast.makeText(activity, R.string.unlock_pdf_done, Toast.LENGTH_LONG).show()
                host.refreshList()
            } catch (e: PdfProtection.WrongPassword) {
                progress.dismiss()
                Toast.makeText(activity, R.string.unlock_pdf_wrong, Toast.LENGTH_LONG).show()
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

    /** Mockup 05 with the "Change password" title: the current password is checked first. */
    private fun promptChangePassword(doc: ScanDocument) {
        promptCurrentPassword(activity, doc.name) { current ->
            if (!repo.verifyPdfPassword(doc.id, current)) {
                Toast.makeText(activity, R.string.unlock_pdf_wrong, Toast.LENGTH_LONG).show()
            } else {
                promptNewPasswordForScan(doc, current = current)
            }
        }
    }

    /** Mockup 05 for a document the host already knows (the reader's More → Protect PDF). */
    fun protectDocument(doc: ScanDocument) = promptNewPasswordForScan(doc)

    /**
     * Mockup 05. [current] is the verified current password when changing it; null when protecting
     * for the first time. The sheet only calls back with a password that is long enough and typed
     * twice the same, so no validation happens here.
     */
    private fun promptNewPasswordForScan(doc: ScanDocument, current: String? = null) {
        val changing = current != null
        ProtectSheet.show(
            activity = activity,
            doc = ProtectSheet.Doc(
                name = doc.name,
                pages = doc.pageCount,
                sizeBytes = doc.pdfFile.length().coerceAtLeast(0L)
            ),
            titleRes = if (changing) R.string.protect_change_title else R.string.protect_sheet_title,
            primaryTextRes = if (changing) R.string.protect_action_change else R.string.protect_action_share,
            secondaryVisible = !changing,
            rememberAvailable = false // L2's Vault password store is not merged yet
        ) { password, _remember, shareNow ->
            activity.lifecycleScope.launch {
                val progress = host.showProgress()
                try {
                    val updated = withContext(Dispatchers.IO) {
                        if (changing) repo.changePdfPassword(doc.id, current!!, password)
                        else repo.protectPdf(doc.id, password)
                    }
                    progress.dismiss()
                    Toast.makeText(activity, R.string.protect_done, Toast.LENGTH_LONG).show()
                    host.refreshList()
                    if (!changing) offerProtectedCopy(updated, shareNow)
                } catch (e: PdfProtection.WrongPassword) {
                    progress.dismiss()
                    Toast.makeText(activity, R.string.unlock_pdf_wrong, Toast.LENGTH_LONG).show()
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

    /** "Protect & share" shares the protected PDF; "Save protected copy" opens the save screen. */
    private fun offerProtectedCopy(doc: ScanDocument, share: Boolean) {
        if (share) {
            ShareUtils.share(activity, doc)
            return
        }
        activity.startActivity(
            ExportResultActivity.intent(
                activity,
                outputPath = doc.pdfFile.path,
                previewPath = doc.pageFiles.firstOrNull()?.path,
                mime = "application/pdf",
                displayName = DownloadSaver.pdfNameFor(doc.name),
                beforeBytes = 0L,
                afterBytes = doc.pdfFile.length(),
                width = 0,
                height = 0,
                title = activity.getString(R.string.protect_sheet_title),
                warning = null,
                docId = doc.id
            )
        )
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
     * Locking a device PDF honestly means importing it (pages become images) and protecting the new
     * ScanWhirl PDF. The confirmation says so before anything is written.
     */
    private fun lockDevicePdf(uri: Uri) {
        SwDialog.card(activity)
            .setTitle(R.string.lock_device_title)
            .setMessage(R.string.lock_device_msg)
            .setPositiveButton(R.string.continue_label) { _, _ ->
                ProtectSheet.show(
                    activity = activity,
                    doc = ProtectSheet.Doc(name = "", pages = 0, sizeBytes = 0L)
                ) { password, _remember, shareNow ->
                    runLockDevicePdf(uri, password, shareNow)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showSw(isSheet = false)
    }

    private fun runLockDevicePdf(uri: Uri, password: String, share: Boolean) {
        val progress = host.showProgress()
        activity.lifecycleScope.launch {
            try {
                val before = withContext(Dispatchers.IO) {
                    activity.contentResolver.openFileDescriptor(uri, "r")
                        ?.use { it.statSize }?.takeIf { it > 0 } ?: 0L
                }
                val name = FlowUtil.devicePdfName(repo, uri)
                val doc = withContext(Dispatchers.IO) { repo.importPdfAsScan(uri, name) }
                val locked = try {
                    withContext(Dispatchers.IO) { repo.protectPdf(doc.id, password) }
                } catch (e: Exception) {
                    // PM-3.1b review C3: the user never keeps an unprotected copy they did not ask for.
                    runCatching { withContext(Dispatchers.IO) { repo.moveToTrash(doc.id) } }
                    host.refreshList()
                    throw e
                }
                progress.dismiss()
                host.refreshList()
                Toast.makeText(activity, R.string.protect_done, Toast.LENGTH_LONG).show()
                if (share) {
                    ShareUtils.share(activity, locked)
                } else {
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
                }
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
     * Unlock a PDF file from storage. ScanWhirl recognises its own locks by a SHA-256 index of the
     * protected file; any other PDF stays untouched with an honest explanation (we cannot break
     * other people's passwords).
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
                promptDeviceUnlock(doc, uri)
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

    /** Mockup 09 for a file in storage: a right password exports a clean copy next to it. */
    private fun promptDeviceUnlock(doc: ScanDocument, uri: Uri) {
        UnlockSheet.show(
            activity = activity,
            documentName = doc.name,
            verify = { candidate -> repo.verifyPdfPassword(doc.id, candidate) },
            cooldown = cooldownFor(doc.id),
            vaultAvailable = false, // L2
            onUnlocked = { password -> runUnlockDevicePdf(doc, uri, password) },
            onRemoveProtection = { password -> runUnlockDevicePdf(doc, uri, password) }
        )
    }

    /** Exports a clean (password-free) copy of the locked scan's pages. */
    private fun runUnlockDevicePdf(doc: ScanDocument, uri: Uri, password: String) {
        val progress = host.showProgress()
        activity.lifecycleScope.launch {
            try {
                if (!repo.verifyPdfPassword(doc.id, password)) {
                    progress.dismiss()
                    Toast.makeText(activity, R.string.unlock_pdf_wrong, Toast.LENGTH_LONG).show()
                    return@launch
                }
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

    companion object {
        /** Per-document, per-process pacing (an id is only reused within one app run). */
        private val cooldowns = HashMap<String, TryCooldown>()

        /** Asks for the document's current password; [onEntered] receives it unchecked. */
        fun promptCurrentPassword(activity: AppCompatActivity, docName: String, onEntered: (String) -> Unit) {
            val (builder, input) = SwDialog.input(
                activity,
                hint = activity.getString(R.string.protect_hint),
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            )
            builder
                .setTitle(R.string.unlock_current_pw_title)
                .setMessage(docName)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    onEntered(input.text?.toString().orEmpty())
                }
                .setNegativeButton(android.R.string.cancel, null)
                .showSw(isSheet = false)
        }
    }
}
