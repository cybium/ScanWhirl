package com.scanwhirl.app.ui.kit

import android.app.Activity
import android.content.Intent
import com.scanwhirl.app.ui.shell.ShellActivity
import com.scanwhirl.app.util.ProGate

/**
 * v2 UI: 06_pro — the one "this needs Pro" path for screens that are not the shell.
 *
 * The Tools hub, the Home quick row and the Library create row go through [launchTool], whose
 * host shows the Pro screen. A Pro entry point outside the shell (the PDF reader dock, an
 * activity opened by another feature) must land on the same offer: the shell's Pro screen, not
 * a toast, a Coming-soon sheet or the Pro screen's old "not available" panel.
 *
 * Returns true when the caller may continue; false means this screen should stop and the offer
 * is already on its way.
 */
object ProOffer {

    /**
     * Test seam: null follows the real build flag; true/false pretends that build so a unit
     * test can prove the release rule (recorded purchase only) without a release APK.
     */
    @androidx.annotation.VisibleForTesting
    internal var testBuildForTests: Boolean? = null

    fun require(activity: Activity): Boolean {
        val testBuild = testBuildForTests ?: com.scanwhirl.app.BuildConfig.TEST_BUILD
        if (ProGate.isPro(activity, testBuild)) return true
        activity.startActivity(
            Intent(activity, ShellActivity::class.java)
                .putExtra(ShellActivity.EXTRA_OPEN_PRO, true)
        )
        return false
    }
}
