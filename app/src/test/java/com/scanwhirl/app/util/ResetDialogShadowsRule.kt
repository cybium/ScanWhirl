package com.scanwhirl.app.util

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowDialog

/**
 * Clears Robolectric's process-wide dialog registries before and after every test.
 * ShadowDialog.getLatestDialog / ShadowAlertDialog.getLatestAlertDialog read static lists that
 * survive across test classes in the same fork. Test-only; no production code references it.
 */
class ResetDialogShadowsRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            reset()
            try { base.evaluate() } finally { reset() }
        }
    }

    private fun reset() {
        ShadowDialog.reset()
        ShadowAlertDialog.reset()
    }
}