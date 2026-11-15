package com.scanwhirl.app.ui.tools

import android.app.Activity

/**
 * v2 UI: 03_tools_hub — the entry point L1's library selection bar calls.
 *
 * Signature for L1: `BatchLauncher.start(activity, ids)` where [ids] are the selected document
 * ids. It opens [BatchActivity] with those documents already selected, straight on the action
 * sheet. The registry's Batch tile opens the same screen with the chooser.
 */
object BatchLauncher {

    fun start(activity: Activity, ids: List<String>) {
        activity.startActivity(BatchActivity.intent(activity, ids))
    }
}
