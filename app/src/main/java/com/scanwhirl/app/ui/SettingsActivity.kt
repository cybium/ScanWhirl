package com.scanwhirl.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.scanwhirl.app.R
import com.scanwhirl.app.databinding.ActivitySettingsBinding
import com.scanwhirl.app.util.Prefs

class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding

    /** L2: the "Text language" summary (built programmatically in [addTextLanguageRow]). */
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

        // test25 Phase 5A / D1: Orbital-only dark theme — the row stays in the
        // XML (locked ID) but is hidden, and the summary is pinned to the one
        // theme we ship. The row lives inside the grouped Appearance card, so
        // hiding it leaves no empty card behind.
        binding.rowTheme.visibility = android.view.View.GONE
        binding.subTheme.text = "Orbital Dark"

        binding.rowTheme.setOnClickListener { chooseTheme() }
        binding.rowFont.setOnClickListener { chooseFont() }
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
        hideSection(binding.rowAbout)
        hideSection(binding.rowTrash)
        binding.rowAbout.setOnClickListener { showAbout() }
        binding.rowTrash.setOnClickListener { startActivity(TrashActivity.intent(this)) }

        // L2: OCR text language - the same setting the Text screen's language chip changes.
        addTextLanguageRow()

        // SECURITY_AUDIT 2026-11-15: screenshot policy for ordinary documents.
        addBlockScreenshotsRow()

        // Pro accent theme: Cyan (default) or Gold.
        addAccentRow()

        updateSummaries()
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
                setText(R.string.settings_ocr_language)
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
            setPadding((16 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt())
        }
        val texts = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
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
                setText(R.string.settings_block_screenshots_note)
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
            isChecked = Prefs.blockDocScreenshots(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setBlockDocScreenshots(this@SettingsActivity, checked)
            }
        }
        row.addView(toggle)
        val index = parent.indexOfChild(binding.rowPrivacy)
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
        if (index >= 0) {
            parent.addView(divider, index + 1)
            parent.addView(row, index + 2)
        } else {
            parent.addView(divider)
            parent.addView(row)
        }
    }

    private lateinit var subAccent: android.widget.TextView

    /**
     * Settings > Look: Cyan (default) or Gold (Pro). Free users see the Gold
     * option with a PRO tag and land on the Pro screen; the choice is stored only
     * when allowed, and the launcher alias follows it.
     */
    private fun addAccentRow() {
        val parent = binding.rowFont.parent as? android.view.ViewGroup ?: return
        val d = resources.displayMetrics.density
        val row = android.widget.LinearLayout(this).apply {
            id = R.id.rowAccent
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
            setPadding((16 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt())
            setOnClickListener { chooseAccent() }
        }
        val texts = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        texts.addView(
            android.widget.TextView(this).apply {
                setText(R.string.settings_accent)
                textSize = 15f
                setTextColor(
                    com.scanwhirl.app.ui.kit.SwStyle.color(
                        this@SettingsActivity, R.color.text_hologram_hi
                    )
                )
            }
        )
        subAccent = android.widget.TextView(this).apply {
            id = R.id.subAccent
            textSize = 12.5f
            setPadding(0, (2 * d).toInt(), 0, 0)
            setTextColor(
                com.scanwhirl.app.ui.kit.SwStyle.color(
                    this@SettingsActivity, R.color.text_hologram_mid
                )
            )
        }
        texts.addView(subAccent)
        row.addView(
            texts,
            android.widget.LinearLayout.LayoutParams(
                0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        )
        row.addView(
            android.widget.ImageView(this).apply {
                setImageResource(R.drawable.ic_sym_chevron_right)
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                imageTintList = android.content.res.ColorStateList.valueOf(
                    com.scanwhirl.app.ui.kit.SwStyle.color(
                        this@SettingsActivity, R.color.text_hologram_dim
                    )
                )
            },
            android.widget.LinearLayout.LayoutParams((20 * d).toInt(), (20 * d).toInt())
        )
        val index = parent.indexOfChild(binding.rowFont)
        parent.addView(row, if (index >= 0) index + 1 else parent.childCount)
        updateAccentSummary()
    }

    private fun updateAccentSummary() {
        if (!::subAccent.isInitialized) return
        subAccent.text = getString(
            if (com.scanwhirl.app.ui.kit.SwTheme.isGold(this)) R.string.accent_gold
            else R.string.accent_cyan
        )
    }

    private fun chooseAccent() {
        val goldChosen = com.scanwhirl.app.ui.kit.SwTheme.isGold(this)
        val labels = listOf(
            getString(R.string.accent_cyan),
            getString(R.string.accent_gold) + "  ·  PRO"
        )
        com.scanwhirl.app.ui.kit.SwSheet.builder(this)
            .setTitle(R.string.settings_accent)
            .setOptions(labels, if (goldChosen) 1 else 0) { which, _ ->
                if (which != 1) {
                    applyAccent(gold = false)
                } else if (!com.scanwhirl.app.util.ProGate.isPro(this)) {
                    // Free: the gold option is visible with its PRO tag and routes
                    // through the central Pro gate.
                    startActivity(
                        Intent(this, com.scanwhirl.app.ui.shell.ShellActivity::class.java)
                            .putExtra(com.scanwhirl.app.ui.shell.ShellActivity.EXTRA_OPEN_PRO, true)
                    )
                } else {
                    confirmGold()
                }
            }
            .setSecondary(android.R.string.cancel)
            .build()
            .show()
    }

    private fun confirmGold() {
        com.scanwhirl.app.ui.kit.SwSheet.builder(this)
            .setTitle(R.string.accent_gold)
            .setMessage(R.string.accent_gold_warning)
            .setPrimary(R.string.accent_gold_use) { applyAccent(gold = true) }
            .setSecondary(android.R.string.cancel)
            .build()
            .show()
    }

    private fun applyAccent(gold: Boolean) {
        Prefs.setAccent(
            this,
            if (gold) Prefs.ACCENT_GOLD else Prefs.ACCENT_CYAN
        )
        com.scanwhirl.app.ui.kit.SwTheme.applyLauncherIcon(this, gold)
        updateAccentSummary()
        // Rebuild every kit surface in the new accent.
        recreate()
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
            .setTitle(R.string.settings_ocr_language)
            .setMessage(R.string.ocr_lang_note)
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
        // M07: Trash count, read off the main thread.
        val repo = com.scanwhirl.app.store.ScanRepository(this)
        lifecycleScope.launch {
            val n = withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { repo.trashCount() }.getOrDefault(0) }
            binding.subTrash.text = if (n == 0) getString(R.string.trash_settings_empty)
            else resources.getQuantityString(R.plurals.trash_count, n, n)
        }
    }

    private fun updateSummaries() {
        // subTheme is not updated here: D1 pins it to "Orbital Dark" in onCreate.
        binding.subFont.setText(
            when (Prefs.fontStyle(this)) {
                Prefs.FONT_SERIF -> R.string.font_serif
                Prefs.FONT_MONO -> R.string.font_mono
                else -> R.string.font_default
            }
        )
        val tag = Prefs.localeTag(this)
        binding.subLanguage.text = if (tag.isEmpty()) {
            getString(R.string.language_system)
        } else {
            languageLabel(tag)
        }
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
            subOcrLanguage.setText(
                ocrLanguageLabel(com.scanwhirl.app.util.OcrLanguageSettings.get(this))
            )
        }
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
        val values = arrayOf(Prefs.THEME_LIGHT, Prefs.THEME_DARK, Prefs.THEME_SYSTEM)
        val labels = arrayOf(
            getString(R.string.theme_light),
            getString(R.string.theme_dark),
            getString(R.string.theme_system)
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
        val tags = arrayOf(
            "", "en", "hi", "ur", "es", "fr", "de", "pt", "ar", "bn", "ru", "zh"
        )
        val labels = tags.map { if (it.isEmpty()) getString(R.string.language_system) else languageLabel(it) }
            .toMutableList()
        labels.add(getString(R.string.language_more))
        val checked = tags.indexOf(Prefs.localeTag(this)).coerceAtLeast(0)
        com.scanwhirl.app.ui.kit.SwSheet.builder(this)
            .setTitle(R.string.settings_language)
            .setOptions(labels.toList(), checked) { which, _ ->
                if (which >= tags.size) {
                    openSystemLanguageSettings()
                } else {
                    applyLanguage(tags[which])
                }
            }
            .setSecondary(android.R.string.cancel)
            .build()
            .show()
    }

    private fun applyLanguage(tag: String) {
        Prefs.setLocaleTag(this, tag)
        AppCompatDelegate.setApplicationLocales(
            if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
            else LocaleListCompat.forLanguageTags(tag)
        )
        updateSummaries()
    }

    private fun openSystemLanguageSettings() {
        try {
            val intent = if (Build.VERSION.SDK_INT >= 33) {
                Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.parse("package:$packageName"))
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            }
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
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

    private fun languageLabel(tag: String): String = when (tag) {
        "en" -> "English"
        "hi" -> "हिन्दी"
        "ur" -> "اردو"
        "es" -> "Español"
        "fr" -> "Français"
        "de" -> "Deutsch"
        "pt" -> "Português"
        "ar" -> "العربية"
        "bn" -> "বাংলা"
        "ru" -> "Русский"
        "zh" -> "中文"
        else -> tag
    }

    companion object {
        fun intent(context: android.content.Context): Intent =
            Intent(context, SettingsActivity::class.java)
    }
}
