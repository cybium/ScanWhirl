package com.scanwhirl.app.ui

import android.app.Application
import android.content.res.Configuration
import android.graphics.Color
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.util.CategoryRules
import com.scanwhirl.app.util.CategoryStyle
import com.scanwhirl.app.util.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * W8 merge with integration 63d3994d: the two new assertions integration added to
 * HomeSlice2Test / UiSmokeTest are kept here because those legacy-host test files were
 * deleted with MainActivity. Same assertions, same intent, no live screen requirements
 * changed by the merge.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class W8IntegrationAssertionsTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        RuntimeEnvironment.setQualifiers("en-rUS-w360dp-h800dp-xhdpi")
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    private fun luminance(c: Int): Double {
        fun ch(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * ch(Color.red(c)) + 0.7152 * ch(Color.green(c)) + 0.0722 * ch(Color.blue(c))
    }

    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    @Test
    fun `category token contrast meets AA and call sites paint the themed token`() {
        fun c(res: Int) = ContextCompat.getColor(app, res)
        val categoryRes = mapOf(
            CategoryRules.STUDY to R.color.cat_study,
            CategoryRules.RECEIPTS to R.color.cat_receipts,
            CategoryRules.IDS to R.color.cat_ids,
            CategoryRules.FORMS to R.color.cat_forms,
            CategoryRules.OTHER to R.color.cat_other
        )
        for ((cat, res) in categoryRes) {
            val tokenRatio = contrast(c(res), c(R.color.rv_bg))
            assertTrue("$cat token contrast $tokenRatio", tokenRatio >= 4.5)
            val painted = CategoryStyle.color(cat, app)
            assertEquals("call sites use the themed $cat token", c(res), painted)
            assertTrue("$cat painted contrast ${contrast(painted, c(R.color.rv_bg))}",
                contrast(painted, c(R.color.rv_bg)) >= 4.5)
        }
    }

    @Test
    fun `follow system applies the system night state under both qualifiers`() {
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            AppCompatDelegate.getDefaultNightMode())
        assertEquals(Prefs.THEME_SYSTEM, Prefs.themeMode(app))
        try {
            for (night in listOf(false, true)) {
                RuntimeEnvironment.setQualifiers(
                    "en-rUS-w360dp-h800dp-${if (night) "night" else "notnight"}-xhdpi"
                )
                val expected = if (night) Configuration.UI_MODE_NIGHT_YES
                else Configuration.UI_MODE_NIGHT_NO
                assertEquals("system night=$night is applied to the configuration",
                    expected,
                    app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
            }
        } finally {
            RuntimeEnvironment.setQualifiers("en-rUS-w360dp-h800dp-xhdpi")
        }
    }
}
