package com.scanwhirl.app.ui.qr

import android.app.Activity
import com.scanwhirl.app.ui.kit.ProOffer

/**
 * v2 UI: 03_tools_hub — the Tools-hub entry point for the QR code reader (Create group, Pro).
 *
 * The Pro gate is the shared [ProOffer.require] path: a Free build lands on the shell's Pro
 * screen, a Pro build opens the scanner. Nothing is granted from the tile itself.
 */
object QrToolLauncher {

    fun open(activity: Activity) {
        if (!ProOffer.require(activity)) return
        activity.startActivity(QrActivity.intent(activity))
    }
}
