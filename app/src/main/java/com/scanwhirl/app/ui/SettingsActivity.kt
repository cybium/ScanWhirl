package com.scanwhirl.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.scanwhirl.app.R
import com.scanwhirl.app.databinding.ActivitySettingsBinding
import com.scanwhirl.app.util.Prefs

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
            R.id.rowFont, R.id.rowLanguage, R.id.rowOcrLanguage, R.id.rowViewer, R.id.rowPrivacy
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
        val tag = com.scanwhirl.app.util.AppLanguage.currentTag(this)
        val appLanguage = if (tag.isEmpty()) {
            getString(R.string.language_system)
        } else {
            com.scanwhirl.app.util.AppLanguage.label(tag)
        }
        // C1 (2026-11-20 owner): say what the row changes, not only the language name.
        binding.subLanguage.text = getString(R.string.settings_language_summary, appLanguage)
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
                R.string.settings_ocr_language_summary,
                getString(ocrLanguageLabel(com.scanwhirl.app.util.OcrLanguageSettings.get(this)))
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
        fun intent(context: android.content.Context): Intent =
            Intent(context, SettingsActivity::class.java)
    }
}
