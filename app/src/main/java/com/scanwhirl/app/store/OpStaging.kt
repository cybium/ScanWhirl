package com.scanwhirl.app.store

import java.io.File
import java.io.IOException

/**
 * Private working folders for operations that build a PDF before swapping it in (PM-3.1b: protect,
 * unlock, change password; later publish and drafts). They live in `filesDir/op_staging`, on the
 * same filesystem as `documents/`, and never inside a document folder, so a plaintext temporary can
 * never sit beside a locked document.
 *
 * There is no age threshold. The live set is process-wide, so an operation in flight is never swept.
 * Recovery runs at process start ([sweep]): every entry not live in THIS process belongs to a dead one.
 */
internal object OpStaging {
    const val DIR = "op_staging"
    private val live: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    fun root(filesDir: File): File = File(filesDir, DIR).apply { mkdirs() }

    /** Exclusive new working folder, registered live BEFORE mkdir. */
    fun create(filesDir: File): File {
        repeat(8) {
            val token = SaveFormat.newToken()
            live.add(token)
            val dir = File(root(filesDir), token)
            if (dir.mkdir()) return dir
            live.remove(token)
        }
        throw IOException("Could not prepare a working folder")
    }

    fun release(dir: File) {
        runCatching { dir.deleteRecursively() }
        live.remove(dir.name)
    }

    /** The folder was renamed away (PM-2.1b): stop tracking it without deleting. */
    fun forget(dir: File) {
        live.remove(dir.name)
    }

    /** Deletes every entry that is not live in this process. Returns how many were removed. */
    fun sweep(filesDir: File): Int = root(filesDir).listFiles().orEmpty()
        .filter { it.name !in live && it.deleteRecursively() }.size

    internal fun resetForTests() = live.clear()
}
