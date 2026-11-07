package com.scanwhirl.app.tools

import android.content.Context
import android.net.Uri
import java.util.Locale

/**
 * Shared helpers for the tool flows extracted from `MainActivity` in test26
 * Phase 2. Bodies moved verbatim; only the receiver changed.
 */
internal object FlowUtil {

    /** Reads a content Uri fully; null when unreadable. */
    fun readUriBytes(context: Context, uri: Uri): ByteArray? = try {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (e: Exception) {
        null
    }

    fun intText(value: Int): String = String.format(Locale.ROOT, "%d", value)

    fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().trim('_')
        return cleaned.ifBlank { "image" }.take(60)
    }
}
