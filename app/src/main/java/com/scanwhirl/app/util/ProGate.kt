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

    fun isPro(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
}
