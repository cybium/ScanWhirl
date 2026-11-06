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
 */
object ProGate {
    private const val PREFS = "scanwhirl_prefs"
    private const val KEY = "pro_unlocked"

    /**
     * True in test builds so every Pro extra can be tried free, or once a
     * real one-time purchase has been recorded (Play build).
     */
    fun isPro(context: Context): Boolean =
        com.scanwhirl.app.BuildConfig.TEST_BUILD ||
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    /** Records a completed purchase (Play build). Test builds are always Pro. */
    fun setPro(context: Context, unlocked: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, unlocked).apply()
    }
}
