package com.scanwhirl.app.util

import android.content.Context

/**
 * Unlock flag for paid extras (one-time purchase; wired to Play Billing when
 * the extras ship). The rest of the app stays free.
 *
 * Remaining legitimate Pro features: encrypted Private (WP-14), Fit (WP-17).
 */
object ProGate {
    private const val PREFS = "scanwhirl_prefs"
    private const val KEY = "pro_unlocked"
    private const val KEY_TEST_OVERRIDE = "pro_test_override" // 0 follow, 1 free, 2 pro

    /**
     * Pure resolver — extracted so it can be unit-tested for all combinations
     * without touching SharedPreferences or BuildConfig.
     *
     * - Test build:  override 1 → false, 2 → true, any other → true (default Pro).
     * - Production:  override is NEVER consulted; only [purchased] counts.
     *   A production APK must NEVER grant Pro from a persisted test preference.
     */
    fun resolveProState(testBuild: Boolean, override: Int, purchased: Boolean): Boolean =
        if (testBuild) {
            when (override) {
                1 -> false  // forced Free (UI review)
                2 -> true   // forced Pro (UI review)
                else -> true // test build default: Pro
            }
        } else {
            // Production: ignore override entirely
            purchased
        }

    /**
     * True in test builds so every Pro extra can be exercised, or once a
     * real one-time purchase has been recorded (Play build). The test-build
     * override lets the Free UI be reviewed on a real phone without a second APK.
     */
    fun isPro(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return resolveProState(
            testBuild = com.scanwhirl.app.BuildConfig.TEST_BUILD,
            override = prefs.getInt(KEY_TEST_OVERRIDE, 0),
            purchased = prefs.getBoolean(KEY, false)
        )
    }

    /** Records a completed purchase (Play build). Test builds are always Pro. */
    fun setPro(context: Context, unlocked: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, unlocked).apply()
    }

    /** Forces the Free or Pro UI for review. Pass null to follow the real state. */
    fun setTestOverride(context: Context, pro: Boolean?) {
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (pro == null) edit.remove(KEY_TEST_OVERRIDE)
        else edit.putInt(KEY_TEST_OVERRIDE, if (pro) 2 else 1)
        edit.apply()
    }
}
