package com.scanwhirl.app.util

import android.content.Context
import android.view.Window
import android.view.WindowManager
import com.scanwhirl.app.ScanWhirlApp

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

    /**
     * SECURITY_JUDGE 1.3.0 finding 1: child screens receive only a document id,
     * not the parent's window state. FLAG_SECURE belongs to a window and does not
     * carry over when a page tap or a "save protected copy" opens a new activity,
     * so resolve protection from the record on disk (protected/locked/Vaulted),
     * then apply the same policy as the parent viewer. Reading the id every time
     * also makes the decision survive recreation without trusting an intent flag.
     */
    fun applyForDocumentId(context: Context, window: Window?, docId: String?) {
        if (docId.isNullOrBlank()) return
        val doc = runCatching { ScanWhirlApp.from(context).repository.getDocument(docId) }.getOrNull()
        applyForDocument(context, window, doc?.isVaulted == true || doc?.isLocked == true)
    }
}
