package com.scanwhirl.app.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale

/**
 * Dates follow the app language, not the phone. Pinned to API 28 so AppCompat keeps the
 * application locales itself (from 33 it forwards them to the platform LocaleManager, which
 * Robolectric does not simulate); production calls the same API on every version.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppLocaleTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @After
    fun clear() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        Prefs.setLocaleTag(context, "")
    }

    @Test
    fun `phone language keeps the device locale`() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        Prefs.setLocaleTag(context, "")

        assertEquals(AppLanguage.PHONE, AppLanguage.currentTag(context))
        assertEquals(Locale.getDefault(), AppLocale.of(context))
    }

    @Test
    fun `a language chosen in Android per-app settings drives the date locale`() {
        Prefs.setLocaleTag(context, "")
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(AppLanguage.HINDI))

        assertEquals(AppLanguage.HINDI, AppLanguage.currentTag(context))
        assertEquals("hi", AppLocale.of(context).language)
        val date = GregorianCalendar(2026, Calendar.JANUARY, 14).time
        val hindi = SimpleDateFormat("d MMM", AppLocale.of(context)).format(date)
        assertTrue("Hindi app language must not render an English date, got '$hindi'", hindi != "14 Jan")
    }

    @Test
    fun `the saved tag is the fallback and English stays the English locale`() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        Prefs.setLocaleTag(context, AppLanguage.ENGLISH)

        assertEquals(AppLanguage.ENGLISH, AppLanguage.currentTag(context))
        assertEquals("en", AppLocale.of(context).language)
        val date = GregorianCalendar(2026, Calendar.JANUARY, 14).time
        assertEquals("14 Jan", SimpleDateFormat("d MMM", AppLocale.of(context)).format(date))
    }
}
