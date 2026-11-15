package com.scanwhirl.app.util

import android.content.Context
import android.view.Window
import android.view.WindowManager

/**
 * Screen capture hardening for screens that show secrets (Vault, unlock and protect sheets).
 *
 * `FLAG_SECURE` has three effects at once: screenshots and screen recording show a black
 * window, the recent-apps thumbnail is blank, and the content is not projected to a virtual
 * display. It is applied only where a secret is on screen, so the rest of the app stays
 * screenshot-friendly.
 */
object SecureView {

    fun apply(window: Window?) {
        window ?: return
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun clear(window: Window?) {
        window ?: return
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun isSecure(window: Window?): Boolean =
        window != null && (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0

    /**
     * The document-viewer policy (SECURITY_AUDIT 2026-11-15): a protected/locked or
     * Vault document is always blocked; an ordinary scan follows the user's
     * "Block screenshots of documents" setting (default off, so normal scans stay
     * shareable by screenshot).
     */
    fun applyForDocument(context: Context, window: Window?, protected: Boolean) {
        if (protected || Prefs.blockDocScreenshots(context)) apply(window)
    }
}
