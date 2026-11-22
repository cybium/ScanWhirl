package com.scanwhirl.app.util

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowDialog

/**
 * Clears Robolectric's process-wide dialog registries before and after every test.
 *
 * [ShadowDialog.getLatestDialog] and [ShadowAlertDialog.getLatestAlertDialog] read static lists
 * that survive across test classes in the same fork. A sheet left behind by an earlier class can
 * therefore change what a later class sees (a stale dialog, or none after a reset at the wrong
 * time). Tests that assert on the "latest dialog" must start from a clean registry and leave it
 * clean for the next class. Test-only: no production code references this rule.
 */
class ResetDialogShadowsRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            reset()
            try {
                base.evaluate()
            } finally {
                reset()
            }
        }
    }

    private fun reset() {
        ShadowDialog.reset()
        ShadowAlertDialog.reset()
    }
}
