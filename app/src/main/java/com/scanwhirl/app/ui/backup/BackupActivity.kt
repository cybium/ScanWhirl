package com.scanwhirl.app.ui.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.store.backup.FullBackup
import com.scanwhirl.app.store.vault.VaultAuthException
import com.scanwhirl.app.store.vault.VaultCrypto
import com.scanwhirl.app.store.vault.VaultStore
import com.scanwhirl.app.ui.BaseActivity
import com.scanwhirl.app.ui.applySystemBarsPadding
import com.scanwhirl.app.ui.kit.PasswordField
import com.scanwhirl.app.ui.kit.SwSheet
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.ui.kit.bindCredentialSubmit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Me -> Backup & restore: one encrypted file with every scan, the Vault, signatures and settings.
 *
 * Back up: choose the Vault recovery code or a backup password (10+ characters, the kit's strength
 * meter), then "Save to device" (SAF) or "Share" (the share sheet lists Google Drive when it is
 * installed; the app has no INTERNET permission and never uploads anything itself).
 * Restore: pick any file with SAF, the header says whether it needs a password or the recovery
 * code, and the merge never duplicates a document.
 */
class BackupActivity : BaseActivity() {

    companion object {
        /** Test seam: a Vault with cheap iterations, exactly like VaultActivity. */
        @androidx.annotation.VisibleForTesting
        var vaultOverride: VaultStore? = null

        fun intent(context: Context): Intent = Intent(context, BackupActivity::class.java)
    }

    private val backup by lazy { FullBackup(this, vault) }
    private val vault: VaultStore by lazy {
        vaultOverride ?: VaultStore(this, ScanWhirlApp.from(this).repository)
    }

    private var pendingRestore: Pair<File, String>? = null

    /** The Vault was locked: prompt for it, then run the restore that was waiting. */
    private val unlockVault = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val pending = pendingRestore ?: return@registerForActivityResult
        pendingRestore = null
        // Do not ask again: if the user declined, restore everything else and say the Vault was
        // skipped (the device QA caught a loop that reopened this prompt forever).
        runRestore(pending.first, pending.second, allowPrompt = false)
    }

    private lateinit var lastLine: TextView
    private lateinit var reminder: CheckBox

    /** The archive just written, waiting for "save" or "share". */
    private var pending: File? = null

    /**
     * What [FullBackup.write] had to leave out of [pending] (today: the Vault during a pending
     * setup). Kept next to the file because the "Backup ready" sheet returns after the share
     * sheet, and it must not lose the honesty note on the way back.
     */
    private var pendingWarnings: List<String> = emptyList()
    private var restoreSource: File? = null

    /** Set while the system share sheet is up, so the "Backup ready" sheet can return with it. */
    private var awaitingShareReturn = false

    private val saveTo = registerForActivityResult(ActivityResultContracts.CreateDocument(FullBackup.MIME)) { uri ->
        // B1: never discard the ready archive before we know the write succeeded. Cancelling the
        // save dialog used to null `pending` and delete the cached file, so the person lost an
        // 18 MB backup they had already encrypted. It stays until a confirmed success.
        val file = pending ?: return@registerForActivityResult
        if (uri == null) {
            // The person backed out of the save dialog: offer the same choices again.
            offerSaving(file)
            return@registerForActivityResult
        }
        // Read the destination's own name before writing: it is what the person sees in the
        // result, and it is never used as proof of anything.
        val destination = displayName(uri)
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { writeDurably(file, uri) }.getOrDefault(false)
            }
            if (ok) {
                pending = null
                pendingWarnings = emptyList()
                file.delete()
                backup.markBackedUp()
                refresh()
                // UI-W8: "Saved to <location>" only after the bytes were written, flushed and
                // synced, and the count matched the archive.
                toast(getString(R.string.backup_saved_to, destination ?: getString(R.string.backup_saved_name_unknown)))
            } else {
                toast(R.string.backup_failed)
                // A failed write is not a finished save. Keep the cached file and let the person
                // retry "Save to device" or share it, without rebuilding the whole archive.
                offerSaving(file)
            }
        }
    }

    private val pickBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val copied = File(cacheDir, "picked-backup-${System.nanoTime()}.swbackup")
            var handedOff = false
            try {
                val result = com.scanwhirl.app.util.BoundedCopy.copy(this@BackupActivity, uri, copied, FullBackup.MAX_ARCHIVE_BYTES)
                if (result == com.scanwhirl.app.util.BoundedCopy.Result.CANCELLED) return@launch
                if (result != com.scanwhirl.app.util.BoundedCopy.Result.OK) {
                    toast(when (result) {
                        com.scanwhirl.app.util.BoundedCopy.Result.TOO_LARGE -> R.string.s6c_backup_too_large
                        com.scanwhirl.app.util.BoundedCopy.Result.NO_SPACE -> R.string.s6c_backup_no_space
                        else -> R.string.s6c_backup_unreadable
                    })
                    return@launch
                }
                restoreSource?.let { com.scanwhirl.app.util.BoundedCopy.release(it) }
                restoreSource = copied
                askRestoreSecret(copied)
                handedOff = true
            } finally {
                if (!handedOff) com.scanwhirl.app.util.BoundedCopy.release(copied)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.scanwhirl.app.util.SecureView.apply(window)
        setContentView(buildScreen())
        refresh()
    }

    override fun onDestroy() {
        pending?.delete()
        restoreSource?.let { com.scanwhirl.app.util.BoundedCopy.release(it) }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the share sheet (shared or cancelled): the archive is still cached, so
        // offer "Save to device" again instead of making the user rebuild an 18 MB backup.
        if (!awaitingShareReturn) return
        awaitingShareReturn = false
        val file = pending ?: return
        if (file.isFile) {
            offerSaving(file)
            // UI-W8: sharing hands the file to another app; ScanWhirl has no way to know whether
            // that app wrote it, so the person hears the limit instead of a "saved" claim.
            toast(R.string.backup_share_unconfirmed)
        }
    }

    // ------------------------------------------------------------------

    private fun buildScreen(): ViewGroup {
        // UI-W8: an explicit up control, because this screen is FLAG_SECURE (no screenshot can
        // show a system gesture) and a person who cannot find Back must not be trapped here.
        // The bars are padded once on the outer column so both the header and the scrolling
        // content clear the clock and the navigation bar / gesture area.
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            applySystemBarsPadding()
        }
        shell.addView(
            backControl(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(8f)
                topMargin = dp(4f)
            }
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(18f), dp(20f), dp(24f))
        }
        root.addView(
            SwStyle.text(this, 24f, SwStyle.color(this, R.color.text_hologram_hi), bold = true).apply {
                text = getString(R.string.backup_title)
            }
        )
        root.addView(
            SwStyle.text(this, 13f, SwStyle.color(this, R.color.text_hologram_mid)).apply {
                text = getString(R.string.backup_explain)
                setLineSpacing(0f, 1.25f)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(6f) }
        )
        lastLine = SwStyle.text(this, 13f, SwStyle.color(this, R.color.cyan_primary), bold = true).apply {
            id = R.id.backupLastLine
        }
        root.addView(
            lastLine,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(12f) }
        )
        root.addView(
            primary(getString(R.string.backup_now), R.id.backupNow) { startBackup() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52f)).apply { topMargin = dp(16f) }
        )
        root.addView(
            outline(getString(R.string.backup_restore), R.id.backupRestore) { pickBackup.launch(arrayOf("*/*")) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52f)).apply { topMargin = dp(10f) }
        )
        reminder = CheckBox(this).apply {
            id = R.id.backupReminder
            text = getString(R.string.backup_remind)
            isChecked = prefs().getBoolean(FullBackup.PREF_REMIND, true)
            setTextColor(SwStyle.color(this@BackupActivity, R.color.text_hologram_hi))
            setOnCheckedChangeListener { _, on ->
                prefs().edit().putBoolean(FullBackup.PREF_REMIND, on).apply()
            }
        }
        root.addView(
            reminder,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(10f) }
        )
        root.addView(
            SwStyle.text(this, 12f, SwStyle.color(this, R.color.text_hologram_dim)).apply {
                text = getString(R.string.backup_no_internet)
                setLineSpacing(0f, 1.25f)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(14f) }
        )
        shell.addView(
            ScrollView(this).apply { addView(root) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        return shell
    }

    /** A 48dp text button named Back, first in the reading order of the screen. */
    private fun backControl(): TextView =
        SwStyle.text(this, 15f, SwStyle.color(this, R.color.text_hologram_hi), bold = true).apply {
            id = R.id.backupBack
            text = getString(R.string.backup_back)
            contentDescription = text
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            minHeight = dp(48f)
            minWidth = dp(48f)
            setPadding(dp(12f), 0, dp(12f), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { finish() }
        }

    private fun primary(text: String, id: Int, onClick: () -> Unit): TextView =
        SwStyle.text(this, 15f, SwStyle.color(this, R.color.sw_disc_ink), bold = true).apply {
            this.id = id
            this.text = text
            gravity = Gravity.CENTER
            background = SwStyle.rounded(
                this@BackupActivity, SwStyle.color(this@BackupActivity, R.color.cyan_primary), radiusDp = 16f
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun outline(text: String, id: Int, onClick: () -> Unit): TextView =
        SwStyle.text(this, 15f, SwStyle.color(this, R.color.text_hologram_hi), bold = true).apply {
            this.id = id
            this.text = text
            gravity = Gravity.CENTER
            background = SwStyle.rounded(
                this@BackupActivity,
                SwStyle.color(this@BackupActivity, R.color.sw_cyan_soft),
                radiusDp = 16f,
                stroke = SwStyle.color(this@BackupActivity, R.color.sw_cyan_hair_strong)
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun refresh() {
        val days = backup.daysSinceLastBackup()
        lastLine.text = when {
            days == null -> getString(R.string.backup_never)
            days == 0L -> getString(R.string.backup_today)
            days == 1L -> getString(R.string.backup_one_day)
            else -> getString(R.string.backup_days_ago, days)
        }
    }

    private fun prefs() = getSharedPreferences("scanwhirl_prefs", 0)

    // ------------------------------------------------------------------
    // Backup
    // ------------------------------------------------------------------

    private fun startBackup() {
        val canUseCode = runCatching { vault.isSetUp() }.getOrDefault(false)
        val sheet = SwSheet.builder(this)
            .setTitle(R.string.backup_choose)
            .setMessage(R.string.backup_choose_note)
            .build()
        if (canUseCode) {
            sheet.setPrimary(R.string.backup_use_code) { sheet.dismiss(); askRecoveryCode() }
            sheet.setSecondary(R.string.backup_use_password) { sheet.dismiss(); askPassword() }
            sheet.secure()
        } else {
            sheet.setPrimary(R.string.backup_use_password) { sheet.dismiss(); askPassword() }
            sheet.setSecondary(R.string.backup_cancel) { }
        }
        sheet.show()
    }

    private fun askRecoveryCode() {
        val sheet = SwSheet.builder(this)
            .setTitle(R.string.backup_code_title)
            .setMessage(R.string.backup_code_note)
            .setInput(hint = getString(R.string.backup_code_hint))
            .build()
        val input = checkNotNull(sheet.inputView)
        sheet.bindCredentialSubmit(
            this, input, R.string.backup_continue, dispatcher = Dispatchers.IO,
            read = {
                val code = VaultCrypto.normalizeRecoveryCode(input.text.toString())
                if (VaultCrypto.isRecoveryCode(code)) code else {
                    toast(R.string.backup_code_wrong)
                    null
                }
            },
            work = { code ->
                // Prove the code before starting the separate archive-encryption operation.
                val known = vault.unlockWithRecoveryForBackup(code) {
                    withContext(Dispatchers.Main) { sheet.isShowing && !isFinishing && !isDestroyed }
                }
                if (known) VaultCrypto.formatRecoveryCode(code) else null
            }
        ) { code ->
            if (code == null) {
                toast(R.string.backup_code_wrong)
                return@bindCredentialSubmit
            }
            sheet.dismiss()
            create(code, FullBackup.Wrap.RECOVERY_CODE)
        }
        sheet.setSecondary(R.string.backup_cancel) { }
        sheet.show()
    }

    private fun askPassword() {
        val field = PasswordField(this).apply {
            editText.hint = getString(R.string.backup_password_hint)
            editText.id = R.id.backupPassword
        }
        val sheet = SwSheet.builder(this)
            .setTitle(R.string.backup_password_title)
            .setMessage(getString(R.string.backup_password_note, FullBackup.MIN_PASSWORD))
            .setContent(field)
            .build()
        sheet.setPrimaryStayOpen(R.string.backup_continue) {
            val secret = field.text()
            if (secret.length < FullBackup.MIN_PASSWORD) {
                field.setError(getString(R.string.backup_password_short, FullBackup.MIN_PASSWORD))
                return@setPrimaryStayOpen
            }
            sheet.dismiss()
            create(secret, FullBackup.Wrap.PASSWORD)
        }
        sheet.setSecondary(R.string.backup_cancel) { }
        sheet.secure().show()
    }

    private fun create(secret: String, wrap: FullBackup.Wrap) {
        val file = backup.newArchiveFile()
        toast(R.string.backup_working)
        lifecycleScope.launch {
            // Keep the whole report: it says when part of the phone is not in the archive
            // (a Vault whose setup is unfinished), and the person must hear that here.
            val report = withContext(Dispatchers.IO) {
                runCatching { backup.write(file, secret, wrap) }
            }
            val written = report.getOrNull()
            if (written == null) {
                file.delete()
                toast(R.string.backup_failed)
                return@launch
            }
            pending = file
            pendingWarnings = written.warnings
            offerSaving(file)
        }
    }

    private fun offerSaving(file: File) {
        SwSheet.builder(this)
            .setTitle(R.string.backup_ready)
            .setMessage(readyMessage(file))
            .setPrimary(R.string.backup_save_device) { saveTo.launch(file.name) }
            .setSecondary(R.string.backup_share) { share(file) }
            .build().show()
    }

    /** The size note, then one line per warning the write reported. */
    private fun readyMessage(file: File): String {
        val sizeNote = getString(
            R.string.backup_ready_note,
            // F3 (hunt H6-5): a real size ("412 kB"), never a truncated "0 MB".
            android.text.format.Formatter.formatShortFileSize(this, file.length())
        )
        return (listOf(sizeNote) + pendingWarnings).joinToString("\n")
    }

    private fun share(file: File) {
        pending = file
        awaitingShareReturn = true
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = FullBackup.MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.backup_subject))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.backup_share_title)))
        // UI-W8: no markBackedUp() here. The share sheet can be cancelled, and ScanWhirl cannot
        // see whether the chosen app wrote anything; "last backup: today" would be a guess.
    }

    /**
     * UI-W8: writes [file] to the SAF [uri] and only then reports success.
     *
     * The bytes are flushed and, when the provider hands us a file descriptor stream, fsynced;
     * the written count must match the archive. A provider that accepts fewer bytes, throws, or
     * cannot be opened makes this false, so the screen says the backup could not be written
     * instead of "Saved to ...".
     */
    private fun writeDurably(file: File, uri: Uri): Boolean {
        val expected = file.length()
        if (expected <= 0L) return false
        val stream = contentResolver.openOutputStream(uri, "w") ?: return false
        var copied = 0L
        stream.use { out ->
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    copied += read
                }
            }
            out.flush()
            (out as? java.io.FileOutputStream)?.fd?.sync()
        }
        return copied == expected
    }

    /** The destination's own display name (allowed to be unknown): shown, never proof. */
    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    // ------------------------------------------------------------------
    // Restore
    // ------------------------------------------------------------------

    private fun askRestoreSecret(file: File) {
        var restoreRequested = false
        val header = runCatching { backup.header(file) }.getOrNull()
        if (header == null) {
            toast(R.string.backup_not_a_backup)
            com.scanwhirl.app.util.BoundedCopy.release(file)
            return
        }
        if (header.wrap == FullBackup.Wrap.RECOVERY_CODE) {
            val sheet = SwSheet.builder(this)
                .setTitle(R.string.backup_restore)
                .setMessage(R.string.u4_backup_restore_code_note)
                .setInput(hint = getString(R.string.backup_code_hint))
                .build()
            // B1: stay open on a bad format. A short code or one with the wrong alphabet is not
            // a wrong password: it cannot open any backup, so say so before any KDF work.
            sheet.setPrimaryStayOpen(R.string.backup_restore_go) {
                val code = VaultCrypto.normalizeRecoveryCode(sheet.inputView?.text?.toString().orEmpty())
                if (!VaultCrypto.isRecoveryCode(code)) {
                    toast(R.string.backup_code_wrong)
                    return@setPrimaryStayOpen
                }
                restoreRequested = true
                sheet.dismiss()
                runRestore(file, code)
            }
            sheet.setSecondary(R.string.backup_cancel) { com.scanwhirl.app.util.BoundedCopy.release(file) }
            sheet.setOnDismissListener {
                if (!restoreRequested) com.scanwhirl.app.util.BoundedCopy.release(file)
            }
            sheet.secure().show()
        } else {
            val field = PasswordField(this).apply {
                editText.hint = getString(R.string.backup_password_hint)
                editText.id = R.id.backupPassword
            }
            val sheet = SwSheet.builder(this)
                .setTitle(R.string.backup_restore)
                .setMessage(R.string.u4_backup_restore_password_note)
                .setContent(field)
                .build()
            sheet.setPrimaryStayOpen(R.string.backup_restore_go) {
                val secret = field.text()
                if (secret.length < FullBackup.MIN_PASSWORD) {
                    field.setError(getString(R.string.backup_password_short, FullBackup.MIN_PASSWORD))
                    return@setPrimaryStayOpen
                }
                restoreRequested = true
                sheet.dismiss()
                runRestore(file, secret)
            }
            sheet.setSecondary(R.string.backup_cancel) { com.scanwhirl.app.util.BoundedCopy.release(file) }
            sheet.setOnDismissListener {
                if (!restoreRequested) com.scanwhirl.app.util.BoundedCopy.release(file)
            }
            sheet.secure().show()
        }
    }

    private fun runRestore(file: File, secret: String, allowPrompt: Boolean = true) {
        // The backup carries Vault entries and this phone's Vault is set up: unlock it first, so
        // the entries can be re-encrypted under its key instead of being skipped.
        if (allowPrompt && runCatching { backup.needsVaultUnlock(file) }.getOrDefault(false)) {
            pendingRestore = file to secret
            unlockVault.launch(
                com.scanwhirl.app.ui.vault.VaultActivity.intent(this, getString(R.string.backup_vault_reason))
            )
            return
        }
        toast(R.string.backup_restoring)
        lifecycleScope.launch {
            val report = try {
                runCatching { kotlinx.coroutines.runInterruptible(Dispatchers.IO) { backup.restore(file, secret) } }
            } finally {
                com.scanwhirl.app.util.BoundedCopy.release(file)
            }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            report.onSuccess { r ->
                // A restore is not a backup: only a durable backup WRITE (saveTo above) may move
                // the "last backup" freshness line and postpone the reminder (F-04).
                refresh()
                val message = buildString {
                    append(buildParts(r))
                    r.warnings.forEach { append(" ").append(it) }
                }
                // B1: "Backup restored" is only true when nothing was skipped or warned about.
                val hadWarnings = r.warnings.isNotEmpty() || r.docsAlreadyHere > 0 ||
                    r.vaultAlreadyHere > 0 || r.docsFailed > 0 || r.vaultLocked
                SwSheet.builder(this@BackupActivity)
                    .setTitle(if (hadWarnings) R.string.b1_restore_warning_title else R.string.backup_restored)
                    .setMessage(message)
                    .setPrimary(android.R.string.ok) { }
                    .build().show()
                withContext(Dispatchers.IO) {
                    runCatching { ScanWhirlApp.from(this@BackupActivity).indexer.resumePending() }
                }
            }.onFailure { error ->
                val text = when (error) {
                    is com.scanwhirl.app.store.backup.BackupResourceException ->
                        getString(if (error.noSpace) R.string.s6c_backup_no_space else R.string.s6c_backup_limits)
                    is VaultAuthException -> getString(R.string.backup_wrong_secret)
                    else -> getString(R.string.backup_not_a_backup)
                }
                SwSheet.builder(this@BackupActivity)
                    .setTitle(R.string.backup_failed_title)
                    .setMessage(text)
                    .setPrimary(android.R.string.ok) { }
                    .build().show()
            }
        }
    }

    /** One sentence per outcome, with plurals: "No new documents. 32 were already here…". */
    private fun buildParts(report: FullBackup.Report): String {
        val res = resources
        val parts = mutableListOf<String>()
        // English and Hindi plural rules have no "zero" form, so 0 is worded explicitly.
        parts += if (report.documents == 0) res.getString(R.string.backup_restored_none)
        else res.getQuantityString(R.plurals.backup_restored_docs, report.documents, report.documents)
        if (report.docsAlreadyHere > 0) {
            parts += res.getQuantityString(
                R.plurals.backup_restored_skipped, report.docsAlreadyHere, report.docsAlreadyHere
            )
        }
        if (report.vaultAlreadyHere > 0) {
            parts += res.getQuantityString(
                R.plurals.b1_restore_vault_already_here, report.vaultAlreadyHere, report.vaultAlreadyHere
            )
        }
        if (report.docsFailed > 0) {
            parts += res.getQuantityString(R.plurals.b1_restore_docs_failed, report.docsFailed, report.docsFailed)
        }
        if (report.vaultEntries > 0) {
            parts += res.getQuantityString(R.plurals.backup_restored_vault, report.vaultEntries, report.vaultEntries)
        }
        if (report.vaultReencrypted > 0) {
            parts += res.getQuantityString(
                R.plurals.backup_restored_vault_reencrypted, report.vaultReencrypted, report.vaultReencrypted
            )
        }
        if (report.vaultLocked) parts += getString(R.string.backup_vault_locked)
        return parts.joinToString(" ")
    }

    // ------------------------------------------------------------------

    private fun dp(value: Float): Int = SwStyle.dp(this, value)
    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
