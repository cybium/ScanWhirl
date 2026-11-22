package com.scanwhirl.app.util

import com.scanwhirl.app.BuildConfig

/**
 * Lane CR feature flag for "Replace original". Ships OFF (COUNCIL §5).
 *
 * The mutable [testOverride] seam is honoured ONLY in debug/test builds.
 * Production release ignores it entirely, so a stray test setter can never
 * enable this feature in a shipped build.
 */
object ReplaceFeature {
    const val ENABLED = false

    @Volatile
    var testOverride: Boolean? = null

    /** Pure form so a test can prove release config cannot be flipped. */
    fun resolve(allowOverride: Boolean, override: Boolean?): Boolean =
        if (allowOverride) override ?: ENABLED else ENABLED

    fun isEnabled(): Boolean = resolve(
        allowOverride = BuildConfig.DEBUG || BuildConfig.TEST_BUILD,
        override = testOverride
    )

    internal fun resetForTests() {
        testOverride = null
    }
}
