package com.scanwhirl.app.tools

import android.content.Context
import java.io.File

/**
 * V2C: a small durable, named receipt log. Review shows the last receipt for
 * its staging folder (and the last global scan receipt) instead of relying on
 * a Toast that disappears on rotation or process death. No document bytes and
 * no exception text are stored: only a short name, a localized detail and a
 * timestamp.
 */
object V2cReceiptStore {

    private const val FILE = "v2c_receipts_v1.txt"
    private const val SEP = '\u001f'

    data class Receipt(val key: String, val name: String, val detail: String, val at: Long)

    @Synchronized
    fun record(context: Context, key: String, name: String, detail: String) {
        val line = listOf(key, name, detail, System.currentTimeMillis().toString())
            .joinToString(SEP.toString()) { sanitize(it) }
        runCatching { File(context.filesDir, FILE).appendText(line + "\n") }
    }

    /** Latest receipt for [key], or the latest one of any key when [key] is null. */
    @Synchronized
    fun latest(context: Context, key: String? = null): Receipt? =
        runCatching { File(context.filesDir, FILE).readLines() }.getOrDefault(emptyList())
            .asReversed()
            .mapNotNull(::parse)
            .firstOrNull { key == null || it.key == key }

    @Synchronized
    internal fun resetForTest(context: Context) {
        runCatching { File(context.filesDir, FILE).delete() }
    }

    private fun parse(line: String): Receipt? {
        val parts = line.split(SEP)
        if (parts.size != 4) return null
        val at = parts[3].toLongOrNull() ?: return null
        return Receipt(parts[0], parts[1], parts[2], at)
    }

    private fun sanitize(text: String): String =
        text.replace(SEP, ' ').replace('\n', ' ').replace('\r', ' ')
}
