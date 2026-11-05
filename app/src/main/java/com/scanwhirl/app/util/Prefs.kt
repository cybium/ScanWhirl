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

    fun showGreeting(c: Context): Boolean = sp(c).getBoolean(KEY_GREETING, true)
    fun setShowGreeting(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_GREETING, v).apply()

    fun showWallpaper(c: Context): Boolean = sp(c).getBoolean(KEY_WALLPAPER_ON, false)
    fun setShowWallpaper(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_WALLPAPER_ON, v).apply()
    fun wallpaperUri(c: Context): String = sp(c).getString(KEY_WALLPAPER_URI, "") ?: ""
    fun setWallpaperUri(c: Context, v: String) = sp(c).edit().putString(KEY_WALLPAPER_URI, v).apply()

    fun pickedPdfs(c: Context): Set<String> =
        sp(c).getStringSet(KEY_PICKED, emptySet()) ?: emptySet()

    fun addPickedPdf(c: Context, uri: String) {
        sp(c).edit().putStringSet(KEY_PICKED, pickedPdfs(c) + uri).apply()
    }
}
