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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
        // C1 owner 2026-11-20: the summary now adds what the row changes.
        assertSummary { "हिन्दी" }
    }

    @Test
    fun `summary falls back to the saved tag`() {
        Prefs.setLocaleTag(context, "en")
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())

        assertEquals("en", AppLanguage.currentTag(context))
        // C1 owner 2026-11-20: the summary now adds what the row changes.
        assertSummary { "English" }
    }

    @Test
    fun `no choice at all reads as the phone language`() {
        Prefs.setLocaleTag(context, "")
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())

        assertEquals(AppLanguage.PHONE, AppLanguage.currentTag(context))
        // C1 owner 2026-11-20: the summary now adds what the row changes.
        assertSummary { it.getString(R.string.language_system) }
    }

    @Test
    fun `settings shows two differently named language rows`() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val appRow = activity.findViewById<android.view.View>(R.id.rowLanguage)
                val ocrRow = activity.findViewById<android.view.View>(R.id.rowOcrLanguage)
                val appName = activity.getString(R.string.settings_language)
                val ocrName = activity.getString(R.string.settings_ocr_language)
                assertNotNull("the text reading language row exists", ocrRow)
                assertTrue("the two rows must not share a name", appName != ocrName)
                assertTrue(
                    "the app language row is named \"$appName\": ${appRow.contentDescription}",
                    appRow.contentDescription.contains(appName)
                )
                assertTrue(
                    "the text row is named \"$ocrName\": ${ocrRow.contentDescription}",
                    ocrRow.contentDescription.contains(ocrName)
                )
                assertTrue(
                    "the app row summary adds what it changes",
                    appRow.contentDescription.length > appName.length
                )
                assertTrue(
                    "the text row summary adds what it changes",
                    ocrRow.contentDescription.length > ocrName.length
                )
            }
        }
    }

    /** C1 owner 2026-11-20: the exact words the owner chose, in both languages. */
    @Test
    fun `both language rows use the owner words in English and Hindi`() {
        fun strings(tag: String): Context {
            val config = android.content.res.Configuration(context.resources.configuration).apply {
                setLocale(java.util.Locale(tag, if (tag == "hi") "IN" else "US"))
            }
            return context.createConfigurationContext(config)
        }
        val en = strings("en")
        val hi = strings("hi")
        assertEquals("App language", en.getString(R.string.settings_language))
        assertEquals("ऐप की भाषा", hi.getString(R.string.settings_language))
        assertEquals("Text reading language", en.getString(R.string.settings_ocr_language))
        assertEquals("लिखावट पढ़ने की भाषा", hi.getString(R.string.settings_ocr_language))
    }

    /**
     * Compares the row with the formatter resolved in the activity's own language, so a
     * Hindi screen is checked against Hindi copy (the application context stays English).
     */
    private fun assertSummary(appLabel: (Context) -> String) {
        var text = ""
        var expected = ""
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                text = activity.findViewById<TextView>(R.id.subLanguage).text.toString()
                expected = activity.getString(R.string.settings_language_summary, appLabel(activity))
            }
        }
        assertEquals(expected, text)
    }

}
