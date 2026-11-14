package com.scanwhirl.app.ui.qr

import android.content.Context
import android.util.Base64
import com.scanwhirl.app.util.Durable
import java.io.File

/**
 * The last [MAX] scanned codes, newest first, in the app's private storage
 * (`filesDir/qr_history.txt`). A scan is only added after the scanner reports a value, so a
 * cancelled scan saves nothing. "Clear" removes everything.
 *
 * Stored as one `kind\tat\tbase64(value)` line per entry, so a value with tabs or newlines
 * never breaks the file; unreadable lines are skipped instead of crashing the screen.
 */
class QrHistory(
    context: Context,
    private val file: File = File(context.applicationContext.filesDir, FILE_NAME)
) {

    data class Item(val value: String, val kind: QrKind, val at: Long)

    companion object {
        const val FILE_NAME = "qr_history.txt"
        const val MAX = 20

        private const val LOCK_TAG = 0
        private val LOCK = Any()
    }

    /** Adds [value] at the top of the list, dropping the oldest beyond [MAX]. */
    fun add(value: String, kind: QrKind, at: Long = System.currentTimeMillis()): Item = synchronized(LOCK) {
        val item = Item(value.take(QrContent.MAX_VALUE), kind, at)
        val kept = buildList {
            add(item)
            list().filterNot { it.value == item.value && it.kind == item.kind }.take(MAX - 1).forEach { add(it) }
        }
        write(kept)
        item
    }

    /** Newest first, at most [MAX]. */
    fun list(): List<Item> = synchronized(LOCK) {
        if (!file.isFile) return emptyList()
        runCatching {
            file.readLines(Charsets.UTF_8)
                .mapNotNull(::parseLine)
                .take(MAX)
        }.getOrDefault(emptyList())
    }

    fun clear() = synchronized(LOCK) {
        try {
            file.delete()
        } catch (_: Exception) {
        }
    }

    fun isEmpty(): Boolean = list().isEmpty()

    // ------------------------------------------------------------------

    private fun parseLine(line: String): Item? {
        if (line.isBlank()) return null
        val parts = line.split('\t')
        if (parts.size != 3) return null
        val kind = runCatching { QrKind.valueOf(parts[0]) }.getOrNull() ?: return null
        val at = parts[1].toLongOrNull() ?: return null
        val value = runCatching {
            String(Base64.decode(parts[2], Base64.NO_WRAP), Charsets.UTF_8)
        }.getOrNull() ?: return null
        return Item(value, kind, at)
    }

    private fun write(items: List<Item>) {
        val text = buildString {
            items.forEach { item ->
                append(item.kind.name)
                append('\t')
                append(item.at)
                append('\t')
                append(Base64.encodeToString(item.value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
                append('\n')
            }
        }
        try {
            file.parentFile?.mkdirs()
            Durable.atomicWrite(file, text.toByteArray(Charsets.UTF_8))
        } catch (_: Exception) {
            // A full disk must not lose the scan on screen; the history is best effort.
        }
    }
}
