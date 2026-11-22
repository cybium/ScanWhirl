package com.scanwhirl.app.util

import android.content.Context

/**
 * Lane Z (council §8): the in-app text-size choice.
 *
 * The value is a *multiplier on the phone's own font scale*, not an absolute size.
 * Default (1.0) therefore follows the phone exactly — a person who set 200% in
 * Android still gets 200% here, and the existing `fontScale >= 1.6` layout
 * breakpoints keep reading the real configuration. Small/Large/Largest are
 * 0.9/1.15/1.3; the multiplied result is clamped to 0.85-2.0 so a phone already
 * set to 200% cannot be pushed into a 260% layout nobody designed or tested.
 *
 * Applied once in [com.scanwhirl.app.ui.BaseActivity.attachBaseContext], which
 * every screen extends, so the whole app shares one scale.
 */
object TextSizePrefs {
    private const val FILE = "scanwhirl_text_size"
    private const val KEY_MODE = "mode"

    const val MODE_SMALL = "small"
    const val MODE_DEFAULT = "default"
    const val MODE_LARGE = "large"
    const val MODE_LARGEST = "largest"

    const val FACTOR_SMALL = 0.9f
    const val FACTOR_DEFAULT = 1.0f
    const val FACTOR_LARGE = 1.15f
    const val FACTOR_LARGEST = 1.3f

    const val MIN_FONT_SCALE = 0.85f
    const val MAX_FONT_SCALE = 2.0f

    /** Order shown in the Settings chooser, with the exact council labels. */
    val MODES = listOf(MODE_SMALL, MODE_DEFAULT, MODE_LARGE, MODE_LARGEST)

    fun mode(c: Context): String {
        val saved = prefs(c).getString(KEY_MODE, MODE_DEFAULT) ?: MODE_DEFAULT
        return if (saved in MODES) saved else MODE_DEFAULT
    }

    fun setMode(c: Context, mode: String) {
        prefs(c).edit().putString(KEY_MODE, if (mode in MODES) mode else MODE_DEFAULT).apply()
    }

    fun factor(mode: String): Float = when (mode) {
        MODE_SMALL -> FACTOR_SMALL
        MODE_LARGE -> FACTOR_LARGE
        MODE_LARGEST -> FACTOR_LARGEST
        else -> FACTOR_DEFAULT
    }

    /** The honest percent shown under the row: the multiplier, not a redesign promise. */
    fun percent(mode: String): Int = (factor(mode) * 100f).toInt()

    /** The phone's own scale, guarded against a missing or nonsensical value. */
    fun systemFontScale(c: Context): Float {
        val value = c.resources.configuration.fontScale
        return if (value.isFinite() && value > 0f) value else 1f
    }

    /** The scale an activity applies: phone scale × chosen factor, clamped. */
    fun effectiveFontScale(c: Context): Float =
        (systemFontScale(c) * factor(mode(c))).coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)

    private fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
