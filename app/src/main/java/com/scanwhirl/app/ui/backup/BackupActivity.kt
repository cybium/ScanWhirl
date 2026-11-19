package com.scanwhirl.app.ui.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import com.scanwhirl.app.ui.kit.PasswordField
import com.scanwhirl.app.ui.kit.SwSheet
import com.scanwhirl.app.ui.kit.SwStyle
import kotlinx.coroutines.Dispatchers
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
        val file = pending
        pending = null
        pendingWarnings = emptyList()
        if (uri == null || file == null) return@registerForActivityResult
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { it.copyTo(out, 64 * 1024) }
                    } ?: error("no stream")
                }.isSuccess
            }
            file.delete()
            if (ok) {
                backup.markBackedUp()
                refresh()
                toast(R.string.backup_saved)
            } else {
                toast(R.string.backup_failed)
            }
        }
    }

    private val pickBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val copied = withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File(cacheDir, "picked-backup-${System.nanoTime()}.swbackup")
                    contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { input.copyTo(it, 64 * 1024) }
                    } ?: error("no stream")
                    tmp
                }.getOrNull()
            }
            if (copied == null) {
                toast(R.string.backup_failed)
                return@launch
            }
            restoreSource = copied
            askRestoreSecret(copied)
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
        restoreSource?.delete()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the share sheet (shared or cancelled): the archive is still cached, so
        // offer "Save to device" again instead of making the user rebuild an 18 MB backup.
        if (!awaitingShareReturn) return
        awaitingShareReturn = false
        val file = pending ?: return
        if (file.isFile) offerSaving(file)
    }

    // ------------------------------------------------------------------

    private fun buildScreen(): ViewGroup {
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
        return ScrollView(this).apply { addView(root) }
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
        sheet.setPrimaryStayOpen(R.string.backup_continue) {
            val code = VaultCrypto.normalizeRecoveryCode(sheet.inputView?.text?.toString().orEmpty())
            if (!VaultCrypto.isRecoveryCode(code)) {
                toast(R.string.backup_code_wrong)
                return@setPrimaryStayOpen
            }
            // Proving the code now guarantees the file can be opened later: a typo would
            // otherwise produce an undecryptable backup.
            val known = runCatching { vault.unlockWithRecovery(code) }.getOrDefault(false)
            if (!known) {
                toast(R.string.backup_code_wrong)
                return@setPrimaryStayOpen
            }
            sheet.dismiss()
            create(VaultCrypto.formatRecoveryCode(code), FullBackup.Wrap.RECOVERY_CODE)
        }
        sheet.setSecondary(R.string.backup_cancel) { }
        sheet.secure().show()
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
    private fun readyMessage(file: File): String =
        (listOf(getString(R.string.backup_ready_note, (file.length() / (1024 * 1024)).toInt())) + pendingWarnings)
            .joinToString("\n")

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
        backup.markBackedUp()
        refresh()
    }

    // ------------------------------------------------------------------
    // Restore
    // ------------------------------------------------------------------

    private fun askRestoreSecret(file: File) {
        val header = runCatching { backup.header(file) }.getOrNull()
        if (header == null) {
            toast(R.string.backup_not_a_backup)
            file.delete()
            return
        }
        if (header.wrap == FullBackup.Wrap.RECOVERY_CODE) {
            val sheet = SwSheet.builder(this)
                .setTitle(R.string.backup_restore)
                .setMessage(R.string.backup_code_note)
                .setInput(hint = getString(R.string.backup_code_hint))
                .build()
            sheet.setPrimary(R.string.backup_restore_go) {
                val code = VaultCrypto.normalizeRecoveryCode(sheet.inputView?.text?.toString().orEmpty())
                sheet.dismiss()
                runRestore(file, code)
            }
            sheet.setSecondary(R.string.backup_cancel) { file.delete() }
            sheet.secure().show()
        } else {
            val field = PasswordField(this).apply {
                editText.hint = getString(R.string.backup_password_hint)
                editText.id = R.id.backupPassword
            }
            val sheet = SwSheet.builder(this)
                .setTitle(R.string.backup_restore)
                .setMessage(getString(R.string.backup_password_note, FullBackup.MIN_PASSWORD))
                .setContent(field)
                .build()
            sheet.setPrimary(R.string.backup_restore_go) {
                sheet.dismiss()
                runRestore(file, field.text())
            }
            sheet.setSecondary(R.string.backup_cancel) { file.delete() }
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
            val report = withContext(Dispatchers.IO) { runCatching { backup.restore(file, secret) } }
            file.delete()
            report.onSuccess { r ->
                backup.markBackedUp()
                refresh()
                val message = buildString {
                    append(buildParts(r))
                    r.warnings.forEach { append(" ").append(it) }
                }
                SwSheet.builder(this@BackupActivity)
                    .setTitle(R.string.backup_restored)
                    .setMessage(message)
                    .setPrimary(android.R.string.ok) { }
                    .build().show()
                withContext(Dispatchers.IO) {
                    runCatching { ScanWhirlApp.from(this@BackupActivity).indexer.resumePending() }
                }
            }.onFailure { error ->
                val text = when (error) {
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
        if (report.skipped > 0) {
            parts += res.getQuantityString(R.plurals.backup_restored_skipped, report.skipped, report.skipped)
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
}
