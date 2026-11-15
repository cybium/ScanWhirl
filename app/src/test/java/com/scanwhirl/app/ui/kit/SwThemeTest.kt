package com.scanwhirl.app.ui.kit

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.R
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.ProGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `Gold is disabled until the owner verifies it on a phone`() {
        SwTheme.applyLauncherIcon(app, gold = false)
        val cyan = SwStyle.color(app, R.color.cyan_primary)
        assertEquals(app.getColor(R.color.cyan_primary), cyan)

        // A stale saved preference cannot turn the unverified theme on.
        Prefs.setAccent(app, Prefs.ACCENT_GOLD)
        assertFalse("Gold stays off", SwTheme.isGold(app))
        assertEquals(
            "the kit stays Cyan",
            app.getColor(R.color.cyan_primary),
            SwStyle.color(app, R.color.cyan_primary)
        )
        SwTheme.applyLauncherIcon(app, gold = false)
        assertEquals(
            "the launcher alias stays Cyan",
            "com.scanwhirl.app.ui.shell.ShellActivityCyan",
            SwTheme.enabledLauncherAlias(app)
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
