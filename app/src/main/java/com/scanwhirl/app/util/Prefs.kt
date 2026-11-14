package com.scanwhirl.app.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/** Small persisted app settings (theme, font, language, viewer, sort). */
object Prefs {
    private const val FILE = "scanwhirl_prefs"

    private const val KEY_THEME = "theme_mode"
    private const val KEY_FONT = "font_style"
    private const val KEY_VIEWER = "default_viewer"
    private const val KEY_SORT = "sort_mode"
    private const val KEY_LOCALE = "locale_tag"
    private const val KEY_GREETING = "show_greeting"
    private const val KEY_WALLPAPER_URI = "wallpaper_uri"
    private const val KEY_WALLPAPER_ON = "wallpaper_on"
    private const val KEY_PICKED = "picked_pdfs"
    private const val KEY_INDEX_BACKFILL_DONE = "index_backfill_done"

    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"
    const val THEME_SYSTEM = "system"

    const val FONT_DEFAULT = "default"
    const val FONT_SERIF = "serif"
    const val FONT_MONO = "mono"

    const val VIEWER_IN_APP = "in_app"
    const val VIEWER_SYSTEM = "system"

    const val SORT_DATE = "date"
    const val SORT_MODIFIED = "modified"
    const val SORT_NAME = "name"
    const val SORT_CATEGORY = "category"

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun themeMode(c: Context): String = sp(c).getString(KEY_THEME, THEME_DARK) ?: THEME_DARK
    fun setThemeMode(c: Context, v: String) = sp(c).edit().putString(KEY_THEME, v).apply()

    fun nightMode(value: String): Int = when (value) {
        THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        THEME_SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        else -> AppCompatDelegate.MODE_NIGHT_YES
    }

    fun fontStyle(c: Context): String = sp(c).getString(KEY_FONT, FONT_DEFAULT) ?: FONT_DEFAULT
    fun setFontStyle(c: Context, v: String) = sp(c).edit().putString(KEY_FONT, v).apply()

    fun defaultViewer(c: Context): String =
        sp(c).getString(KEY_VIEWER, VIEWER_IN_APP) ?: VIEWER_IN_APP
    fun setDefaultViewer(c: Context, v: String) = sp(c).edit().putString(KEY_VIEWER, v).apply()

    fun sortMode(c: Context): String = sp(c).getString(KEY_SORT, SORT_DATE) ?: SORT_DATE
    fun setSortMode(c: Context, v: String) = sp(c).edit().putString(KEY_SORT, v).apply()

    fun localeTag(c: Context): String = sp(c).getString(KEY_LOCALE, "") ?: ""
    fun setLocaleTag(c: Context, v: String) = sp(c).edit().putString(KEY_LOCALE, v).apply()

    /** Home status line (count · size). Opt-in since Home slice 2: no greeting noise by default. */
    // M08: Status line and Wallpaper are retired (Settings no longer offers them), so an
    // earlier "on" can't leave someone stuck with a feature they can no longer switch off.
    @Suppress("UNUSED_PARAMETER")
    fun showGreeting(c: Context): Boolean = false
    fun setShowGreeting(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_GREETING, v).apply()

    fun showWallpaper(c: Context): Boolean = sp(c).getBoolean(KEY_WALLPAPER_ON, false)
    fun setShowWallpaper(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_WALLPAPER_ON, v).apply()
    fun wallpaperUri(c: Context): String = sp(c).getString(KEY_WALLPAPER_URI, "") ?: ""
    fun setWallpaperUri(c: Context, v: String) = sp(c).edit().putString(KEY_WALLPAPER_URI, v).apply()

    /** Auto-enhance on scan: ON uses Google's SCANNER_MODE_FULL, OFF uses BASE. */
    private const val KEY_AUTO_ENHANCE = "auto_enhance_scan"

    // M08: default ON (Google FULL) — chosen by the owner after a same-page device comparison.
    // An explicit earlier choice of OFF is kept.
    fun autoEnhanceScan(c: Context): Boolean = sp(c).getBoolean(KEY_AUTO_ENHANCE, true)
    fun setAutoEnhanceScan(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_AUTO_ENHANCE, v).apply()

    fun pickedPdfs(c: Context): Set<String> =
        sp(c).getStringSet(KEY_PICKED, emptySet()) ?: emptySet()

    fun addPickedPdf(c: Context, uri: String) {
        sp(c).edit().putStringSet(KEY_PICKED, pickedPdfs(c) + uri).apply()
    }

    /** True once the one-time library OCR backfill has been enqueued. Default false. */
    fun indexBackfillDone(c: Context): Boolean =
        sp(c).getBoolean(KEY_INDEX_BACKFILL_DONE, false)
    fun setIndexBackfillDone(c: Context, v: Boolean) =
        sp(c).edit().putBoolean(KEY_INDEX_BACKFILL_DONE, v).apply()

    private const val KEY_VAULT_PIN = "vault_pin_sha256"

    private const val KEY_AUTO_COPY = "auto_copy_to_documents"

    /** T4.7 (N10): also copy the finished PDF into Documents/ScanWhirl. Off by default. */
    fun autoCopyToDocuments(c: Context): Boolean = sp(c).getBoolean(KEY_AUTO_COPY, false)
    fun setAutoCopyToDocuments(c: Context, v: Boolean) =
        sp(c).edit().putBoolean(KEY_AUTO_COPY, v).apply()

    private const val KEY_MAKE_SEARCHABLE = "make_searchable"

    /** Make PDFs searchable: embed a hidden OCR text layer. Default ON. */
    fun makeSearchable(c: Context): Boolean = sp(c).getBoolean(KEY_MAKE_SEARCHABLE, true)
    fun setMakeSearchable(c: Context, v: Boolean) =
        sp(c).edit().putBoolean(KEY_MAKE_SEARCHABLE, v).apply()

    fun hasVaultPin(c: Context): Boolean =
        !sp(c).getString(KEY_VAULT_PIN, null).isNullOrBlank()

    fun setVaultPin(c: Context, pin: String) {
        require(pin.length == 4 && pin.all { it.isDigit() }) { "PIN must be 4 digits" }
        sp(c).edit().putString(KEY_VAULT_PIN, sha256("scanwhirl_vault:$pin")).apply()
    }

    fun verifyVaultPin(c: Context, pin: String): Boolean {
        val stored = sp(c).getString(KEY_VAULT_PIN, null) ?: return false
        return stored == sha256("scanwhirl_vault:$pin")
    }

    internal fun sha256(input: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    // ---- v2 Library sort (03_tools_hub) ----------------------------------

    private const val KEY_LIB_SORT = "library_sort"
    const val LIB_NEWEST = "newest"
    const val LIB_OLDEST = "oldest"
    const val LIB_NAME_ASC = "name_asc"
    const val LIB_NAME_DESC = "name_desc"
    const val LIB_SIZE = "size"

    fun librarySort(c: Context): String = sp(c).getString(KEY_LIB_SORT, LIB_NEWEST) ?: LIB_NEWEST
    fun setLibrarySort(c: Context, v: String) = sp(c).edit().putString(KEY_LIB_SORT, v).apply()

    // ---- gallery import: auto-crop or keep as-is (01_home_A) ----------------

    private const val KEY_GALLERY_CROP = "gallery_auto_crop"
    fun galleryAutoCrop(c: Context): Boolean = sp(c).getBoolean(KEY_GALLERY_CROP, true)
    fun setGalleryAutoCrop(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_GALLERY_CROP, v).apply()
}
