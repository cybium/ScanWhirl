package com.scanwhirl.app.ui.kit

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.ProGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * v2 UI: Pro accent themes — Cyan (default) and Gold (Pro only).
 * The kit's single colour entry point must recolour in Gold, and the launcher
 * alias pair must carry exactly one enabled icon.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SwThemeTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        Prefs.setAccent(app, Prefs.ACCENT_CYAN)
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null) // test build: Pro
    }

    @After
    fun tearDown() {
        Prefs.setAccent(app, Prefs.ACCENT_CYAN)
        ProGate.setPro(app, false)
        ProGate.setTestOverride(app, null)
        SwTheme.applyLauncherIcon(app, gold = false)
    }

    @Test
    fun `Gold recolours the kit accent tokens for Pro`() {
        SwTheme.applyLauncherIcon(app, gold = false)
        val cyan = SwStyle.color(app, R.color.cyan_primary)
        assertEquals(app.getColor(R.color.cyan_primary), cyan)

        Prefs.setAccent(app, Prefs.ACCENT_GOLD)
        assertTrue("Pro + Gold is active", SwTheme.isGold(app))
        val gold = SwStyle.color(app, R.color.cyan_primary)
        assertEquals("cyan primary becomes gold", app.getColor(R.color.gold_pro_primary), gold)
        assertNotEquals("the accent really changed", cyan, gold)
        assertEquals(
            "gold tokens stay gold",
            app.getColor(R.color.gold_pro_primary),
            SwStyle.color(app, R.color.gold_pro_primary)
        )
        assertEquals(
            "the cyan hairline becomes the gold hairline",
            app.getColor(R.color.sw_gold_hair),
            SwStyle.color(app, R.color.sw_cyan_hair)
        )
    }

    @Test
    fun `a Free build never shows Gold even with the pref set`() {
        Prefs.setAccent(app, Prefs.ACCENT_GOLD)
        ProGate.setTestOverride(app, false)
        assertEquals(
            "Free falls back to Cyan",
            false,
            SwTheme.isGold(app)
        )
        assertEquals(
            app.getColor(R.color.cyan_primary),
            SwStyle.color(app, R.color.cyan_primary)
        )
    }

    @Test
    fun `the launcher alias pair enables exactly the chosen icon`() {
        SwTheme.applyLauncherIcon(app, gold = false)
        assertEquals("Cyan icon", "com.scanwhirl.app.ui.shell.ShellActivityCyan",
            SwTheme.enabledLauncherAlias(app))

        SwTheme.applyLauncherIcon(app, gold = true)
        assertEquals("Gold icon", "com.scanwhirl.app.ui.shell.ShellActivityGold",
            SwTheme.enabledLauncherAlias(app))

        SwTheme.applyLauncherIcon(app, gold = false)
        assertEquals("back to Cyan", "com.scanwhirl.app.ui.shell.ShellActivityCyan",
            SwTheme.enabledLauncherAlias(app))
    }
}
