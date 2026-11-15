package com.scanwhirl.app.util

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Clipboard handling for secrets (recovery codes, Wi-Fi passwords, PDF passwords).
 *
 * - On Android 13+ the clip is marked `EXTRA_IS_SENSITIVE`, so the system does not show its
 *   content in the clipboard preview.
 * - The clip is cleared automatically after [CLEAR_AFTER_MS], but only when it still holds the
 *   same text (the user may have copied something else in the meantime).
 * - Nothing is ever logged.
 */
object ClipboardPrivacy {

    const val CLEAR_AFTER_MS = 60_000L

    fun copy(context: Context, label: String, text: String, sensitive: Boolean = true) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        if (sensitive && Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = android.os.PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        clipboard.setPrimaryClip(clip)
        scheduleClear(context.applicationContext, text)
    }

    /** Clears the clipboard if [expected] is still what is on it. */
    fun clearIfUnchanged(context: Context, expected: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val current = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(context)?.toString()
        if (current == expected) {
            if (Build.VERSION.SDK_INT >= 28) {
                clipboard.clearPrimaryClip()
            } else {
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }
    }

    private fun scheduleClear(context: Context, text: String) {
        Handler(Looper.getMainLooper()).postDelayed({ clearIfUnchanged(context, text) }, CLEAR_AFTER_MS)
    }
}
