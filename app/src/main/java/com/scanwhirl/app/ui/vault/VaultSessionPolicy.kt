package com.scanwhirl.app.ui.vault

/**
 * v2 UI: 04_vault — the Vault relocks after the app has been in the background for the
 * 2-minute grace period (design §4.1). Pure so the timer is testable without sleeping.
 */
object VaultSessionPolicy {

    const val GRACE_MILLIS = 2L * 60 * 1000

    /** [leftAt] is 0 when the app has not been backgrounded yet. */
    fun shouldRelock(leftAt: Long, now: Long, graceMillis: Long = GRACE_MILLIS): Boolean {
        if (leftAt <= 0) return false
        return now - leftAt >= graceMillis
    }
}
