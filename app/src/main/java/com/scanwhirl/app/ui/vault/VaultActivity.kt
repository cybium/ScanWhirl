package com.scanwhirl.app.ui.vault

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.text.format.Formatter
import android.util.TypedValue
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
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.lifecycleScope
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.model.ScanDocument
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.store.vault.VaultCrypto
import com.scanwhirl.app.store.vault.VaultKeyChallenge
import com.scanwhirl.app.store.vault.VaultKeyInvalidatedException
import com.scanwhirl.app.store.vault.VaultLockedException
import com.scanwhirl.app.store.vault.VaultSession
import com.scanwhirl.app.store.vault.VaultStore
import com.scanwhirl.app.ui.BaseActivity
import com.scanwhirl.app.ui.PdfViewerActivity
import com.scanwhirl.app.ui.applySystemBarsPadding
import com.scanwhirl.app.ui.kit.PasswordField
import com.scanwhirl.app.ui.kit.SwSheet
import com.scanwhirl.app.ui.kit.SwStyle
import com.scanwhirl.app.util.ClipboardPrivacy
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
 * characters back. Unlock: fingerprint/face or the Vault passcode. The biometric path is
 * `androidx.biometric.BiometricPrompt` with a CryptoObject wrapping the auth-bound Keystore
 * cipher, so the vault key is unwrapped only by a successful BIOMETRIC_STRONG authentication;
 * the passcode (with its five-try lockout) works on every device and is always the fallback.
 * Files come from the library, are encrypted into `filesDir/vault2`, verified, then the plain
 * library copy is purged. Backup and restore use the `.swvault` container and the recovery code
 * alone.
 *
 * DEVICE-UNPROVEN: the real prompt, the hardware Keystore cipher, and KeyPermanentlyInvalidated
 * behaviour. Robolectric tests use [fingerprintStarter] and the `VaultKeys` fake.
 */
class VaultActivity : BaseActivity() {

    companion object {
        /** Test seam: a VaultStore built with the fake Keystore and a small PBKDF2 count. */
        @Volatile
        internal var storeOverride: VaultStore? = null

        /**
         * Test seam: the machine's answer instead of `BiometricManager.canAuthenticate`
         * (Robolectric has no biometric hardware).
         */
        @Volatile
        internal var biometricStatusOverride: Int? = null

        /**
         * Test seam: runs the fingerprint prompt instead of `BiometricPrompt` and returns
         * whether the user authenticated (the real prompt reports through callbacks).
         */
        @Volatile
        internal var fingerprintStarter: ((VaultKeyChallenge) -> Boolean)? = null

        /** Extra: why another screen sent the user here (shown above the passcode field). */
        const val EXTRA_REASON = "vault_reason"

        fun intent(context: Context, reason: String? = null): Intent =
            Intent(context, VaultActivity::class.java).apply {
                if (reason != null) putExtra(EXTRA_REASON, reason)
            }
    }

    private enum class UnlockMethod { PASSCODE, BIOMETRIC, RECOVERY }

    private lateinit var repo: ScanRepository
    private lateinit var store: VaultStore

    private lateinit var statsTitle: TextView
    private lateinit var statsBody: TextView
    private lateinit var banner: TextView
    private lateinit var statusLine: TextView
    private lateinit var filesHeader: LinearLayout
    private lateinit var addLink: TextView
    private lateinit var list: LinearLayout
    private lateinit var empty: LinearLayout
    private lateinit var lockedPlaceholder: LinearLayout
    private lateinit var setupButton: TextView
    private lateinit var unlockButton: TextView
    private lateinit var lockButton: android.widget.ImageButton
    private lateinit var recoveryButton: TextView
    private lateinit var backupButton: TextView

    private var promptShown = false
    private var shownUnlocked = false
    private var unlockMethod = UnlockMethod.PASSCODE
    private var unlockSheet: SwSheet? = null
    private var attemptedUnlock = false
    private var autoPrompted = false
    private var fingerprintSetupAsked = false

    /** Why another screen sent the user here ("Unlock the Vault to save this password."). */
    private var reason: String? = null

    private val createBackup = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> if (uri != null) writeBackup(uri) }

    private val openBackup = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) readBackup(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Security audit: the Vault shows secrets (recovery code, passcodes, private file
        // names). FLAG_SECURE blocks screenshots/screen recording and blanks the recents card.
        com.scanwhirl.app.util.SecureView.apply(window)
        repo = ScanWhirlApp.from(this).repository
        store = storeOverride ?: VaultStore(this, repo)
        reason = intent?.getStringExtra(EXTRA_REASON)
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
        content.addView(statusRow())
        content.addView(primaryRow())
        content.addView(filesHeaderRow())
        content.addView(emptyCard())
        content.addView(lockedCard())
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
                    com.scanwhirl.app.ui.kit.SwHaptics.tick(findViewById(android.R.id.content))
                    com.scanwhirl.app.ui.kit.SwMotion.relockRing(lockButton, 0)
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

    /** "Still locked..." after a dismissed or failed attempt: the sheet never fails silently. */
    private fun statusRow(): View {
        statusLine = SwStyle.text(this, 13f, SwStyle.color(this, R.color.laser_danger), bold = true).apply {
            id = R.id.vaultStatus
            background = SwStyle.rounded(
                this@VaultActivity,
                SwStyle.color(this@VaultActivity, R.color.sw_cyan_soft),
                radiusDp = 14f,
                stroke = SwStyle.color(this@VaultActivity, R.color.laser_danger)
            )
            setPadding(
                SwStyle.dp(this@VaultActivity, 14f), SwStyle.dp(this@VaultActivity, 10f),
                SwStyle.dp(this@VaultActivity, 14f), SwStyle.dp(this@VaultActivity, 10f)
            )
            isVisible = false
        }
        statusLine.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = SwStyle.dp(this@VaultActivity, 12f) }
        return statusLine
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
            com.scanwhirl.app.ui.kit.SwMotion.brandArt(this),
            LinearLayout.LayoutParams(SwStyle.dp(this, 64f), SwStyle.dp(this, 64f))
        )
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

    /** While locked, "Private files" says what is there instead of showing empty space. */
    private fun lockedCard(): View {
        lockedPlaceholder = LinearLayout(this).apply {
            id = R.id.vaultLockedPlaceholder
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = SwStyle.card(this@VaultActivity, 16f)
            val pad = SwStyle.dp(this@VaultActivity, 14f)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = SwStyle.dp(this@VaultActivity, 10f) }
            isVisible = false
        }
        lockedPlaceholder.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_sym_lock)
                imageTintList = ColorStateList.valueOf(SwStyle.color(this@VaultActivity, R.color.text_hologram_dim))
            },
            LinearLayout.LayoutParams(SwStyle.dp(this, 20f), SwStyle.dp(this, 20f))
        )
        lockedPlaceholder.addView(
            SwStyle.text(this, 14f, SwStyle.color(this, R.color.text_hologram_mid)).apply {
                text = getString(R.string.vault_locked_placeholder)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = SwStyle.dp(this@VaultActivity, 10f) }
        )
        return lockedPlaceholder
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
            compoundDrawablePadding = SwStyle.dp(this@VaultActivity, 6f)
            compoundDrawableTintList = ColorStateList.valueOf(
                SwStyle.color(this@VaultActivity, R.color.cyan_primary)
            )
            // Two of these share a 360dp row: the 24dp icon plus a label must never wrap and
            // push the icon out of the 46dp button. One line, and the label shrinks to 10sp
            // before it is ever clipped (owner saw the icons leaving the button on a phone).
            includeFontPadding = false
            maxLines = 1
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadding(
                SwStyle.dp(this@VaultActivity, 6f), 0,
                SwStyle.dp(this@VaultActivity, 6f), 0
            )
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this, 10, 14, 1, TypedValue.COMPLEX_UNIT_SP
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
            else -> resources.getQuantityString(
                R.plurals.vault_documents_protected, entries.size, entries.size
            )
        }
        statsBody.text = when {
            !setUp -> getString(R.string.vault_not_setup_body)
            !unlocked -> getString(R.string.vault_locked_body)
            else -> getString(R.string.vault_stats_body)
        }
        banner.isVisible = setUp && unlocked
        banner.text = when (unlockMethod) {
            UnlockMethod.BIOMETRIC -> getString(R.string.vault_banner_fingerprint)
            UnlockMethod.RECOVERY -> getString(R.string.vault_banner_recovery)
            UnlockMethod.PASSCODE -> getString(R.string.vault_banner_passcode)
        }
        statusLine.isVisible = setUp && !unlocked && attemptedUnlock
        statusLine.text = getString(R.string.vault_still_locked)
        setupButton.isVisible = !setUp
        unlockButton.isVisible = setUp && !unlocked
        filesHeader.isVisible = setUp
        addLink.isVisible = unlocked
        lockedPlaceholder.isVisible = setUp && !unlocked
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
                    resources.getQuantityString(R.plurals.vault_pages, entry.pageCount, entry.pageCount),
                    Formatter.formatShortFileSize(this, entry.sizeBytes),
                    date
                )
            } else {
                getString(
                    R.string.vault_entry_meta_files,
                    resources.getQuantityString(R.plurals.vault_file_count, entry.fileCount, entry.fileCount),
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
                        pass.setError(e.message ?: getString(R.string.vault_passcode_short))
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
        sheet.view.applySystemBarsPadding(top = false)
        // No auto-focus: opening the keyboard on this two-field sheet hid the primary button.
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
        sheet.view.applySystemBarsPadding(top = false)
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
            if (migrateLegacy) migrateLegacyAsync() else {
                render()
                offerFingerprintSetup()
            }
        }
        sheet.show()
        sheet.view.applySystemBarsPadding(top = false)
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
        sheet.view.applySystemBarsPadding(top = false)
        pin.editText.requestFocus()
    }

    private fun migrateLegacyAsync() {
        lifecycleScope.launch {
            try {
                val moved = withContext(Dispatchers.IO) { store.migrateLegacyVault() }
                render()
                offerFingerprintSetup()
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
        unlockSheet?.dismiss()
        val offered = fingerprintOffered()
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pass = passcodeField(R.string.vault_passcode_hint_generic, R.id.vaultUnlockPasscode)
        pass.errorView.id = R.id.vaultUnlockError
        pass.editText.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
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
            .setMessage(
                reason ?: getString(
                    if (offered) R.string.vault_unlock_note_fingerprint else R.string.vault_unlock_note_passcode
                )
            )
            .setContent(content)
            .build()
        recoveryLink.setOnClickListener {
            sheet.dismiss()
            showRecoveryUnlock()
        }
        if (offered) {
            sheet.setSecondary(R.string.vault_use_fingerprint) { startFingerprintUnlock() }
            sheet.secondaryButton.setCompoundDrawablesRelativeWithIntrinsicBounds(
                R.drawable.ic_sym_fingerprint, 0, 0, 0
            )
            sheet.secondaryButton.compoundDrawablePadding = SwStyle.dp(this@VaultActivity, 8f)
            sheet.secondaryButton.compoundDrawableTintList =
                ColorStateList.valueOf(SwStyle.color(this@VaultActivity, R.color.cyan_primary))
        }
        sheet.setPrimary(R.string.vault_unlock_primary)
        sheet.keepOpenOnPrimary()
        val attempt = { onPasscodeEntered(sheet, pass) }
        sheet.primaryButton.setOnClickListener { attempt() }
        pass.editText.setOnEditorActionListener { _, _, _ ->
            attempt()
            true
        }
        sheet.show()
        sheet.view.applySystemBarsPadding(top = false)
        unlockSheet = sheet
        pass.editText.requestFocus()
        if (offered && !autoPrompted) {
            autoPrompted = true
            startFingerprintUnlock(auto = true)
        }
    }

    /** The one place a passcode is checked: a failure is always visible, never a silent close. */
    private fun onPasscodeEntered(sheet: SwSheet, pass: PasswordField) {
        attemptedUnlock = true
        val result = store.unlockWithPasscode(pass.text())
        if (result.ok) {
            pass.setError(null)
            com.scanwhirl.app.ui.kit.SwHaptics.confirm(pass)
            unlockMethod = UnlockMethod.PASSCODE
            sheet.dismiss()
            unlockSheet = null
            render()
            com.scanwhirl.app.ui.kit.SwMotion.relockRing(lockButton)
            offerFingerprintSetup()
            return
        }
        render()
        if (result.pauseMillis > 0) {
            pass.setError(
                getString(R.string.vault_unlock_pause, (result.pauseMillis + 999) / 1000)
            )
        } else {
            pass.setError(
                resources.getQuantityString(
                    R.plurals.vault_unlock_wrong_left, result.triesLeft, result.triesLeft
                )
            )
        }
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
                offerFingerprintSetup()
            } else {
                field.setError(getString(R.string.vault_recovery_wrong))
            }
        }
        sheet.show()
        sheet.view.applySystemBarsPadding(top = false)
        field.editText.requestFocus()
    }

    /** The machine's answer for BIOMETRIC_STRONG (override-aware, so tests need no sensor). */
    private fun canAuthenticateStatus(): Int {
        biometricStatusOverride?.let { return it }
        if (Build.VERSION.SDK_INT < VaultUnlockPolicy.MIN_API) {
            return BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED
        }
        return try {
            BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        } catch (e: Exception) {
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED
        }
    }

    /** The hardware can prompt at all: the fingerprint is worth offering or setting up. */
    private fun biometricHardwareReady(): Boolean =
        Build.VERSION.SDK_INT >= VaultUnlockPolicy.MIN_API &&
            canAuthenticateStatus() == BiometricManager.BIOMETRIC_SUCCESS

    /**
     * The fingerprint path is offered only when the platform can run the AndroidX prompt, this
     * phone has a valid auth-bound Keystore key, and `BIOMETRIC_STRONG` is enrollable/ready.
     * Otherwise the passcode sheet is the whole (working) offer.
     */
    private fun fingerprintOffered(): Boolean = VaultUnlockPolicy.fingerprintOffered(
        apiLevel = Build.VERSION.SDK_INT,
        haveKeystoreKey = store.canUseBiometric(),
        canAuthenticate = canAuthenticateStatus()
    )

    /**
     * Shows the AndroidX `BiometricPrompt` with a CryptoObject wrapping the auth-bound Keystore
     * cipher, so the vault key is unwrapped only by a successful BIOMETRIC_STRONG authentication.
     * [auto] marks the prompt that starts with the sheet: cancelling it stays silent, while a
     * failure from the "Use fingerprint" button is explained.
     * DEVICE-UNPROVEN (Robolectric has no biometric hardware); tests drive [fingerprintStarter].
     */
    private fun startFingerprintUnlock(auto: Boolean = false) {
        val challenge = try {
            store.beginBiometricUnlock()
        } catch (e: VaultKeyInvalidatedException) {
            showError(getString(R.string.vault_fingerprint_reset))
            return
        } catch (e: Exception) {
            if (!auto) showError(getString(R.string.vault_fingerprint_unavailable))
            return
        }
        val starter = fingerprintStarter
        if (starter != null) {
            if (starter(challenge)) {
                onFingerprintAuthenticated(challenge)
            } else if (!auto) {
                showFingerprintFailed()
            }
            return
        }
        val cipher = challenge.cipher
        if (cipher == null || Build.VERSION.SDK_INT < VaultUnlockPolicy.MIN_API) {
            if (!auto) showFingerprintFailed()
            return
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    when (errorCode) {
                        // "Use passcode" / a cancelled prompt: the passcode sheet is already up.
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_CANCELED,
                        BiometricPrompt.ERROR_TIMEOUT -> Unit
                        BiometricPrompt.ERROR_LOCKOUT,
                        BiometricPrompt.ERROR_LOCKOUT_PERMANENT ->
                            showError(getString(R.string.vault_fingerprint_locked))
                        else -> if (!auto) showError(errString.toString())
                    }
                }

                override fun onAuthenticationFailed() {
                    // A single non-matching finger: the system keeps the prompt open.
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onFingerprintAuthenticated(challenge)
                }
            }
        )
        try {
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle(getString(R.string.vault_unlock_sheet_title))
                    .setSubtitle(
                        getString(
                            if (fingerprintOffered()) R.string.vault_unlock_note_fingerprint
                            else R.string.vault_unlock_note_passcode
                        )
                    )
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .setNegativeButtonText(getString(R.string.vault_use_passcode))
                    .setConfirmationRequired(false)
                    .build(),
                BiometricPrompt.CryptoObject(cipher)
            )
        } catch (e: Exception) {
            showFingerprintFailed()
        }
    }

    private fun onFingerprintAuthenticated(challenge: VaultKeyChallenge) {
        if (store.completeBiometricUnlock(challenge)) {
            unlockMethod = UnlockMethod.BIOMETRIC
            promptShown = true
            unlockSheet?.dismiss()
            unlockSheet = null
            render()
            com.scanwhirl.app.ui.kit.SwMotion.relockRing(lockButton)
        } else {
            showFingerprintFailed()
        }
    }

    /** Never a dead end: the error says so, and the passcode sheet comes back. */
    private fun showFingerprintFailed() {
        unlockSheet?.dismiss()
        unlockSheet = null
        showError(getString(R.string.vault_fingerprint_failed)) { showUnlock() }
    }

    /**
     * Creates the fingerprint wrap for a Vault that has none (fresh setup, restore, or an
     * upgrade from a build whose Keystore key is gone). A per-use auth-bound Keystore key can
     * only encrypt with a prompt-authorized cipher, so this is the one moment that can turn
     * fingerprint unlock back on: right after the user proved the passcode. Never blocks the
     * Vault and is asked at most once per screen.
     * DEVICE-UNPROVEN on hardware; tests drive [fingerprintStarter].
     */
    private fun offerFingerprintSetup() {
        if (fingerprintSetupAsked || !biometricHardwareReady() || store.canUseBiometric()) return
        fingerprintSetupAsked = true
        val challenge = try {
            store.beginKeystoreWrap()
        } catch (e: Exception) {
            return
        }
        val starter = fingerprintStarter
        if (starter != null) {
            if (starter(challenge)) {
                store.completeKeystoreWrap(challenge)
                render()
            }
            return
        }
        val cipher = challenge.cipher ?: return
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // "Not now" or a cancel: the Vault stays open, the passcode keeps working.
                }

                override fun onAuthenticationFailed() = Unit

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (store.completeKeystoreWrap(challenge)) render()
                }
            }
        )
        try {
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle(getString(R.string.vault_fingerprint_setup_title))
                    .setSubtitle(getString(R.string.vault_fingerprint_setup_note))
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .setNegativeButtonText(getString(R.string.vault_not_now))
                    .setConfirmationRequired(false)
                    .build(),
                BiometricPrompt.CryptoObject(cipher)
            )
        } catch (e: Exception) {
            // No wrap this time; the passcode and recovery code still open the Vault.
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
        sheet.view.applySystemBarsPadding(top = false)
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
        sheet.view.applySystemBarsPadding(top = false)
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
        sheet.view.applySystemBarsPadding(top = false)
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
        sheet.view.applySystemBarsPadding(top = false)
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
                    offerFingerprintSetup()
                    Toast.makeText(this, R.string.vault_passcode_changed, Toast.LENGTH_SHORT).show()
                    if (afterRestore) Toast.makeText(
                        this@VaultActivity, R.string.vault_restore_done, Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        sheet.show()
        sheet.view.applySystemBarsPadding(top = false)
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
        // Security audit: a recovery code is a master key. Mark the clip sensitive (Android 13+
        // hides it from the clipboard preview) and clear it after a minute.
        ClipboardPrivacy.copy(this, getString(R.string.vault_recovery_button), code, sensitive = true)
        Toast.makeText(this, R.string.vault_copy_done, Toast.LENGTH_SHORT).show()
    }

    private fun showError(message: String, after: (() -> Unit)? = null) {
        SwSheet.builder(this)
            .setTitle(R.string.vault_screen_title)
            .setMessage(message)
            .setPrimary(R.string.sheet_got_it) { after?.invoke() }
            .build()
            .show()
    }
}
