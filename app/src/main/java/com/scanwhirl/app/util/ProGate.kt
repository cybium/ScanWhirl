package com.scanwhirl.app.util

import android.content.Context

/**
 * Device-local cache for paid extras. Existing installs keep this grant offline until Play can
 * resolve ownership. Full Backup must not export or restore either key here.
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
    fun isPro(context: Context): Boolean = isPro(context, com.scanwhirl.app.BuildConfig.TEST_BUILD)

    /**
     * [isPro] with the build flag exposed, so a unit test can drive the release rule (recorded
     * purchase only, no override) instead of the test build's "always Pro" default.
     */
    @androidx.annotation.VisibleForTesting
    internal fun isPro(context: Context, testBuild: Boolean): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return resolveProState(
            testBuild = testBuild,
            override = if (testBuild) readOverride(context) else 0,
            purchased = try {
                prefs.getBoolean(KEY, false)
            } catch (_: ClassCastException) {
                false // Older app versions could restore arbitrary preference types.
            }
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

    /** Test-build only: true when QA forced Free, false when forced Pro, null for default. */
    fun testOverride(context: Context): Boolean? {
        if (!com.scanwhirl.app.BuildConfig.TEST_BUILD) return null
        return when (readOverride(context)) {
            1 -> false
            2 -> true
            else -> null
        }
    }

    private fun readOverride(context: Context): Int = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_TEST_OVERRIDE, 0)
    } catch (_: ClassCastException) {
        0
    }
}
