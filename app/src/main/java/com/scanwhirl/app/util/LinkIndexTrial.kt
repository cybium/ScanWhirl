package com.scanwhirl.app.util

import android.content.Context

/**
 * LX: the one-free-use entitlement for Link index, kept in a small private
 * preferences file (never backed up, never sent anywhere). Pro owners are never
 * counted. A failed export does not consume the use: [recordSuccess] runs only
 * after the linked copy has been verified and published.
 */
object LinkIndexTrial {

    private const val PREFS = "scanwhirl_link_index"
    private const val KEY_USES = "free_uses"
    const val FREE_USES = 1

    /** Test seam: skip the offer and the counter in Robolectric lifecycle tests. */
    @Volatile
    @androidx.annotation.VisibleForTesting
    var autoAcceptForTests = false

    private fun sp(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun uses(context: Context): Int = try {
        sp(context).getInt(KEY_USES, 0).coerceAtLeast(0)
    } catch (_: ClassCastException) {
        0
    }

    fun isPro(context: Context): Boolean = ProGate.isPro(context)

    /** True before the first successful use for a Free user. */
    fun shouldOffer(context: Context): Boolean =
        !autoAcceptForTests && !isPro(context) && uses(context) == 0

    /** True once the free use is spent: the caller must send the user to [ProOffer]. */
    fun needsPro(context: Context): Boolean =
        !autoAcceptForTests && !isPro(context) && uses(context) >= FREE_USES

    fun recordSuccess(context: Context) {
        if (autoAcceptForTests || isPro(context)) return
        sp(context).edit().putInt(KEY_USES, uses(context) + 1).commit()
    }

    @androidx.annotation.VisibleForTesting
    internal fun resetForTests(context: Context) {
        sp(context).edit().clear().commit()
        autoAcceptForTests = false
    }
}
