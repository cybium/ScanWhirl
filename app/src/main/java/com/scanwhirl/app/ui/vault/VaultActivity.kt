package com.scanwhirl.app.ui.vault

import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.store.vault.VaultCrypto
import com.scanwhirl.app.store.vault.VaultLockedException
import com.scanwhirl.app.store.vault.VaultSession
import com.scanwhirl.app.store.vault.VaultStore
import com.scanwhirl.app.ui.BaseActivity
import com.scanwhirl.app.ui.PdfViewerActivity
import com.scanwhirl.app.ui.applySystemBarsPadding
import com.scanwhirl.app.ui.kit.PasswordField
import com.scanwhirl.app.ui.kit.SwSheet
import com.scanwhirl.app.ui.kit.SwStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v2 UI: 04_vault — the encrypted Vault tab (design §4.1).
 *
 * Setup: passcode ×2 → the recovery code is shown once → the user types its first 4
 * characters back. Unlock: the phone's own lock (fingerprint / face / PIN through the
 * system keyguard confirmation, which also authorizes the Keystore key wrap) or the Vault
 * passcode, with the five-try 30 s pause doubling. Files come from the library, are
 * encrypted into `filesDir/vault2`, verified, then the plain library copy is purged.
 * Backup and restore use the `.swvault` container and the recovery code alone.
 *
 * The framework `android.hardware.biometrics.BiometricPrompt` has no public constructor in
 * the Android SDK (it is system API); the app-facing prompt lives in the AndroidX biometric
 * library, which this lane may not add. `KeyguardManager`'s device-credential confirmation
 * is the documented no-library way to authorize an auth-bound Keystore key, and shows the
 * phone's own fingerprint/face/PIN prompt. DEVICE-UNPROVEN on real hardware.
 */
class VaultActivity : BaseActivity() {

    companion object {
        /** Test seam: a VaultStore built with the fake Keystore and a small PBKDF2 count. */
        @Volatile
        internal var storeOverride: VaultStore? = null

        /** Test seam: the system keyguard confirmation (Robolectric has no keyguard service). */
        @Volatile
        internal var credentialIntentFactory: (() -> Intent?)? = null

        fun intent(context: Context): Intent = Intent(context, VaultActivity::class.java)
    }

    private enum class UnlockMethod { PASSCODE, PHONE_LOCK, RECOVERY }

    private lateinit var repo: ScanRepository
    private lateinit var store: VaultStore

    private lateinit var statsTitle: TextView
    private lateinit var statsBody: TextView
    private lateinit var banner: TextView
    private lateinit var filesHeader: LinearLayout
    private lateinit var addLink: TextView
    private lateinit var list: LinearLayout
    private lateinit var empty: LinearLayout
    private lateinit var setupButton: TextView
    private lateinit var unlockButton: TextView
    private lateinit var lockButton: android.widget.ImageButton
    private lateinit var recoveryButton: TextView
    private lateinit var backupButton: TextView

    private var promptShown = false
    private var shownUnlocked = false
    private var unlockMethod = UnlockMethod.PASSCODE

    private val createBackup = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> if (uri != null) writeBackup(uri) }

    private val openBackup = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) readBackup(uri) }

    /** The phone's own lock (fingerprint / face / PIN) confirmed: unlock with the Keystore wrap. */
    private val confirmDeviceLock = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        if (store.unlockWithBiometric()) {
            unlockMethod = UnlockMethod.PHONE_LOCK
            promptShown = true
            render()
        } else {
            showError(getString(R.string.vault_phone_lock_failed))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ScanWhirlApp.from(this).repository
        store = storeOverride ?: VaultStore(this, repo)
        runCatching { store.recover() }

        val root = buildScreen()
        setContentView(root)
        root.applySystemBarsPadding()
        render()
        maybePromptOnOpen()
    }

    override fun onStart() {
        super.onStart()
        // VaultSession drops the key itself once the 2-minute background grace is over.
        if (shownUnlocked && !store.isUnlocked()) {
            store.lock() // also wipes the decrypted cache copies
            unlockMethod = UnlockMethod.PASSCODE
            promptShown = false
            render()
            maybePromptOnOpen()
        } else if (store.isUnlocked()) {
            VaultSession.onForeground()
        }
    }

    override fun onStop() {
        if (!isChangingConfigurations) VaultSession.onBackground()
        super.onStop()
    }

    // ------------------------------------------------------------------
    // Screen
    // ------------------------------------------------------------------

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = SwStyle.gradient(
                this@VaultActivity,
                0x1200F0FF,
                SwStyle.color(this@VaultActivity, R.color.rv_bg),
                radiusDp = 0f
            )
        }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val h = SwStyle.dp(this@VaultActivity, 18f)
            setPadding(h, 0, h, SwStyle.dp(this@VaultActivity, 40f))
        }
        scroll.addView(
            content,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        content.addView(appBar())
        content.addView(emblem())
        content.addView(statsCard())
        content.addView(actionRow())
        content.addView(bannerRow())
        content.addView(primaryRow())
        content.addView(filesHeaderRow())
        content.addView(emptyCard())
        list = LinearLayout(this).apply {
            id = R.id.vaultList
            orientation = LinearLayout.VERTICAL
        }
        content.addView(list)
        return root
    }

    private fun appBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, SwStyle.dp(this@VaultActivity, 14f), 0, SwStyle.dp(this@VaultActivity, 4f))
        }
        bar.addView(
            SwStyle.text(this, 24f, SwStyle.color(this, R.color.text_hologram_hi), bold = true).apply {
                text = getString(R.string.vault_screen_title)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        lockButton = SwStyle.iconButton(
            this, R.drawable.ic_sym_lock, SwStyle.color(this, R.color.text_hologram_mid)
        ).apply {
            id = R.id.vaultLock
            contentDescription = getString(R.string.vault_locked_desc)
            setOnClickListener {
                if (store.isUnlocked()) {
                    store.lock()
                    promptShown = false
                    render()
                    maybePromptOnOpen()
                } else {
                    maybePromptOnOpen()
                }
            }
        }
        bar.addView(lockButton)
        bar.addView(
            SwStyle.iconButton(
                this, R.drawable.ic_sym_more_vert, SwStyle.color(this, R.color.text_hologram_mid)
            ).apply {
                id = R.id.vaultMore
                contentDescription = getString(R.string.vault_more_desc)
                setOnClickListener { showMore() }
            }
        )
        return bar
    }

    private fun emblem(): View {
        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, SwStyle.dp(this@VaultActivity, 148f)
            ).apply { topMargin = SwStyle.dp(this@VaultActivity, 14f) }
        }
        fun orbit(sizeDp: Float, alpha: Int) {
            frame.addView(
                View(this).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(android.graphics.Color.TRANSPARENT)
                        setStroke(
                            SwStyle.dp(this@VaultActivity, 1f).coerceAtLeast(1),
                            SwStyle.color(this@VaultActivity, R.color.cyan_primary).let { c ->
                                android.graphics.Color.argb(alpha, (c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
                            }
                        )
                    }
                },
                FrameLayout.LayoutParams(
                    SwStyle.dp(this@VaultActivity, sizeDp),
                    SwStyle.dp(this@VaultActivity, sizeDp)
                ).apply { gravity = Gravity.CENTER }
            )
        }
        orbit(130f, 0x33)
        orbit(190f, 0x22)
        orbit(260f, 0x14)

        val shield = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0x4000F0FF, 0x0A00F0FF)
            ).apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = SwStyle.dp(this@VaultActivity, 24f).toFloat()
                setStroke(
                    SwStyle.dp(this@VaultActivity, 1f).coerceAtLeast(1),
                    SwStyle.color(this@VaultActivity, R.color.sw_cyan_hair_strong)
                )
            }
        }
        shield.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_sym_shield_lock)
                imageTintList = ColorStateList.valueOf(SwStyle.color(this@VaultActivity, R.color.cyan_primary))
            },
            FrameLayout.LayoutParams(
                SwStyle.dp(this@VaultActivity, 34f), SwStyle.dp(this@VaultActivity, 34f)
            ).apply { gravity = Gravity.CENTER }
        )
        frame.addView(
            shield,
            FrameLayout.LayoutParams(
                SwStyle.dp(this@VaultActivity, 60f), SwStyle.dp(this@VaultActivity, 60f)
            ).apply { gravity = Gravity.CENTER }
        )
        return frame
    }

    private fun statsCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = SwStyle.card(this@VaultActivity, 26f)
            val pad = SwStyle.dp(this@VaultActivity, 16f)
            setPadding(pad, pad, pad, pad)
        }
        statsTitle = SwStyle.text(this, 22f, SwStyle.color(this, R.color.text_hologram_hi), bold = true).apply {
            id = R.id.vaultStatsTitle
        }
        card.addView(statsTitle)
        statsBody = SwStyle.text(this, 12.5f, SwStyle.color(this, R.color.text_hologram_mid), bold = true).apply {
            id = R.id.vaultStatsBody
            setLineSpacing(0f, 1.3f)
        }
        card.addView(
            statsBody,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = SwStyle.dp(this@VaultActivity, 4f) }
        )
        return card
    }

    private fun actionRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = SwStyle.dp(this@VaultActivity, 10f) }
        }
        recoveryButton = outlineButton(R.string.vault_recovery_button, R.drawable.ic_sym_key).apply {
            id = R.id.vaultRecoveryButton
            setOnClickListener { showRecoveryInfo() }
        }
        backupButton = outlineButton(R.string.vault_backup_button, R.drawable.ic_sym_archive).apply {
            id = R.id.vaultBackupButton
            setOnClickListener { startBackup() }
        }
        row.addView(recoveryButton, LinearLayout.LayoutParams(0, SwStyle.dp(this, 46f), 1f))
        row.addView(
            backupButton,
            LinearLayout.LayoutParams(0, SwStyle.dp(this, 46f), 1f).apply {
                marginStart = SwStyle.dp(this@VaultActivity, 10f)
            }
        )
        return row
    }

    private fun bannerRow(): View {
        banner = SwStyle.text(this, 13f, SwStyle.color(this, R.color.ok_green), bold = true).apply {
            id = R.id.vaultBanner
            background = SwStyle.rounded(
                this@VaultActivity,
                SwStyle.color(this@VaultActivity, R.color.ok_green_soft),
                radiusDp = 14f,
                stroke = SwStyle.color(this@VaultActivity, R.color.ok_green_hair)
            )
            setPadding(
                SwStyle.dp(this@VaultActivity, 14f), SwStyle.dp(this@VaultActivity, 10f),
                SwStyle.dp(this@VaultActivity, 14f), SwStyle.dp(this@VaultActivity, 10f)
            )
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_sym_fingerprint, 0, 0, 0)
            compoundDrawablePadding = SwStyle.dp(this@VaultActivity, 8f)
            compoundDrawableTintList = ColorStateList.valueOf(
                SwStyle.color(this@VaultActivity, R.color.ok_green)
            )
            isVisible = false
        }
        banner.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = SwStyle.dp(this@VaultActivity, 12f) }
        return banner
    }

    private fun primaryRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = SwStyle.dp(this@VaultActivity, 12f) }
        }
        setupButton = primaryButton(R.string.vault_setup_button).apply {
            id = R.id.vaultSetupButton
            setOnClickListener { showSetup(migrateLegacy = false) }
        }
        unlockButton = primaryButton(R.string.vault_unlock_button).apply {
            id = R.id.vaultUnlockButton
            setOnClickListener { showUnlock() }
        }
        row.addView(setupButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, SwStyle.dp(this, 54f)))
        row.addView(
            unlockButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, SwStyle.dp(this@VaultActivity, 54f))
        )
        return row
    }

    private fun filesHeaderRow(): View {
        filesHeader = LinearLayout(this).apply {
            id = R.id.vaultFilesHeader
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = SwStyle.dp(this@VaultActivity, 18f) }
        }
        filesHeader.addView(
            SwStyle.text(this, 16f, SwStyle.color(this, R.color.text_hologram_hi), bold = true).apply {
                text = getString(R.string.vault_private_files)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        addLink = SwStyle.text(this, 14f, SwStyle.color(this, R.color.cyan_primary), bold = true).apply {
            id = R.id.vaultAddFiles
            text = getString(R.string.vault_add_files)
            isClickable = true
            isFocusable = true
            setPadding(
                SwStyle.dp(this@VaultActivity, 8f), SwStyle.dp(this@VaultActivity, 6f),
                SwStyle.dp(this@VaultActivity, 2f), SwStyle.dp(this@VaultActivity, 6f)
            )
            setOnClickListener { showAddFiles() }
        }
        filesHeader.addView(addLink)
        return filesHeader
    }

    private fun emptyCard(): View {
        empty = LinearLayout(this).apply {
            id = R.id.vaultEmpty
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = SwStyle.card(this@VaultActivity, 18f)
            val pad = SwStyle.dp(this@VaultActivity, 22f)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = SwStyle.dp(this@VaultActivity, 10f) }
        }
        empty.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_sym_encrypted)
                imageTintList = ColorStateList.valueOf(SwStyle.color(this@VaultActivity, R.color.text_hologram_dim))
            },
            LinearLayout.LayoutParams(SwStyle.dp(this, 44f), SwStyle.dp(this, 44f))
        )
        empty.addView(
            SwStyle.text(this, 16f, SwStyle.color(this, R.color.text_hologram_hi), bold = true).apply {
                text = getString(R.string.vault_no_files_title)
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = SwStyle.dp(this@VaultActivity, 10f) }
        )
        empty.addView(
            SwStyle.text(this, 13f, SwStyle.color(this, R.color.text_hologram_mid)).apply {
                text = getString(R.string.vault_empty_body)
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.3f)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = SwStyle.dp(this@VaultActivity, 5f) }
        )
        return empty
    }

    private fun outlineButton(textRes: Int, icon: Int): TextView =
        SwStyle.text(this, 14f, SwStyle.color(this, R.color.text_hologram_hi), bold = true).apply {
            text = getString(textRes)
            gravity = Gravity.CENTER
            background = SwStyle.rounded(
                this@VaultActivity,
                SwStyle.color(this@VaultActivity, R.color.sw_cyan_soft),
                radiusDp = 14f,
                stroke = SwStyle.color(this@VaultActivity, R.color.sw_cyan_hair_strong)
            )
            setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
            compoundDrawablePadding = SwStyle.dp(this@VaultActivity, 7f)
            compoundDrawableTintList = ColorStateList.valueOf(
                SwStyle.color(this@VaultActivity, R.color.cyan_primary)
            )
            isClickable = true
            isFocusable = true
        }

    private fun primaryButton(textRes: Int): TextView =
        SwStyle.text(this, 15f, SwStyle.color(this, R.color.sw_disc_ink), bold = true).apply {
            text = getString(textRes)
            gravity = Gravity.CENTER
            background = SwStyle.rounded(
                this@VaultActivity, SwStyle.color(this@VaultActivity, R.color.cyan_primary), radiusDp = 16f
            )
            isClickable = true
            isFocusable = true
        }

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------

    private fun render() {
        val setUp = store.isSetUp()
        val unlocked = store.isUnlocked()
        shownUnlocked = unlocked
        val entries = if (unlocked) runCatching { store.listEntries() }.getOrDefault(emptyList()) else emptyList()

        statsTitle.text = when {
            !setUp -> getString(R.string.vault_not_setup_title)
            !unlocked -> getString(R.string.vault_locked_title)
            else -> {
                val files = entries.sumOf { it.fileCount }
                resources.getQuantityString(R.plurals.vault_files_protected, files, files)
            }
        }
        statsBody.text = when {
            !setUp -> getString(R.string.vault_not_setup_body)
            !unlocked -> getString(R.string.vault_locked_body)
            else -> getString(R.string.vault_stats_body)
        }
        banner.isVisible = setUp && unlocked
        banner.text = when (unlockMethod) {
            UnlockMethod.PHONE_LOCK -> getString(R.string.vault_banner_phone_lock)
            UnlockMethod.RECOVERY -> getString(R.string.vault_banner_recovery)
            UnlockMethod.PASSCODE -> getString(R.string.vault_banner_passcode)
        }
        setupButton.isVisible = !setUp
        unlockButton.isVisible = setUp && !unlocked
        filesHeader.isVisible = setUp
        addLink.isVisible = unlocked
        list.isVisible = unlocked && entries.isNotEmpty()
        empty.isVisible = unlocked && entries.isEmpty()
        recoveryButton.isEnabled = setUp
        backupButton.isEnabled = setUp
        recoveryButton.alpha = if (setUp) 1f else 0.5f
        backupButton.alpha = if (setUp) 1f else 0.5f
        paintEntries(entries)
    }

    private fun paintEntries(entries: List<VaultStore.Entry>) {
        list.removeAllViews()
        entries.forEach { entry ->
            val card = VaultCard(this)
            val date = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(entry.addedAt))
            val meta = if (entry.pageCount > 0) {
                getString(
                    R.string.vault_entry_meta,
                    entry.pageCount,
                    Formatter.formatShortFileSize(this, entry.sizeBytes),
                    date
                )
            } else {
                getString(
                    R.string.vault_entry_meta_files,
                    entry.pageCount,
                    entry.fileCount,
                    date
                )
            }
            card.bind(entry, meta, onOpen = { openEntry(entry) }, onMore = { showEntryActions(entry) })
            list.addView(
                card,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = SwStyle.dp(this@VaultActivity, 10f) }
            )
        }
    }

    private fun maybePromptOnOpen() {
        if (promptShown) return
        promptShown = true
        when {
            !store.isSetUp() && store.hasLegacyPin() -> showLegacyPin()
            !store.isSetUp() -> showSetup(migrateLegacy = false)
            !store.isUnlocked() -> showUnlock()
        }
    }

    // ------------------------------------------------------------------
    // Setup and recovery
    // ------------------------------------------------------------------

    private fun showSetup(migrateLegacy: Boolean) {
        if (store.isSetUp()) return
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pass = passcodeField(R.string.vault_passcode_hint, R.id.vaultPasscodeInput)
        val confirm = passcodeField(R.string.vault_passcode_confirm_hint, R.id.vaultPasscodeConfirm)
        content.addView(pass)
        content.addView(
            confirm,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = SwStyle.dp(this@VaultActivity, 10f) }
        )
        val sheet = SwSheet.builder(this)
            .setTitle(R.string.vault_setup_sheet_title)
            .setMessage(R.string.vault_setup_note)
            .setContent(content)
            .setSecondary(R.string.vault_cancel)
            .build()
        sheet.setPrimary(R.string.vault_setup_primary)
        sheet.keepOpenOnPrimary()
        sheet.primaryButton.setOnClickListener {
            val code = pass.text()
            val again = confirm.text()
            pass.setError(null)
            confirm.setError(null)
            when {
                code.length < VaultStore.MIN_PASSCODE_LENGTH || !code.all { it.isDigit() } ->
                    pass.setError(getString(R.string.vault_passcode_short))
                code != again -> confirm.setError(getString(R.string.vault_passcode_mismatch))
                else -> {
                    val setup = try {
                        store.setup(code)
                    } catch (e: Exception) {
                        pass.setError(e.message ?: getString(R.string.vault_unlock_wrong))
                        return@setOnClickListener
                    }
                    sheet.dismiss()
                    unlockMethod = UnlockMethod.PASSCODE
                    render()
                    showRecoveryCode(setup.recoveryCode, migrateLegacy)
                }
            }
        }
        sheet.show()
        pass.editText.requestFocus()
    }

    private fun showRecoveryCode(code: String, migrateLegacy: Boolean) {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val codeView = SwStyle.text(
            this, 20f, SwStyle.color(this, R.color.cyan_primary), bold = true, letterSpacing = 0.06f
        ).apply {
            id = R.id.vaultRecoveryCode
            text = VaultCrypto.formatRecoveryCode(code)
            gravity = Gravity.CENTER
            background = SwStyle.rounded(
                this@VaultActivity,
                SwStyle.color(this@VaultActivity, R.color.void_nebula),
                radiusDp = 14f,
                stroke = SwStyle.color(this@VaultActivity, R.color.sw_field_border)
            )
            setPadding(
                SwStyle.dp(this@VaultActivity, 10f), SwStyle.dp(this@VaultActivity, 14f),
                SwStyle.dp(this@VaultActivity, 10f), SwStyle.dp(this@VaultActivity, 14f)
            )
            typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.BOLD)
        }
        content.addView(codeView)
        val warning = SwStyle.text(this, 12f, SwStyle.color(this, R.color.laser_danger), bold = true).apply {
            text = getString(R.string.vault_recovery_copy_warning)
            isVisible = false
        }
        content.addView(
            warning,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = SwStyle.dp(this@VaultActivity, 8f) }
        )
        val copy = SwStyle.text(this, 14f, SwStyle.color(this, R.color.cyan_primary), bold = true).apply {
            id = R.id.vaultRecoveryCopy
            text = getString(R.string.vault_recovery_copy)
            gravity = Gravity.CENTER
            isClickable = true
            minimumHeight = SwStyle.dp(this@VaultActivity, 44f)
            setOnClickListener {
                copyRecoveryCode(code)
                warning.isVisible = true
            }
        }
        content.addView(copy)
        content.addView(
            SwStyle.text(this, 13f, SwStyle.color(this, R.color.text_hologram_mid)).apply {
                text = getString(R.string.vault_recovery_once)
                setLineSpacing(0f, 1.3f)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = SwStyle.dp(this@VaultActivity, 10f) }
        )

        val sheet = SwSheet.builder(this)
            .setTitle(R.string.vault_recovery_sheet_title)
            .setMessage(R.string.vault_recovery_note)
            .setContent(content)
            .build()
        sheet.setPrimary(R.string.vault_recovery_saved) {
            showConfirmRecovery(code, migrateLegacy)
        }
        sheet.show()
    }

    private fun showConfirmRecovery(code: String, migrateLegacy: Boolean) {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val field = codeField(R.string.vault_confirm_hint, R.id.vaultRecoveryInput, maxLength = 4)
        content.addView(field)
        val sheet = SwSheet.builder(this)
            .setTitle(R.string.vault_confirm_title)
            .setMessage(R.string.vault_confirm_note)
            .setContent(content)
            .build()
        sheet.setPrimary(R.string.vault_finish)
        sheet.keepOpenOnPrimary()
        sheet.primaryButton.setOnClickListener {
            val typed = VaultCrypto.normalizeRecoveryCode(field.text())
            if (typed != VaultCrypto.normalizeRecoveryCode(code).take(4)) {
                field.setError(getString(R.string.vault_confirm_wrong))
                return@setOnClickListener
            }
            field.setError(null)
            sheet.dismiss()
            if (migrateLegacy) migrateLegacyAsync() else render()
        }
        sheet.show()
        field.editText.requestFocus()
    }

    private fun showLegacyPin(migrateOnly: Boolean = false) {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pin = passcodeField(R.string.vault_legacy_pin_hint, R.id.vaultLegacyPin, maxLength = 4)
        content.addView(pin)
        val sheet = SwSheet.builder(this)
            .setTitle(R.string.vault_legacy_title)
            .setMessage(R.string.vault_legacy_note)
            .setContent(content)
            .build()
        if (!migrateOnly) {
            sheet.setSecondary(R.string.vault_legacy_skip) { showSetup(migrateLegacy = false) }
        } else {
            sheet.setSecondary(R.string.vault_cancel)
        }
        sheet.setPrimary(R.string.vault_legacy_continue)
        sheet.keepOpenOnPrimary()
        sheet.primaryButton.setOnClickListener {
            val value = pin.text().trim()
            if (!store.verifyLegacyPin(value)) {
                pin.setError(getString(R.string.vault_legacy_wrong))
                return@setOnClickListener
            }
            pin.setError(null)
            sheet.dismiss()
            if (migrateOnly) migrateLegacyAsync() else showSetup(migrateLegacy = true)
        }
        sheet.show()
        pin.editText.requestFocus()
    }

    private fun migrateLegacyAsync() {
        lifecycleScope.launch {
            try {
                val moved = withContext(Dispatchers.IO) { store.migrateLegacyVault() }
                render()
                if (moved > 0) Toast.makeText(
                    this@VaultActivity,
                    getString(R.string.vault_legacy_migrated, moved),
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                render()
                showError(getString(R.string.vault_add_failed, e.message ?: ""))
            }
        }
    }

    // ------------------------------------------------------------------
    // Unlock
    // ------------------------------------------------------------------

    private fun showUnlock() {
        if (!store.isSetUp() || store.isUnlocked()) return
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pass = passcodeField(R.string.vault_passcode_hint_generic, R.id.vaultUnlockPasscode)
        pass.errorView.id = R.id.vaultUnlockError
        content.addView(pass)
        val recoveryLink = SwStyle.text(this, 14f, SwStyle.color(this, R.color.cyan_primary), bold = true).apply {
            id = R.id.vaultUseRecovery
            text = getString(R.string.vault_use_recovery)
            gravity = Gravity.CENTER
            isClickable = true
            minimumHeight = SwStyle.dp(this@VaultActivity, 44f)
        }
        content.addView(recoveryLink)

        val sheet = SwSheet.builder(this)
            .setTitle(R.string.vault_unlock_sheet_title)
            .setMessage(R.string.vault_unlock_note)
            .setContent(content)
            .build()
        recoveryLink.setOnClickListener {
            sheet.dismiss()
            showRecoveryUnlock()
        }
        if (phoneLockAvailable()) {
            sheet.setSecondary(R.string.vault_use_phone_lock) { authenticateWithPhoneLock() }
        }
        sheet.setPrimary(R.string.vault_unlock_primary)
        sheet.keepOpenOnPrimary()
        sheet.primaryButton.setOnClickListener {
            val result = store.unlockWithPasscode(pass.text())
            if (result.ok) {
                pass.setError(null)
                unlockMethod = UnlockMethod.PASSCODE
                sheet.dismiss()
                render()
            } else if (result.pauseMillis > 0) {
                pass.setError(getString(R.string.vault_unlock_pause, (result.pauseMillis + 999) / 1000))
            } else {
                pass.setError(getString(R.string.vault_unlock_wrong))
            }
        }
        sheet.show()
        pass.editText.requestFocus()
    }

    private fun showRecoveryUnlock() {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val field = codeField(R.string.vault_recovery_hint, R.id.vaultRestoreCode, maxLength = 40)
        content.addView(field)
        val sheet = SwSheet.builder(this)
            .setTitle(R.string.vault_recovery_unlock_title)
            .setMessage(R.string.vault_recovery_unlock_note)
            .setContent(content)
            .build()
        sheet.setPrimary(R.string.vault_unlock_primary)
        sheet.keepOpenOnPrimary()
        sheet.primaryButton.setOnClickListener {
            if (store.unlockWithRecovery(field.text())) {
                field.setError(null)
                unlockMethod = UnlockMethod.RECOVERY
                sheet.dismiss()
                render()
            } else {
                field.setError(getString(R.string.vault_recovery_wrong))
            }
        }
        sheet.show()
        field.editText.requestFocus()
    }

    /**
     * The phone-lock path needs a Keystore key wrap on this phone and a secure device lock
     * (fingerprint, face or PIN). Without either, the passcode always works.
     */
    private fun phoneLockAvailable(): Boolean {
        if (!store.hasBiometricKey()) return false
        val keyguard = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager ?: return false
        return keyguard.isDeviceSecure
    }

    /**
     * Shows the system keyguard confirmation. Finishing it authenticates the user to the
     * device, which authorizes the auth-bound Keystore key; [confirmDeviceLock] then unwraps
     * the vault key. No new library and no new permission are needed for this.
     */
    private fun authenticateWithPhoneLock() {
        val keyguard = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
        val intent = credentialIntentFactory?.invoke() ?: keyguard?.createConfirmDeviceCredentialIntent(
            getString(R.string.vault_phone_lock_prompt_title),
            getString(R.string.vault_phone_lock_prompt_note)
        )
        if (intent == null) {
            showError(getString(R.string.vault_phone_lock_unavailable))
            return
        }
        try {
            confirmDeviceLock.launch(intent)
        } catch (e: Exception) {
            showError(getString(R.string.vault_phone_lock_unavailable))
        }
    }

    // ------------------------------------------------------------------
    // Files
    // ------------------------------------------------------------------

    private fun showAddFiles() {
        if (!store.isUnlocked()) {
            showUnlock()
            return
        }
        val docs = runCatching { repo.listDocuments() }.getOrDefault(emptyList())
        if (docs.isEmpty()) {
            SwSheet.note(this, getString(R.string.vault_add_sheet_title), getString(R.string.vault_add_empty)).show()
            return
        }
        val sheet = SwSheet.builder(this)
            .setTitle(R.string.vault_add_sheet_title)
            .setMessage(R.string.vault_add_sheet_note)
            .setSecondary(R.string.vault_cancel)
            .build()
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        docs.take(20).forEach { doc ->
            body.addView(pickRow(doc.name, "${doc.pageCount}") {
                sheet.dismiss()
                addToVault(doc)
            })
        }
        sheet.setContent(body)
        sheet.show()
    }

    private fun pickRow(title: String, meta: String, onClick: () -> Unit): View =
        LinearLayout(this).apply {
            id = R.id.vaultAddRow
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = SwStyle.dp(this@VaultActivity, 52f)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            val d = resources.displayMetrics.density
            setPadding((2 * d).toInt(), (6 * d).toInt(), (2 * d).toInt(), (6 * d).toInt())
            addView(
                SwStyle.text(this@VaultActivity, 15f, SwStyle.color(this@VaultActivity, R.color.text_hologram_hi), bold = true).apply {
                    text = title
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(
                SwStyle.text(this@VaultActivity, 12f, SwStyle.color(this@VaultActivity, R.color.text_hologram_dim)).apply {
                    text = meta
                }
            )
        }

    private fun addToVault(doc: ScanDocument) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { store.addDocument(doc.id) }
                render()
                Toast.makeText(this@VaultActivity, R.string.vault_added, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                showError(getString(R.string.vault_add_failed, e.message ?: ""))
            }
        }
    }

    private fun openEntry(entry: VaultStore.Entry) {
        lifecycleScope.launch {
            try {
                val opened = withContext(Dispatchers.IO) { store.openDocument(entry.id) }
                startActivity(
                    PdfViewerActivity.fileIntent(this@VaultActivity, opened.pdfFile.absolutePath, opened.name)
                )
            } catch (e: Exception) {
                showError(getString(R.string.vault_open_failed, e.message ?: ""))
            }
        }
    }

    private fun showEntryActions(entry: VaultStore.Entry) {
        val sheet = SwSheet.builder(this)
            .setTitle(entry.name)
            .setPrimary(R.string.vault_open) { openEntry(entry) }
            .setSecondary(R.string.vault_cancel)
            .build()
        val remove = SwStyle.text(this, 13.5f, SwStyle.color(this, R.color.laser_danger), bold = true).apply {
            text = getString(R.string.vault_remove)
            gravity = Gravity.CENTER
            minimumHeight = SwStyle.dp(this@VaultActivity, 44f)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                sheet.dismiss()
                confirmRemove(entry)
            }
        }
        sheet.setContent(remove)
        sheet.show()
    }

    private fun confirmRemove(entry: VaultStore.Entry) {
        SwSheet.builder(this)
            .setTitle(getString(R.string.vault_remove))
            .setMessage(getString(R.string.vault_remove_note))
            .setPrimary(R.string.vault_remove) {
                store.removeFromVault(entry.id)
                render()
                Toast.makeText(this, R.string.vault_removed, Toast.LENGTH_SHORT).show()
            }
            .setSecondary(R.string.vault_cancel)
            .build()
            .show()
    }

    // ------------------------------------------------------------------
    // Backup, restore
    // ------------------------------------------------------------------

    private fun startBackup() {
        if (!store.isSetUp()) {
            SwSheet.note(this, getString(R.string.vault_backup_button), getString(R.string.vault_backup_requires_setup)).show()
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        createBackup.launch(getString(R.string.vault_backup_name, stamp))
    }

    private fun startRestore() {
        openBackup.launch(arrayOf("*/*"))
    }

    private fun writeBackup(uri: Uri) {
        lifecycleScope.launch {
            val temp = File(cacheDir, "vault-backup-${System.currentTimeMillis()}.swvault")
            try {
                withContext(Dispatchers.IO) {
                    store.exportBackup(temp)
                    val out = contentResolver.openOutputStream(uri)
                        ?: throw IOException("The chosen file cannot be opened")
                    out.use { target -> temp.inputStream().use { source -> source.copyTo(target) } }
                }
                Toast.makeText(this@VaultActivity, R.string.vault_backup_done, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                showError(getString(R.string.vault_backup_failed, e.message ?: ""))
            } finally {
                temp.delete()
            }
        }
    }

    private fun readBackup(uri: Uri) {
        lifecycleScope.launch {
            val temp = File(cacheDir, "vault-restore-${System.currentTimeMillis()}.swvault")
            try {
                withContext(Dispatchers.IO) {
                    val input = contentResolver.openInputStream(uri)
                        ?: throw IOException("The chosen file cannot be read")
                    input.use { source -> temp.outputStream().use { target -> source.copyTo(target) } }
                }
                showRestoreCode(temp)
            } catch (e: Exception) {
                temp.delete()
                showError(getString(R.string.vault_restore_failed, e.message ?: ""))
            }
        }
    }

    private fun showRestoreCode(file: File) {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val field = codeField(R.string.vault_recovery_hint, R.id.vaultRestoreCode, maxLength = 40)
        content.addView(field)
        val sheet = SwSheet.builder(this)
            .setTitle(R.string.vault_restore_title)
            .setMessage(R.string.vault_restore_note)
            .setContent(content)
            .setSecondary(R.string.vault_cancel) { file.delete() }
            .build()
        sheet.setPrimary(R.string.vault_restore_primary)
        sheet.keepOpenOnPrimary()
        sheet.primaryButton.setOnClickListener {
            val code = field.text()
            if (VaultCrypto.isRecoveryCode(code).not()) {
                field.setError(getString(R.string.vault_recovery_wrong))
                return@setOnClickListener
            }
            sheet.primaryButton.isEnabled = false
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { store.importBackup(file, code) }
                    sheet.dismiss()
                    file.delete()
                    unlockMethod = UnlockMethod.RECOVERY
                    render()
                    Toast.makeText(this@VaultActivity, R.string.vault_restore_done, Toast.LENGTH_LONG).show()
                    showNewPasscode(afterRestore = true)
                } catch (e: Exception) {
                    field.setError(getString(R.string.vault_restore_failed, e.message ?: ""))
                    sheet.primaryButton.isEnabled = true
                }
            }
        }
        sheet.show()
        field.editText.requestFocus()
    }

    // ------------------------------------------------------------------
    // Options, passcode, recovery
    // ------------------------------------------------------------------

    private fun showMore() {
        val sheet = SwSheet.builder(this)
            .setTitle(R.string.vault_more_title)
            .setSecondary(R.string.vault_cancel)
            .build()
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (!store.isUnlocked()) {
            body.addView(optionRow(getString(R.string.vault_unlock_first)) {
                sheet.dismiss()
                showUnlock()
            })
        } else {
            body.addView(optionRow(getString(R.string.vault_change_passcode)) {
                sheet.dismiss()
                showNewPasscode(afterRestore = false)
            })
            body.addView(optionRow(getString(R.string.vault_lock_now)) {
                sheet.dismiss()
                store.lock()
                unlockMethod = UnlockMethod.PASSCODE
                promptShown = false
                render()
                maybePromptOnOpen()
            })
        }
        if (store.hasLegacyPin()) {
            body.addView(optionRow(getString(R.string.vault_migrate_legacy)) {
                sheet.dismiss()
                if (store.isUnlocked()) showLegacyPin(migrateOnly = true) else showUnlock()
            })
        }
        body.addView(optionRow(getString(R.string.vault_recovery_button)) {
            sheet.dismiss()
            showRecoveryInfo()
        })
        body.addView(optionRow(getString(R.string.vault_restore_title)) {
            sheet.dismiss()
            startRestore()
        })
        body.addView(optionRow(getString(R.string.vault_backup_button)) {
            sheet.dismiss()
            startBackup()
        })
        sheet.setContent(body)
        sheet.show()
    }

    private fun optionRow(label: String, onClick: () -> Unit): View =
        SwStyle.text(this, 15f, SwStyle.color(this, R.color.text_hologram_hi), bold = true).apply {
            text = label
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = SwStyle.dp(this@VaultActivity, 48f)
            isClickable = true
            isFocusable = true
            setPadding(SwStyle.dp(this@VaultActivity, 2f), 0, SwStyle.dp(this@VaultActivity, 2f), 0)
            setOnClickListener { onClick() }
        }

    private fun showRecoveryInfo() {
        if (!store.isSetUp()) {
            SwSheet.note(this, getString(R.string.vault_recovery_button), getString(R.string.vault_backup_requires_setup)).show()
            return
        }
        SwSheet.builder(this)
            .setTitle(R.string.vault_recovery_button)
            .setMessage(R.string.vault_recovery_explain)
            .setPrimary(R.string.vault_recovery_make_new) {
                if (!store.isUnlocked()) {
                    showUnlock()
                } else {
                    val code = store.changeRecoveryCode()
                    showRecoveryCode(code, migrateLegacy = false)
                }
            }
            .setSecondary(R.string.vault_cancel)
            .build()
            .show()
    }

    private fun showNewPasscode(afterRestore: Boolean) {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pass = passcodeField(R.string.vault_passcode_hint, R.id.vaultNewPasscode)
        val confirm = passcodeField(R.string.vault_passcode_confirm_hint, R.id.vaultNewPasscodeConfirm)
        content.addView(pass)
        content.addView(
            confirm,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = SwStyle.dp(this@VaultActivity, 10f) }
        )
        val sheet = SwSheet.builder(this)
            .setTitle(R.string.vault_new_passcode_title)
            .setMessage(R.string.vault_new_passcode_note)
            .setContent(content)
            .setSecondary(R.string.vault_cancel)
            .build()
        sheet.setPrimary(R.string.vault_save)
        sheet.keepOpenOnPrimary()
        sheet.primaryButton.setOnClickListener {
            val code = pass.text()
            val again = confirm.text()
            pass.setError(null)
            confirm.setError(null)
            when {
                code.length < VaultStore.MIN_PASSCODE_LENGTH || !code.all { it.isDigit() } ->
                    pass.setError(getString(R.string.vault_passcode_short))
                code != again -> confirm.setError(getString(R.string.vault_passcode_mismatch))
                else -> {
                    try {
                        store.changePasscode(code)
                    } catch (e: VaultLockedException) {
                        pass.setError(getString(R.string.vault_unlock_first))
                        return@setOnClickListener
                    }
                    sheet.dismiss()
                    render()
                    Toast.makeText(this, R.string.vault_passcode_changed, Toast.LENGTH_SHORT).show()
                    if (afterRestore) Toast.makeText(
                        this@VaultActivity, R.string.vault_restore_done, Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        sheet.show()
        pass.editText.requestFocus()
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private fun passcodeField(hintRes: Int, id: Int, maxLength: Int = 32): PasswordField =
        PasswordField(this).apply {
            showStrength = false
            editText.id = id
            editText.hint = getString(hintRes)
            editText.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            editText.filters = arrayOf(InputFilter.LengthFilter(maxLength))
        }

    private fun codeField(hintRes: Int, id: Int, maxLength: Int): PasswordField =
        PasswordField(this).apply {
            showStrength = false
            editText.id = id
            editText.hint = getString(hintRes)
            editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            editText.transformationMethod = null
            editText.filters = arrayOf(InputFilter.LengthFilter(maxLength))
            editText.letterSpacing = 0.08f
        }

    private fun copyRecoveryCode(code: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.vault_recovery_button), code))
        Toast.makeText(this, R.string.vault_copy_done, Toast.LENGTH_SHORT).show()
    }

    private fun showError(message: String) {
        SwSheet.builder(this)
            .setTitle(R.string.vault_screen_title)
            .setMessage(message)
            .setPrimary(R.string.sheet_got_it)
            .build()
            .show()
    }
}
