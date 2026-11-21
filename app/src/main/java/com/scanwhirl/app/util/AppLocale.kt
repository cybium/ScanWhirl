package com.scanwhirl.app.util

import android.content.Context
import java.util.Locale

/**
 * The locale for user-visible dates, sizes and other platform-formatted values.
 *
 * Dates must follow the app language, not the phone: a Hindi screen shows Hindi month names and
 * numerals as the platform formats them. "Phone language" has no tag, so it keeps the device
 * default. [AppLanguage.currentTag] is the single source of truth (AppCompat's application locales
 * first, the saved tag as fallback), so this agrees with Settings and the Home A/अ chip.
 */
object AppLocale {

    /**
     * The locale a date shown on this screen should be formatted with.
     * `PHONE` -> [Locale.getDefault]; `hi` -> `Locale.forLanguageTag("hi")`; `en` -> English.
     */
    fun of(context: Context): Locale {
        val tag = AppLanguage.currentTag(context)
        return if (tag == AppLanguage.PHONE) Locale.getDefault() else Locale.forLanguageTag(tag)
    }
}
