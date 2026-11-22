package com.scanwhirl.app.util

/**
 * Lane CR feature flag for "Replace original".
 *
 * Ships OFF. It may only be switched on after a GPT Sol security GO and an
 * owner phone drill (COUNCIL_DECISIONS §5). Tests can opt in through
 * [testOverride]; production always reads [ENABLED] unless a test sets it.
 */
object ReplaceFeature {
    const val ENABLED = false

    @Volatile
    var testOverride: Boolean? = null

    fun isEnabled(): Boolean = testOverride ?: ENABLED

    internal fun resetForTests() {
        testOverride = null
    }
}
