package com.scanwhirl.app.ui

import android.content.Context
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.util.AppLanguage
import com.scanwhirl.app.util.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The App language row must name the language the UI is actually in, even when that
 * language was chosen outside the app (Android's per-app language screen / adb), where
 * only AppCompat's application locales change and [Prefs.localeTag] stays empty.
 *
 * Pinned to API 28 so AppCompat keeps the application locales itself (from 33 it forwards
 * them to the platform LocaleManager, which Robolectric does not simulate). Our production
 * code calls the same AppCompat API on every version.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LanguageSummaryTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @After
    fun clearAppLocales() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    }

    @Test
    fun `summary shows Hindi when only the app locales say so`() {
        Prefs.setLocaleTag(context, "")
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("hi"))

        assertEquals("hi", AppLanguage.currentTag(context))
        assertEquals("हिन्दी", summary())
    }

    @Test
    fun `summary falls back to the saved tag`() {
        Prefs.setLocaleTag(context, "en")
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())

        assertEquals("en", AppLanguage.currentTag(context))
        assertEquals("English", summary())
    }

    @Test
    fun `no choice at all reads as the phone language`() {
        Prefs.setLocaleTag(context, "")
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())

        assertEquals(AppLanguage.PHONE, AppLanguage.currentTag(context))
        assertEquals(context.getString(R.string.language_system), summary())
    }

    private fun summary(): String {
        var text = ""
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                text = activity.findViewById<TextView>(R.id.subLanguage).text.toString()
            }
        }
        return text
    }
}
