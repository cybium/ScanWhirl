package com.scanwhirl.app.tools

import android.content.Context
import com.google.android.gms.common.api.ApiException
import com.scanwhirl.app.R
import com.scanwhirl.app.util.ImageTools

/**
 * V2C: maps capture, Book and save failures to the app's own localized reasons.
 * A raw exception message never reaches a sheet, Toast or receipt.
 */
object V2cFailureMap {

    fun message(context: Context, failure: Throwable?): String {
        if (failure == null) return context.getString(R.string.v2c_error_generic)
        if (failure is OutOfMemoryError || ImageTools.isTooLargePhoto(failure)) {
            return context.getString(R.string.v2c_error_too_large)
        }
        if (failure is ApiException) return context.getString(R.string.v2c_error_scanner)
        // Keyword checks classify only; the returned text always comes from resources.
        if (causeChain(failure).any { text ->
                text.contains("space", true) || text.contains("storage", true)
            }
        ) {
            return context.getString(R.string.v2c_error_no_space)
        }
        if (failure is IllegalArgumentException || failure is IllegalStateException ||
            causeChain(failure).any { it.contains("read", true) || it.contains("decode", true) }
        ) {
            return context.getString(R.string.v2c_error_unreadable)
        }
        return context.getString(R.string.v2c_error_generic)
    }

    /**
     * Extracts only the page NUMBER for a localized "Page N ..." message.
     * The exception's own wording is never displayed or stored.
     */
    fun pageNumberOf(failure: Throwable?): Int? {
        val match = causeChain(failure ?: return null)
            .firstNotNullOfOrNull { Regex("Page\\s+(\\d+)", RegexOption.IGNORE_CASE).find(it) }
            ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    private fun causeChain(failure: Throwable): List<String> {
        val out = mutableListOf<String>()
        var current: Throwable? = failure
        val seen = HashSet<Throwable>()
        while (current != null && seen.add(current)) {
            current.message?.let { out += it }
            current = current.cause
        }
        return out
    }
}
