package com.scanwhirl.app.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * The app's UI language. ScanWhirl ships English and Hindi only, so those are the only choices
 * (plus "phone language"). Used by Settings and by the Home language chip, which exists so a
 * Hindi reader never has to find an English "Settings" row to switch.
 */
object AppLanguage {
    const val PHONE = ""
    const val ENGLISH = "en"
    const val HINDI = "hi"

    /** Labels are always written in their own script so anyone can find their language. */
    fun label(tag: String): String = when (tag) {
        ENGLISH -> "English"
        HINDI -> "हिन्दी"
        else -> ""
    }

    /** True when the screen is currently drawn in Hindi (explicit choice or phone language). */
    fun showingHindi(context: Context): Boolean =
        context.resources.configuration.locales[0].language == HINDI

    fun apply(context: Context, tag: String) {
        Prefs.setLocaleTag(context.applicationContext, tag)
        AppCompatDelegate.setApplicationLocales(
            if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
            else LocaleListCompat.forLanguageTags(tag)
        )
    }
}
