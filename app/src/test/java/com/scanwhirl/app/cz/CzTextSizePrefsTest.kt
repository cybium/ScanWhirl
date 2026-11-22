package com.scanwhirl.app.cz

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.util.TextSizePrefs
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Lane Z, item 1: the in-app text-size multiplier.
 *
 * RED on base: TextSizePrefs does not exist. These tests pin the council factors
 * (§8: Small 0.9 / Default follows phone / Large 1.15 / Largest 1.3), persistence,
 * and the 0.85-2.0 clamp before any UI is wired.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CzTextSizePrefsTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        RuntimeEnvironment.setFontScale(1f)
        TextSizePrefs.setMode(app, TextSizePrefs.MODE_DEFAULT)
    }

    @Test
    fun `the four modes carry the council factors`() {
        assertEquals(0.9f, TextSizePrefs.factor(TextSizePrefs.MODE_SMALL), 0.0001f)
        assertEquals(1.0f, TextSizePrefs.factor(TextSizePrefs.MODE_DEFAULT), 0.0001f)
        assertEquals(1.15f, TextSizePrefs.factor(TextSizePrefs.MODE_LARGE), 0.0001f)
        assertEquals(1.3f, TextSizePrefs.factor(TextSizePrefs.MODE_LARGEST), 0.0001f)
        assertEquals(4, TextSizePrefs.MODES.size)
    }

    @Test
    fun `default follows the phone font scale`() {
        RuntimeEnvironment.setFontScale(1.2f)
        assertEquals(1.2f, TextSizePrefs.effectiveFontScale(app), 0.001f)
    }

    @Test
    fun `each option multiplies the phone scale and the result is clamped`() {
        RuntimeEnvironment.setFontScale(1f)
        TextSizePrefs.setMode(app, TextSizePrefs.MODE_SMALL)
        assertEquals(0.9f, TextSizePrefs.effectiveFontScale(app), 0.001f)
        TextSizePrefs.setMode(app, TextSizePrefs.MODE_LARGE)
        assertEquals(1.15f, TextSizePrefs.effectiveFontScale(app), 0.001f)
        TextSizePrefs.setMode(app, TextSizePrefs.MODE_LARGEST)
        assertEquals(1.3f, TextSizePrefs.effectiveFontScale(app), 0.001f)

        RuntimeEnvironment.setFontScale(2f)
        assertEquals(2f, TextSizePrefs.effectiveFontScale(app), 0.001f)

        RuntimeEnvironment.setFontScale(0.9f)
        TextSizePrefs.setMode(app, TextSizePrefs.MODE_SMALL)
        assertEquals(0.85f, TextSizePrefs.effectiveFontScale(app), 0.001f)
    }

    @Test
    fun `a mode survives a fresh read and an unknown value falls back to default`() {
        for (mode in TextSizePrefs.MODES) {
            TextSizePrefs.setMode(app, mode)
            assertEquals(mode, TextSizePrefs.mode(app))
        }
        TextSizePrefs.setMode(app, "not-a-mode")
        assertEquals(TextSizePrefs.MODE_DEFAULT, TextSizePrefs.mode(app))
        TextSizePrefs.setMode(app, TextSizePrefs.MODE_DEFAULT)
    }
}
