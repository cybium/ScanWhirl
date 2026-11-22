package com.scanwhirl.app.tools

import android.content.Context
import java.io.File

/**
 * V2C: a small durable, named receipt log. Review receipts are stored INSIDE
 * their staging folder, so they disappear with the scan flow; only a bounded
 * global log is kept for entries without a staging folder (Book, direct scan).
 * No document bytes and no raw exception text are stored: just a short name,
 * an outcome code/detail and a timestamp.
 */
object V2cReceiptStore {

    private const val FILE = "v2c_receipts_v1.txt"
    private const val SEP = '\u001f'
    private const val MAX_ENTRIES = 20

    data class Receipt(val key: String, val name: String, val detail: String, val at: Long)

    /** Global (non-review) receipts. Bounded so the file cannot grow forever. */
    @Synchronized
    fun record(context: Context, key: String, name: String, detail: String) {
        write(File(context.filesDir, FILE), key, name, detail)
    }

    /** Review receipts live with their staging folder and are removed with it. */
    @Synchronized
    fun recordIn(dir: File, name: String, detail: String) {
        if (!dir.isDirectory) return
        write(File(dir, FILE), "review", name, detail)
    }

    /** Latest review receipt for this staging folder. */
    @Synchronized
    fun latestIn(dir: File): Receipt? = read(File(dir, FILE)).firstOrNull()

    /** Latest receipt for [key], or any receipt when [key] is null. */
    @Synchronized
    fun latest(context: Context, key: String? = null): Receipt? =
        read(File(context.filesDir, FILE)).firstOrNull { key == null || it.key == key }

    @Synchronized
    internal fun resetForTest(context: Context) {
        runCatching { File(context.filesDir, FILE).delete() }
    }

    private fun write(file: File, key: String, name: String, detail: String) {
        val line = listOf(key, name, detail, System.currentTimeMillis().toString())
            .joinToString(SEP.toString()) { sanitize(it) }
        val kept = runCatching { file.readLines() }.getOrDefault(emptyList()).takeLast(MAX_ENTRIES - 1)
        runCatching { file.writeText((kept + line).joinToString("\n", postfix = "\n")) }
    }

    /** Newest first. */
    private fun read(file: File): List<Receipt> =
        runCatching { file.readLines() }.getOrDefault(emptyList())
            .asReversed()
            .mapNotNull(::parse)

    private fun parse(line: String): Receipt? {
        val parts = line.split(SEP)
        if (parts.size != 4) return null
        val at = parts[3].toLongOrNull() ?: return null
        return Receipt(parts[0], parts[1], parts[2], at)
    }

    private fun sanitize(text: String): String =
        text.replace(SEP, ' ').replace('\n', ' ').replace('\r', ' ')
}
