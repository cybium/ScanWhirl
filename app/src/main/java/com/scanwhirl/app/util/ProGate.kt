package com.scanwhirl.app.util

import android.content.Context

/**
 * Unlock flag for paid extras (one-time purchase; wired to Play Billing when
 * the extras ship). The rest of the app stays free.
 *
 * Planned Extras, Pro only:
 *  - Password-protect a PDF
 *  - ID/passport photo sizes
 *  - Share as long image
 *  - Batch export all scans
 *  - Hidden Vault (4-digit PIN)
 *  - OCR text / in-page text search
 */
object ProGate {
    private const val PREFS = "scanwhirl_prefs"
    private const val KEY = "pro_unlocked"
    private const val KEY_TEST_OVERRIDE = "pro_test_override" // 0 follow, 1 free, 2 pro

    /**
     * True in test builds so every Pro extra can be tried free, or once a
     * real one-time purchase has been recorded (Play build). The test-build
     * override lets the Free UI be reviewed on a real phone without a second
     * APK: 1 = forced Free, 2 = forced Pro.
     */
    fun isPro(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        when (prefs.getInt(KEY_TEST_OVERRIDE, 0)) {
            1 -> return false
            2 -> return true
        }
        return com.scanwhirl.app.BuildConfig.TEST_BUILD || prefs.getBoolean(KEY, false)
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
