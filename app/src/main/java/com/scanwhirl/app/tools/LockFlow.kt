package com.scanwhirl.app.tools

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.store.vault.VaultStore
import com.scanwhirl.app.ui.DocumentViewerActivity
import com.scanwhirl.app.ui.ExportResultActivity
import com.scanwhirl.app.ui.SwDialog
import com.scanwhirl.app.ui.kit.PasswordField
import com.scanwhirl.app.ui.kit.SwSheet
import com.scanwhirl.app.ui.protect.ImportUnlockSheet
import com.scanwhirl.app.ui.protect.ProtectSheet
import com.scanwhirl.app.ui.protect.TryCooldown
import com.scanwhirl.app.ui.protect.UnlockSheet
import com.scanwhirl.app.ui.protect.VaultPasswordsFlow
import com.scanwhirl.app.ui.showSw
import com.scanwhirl.app.util.DownloadSaver
import com.scanwhirl.app.util.PdfDecryptor
import com.scanwhirl.app.util.PdfProtection
import com.scanwhirl.app.util.Sha256
import com.scanwhirl.app.util.ShareUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil

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
    private val pickPdf: ((Uri) -> Unit) -> Unit,
    /** Test seam: the real path decrypts and imports; Robolectric cannot run PdfRenderer. */
    private val importProtected: (suspend (Uri, String?, String) -> ScanDocument)? = null
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
        val holder = GrantHolder()
        UnlockSheet.show(
            activity = activity,
            documentName = doc.name,
            verify = { candidate ->
                repo.checkPdfPassword(doc.id, candidate).also { holder.value = it } != null
            },
            cooldown = cooldownFor(doc.id),
            vaultAvailable = VaultPasswordsFlow.has(activity, doc.id),
            onUnlocked = { if (!holder.consume(doc.id) { runUnlockScan(doc, it) }) lostGrant() },
            onRemoveProtection = { if (!holder.consume(doc.id) { runUnlockScan(doc, it) }) lostGrant() },
            onUseVault = { useVaultPassword(doc) { grant -> runUnlockScan(doc, grant) } }
        )
    }

    private fun runUnlockScan(doc: ScanDocument, grant: ScanRepository.PdfPasswordGrant) {
        activity.lifecycleScope.launch {
            val progress = host.showProgress()
            try {
                // The sheet already checked the password; the grant is applied once, without a
                // second 1 300 000-iteration derivation.
                withContext(Dispatchers.IO) {
                    repo.unlockPdf(doc.id, grant)
                    // The clean PDF no longer needs the remembered password.
                    VaultPasswordsFlow.forget(activity, doc.id)
                }
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
                    activity.getString(R.string.lock_op_failed),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** Mockup 05 with the "Change password" title: the current password is checked first. */
    private fun promptChangePassword(doc: ScanDocument) {
        val holder = GrantHolder()
        promptCurrentPassword(
            activity = activity,
            docName = doc.name,
            cooldown = cooldownFor(doc.id),
            verify = { current ->
                repo.checkPdfPassword(doc.id, current).also { holder.value = it } != null
            }
        ) { _ ->
            val checked = holder.take()
            if (checked == null) lostGrant() else promptNewPasswordForScan(doc, granted = checked)
        }
    }

    /** Mockup 05 for a document the host already knows (the reader's More → Protect PDF). */
    fun protectDocument(doc: ScanDocument) = promptNewPasswordForScan(doc)

    /** Mockup 09 for a protected library document (the Tools/Home "Unlock PDF" entry). */
    fun unlockDocument(doc: ScanDocument) = promptUnlockScan(doc)

    /** Mockup 09 for a PDF from storage (the "From a PDF file" row of the Unlock chooser). */
    fun unlockDevicePdfFile(uri: Uri, onRefusalDismiss: () -> Unit) =
        unlockDevicePdf(uri, onRefusalDismiss)

    /**
     * The import dead-end fix: a password-protected PDF opens the Unlock sheet, then its
     * decrypted pages become a new document. The source file is only read.
     */
    fun importProtectedPdf(uri: Uri, suggestedName: String?) {
        activity.lifecycleScope.launch {
            // If this is a ScanWhirl export of a library document, its remembered password can
            // fill the sheet (same hash index the device unlock uses).
            val match = withContext(Dispatchers.IO) {
                runCatching {
                    val hash = activity.contentResolver.openInputStream(uri)?.use { Sha256.of(it) }
                    if (hash == null) null else repo.findLockedDocumentByHash(hash)
                }.getOrNull()
            }
            val vaultAvailable = match != null && VaultPasswordsFlow.has(activity, match.id)
            val useVault: (((String?) -> Unit) -> Unit)? = if (vaultAvailable && match != null) {
                { done -> VaultPasswordsFlow.readForUnlock(activity, match.id) { pw -> done(pw) } }
            } else {
                null
            }
            ImportUnlockSheet.show(
                activity = activity,
                documentName = suggestedName?.takeIf { it.isNotBlank() } ?: "PDF",
                vaultAvailable = vaultAvailable,
                onUseVault = useVault
            ) { password, done ->
                // The decrypt is slower than a rotation: it must finish and publish the document
                // even when this Activity is gone. The app scope outlives the screen; every side
                // effect that needs a live window (progress, refresh, viewer) is gated below.
                val app = ScanWhirlApp.from(activity)
                app.appScope.launch {
                    fun hostAlive(): Boolean = !activity.isFinishing && !activity.isDestroyed &&
                        activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                    val progress = withContext(Dispatchers.Main) {
                        if (hostAlive()) host.showProgress() else null
                    }
                    fun dismissProgress() {
                        if (progress != null && hostAlive()) runCatching { progress!!.dismiss() }
                    }
                    try {
                        val doc = withContext(Dispatchers.IO) {
                            importProtected?.invoke(uri, suggestedName, password)
                                ?: repo.importProtectedPdfAsScan(uri, suggestedName, password)
                        }
                        // Indexing is host independent: a document created after rotation still has
                        // to be searchable and visible when the Library next loads.
                        app.indexer.enqueue(doc.id)
                        withContext(Dispatchers.Main) {
                            dismissProgress()
                            if (hostAlive()) {
                                host.refreshList()
                                activity.startActivity(DocumentViewerActivity.intent(activity, doc.id))
                            }
                            done(ImportUnlockSheet.Result.OK)
                        }
                    } catch (e: PdfDecryptor.WrongPassword) {
                        withContext(Dispatchers.Main) { dismissProgress(); done(ImportUnlockSheet.Result.WRONG) }
                    } catch (e: PdfDecryptor.UnsupportedScheme) {
                        withContext(Dispatchers.Main) { dismissProgress(); done(ImportUnlockSheet.Result.UNSUPPORTED) }
                    } catch (e: PdfDecryptor.Unsupported) {
                        withContext(Dispatchers.Main) { dismissProgress(); done(ImportUnlockSheet.Result.DAMAGED) }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { dismissProgress(); done(ImportUnlockSheet.Result.FAILED) }
                    }
                }
            }
        }
    }

    /**
     * Mockup 05. [granted] is the verified current password when changing it; null when protecting
     * for the first time. The sheet only calls back with a password that is long enough and typed
     * twice the same, so no validation happens here.
     */
    private fun promptNewPasswordForScan(doc: ScanDocument, granted: ScanRepository.PdfPasswordGrant? = null) {
        val changing = granted != null
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
            // The row exists only when a Vault is set up; it is ON by default.
            rememberAvailable = vaultIsSetUp()
        ) { password, remember, shareNow ->
            activity.lifecycleScope.launch {
                val progress = host.showProgress()
                try {
                    val updated = withContext(Dispatchers.IO) {
                        if (changing) repo.changePdfPassword(doc.id, granted!!, password)
                        else repo.protectPdf(doc.id, password)
                    }
                    progress.dismiss()
                    Toast.makeText(activity, R.string.protect_done, Toast.LENGTH_LONG).show()
                    host.refreshList()
                    // The protection (and the save/share the owner picked) completes first. The
                    // Vault step is silent and only affects remembering: a locked Vault never
                    // replaces this screen or loses the save action.
                    val remembered = withContext(Dispatchers.IO) {
                        if (remember) {
                            VaultPasswordsFlow.saveIfUnlocked(activity, doc.id, password)
                        } else {
                            VaultPasswordsFlow.forget(activity, doc.id)
                            true
                        }
                    }
                    // The protection/save always completes first. A locked Vault only asks
                    // afterwards; the owner can unlock then, or decline.
                    if (!changing) offerProtectedCopy(updated, shareNow)
                    if (remember && !remembered) offerVaultSave(doc.id, password)
                } catch (e: PdfProtection.WrongPassword) {
                    progress.dismiss()
                    Toast.makeText(activity, R.string.unlock_pdf_wrong, Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    progress.dismiss()
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.lock_op_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /** True when L2's Vault has been set up on this phone. */
    private fun vaultIsSetUp(): Boolean =
        runCatching { VaultStore(activity, repo).isSetUp() }.getOrDefault(false)

    /**
     * Fills the Unlock sheet from the Vault. A missing, stale or unreadable entry never closes the
     * sheet: the owner can still type the password. The stored password is checked off the main
     * thread (one derivation) and handed on as a grant.
     */
    private fun useVaultPassword(doc: ScanDocument, open: (ScanRepository.PdfPasswordGrant) -> Unit) {
        VaultPasswordsFlow.readForUnlock(activity, doc.id) { password ->
            if (password == null) {
                Toast.makeText(activity, R.string.vault_password_missing, Toast.LENGTH_LONG).show()
                return@readForUnlock
            }
            activity.lifecycleScope.launch {
                val grant = repo.checkPdfPassword(doc.id, password)
                if (grant == null) {
                    Toast.makeText(activity, R.string.vault_password_stale, Toast.LENGTH_LONG).show()
                } else {
                    open(grant)
                }
            }
        }
    }

    /**
     * The Vault was locked when the PDF was protected. Offer to save the password there without
     * touching the protection the owner already has; only a decline says "not saved".
     */
    private fun offerVaultSave(docId: String, password: String) {
        SwSheet.builder(activity)
            .setTitle(R.string.vault_save_prompt)
            .setMessage(R.string.vault_save_prompt_note)
            .setPrimary(R.string.vault_unlock_action) {
                VaultPasswordsFlow.rememberWithUnlock(activity, docId, password) { saved ->
                    if (!saved) {
                        Toast.makeText(activity, R.string.vault_password_not_saved, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setSecondary(R.string.not_now) {
                Toast.makeText(activity, R.string.vault_password_not_saved, Toast.LENGTH_LONG).show()
            }
            .build()
            .show()
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
                docId = doc.id,
                summary = activity.getString(R.string.protect_result_summary, doc.name)
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
                    doc = ProtectSheet.Doc(name = "", pages = 0, sizeBytes = 0L),
                    rememberAvailable = vaultIsSetUp()
                ) { password, remember, shareNow ->
                    runLockDevicePdf(uri, password, shareNow, remember)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showSw(isSheet = false)
    }

    private fun runLockDevicePdf(uri: Uri, password: String, share: Boolean, remember: Boolean) {
        val progress = host.showProgress()
        activity.lifecycleScope.launch {
            try {
                val before = withContext(Dispatchers.IO) {
                    activity.contentResolver.openFileDescriptor(uri, "r")
                        ?.use { it.statSize }?.takeIf { it > 0 } ?: 0L
                }
                val name = FlowUtil.devicePdfName(repo, uri)
                val doc = repo.importPdfAsScanPlanned(
                    uri = uri,
                    name = name,
                    onPlan = { plan ->
                        com.scanwhirl.app.ui.tools.DevicePdfGate.decide(activity, plan)
                    }
                )
                if (doc == null) {
                    progress.dismiss()
                    return@launch
                }
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
                val present = {
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
                                docId = locked.id, // T4.8: record the saved copy on this document
                                summary = activity.getString(R.string.protect_result_summary, locked.name)
                            )
                        )
                    }
                }
                present()
                if (remember) {
                    val saved = withContext(Dispatchers.IO) {
                        VaultPasswordsFlow.saveIfUnlocked(activity, locked.id, password)
                    }
                    if (!saved) offerVaultSave(locked.id, password)
                }
            } catch (e: Exception) {
                progress.dismiss()
                Toast.makeText(
                    activity,
                    activity.getString(R.string.lock_op_failed),
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
    private fun unlockDevicePdf(uri: Uri, onRefusalDismiss: () -> Unit = {}) {
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
                        .setOnDismissListener { onRefusalDismiss() }
                    return@launch
                }
                promptDeviceUnlock(doc, uri)
            } catch (e: Exception) {
                progress.dismiss()
                Toast.makeText(
                    activity,
                    activity.getString(R.string.lock_op_failed),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** Mockup 09 for a file in storage: a right password exports a clean copy next to it. */
    private fun promptDeviceUnlock(doc: ScanDocument, uri: Uri) {
        val holder = GrantHolder()
        UnlockSheet.show(
            activity = activity,
            documentName = doc.name,
            verify = { candidate ->
                repo.checkPdfPassword(doc.id, candidate).also { holder.value = it } != null
            },
            cooldown = cooldownFor(doc.id),
            vaultAvailable = VaultPasswordsFlow.has(activity, doc.id),
            onUnlocked = { if (!holder.consume(doc.id) { runUnlockDevicePdf(doc, uri, it) }) lostGrant() },
            onRemoveProtection = { if (!holder.consume(doc.id) { runUnlockDevicePdf(doc, uri, it) }) lostGrant() },
            onUseVault = { useVaultPassword(doc) { grant -> runUnlockDevicePdf(doc, uri, grant) } }
        )
    }

    /** Exports a clean (password-free) copy of the locked scan's pages. */
    private fun runUnlockDevicePdf(
        doc: ScanDocument,
        uri: Uri,
        grant: ScanRepository.PdfPasswordGrant
    ) {
        val progress = host.showProgress()
        activity.lifecycleScope.launch {
            try {
                // The sheet's grant is the authorization: no second derivation, and a password
                // change made in between is refused by the repository's compare-and-swap.
                val before = withContext(Dispatchers.IO) {
                    activity.contentResolver.openFileDescriptor(uri, "r")
                        ?.use { it.statSize }?.takeIf { it > 0 } ?: 0L
                }
                val clean = withContext(Dispatchers.IO) { repo.unlockedPreviewPdf(doc.id, grant) }
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
            } catch (e: PdfProtection.WrongPassword) {
                progress.dismiss()
                Toast.makeText(activity, R.string.unlock_pdf_wrong, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                progress.dismiss()
                Toast.makeText(
                    activity,
                    activity.getString(R.string.lock_op_failed),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Holds the authorization a sheet's off-main check produced and hands it out once: a dismissed
     * or duplicated callback can never act on a password that was verified earlier.
     */
    private class GrantHolder {
        @Volatile var value: ScanRepository.PdfPasswordGrant? = null

        fun take(): ScanRepository.PdfPasswordGrant? = value.also { value = null }

        fun consume(docId: String, action: (ScanRepository.PdfPasswordGrant) -> Unit): Boolean {
            val grant = take() ?: return false
            if (grant.docId != docId) return false
            action(grant)
            return true
        }
    }

    /**
     * The sheet reported success but its grant was already handed to another callback: fail closed,
     * never re-check implicitly.
     */
    private fun lostGrant() {
        Toast.makeText(activity, R.string.unlock_pdf_wrong, Toast.LENGTH_LONG).show()
    }

    companion object {
        /** Per-document, per-process pacing (an id is only reused within one app run). */
        private val cooldowns = HashMap<String, TryCooldown>()

        /**
         * Asks for the document's current password in an [SwSheet] with the kit's [PasswordField]
         * (mockup 09's field, strength meter off). [verify] is a suspending check run on
         * [Dispatchers.Default] with a progress state and one check at a time: the lock's PBKDF2
         * never runs on the main thread. A wrong password stays in the sheet and follows the Unlock
         * sheet's tries-left and pause pacing ([cooldown]); without [verify] the text is handed to
         * [onEntered] unchecked, as before.
         */
        fun promptCurrentPassword(
            activity: AppCompatActivity,
            docName: String,
            cooldown: TryCooldown = TryCooldown(),
            verify: (suspend (String) -> Boolean)? = null,
            onEntered: (String) -> Unit
        ) {
            val field = PasswordField(activity).apply {
                showStrength = false
                editText.id = R.id.unlockPassword
                editText.hint = activity.getString(R.string.protect_password_hint)
                editText.contentDescription = activity.getString(R.string.protect_label_password)
            }
            val sheet = SwSheet.builder(activity)
                .setTitle(R.string.unlock_current_pw_title)
                .setMessage(docName)
                .setContent(field)
                .setPrimary(R.string.continue_label)
                .build()

            val handler = Handler(Looper.getMainLooper())
            val idleLabel = activity.getString(R.string.continue_label)
            val checkingLabel = activity.getString(R.string.unlock_checking)
            var checking = false
            var check: Job? = null

            fun setChecking(value: Boolean) {
                checking = value
                sheet.primaryButton.isEnabled = !value && cooldown.canTry()
                sheet.primaryButton.text = if (value) checkingLabel else idleLabel
            }

            // Closed for any reason: the derivation is cancelled and its result is never applied.
            sheet.setOnDismissListener {
                handler.removeCallbacksAndMessages(null)
                check?.cancel()
                check = null
                checking = false
            }

            fun clearError() {
                field.setError(null)
            }

            fun tickPause() {
                if (!sheet.isShowing) return
                val left = cooldown.remainingPauseMs()
                if (left <= 0L && cooldown.canTry()) {
                    if (!checking) sheet.primaryButton.isEnabled = true
                    field.setError(null)
                    return
                }
                sheet.primaryButton.isEnabled = false
                field.setError(
                    activity.getString(R.string.unlock_wrong_paused, ceil(left / 1000.0).toInt())
                )
                handler.postDelayed({ tickPause() }, 1000L)
            }

            fun attempt() {
                val text = field.text()
                val verifier = verify
                if (verifier == null) {
                    handler.removeCallbacksAndMessages(null)
                    sheet.dismiss()
                    onEntered(text)
                    return
                }
                // One check at a time: a second tap while the KDF runs is ignored, not queued.
                if (checking) return
                if (!cooldown.canTry()) {
                    tickPause()
                    return
                }
                if (text.isEmpty()) {
                    field.setError(
                        activity.getString(
                            R.string.unlock_wrong_tries, cooldown.triesLeft, cooldown.nextPauseSeconds()
                        )
                    )
                    return
                }
                setChecking(true)
                check = activity.lifecycleScope.launch {
                    val ok = try {
                        withContext(Dispatchers.Default) { verifier(text) }
                    } catch (c: CancellationException) {
                        throw c
                    } catch (_: Exception) {
                        false
                    }
                    if (!sheet.isShowing) return@launch
                    setChecking(false)
                    if (ok) {
                        cooldown.reset()
                        handler.removeCallbacksAndMessages(null)
                        sheet.dismiss()
                        onEntered(text)
                        return@launch
                    }
                    val pause = cooldown.registerFailure()
                    if (pause > 0L) {
                        field.setError(
                            activity.getString(R.string.unlock_wrong_paused, pause / 1000)
                        )
                        tickPause()
                    } else {
                        field.setError(
                            activity.getString(
                                R.string.unlock_wrong_tries, cooldown.triesLeft, cooldown.nextPauseSeconds()
                            )
                        )
                    }
                }
            }

            field.editText.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) = clearError()
            })
            sheet.primaryButton.setOnClickListener { attempt() }
            field.editText.setOnEditorActionListener { _, action, _ ->
                if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                    attempt()
                    true
                } else {
                    false
                }
            }

            // A pause can still be running from an earlier sheet (the pacing survives dismissal).
            if (!cooldown.canTry()) tickPause()

            sheet.show()
            sheet.secure()
            field.editText.requestFocus()
        }
    }
}
