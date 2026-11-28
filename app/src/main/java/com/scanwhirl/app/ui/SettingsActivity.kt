package com.scanwhirl.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.scanwhirl.app.R
import com.scanwhirl.app.databinding.ActivitySettingsBinding
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.PrivacySweep
import com.scanwhirl.app.util.SizeUnits
import com.scanwhirl.app.util.TextSizePrefs
import java.io.File

class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding

    /** L2: the "Text reading language" summary (built in [addTextLanguageRow]). */
    private lateinit var subOcrLanguage: android.widget.TextView

    private val pickWallpaper =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            Prefs.setWallpaperUri(this, uri.toString())
            Prefs.setShowWallpaper(this, true)
            binding.switchWallpaper.isChecked = true
            updateSummaries()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarsPadding()
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.navigationContentDescription = getString(R.string.a11y_back)
        // The switches are the row's control: give each the row's own words so
        // TalkBack never announces an unnamed toggle.
        binding.switchAutoEnhance.contentDescription = getString(R.string.settings_auto_enhance)
        binding.switchAutoCopyToDocuments.contentDescription =
            getString(R.string.settings_auto_copy_to_documents)
        binding.switchSearchablePdf.contentDescription = getString(R.string.settings_searchable_pdf)
        // T32 (phone build 3): this switch measured 66 px (~24 dp) tall at 200 % font
        // on the Infinix, half the 48 dp touch target the other rows keep, and it is
        // the row's only control. Pin the target here instead of leaving it to the
        // switchStyle theme attribute.
        binding.switchSearchablePdf.minimumHeight = com.scanwhirl.app.ui.kit.SwStyle.dp(this, 48f)
        // L5-03: the status-line switch is the row's only control too; pin it as a full target.
        binding.switchGreeting.minimumHeight = com.scanwhirl.app.ui.kit.SwStyle.dp(this, 48f)
        binding.switchGreeting.minimumWidth = com.scanwhirl.app.ui.kit.SwStyle.dp(this, 48f)
        binding.switchGreeting.isFocusable = true

        // B2: the theme is a real choice again — System (default) / Light / Dark.
        // The row stays in the grouped Appearance card; its summary follows the pref.
        binding.rowTheme.visibility = android.view.View.VISIBLE
        // A clickable row must carry its own name: the label lives on child TextViews.
        binding.rowTheme.contentDescription = getString(R.string.settings_theme)

        binding.rowTheme.setOnClickListener { chooseTheme() }
        binding.rowFont.setOnClickListener { chooseFont() }
        // Lane Z rows: text size, storage, and permission-free support actions.
        binding.rowTextSize.setOnClickListener { chooseTextSize() }
        binding.rowClearTemp.setOnClickListener { clearTemporaryFiles() }
        binding.rowRate.setOnClickListener { rateScanWhirl() }
        binding.rowFeedback.setOnClickListener { sendFeedback() }
        // No invented support address ships: hide the row until the lead fills the constant.
        if (supportEmail() == PLACEHOLDER_SUPPORT_EMAIL) {
            binding.rowFeedback.visibility = View.GONE
            binding.dividerFeedback.visibility = View.GONE
        }
        binding.rowGreeting.setOnClickListener {
            binding.switchGreeting.isChecked = !binding.switchGreeting.isChecked
        }
        binding.switchGreeting.isChecked = Prefs.showGreeting(this)
        binding.switchGreeting.setOnCheckedChangeListener { _, checked ->
            Prefs.setShowGreeting(this, checked)
        }
        binding.switchAutoEnhance.isChecked = Prefs.autoEnhanceScan(this)
        binding.switchAutoEnhance.setOnCheckedChangeListener { _, checked ->
            Prefs.setAutoEnhanceScan(this, checked)
        }
        binding.switchAutoCopyToDocuments.isChecked = Prefs.autoCopyToDocuments(this)
        binding.switchAutoCopyToDocuments.setOnCheckedChangeListener { _, checked ->
            Prefs.setAutoCopyToDocuments(this, checked)
        }
        binding.switchSearchablePdf.isChecked = Prefs.makeSearchable(this)
        binding.switchSearchablePdf.setOnCheckedChangeListener { _, checked ->
            Prefs.setMakeSearchable(this, checked)
        }
        binding.rowWallpaper.setOnClickListener {
            pickWallpaper.launch(arrayOf("image/*"))
        }
        binding.switchWallpaper.isChecked = Prefs.showWallpaper(this)
        binding.switchWallpaper.setOnCheckedChangeListener { _, checked ->
            if (checked && Prefs.wallpaperUri(this).isBlank()) {
                // Need a photo before the wallpaper can be on.
                binding.switchWallpaper.isChecked = false
                pickWallpaper.launch(arrayOf("image/*"))
            } else {
                Prefs.setShowWallpaper(this, checked)
                updateSummaries()
            }
        }
        binding.rowPrivacy.setOnClickListener { openPrivacy() }
        binding.rowLanguage.setOnClickListener { chooseLanguage() }
        binding.rowViewer.setOnClickListener { chooseViewer() }
        // Me-hub cleanup: About lives once, in the Me tab. Settings owns look,
        // text, privacy and storage only — no nested About/storage duplicates.
        // About and the privacy policy live in Me > About now. Their card stays as the
        // "Privacy & security" section: the screenshot switch (and, in test builds, the
        // Free/Pro switch) are added into it below. Hiding the whole card hid those rows.
        keepCardAsPrivacySection()
        hideSection(binding.rowTrash)
        binding.rowAbout.setOnClickListener { showAbout() }
        binding.rowTrash.setOnClickListener { startActivity(TrashActivity.intent(this)) }

        // L2: OCR text language - the same setting the Text screen's language chip changes.
        addTextLanguageRow()

        // SECURITY_AUDIT 2026-11-15: screenshot policy for ordinary documents.
        addBlockScreenshotsRow()

        // ATLAS-F2 (014): the greyed "Accent · Gold coming soon" row is gone. Settings only
        // shows what exists; an unbuilt capability is not advertised (product rule).
        // Test builds only: let QA see the Free/Pro gate.
        if (com.scanwhirl.app.BuildConfig.TEST_BUILD) addTestFreeRow()

        updateSummaries()

        // M7 (device sweep 2026-11-19): every tappable row carries its own name, so the node a
        // screen reader focuses is never an unnamed box — even when the row's label TextView is
        // scrolled out of the list (the 336x11dp strip the sweep caught at the top of Settings).
        nameRowsForTalkBack()
    }

    /** Rows whose label lives on child TextViews: the clickable node owns the words instead. */
    private fun nameRowsForTalkBack() {
        val rows = listOf(
            R.id.rowFont, R.id.rowLanguage, R.id.rowOcrLanguage, R.id.rowViewer, R.id.rowPrivacy,
            R.id.rowTextSize, R.id.rowLibrarySize, R.id.rowTempSize, R.id.rowClearTemp,
            R.id.rowRate, R.id.rowFeedback
        )
        for (id in rows) {
            val row = findViewById<android.view.View>(id) ?: continue
            val words = mutableListOf<String>()
            fun collect(v: android.view.View) {
                if (v.visibility != android.view.View.VISIBLE) return
                if (v is android.widget.TextView) {
                    v.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { words += it }
                }
                if (v is android.view.ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i))
            }
            collect(row)
            if (words.isNotEmpty()) row.contentDescription = words.joinToString(", ")
        }
    }

    /** L2: one compact row under the app language, built here so L1's layout stays untouched. */
    private fun addTextLanguageRow() {
        val parent = binding.rowLanguage.parent as? android.view.ViewGroup ?: return
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val row = android.widget.LinearLayout(this).apply {
            id = R.id.rowOcrLanguage
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
            isClickable = true
            isFocusable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { chooseOcrLanguage() }
        }
        row.addView(
            android.widget.ImageView(this).apply {
                setImageResource(R.drawable.ic_sym_language)
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                imageTintList = android.content.res.ColorStateList.valueOf(
                    com.scanwhirl.app.ui.kit.SwStyle.color(this@SettingsActivity, R.color.cyan_primary)
                )
            },
            android.widget.LinearLayout.LayoutParams((22 * density).toInt(), (22 * density).toInt())
        )
        val texts = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        texts.addView(
            android.widget.TextView(this).apply {
                setText(R.string.cdf1_ocr_language)
                setTextColor(com.scanwhirl.app.ui.kit.SwStyle.color(this@SettingsActivity, R.color.text_hologram_hi))
                textSize = 15f
            }
        )
        subOcrLanguage = android.widget.TextView(this).apply {
            id = R.id.subOcrLanguage
            setTextColor(com.scanwhirl.app.ui.kit.SwStyle.color(this@SettingsActivity, R.color.text_hologram_mid))
            textSize = 12.5f
            setPadding(0, (2 * density).toInt(), 0, 0)
        }
        texts.addView(subOcrLanguage)
        row.addView(
            texts,
            android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = (12 * density).toInt() }
        )
        row.addView(
            android.widget.ImageView(this).apply {
                setImageResource(R.drawable.ic_sym_chevron_right)
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                imageTintList = android.content.res.ColorStateList.valueOf(
                    com.scanwhirl.app.ui.kit.SwStyle.color(this@SettingsActivity, R.color.text_hologram_dim)
                )
            },
            android.widget.LinearLayout.LayoutParams((20 * density).toInt(), (20 * density).toInt())
        )
        val index = parent.indexOfChild(binding.rowLanguage)
        parent.addView(row, if (index >= 0) index + 1 else parent.childCount)
    }

    /**
     * SECURITY_AUDIT 2026-11-15: one switch right under Privacy. Protected and
     * Vault documents are blocked regardless; this only covers ordinary scans.
     */
    private fun addBlockScreenshotsRow() {
        val parent = binding.rowPrivacy.parent as? android.view.ViewGroup ?: return
        val d = resources.displayMetrics.density
        val row = android.widget.LinearLayout(this).apply {
            id = R.id.rowBlockScreenshots
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((16 * d).toInt(), (16 * d).toInt(), (16 * d).toInt(), (16 * d).toInt())
        }
        val texts = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, 0, (12 * d).toInt(), 0)
        }
        texts.addView(
            android.widget.TextView(this).apply {
                setText(R.string.settings_block_screenshots)
                textSize = 15f
                setTextColor(
                    com.scanwhirl.app.ui.kit.SwStyle.color(
                        this@SettingsActivity, R.color.text_hologram_hi
                    )
                )
            }
        )
        texts.addView(
            android.widget.TextView(this).apply {
                setText(R.string.cdf1_block_screenshots_note)
                textSize = 12.5f
                setPadding(0, (2 * d).toInt(), 0, 0)
                setTextColor(
                    com.scanwhirl.app.ui.kit.SwStyle.color(
                        this@SettingsActivity, R.color.text_hologram_mid
                    )
                )
            }
        )
        row.addView(
            texts,
            android.widget.LinearLayout.LayoutParams(
                0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginEnd = (12 * d).toInt() }
        )
        val toggle = androidx.appcompat.widget.SwitchCompat(this).apply {
            id = R.id.switchBlockScreenshots
            contentDescription = getString(R.string.settings_block_screenshots)
            // L5-03: one coherent focus stop, named with its own state by SwitchCompat.
            minimumHeight = com.scanwhirl.app.ui.kit.SwStyle.dp(this@SettingsActivity, 48f)
            minimumWidth = com.scanwhirl.app.ui.kit.SwStyle.dp(this@SettingsActivity, 48f)
            isFocusable = true
            isChecked = Prefs.blockDocScreenshots(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setBlockDocScreenshots(this@SettingsActivity, checked)
            }
        }
        row.addView(toggle)
        val divider = android.view.View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, (1 * d).toInt()
            ).apply { marginStart = (54 * d).toInt() }
            setBackgroundColor(
                com.scanwhirl.app.ui.kit.SwStyle.color(
                    this@SettingsActivity, R.color.cyan_border_hair
                )
            )
        }
        addLeadingIcon(row, R.drawable.ic_sym_visibility_off)
        addToPrivacySection(row, divider)
    }

    /** Matches the XML rows: a 24dp decorative leading icon with a 14dp gap. */
    private fun addLeadingIcon(row: android.view.ViewGroup, iconRes: Int) {
        val d = resources.displayMetrics.density
        val icon = android.widget.ImageView(this).apply {
            setImageResource(iconRes)
            imageTintList = android.content.res.ColorStateList.valueOf(
                com.scanwhirl.app.ui.kit.SwStyle.color(
                    this@SettingsActivity, R.color.cyan_primary
                )
            )
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        row.addView(
            icon,
            0,
            android.widget.LinearLayout.LayoutParams((24 * d).toInt(), (24 * d).toInt())
                .apply { marginEnd = (14 * d).toInt() }
        )
    }

    /** Test build only: flip QA into Free so every Pro gate is visible. */
    private fun addTestFreeRow() {
        val parent = binding.rowPrivacy.parent as? android.view.ViewGroup ?: return
        val d = resources.displayMetrics.density
        val row = android.widget.LinearLayout(this).apply {
            id = R.id.rowTestFree
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((16 * d).toInt(), (16 * d).toInt(), (16 * d).toInt(), (16 * d).toInt())
        }
        val texts = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        texts.addView(
            android.widget.TextView(this).apply {
                setText(R.string.settings_test_free)
                textSize = 15f
                setTextColor(
                    com.scanwhirl.app.ui.kit.SwStyle.color(
                        this@SettingsActivity, R.color.text_hologram_hi
                    )
                )
            }
        )
        texts.addView(
            android.widget.TextView(this).apply {
                setText(R.string.settings_test_free_summary)
                textSize = 12.5f
                setPadding(0, (2 * d).toInt(), 0, 0)
                setTextColor(
                    com.scanwhirl.app.ui.kit.SwStyle.color(
                        this@SettingsActivity, R.color.text_hologram_mid
                    )
                )
            }
        )
        row.addView(
            texts,
            android.widget.LinearLayout.LayoutParams(
                0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginEnd = (12 * d).toInt() }
        )
        val toggle = androidx.appcompat.widget.SwitchCompat(this).apply {
            id = R.id.switchTestFree
            contentDescription = getString(R.string.settings_test_free)
            isChecked = com.scanwhirl.app.util.ProGate.testOverride(this@SettingsActivity) == false
            setOnCheckedChangeListener { _, checked ->
                com.scanwhirl.app.util.ProGate.setTestOverride(
                    this@SettingsActivity, if (checked) false else null
                )
                recreate()
            }
        }
        row.addView(toggle)
        val divider = android.view.View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, (1 * d).toInt()
            ).apply { marginStart = (16 * d).toInt() }
            setBackgroundColor(
                com.scanwhirl.app.ui.kit.SwStyle.color(this@SettingsActivity, R.color.cyan_border_hair)
            )
        }
        addLeadingIcon(row, R.drawable.ic_sym_tune)
        addToPrivacySection(row, divider)
    }

    /** Hides the About + Privacy policy rows (and the divider between them), keeps their
     *  card, and relabels its section "Privacy & security". */
    private fun keepCardAsPrivacySection() {
        val rows = binding.rowAbout.parent as? android.view.ViewGroup ?: return
        for (i in 0 until rows.childCount) rows.getChildAt(i).visibility = android.view.View.GONE
        var node: android.view.ViewParent? = rows
        while (node != null && node !is com.google.android.material.card.MaterialCardView) node = node.parent
        val card = node as? android.view.View ?: return
        val section = card.parent as? android.view.ViewGroup ?: return
        val index = section.indexOfChild(card)
        (section.getChildAt(index - 1) as? android.widget.TextView)?.setText(R.string.settings_privacy_section)
    }

    /** Adds [row] at the end of the Privacy & security card, with a divider only when a
     *  visible row is already above it. */
    private fun addToPrivacySection(row: android.view.View, divider: android.view.View) {
        val parent = binding.rowPrivacy.parent as? android.view.ViewGroup ?: return
        val hasVisibleRow = (0 until parent.childCount).any {
            val child = parent.getChildAt(it)
            child.visibility == android.view.View.VISIBLE && child is android.widget.LinearLayout
        }
        if (hasVisibleRow) parent.addView(divider)
        parent.addView(row)
    }

    /**
     * Hides a settings row's card and the section label right above it.
     * Walks up to the nearest card: rows sit at different depths (rowTrash's card is its
     * PARENT, so the old `row.parent.parent` hid the whole page and Settings went blank).
     */
    private fun hideSection(row: android.view.View) {
        var node: android.view.ViewParent? = row.parent
        while (node != null && node !is com.google.android.material.card.MaterialCardView) node = node.parent
        val card = node as? android.view.View ?: return
        val parent = card.parent as? android.view.ViewGroup ?: return
        if (parent === binding.root || parent is android.widget.ScrollView) return
        val index = parent.indexOfChild(card)
        if (index > 0) parent.getChildAt(index - 1).visibility = android.view.View.GONE
        card.visibility = android.view.View.GONE
    }

    private fun chooseOcrLanguage() {
        val languages = com.scanwhirl.app.util.OcrLanguage.entries
        val labels = languages.map { getString(ocrLanguageLabel(it)) }
        val checked = languages.indexOf(com.scanwhirl.app.util.OcrLanguageSettings.get(this)).coerceAtLeast(0)
        com.scanwhirl.app.ui.kit.SwSheet.builder(this)
            .setTitle(R.string.cdf1_ocr_language)
            .setMessage(R.string.u4_ocr_language_note)
            .setOptions(labels, checked) { which, _ ->
                com.scanwhirl.app.util.OcrLanguageSettings.set(this, languages[which])
                updateSummaries()
                Toast.makeText(this, R.string.ocr_lang_saved, Toast.LENGTH_SHORT).show()
            }
            .setSecondary(android.R.string.cancel)
            .build()
            .show()
    }

    private fun ocrLanguageLabel(language: com.scanwhirl.app.util.OcrLanguage): Int =
        when (language) {
            com.scanwhirl.app.util.OcrLanguage.ENGLISH -> R.string.ocr_lang_english
            com.scanwhirl.app.util.OcrLanguage.ENGLISH_HINDI -> R.string.ocr_lang_english_hindi
            com.scanwhirl.app.util.OcrLanguage.HINDI -> R.string.ocr_lang_hindi
        }


    override fun onResume() {
        super.onResume()
        // Lane Z item 3: the two storage sizes are disk I/O, never on the main thread.
        refreshStorageSizes()
        // M07: Trash count, read off the main thread.
        val repo = com.scanwhirl.app.store.ScanRepository(this)
        lifecycleScope.launch {
            val n = withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { repo.trashCount() }.getOrDefault(0) }
            binding.subTrash.text = if (n == 0) getString(R.string.trash_settings_empty)
            else resources.getQuantityString(R.plurals.trash_count, n, n)
        }
    }

    private fun updateSummaries() {
        // B2: name the active theme, including the follow-system default.
        binding.subTheme.setText(
            when (Prefs.themeMode(this)) {
                Prefs.THEME_LIGHT -> R.string.theme_light
                Prefs.THEME_SYSTEM -> R.string.theme_system
                else -> R.string.theme_dark
            }
        )
        binding.subFont.setText(
            when (Prefs.fontStyle(this)) {
                Prefs.FONT_SERIF -> R.string.font_serif
                Prefs.FONT_MONO -> R.string.font_mono
                else -> R.string.font_default
            }
        )
        val tag = com.scanwhirl.app.util.AppLanguage.currentTag(this)
        val appLanguage = if (tag.isEmpty()) {
            getString(R.string.language_system)
        } else {
            com.scanwhirl.app.util.AppLanguage.label(tag)
        }
        // C1 (2026-11-20 owner): say what the row changes, not only the language name.
        binding.subLanguage.text = getString(R.string.cdf1_language_summary, appLanguage)
        binding.subViewer.setText(
            if (Prefs.defaultViewer(this) == Prefs.VIEWER_SYSTEM) {
                R.string.viewer_system
            } else {
                R.string.viewer_in_app
            }
        )
        binding.subWallpaper.setText(
            if (Prefs.showWallpaper(this)) R.string.wallpaper_on else R.string.wallpaper_off
        )
        if (::subOcrLanguage.isInitialized) {
            // C1 (2026-11-20 owner): the second language row now says what it changes.
            subOcrLanguage.text = getString(
                R.string.cdf1_ocr_language_summary,
                getString(ocrLanguageLabel(com.scanwhirl.app.util.OcrLanguageSettings.get(this)))
            )
        }
        // Lane Z item 1: the label plus the honest multiplier under it.
        val sizeMode = TextSizePrefs.mode(this)
        binding.subTextSize.setText(textSizeLabel(sizeMode))
        binding.textSizePercent.text =
            getString(R.string.cdf1_text_size_preview, TextSizePrefs.percent(sizeMode))
    }

    // ---- Lane Z: Text size ---------------------------------------------------

    private fun textSizeLabel(mode: String): Int = when (mode) {
        TextSizePrefs.MODE_SMALL -> R.string.cz_text_size_small
        TextSizePrefs.MODE_LARGE -> R.string.cz_text_size_large
        TextSizePrefs.MODE_LARGEST -> R.string.cz_text_size_largest
        else -> R.string.cdf1_text_size_default
    }

    /** Persist the choice and recreate: BaseActivity reads it in attachBaseContext. */
    private fun chooseTextSize() {
        val modes = TextSizePrefs.MODES
        val labels = modes.map { getString(textSizeLabel(it)) }
        val checked = modes.indexOf(TextSizePrefs.mode(this)).coerceAtLeast(0)
        com.scanwhirl.app.ui.kit.SwSheet.builder(this)
            .setTitle(R.string.cz_text_size_title)
            .setOptions(labels, checked) { which, _ ->
                TextSizePrefs.setMode(this, modes[which])
                recreate()
            }
            .setSecondary(android.R.string.cancel)
            .build()
            .show()
    }

    // ---- Lane Z: Storage -----------------------------------------------------

    /** Recursive size of one directory tree; a single unreadable child is skipped. */
    private fun File.dirSize(): Long {
        if (!exists()) return 0L
        if (isFile) return length()
        return listFiles()?.fold(0L) { total, child ->
            total + runCatching { child.dirSize() }.getOrDefault(0L)
        } ?: 0L
    }

    /**
     * Only the cache entries PrivacySweep may delete. Nothing under filesDir is
     * ever measured here as "temporary": the library, Vault, drafts, trash and
     * in-progress work are excluded both from this number and from the clear action.
     */
    private fun temporaryBytes(): Long {
        val cache = cacheDir ?: return 0L
        val jobRunning = runCatching { PrivacySweep.backupRunning() }.getOrDefault(false)
        val vaultUnlocked = runCatching {
            com.scanwhirl.app.store.vault.VaultSession.isUnlocked()
        }.getOrDefault(false)
        var total = 0L
        PrivacySweep.CACHE_DIRS.forEach { name ->
            if (name == "vault_open" && vaultUnlocked) return@forEach
            if (name == "fullbackup" && jobRunning) return@forEach
            total += File(cache, name).dirSize()
        }
        cache.listFiles()?.forEach { file ->
            val plainFile = file.isFile &&
                PrivacySweep.CACHE_FILE_PREFIXES.any { file.name.startsWith(it) }
            val plainDir = file.isDirectory &&
                PrivacySweep.CACHE_DIR_PREFIXES.any { file.name.startsWith(it) }
            val backupTemp = PrivacySweep.BACKUP_CACHE_PREFIXES.any { file.name.startsWith(it) }
            when {
                plainFile -> total += file.length()
                plainDir -> total += file.dirSize()
                backupTemp && !jobRunning ->
                    total += if (file.isDirectory) file.dirSize() else file.length()
            }
        }
        val documents = File(cache, "documents")
        if (documents.isDirectory) {
            documents.listFiles()?.forEach { child ->
                if (child.name != "share") {
                    total += if (child.isDirectory) child.dirSize() else child.length()
                }
            }
        }
        return total
    }

    /**
     * FX1 critic: the bytes the safe sweep would actually delete RIGHT NOW. Entries that are
     * still in use, or from the last 24 hours, stay in the total but are not removable. This
     * mirrors PrivacySweep's age gate so the row can never say "no removable files" while a
     * cleanup would in fact free bytes (or the reverse).
     */
    private fun removableTemporaryBytes(): Long {
        val cache = cacheDir ?: return 0L
        val jobRunning = runCatching { PrivacySweep.backupRunning() }.getOrDefault(false)
        val vaultUnlocked = runCatching {
            com.scanwhirl.app.store.vault.VaultSession.isUnlocked()
        }.getOrDefault(false)
        val now = System.currentTimeMillis()
        var total = 0L
        PrivacySweep.CACHE_DIRS.forEach { name ->
            if (name == "vault_open" && vaultUnlocked) return@forEach
            if (name == "fullbackup" && jobRunning) return@forEach
            val dir = File(cache, name)
            if (name in PrivacySweep.CAPTURE_CACHE_DIRS) {
                dir.listFiles()?.forEach { entry ->
                    if (now - entry.lastModified() > PrivacySweep.CAPTURE_CACHE_MAX_AGE_MS) {
                        total += if (entry.isDirectory) entry.dirSize() else entry.length()
                    }
                }
            } else {
                total += dir.dirSize()
            }
        }
        cache.listFiles()?.forEach { file ->
            val plainFile = file.isFile &&
                PrivacySweep.CACHE_FILE_PREFIXES.any { file.name.startsWith(it) }
            val plainDir = file.isDirectory &&
                PrivacySweep.CACHE_DIR_PREFIXES.any { file.name.startsWith(it) }
            val backupTemp = PrivacySweep.BACKUP_CACHE_PREFIXES.any { file.name.startsWith(it) }
            when {
                plainFile -> total += file.length()
                plainDir -> total += file.dirSize()
                backupTemp && !jobRunning ->
                    total += if (file.isDirectory) file.dirSize() else file.length()
            }
        }
        val documents = File(cache, "documents")
        if (documents.isDirectory) {
            documents.listFiles()?.forEach { child ->
                if (child.name != "share") {
                    total += if (child.isDirectory) child.dirSize() else child.length()
                }
            }
        }
        return total
    }

    private fun refreshStorageSizes() {
        lifecycleScope.launch {
            val sizes = withContext(Dispatchers.IO) {
                storageThreadProbe?.invoke(Thread.currentThread().name)
                Triple(
                    File(filesDir, "documents").dirSize(),
                    temporaryBytes(),
                    removableTemporaryBytes()
                )
            }
            binding.subLibrarySize.text = SizeUnits.format(sizes.first)
            binding.subTempSize.text = SizeUnits.format(sizes.second)
            binding.subTempRemovable.text =
                getString(R.string.cz_storage_removable, SizeUnits.format(sizes.third))
        }
    }

    /** The only delete this row performs is the same safe sweep used at cold start. */
    private fun clearTemporaryFiles() {
        binding.rowClearTemp.isEnabled = false
        lifecycleScope.launch {
            val freed = withContext(Dispatchers.IO) {
                storageThreadProbe?.invoke(Thread.currentThread().name)
                val before = temporaryBytes()
                runCatching { PrivacySweep.run(applicationContext) }.getOrDefault(0)
                val after = temporaryBytes()
                (before - after).coerceAtLeast(0L)
            }
            withContext(Dispatchers.Main) {
                binding.storageReceipt.text = if (freed > 0L) {
                    getString(R.string.cdf1_storage_cleared, SizeUnits.format(freed))
                } else {
                    getString(R.string.cdf1_storage_nothing)
                }
                binding.rowClearTemp.isEnabled = true
                // FX1: recompute BOTH numbers from the disk after the sweep, so a total that
                // is still nonzero is never printed next to "already empty".
                refreshStorageSizes()
            }
        }
    }

    // ---- Lane Z: Rate and feedback ------------------------------------------

    /**
     * Permission-free fallback for a sideload/demo build or when the In-App Review
     * API cannot reach Play. Never uses INTERNET in this app's own process.
     */
    internal fun openPlayListing() {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
        val web = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
        )
        try {
            if (market.resolveActivity(packageManager) != null) {
                startActivity(market)
                return
            }
        } catch (_: Exception) {
        }
        try {
            if (web.resolveActivity(packageManager) != null) {
                startActivity(web)
                return
            }
        } catch (_: Exception) {
        }
        // No resolver match (sideload/demo or a stripped test environment): still hand
        // the https URL to the system. A real device with a browser opens it; a device
        // with neither throws ActivityNotFoundException, which we swallow silently.
        try {
            startActivity(web)
        } catch (_: Exception) {
        }
    }

    private fun rateScanWhirl() {
        val injected = reviewLauncher
        if (injected != null) {
            injected(this)
            return
        }
        try {
            val manager = com.google.android.play.core.review.ReviewManagerFactory.create(this)
            manager.requestReviewFlow().addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    // The API never reports whether the dialog was actually shown; do not
                    // present a success/failure dialog of our own (Play guidance).
                    manager.launchReviewFlow(this, task.result)
                } else {
                    openPlayListing()
                }
            }
        } catch (_: Throwable) {
            openPlayListing()
        }
    }

    /** Null while the support address is still the placeholder. */
    internal fun buildFeedbackIntent(): Intent? {
        val email = supportEmail()
        if (email == PLACEHOLDER_SUPPORT_EMAIL) return null
        return Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email"))
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
            .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.cz_feedback_subject))
    }

    private fun sendFeedback() {
        val intent = buildFeedbackIntent() ?: return
        if (intent.resolveActivity(packageManager) == null) return
        startActivity(Intent.createChooser(intent, getString(R.string.cz_feedback)))
    }

    private fun openPrivacy() {
        try {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://cybium.github.io/scanwhirl-privacy/")
                )
            )
        } catch (_: Exception) {
            Toast.makeText(this, R.string.privacy_open_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun chooseTheme() {
        // One canonical order, shared with Me: System / Light / Dark.
        val values = arrayOf(Prefs.THEME_SYSTEM, Prefs.THEME_LIGHT, Prefs.THEME_DARK)
        val labels = arrayOf(
            getString(R.string.theme_system),
            getString(R.string.theme_light),
            getString(R.string.theme_dark)
        )
        val checked = values.indexOf(Prefs.themeMode(this)).coerceAtLeast(0)
        com.scanwhirl.app.ui.kit.SwSheet.builder(this)
            .setTitle(R.string.settings_theme)
            .setOptions(labels.toList(), checked) { which, _ ->
                Prefs.setThemeMode(this, values[which])
                AppCompatDelegate.setDefaultNightMode(Prefs.nightMode(values[which]))
                updateSummaries()
            }
            .setSecondary(android.R.string.cancel)
            .build()
            .show()
    }

    private fun chooseFont() {
        val values = arrayOf(Prefs.FONT_DEFAULT, Prefs.FONT_SERIF, Prefs.FONT_MONO)
        val labels = arrayOf(
            getString(R.string.font_default),
            getString(R.string.font_serif),
            getString(R.string.font_mono)
        )
        val checked = values.indexOf(Prefs.fontStyle(this)).coerceAtLeast(0)
        com.scanwhirl.app.ui.kit.SwSheet.builder(this)
            .setTitle(R.string.settings_font)
            .setOptions(labels.toList(), checked) { which, _ ->
                Prefs.setFontStyle(this, values[which])
                recreate()
            }
            .setSecondary(android.R.string.cancel)
            .build()
            .show()
    }

    private fun chooseLanguage() {
        // The app is translated into English and Hindi only; offering other languages showed
        // English anyway, so they are not listed.
        val tags = arrayOf(
            com.scanwhirl.app.util.AppLanguage.PHONE,
            com.scanwhirl.app.util.AppLanguage.ENGLISH,
            com.scanwhirl.app.util.AppLanguage.HINDI
        )
        val labels = tags.map { if (it.isEmpty()) getString(R.string.language_system) else com.scanwhirl.app.util.AppLanguage.label(it) }
        // Same source as the summary: AppCompat's application locales win, our saved tag is
        // only the fallback, so a language set outside the app still shows the right tick.
        val checked = tags.indexOf(com.scanwhirl.app.util.AppLanguage.currentTag(this)).coerceAtLeast(0)
        com.scanwhirl.app.ui.kit.SwSheet.builder(this)
            .setTitle(R.string.settings_language)
            .setOptions(labels, checked) { which, _ ->
                applyLanguage(tags[which.coerceIn(0, tags.lastIndex)])
            }
            .setSecondary(android.R.string.cancel)
            .build()
            .show()
    }

    private fun applyLanguage(tag: String) {
        com.scanwhirl.app.util.AppLanguage.apply(this, tag)
        updateSummaries()
    }

    private fun chooseViewer() {
        val values = arrayOf(Prefs.VIEWER_IN_APP, Prefs.VIEWER_SYSTEM)
        val labels = arrayOf(getString(R.string.viewer_in_app), getString(R.string.viewer_system))
        val checked = values.indexOf(Prefs.defaultViewer(this)).coerceAtLeast(0)
        com.scanwhirl.app.ui.kit.SwSheet.builder(this)
            .setTitle(R.string.settings_default_viewer)
            .setOptions(labels.toList(), checked) { which, _ ->
                Prefs.setDefaultViewer(this, values[which])
                updateSummaries()
            }
            .setSecondary(android.R.string.cancel)
            .build()
            .show()
    }

    private fun showAbout() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        } catch (_: Exception) {
            "1.0"
        }
        val view = com.scanwhirl.app.databinding.DialogAboutBinding
            .inflate(layoutInflater)
        view.aboutText.text = getString(R.string.about_message, version)
        com.scanwhirl.app.ui.kit.SwSheet.builder(this)
            .setTitle(R.string.about_title)
            .setContent(view.root)
            .setPrimary(android.R.string.ok)
            .build()
            .show()
    }

    companion object {
        // TODO(lead): replace with ScanWhirl's real support address before release.
        // Until then the Send feedback row stays hidden — no invented real email ships.
        const val PLACEHOLDER_SUPPORT_EMAIL = "support@example.invalid"
        internal var supportEmailOverride: String? = null
        internal var storageThreadProbe: ((String) -> Unit)? = null
        internal var reviewLauncher: ((android.app.Activity) -> Unit)? = null

        internal fun supportEmail(): String = supportEmailOverride ?: PLACEHOLDER_SUPPORT_EMAIL

        fun intent(context: android.content.Context): Intent =
            Intent(context, SettingsActivity::class.java)
    }
}
