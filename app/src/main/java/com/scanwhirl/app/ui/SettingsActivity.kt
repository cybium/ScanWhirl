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
import com.scanwhirl.app.R
import com.scanwhirl.app.databinding.ActivitySettingsBinding
import com.scanwhirl.app.util.Prefs

class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding

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
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.rowTheme.setOnClickListener { chooseTheme() }
        binding.rowFont.setOnClickListener { chooseFont() }
        binding.rowGreeting.setOnClickListener {
            binding.switchGreeting.isChecked = !binding.switchGreeting.isChecked
        }
        binding.switchGreeting.isChecked = Prefs.showGreeting(this)
        binding.switchGreeting.setOnCheckedChangeListener { _, checked ->
            Prefs.setShowGreeting(this, checked)
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
        binding.rowPro.setOnClickListener { showProDialog() }
        binding.rowLanguage.setOnClickListener { chooseLanguage() }
        binding.rowViewer.setOnClickListener { chooseViewer() }
        binding.rowAbout.setOnClickListener { showAbout() }

        updateSummaries()
    }

    private fun updateSummaries() {
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

    private fun showProDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_pro_title)
            .setMessage(R.string.settings_pro_message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun chooseTheme() {
        val values = arrayOf(Prefs.THEME_LIGHT, Prefs.THEME_DARK, Prefs.THEME_SYSTEM)
        val labels = arrayOf(
            getString(R.string.theme_light),
            getString(R.string.theme_dark),
            getString(R.string.theme_system)
        )
        val checked = values.indexOf(Prefs.themeMode(this)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_theme)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                Prefs.setThemeMode(this, values[which])
                AppCompatDelegate.setDefaultNightMode(Prefs.nightMode(values[which]))
                dialog.dismiss()
                updateSummaries()
            }
            .setNegativeButton(android.R.string.cancel, null)
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
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_font)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                Prefs.setFontStyle(this, values[which])
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton(android.R.string.cancel, null)
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
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_language)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                if (which >= tags.size) {
                    openSystemLanguageSettings()
                } else {
                    applyLanguage(tags[which])
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
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
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_default_viewer)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                Prefs.setDefaultViewer(this, values[which])
                dialog.dismiss()
                updateSummaries()
            }
            .setNegativeButton(android.R.string.cancel, null)
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
        AlertDialog.Builder(this)
            .setTitle(R.string.about_title)
            .setView(view.root)
            .setPositiveButton(android.R.string.ok, null)
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
