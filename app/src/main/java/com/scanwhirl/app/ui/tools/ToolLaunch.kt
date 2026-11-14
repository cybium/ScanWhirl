package com.scanwhirl.app.ui.tools

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.scanwhirl.app.ui.kit.ToolHost
import com.scanwhirl.app.ui.kit.ToolRegistry

/**
 * v2 UI: 03_tools_hub — the one place a tool tile's tap becomes a screen.
 *
 * The registry's launch lambdas call this, so the Tools hub, the Home quick row and the
 * Library create cards all reach the same setup screen. A tool host that is not a Context
 * (the registry's recording test host) falls back to the honest Coming soon sheet.
 */
object ToolLaunch {

    fun start(host: ToolHost, toolId: String) {
        val context = host as? Context
        if (context == null) {
            ToolRegistry.byId(toolId)?.let { host.comingSoon(it) }
            return
        }
        val intent = if (toolId == "passport") {
            PassportActivity.intent(context)
        } else {
            ToolSetupActivity.intent(context, toolId)
        }
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
