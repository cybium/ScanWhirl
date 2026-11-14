package com.scanwhirl.app.ui.qr

import android.app.Activity
import com.scanwhirl.app.R
import com.scanwhirl.app.ui.kit.SwSheet
import com.scanwhirl.app.util.ProGate

/**
 * v2 UI: 03_tools_hub — the Tools-hub entry point for the QR code reader (Create group, Pro).
 *
 * Same order as the other Pro tools: the Pro gate (a sheet, never a dead tap), then the
 * scanner screen. Nothing is granted from the tile itself.
 */
object QrToolLauncher {

    fun open(activity: Activity) {
        if (!ProGate.isPro(activity)) {
            SwSheet.builder(activity)
                .setTitle(R.string.pro_suite_title)
                .setMessage(R.string.qr_pro_message)
                .setPrimary(R.string.sheet_got_it)
                .build()
                .show()
            return
        }
        activity.startActivity(QrActivity.intent(activity))
    }
}
