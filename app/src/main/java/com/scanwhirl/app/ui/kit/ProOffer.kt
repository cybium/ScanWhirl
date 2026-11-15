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

    fun require(activity: Activity): Boolean {
        if (ProGate.isPro(activity)) return true
        activity.startActivity(
            Intent(activity, ShellActivity::class.java)
                .putExtra(ShellActivity.EXTRA_OPEN_PRO, true)
        )
        return false
    }
}
