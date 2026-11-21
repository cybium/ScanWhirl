package com.scanwhirl.app.store

import com.scanwhirl.app.util.Durable
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

    /** PM-2.2b: a seal file inside a staged (or just-published) folder, finished by recovery. */
    const val SEAL = ".publish"
    private const val SEAL_HEADER = "scanwhirl-publish-v1"

    /**
     * What a draft publish promises: which draft it belongs to, and (for Replace) which document it
     * replaces. Recovery uses [token] to find the Trash entry that carries `previous=<token>`.
     */
    class Seal(
        val token: String,
        val draft: String,
        val draftCreated: Long,
        val replaces: String?,
        val pages: List<String>,
        val pagesSha256: String,
        val pdfSha256: String
    )

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

    /**
     * Deletes every entry that is not live in this process, except folders the caller says still
     * hold an UNRESOLVED recovery marker (a failed lock removal must be retried, not swept).
     * Returns how many were removed.
     */
    fun sweep(filesDir: File, keep: (File) -> Boolean = { false }): Int =
        root(filesDir).listFiles().orEmpty()
            .filter { it.name !in live && !keep(it) && it.deleteRecursively() }.size

    /** The same live-operation exclusion [sweep] uses; pruning must not touch a running op. */
    fun isLive(dir: File): Boolean = dir.name in live

    /** PM-2.2b: writes the seal atomically (after every file is fsynced, before the rename). */
    fun writeSeal(dir: File, seal: Seal) {
        val text = buildString {
            append(SEAL_HEADER).append('\n')
            append("token=").append(seal.token).append('\n')
            append("draft=").append(seal.draft).append('\n')
            append("draftCreated=").append(seal.draftCreated).append('\n')
            append("replaces=").append(seal.replaces ?: "-").append('\n')
            append("pages=").append(seal.pages.joinToString(",")).append('\n')
            append("pagesSha256=").append(seal.pagesSha256).append('\n')
            append("pdfSha256=").append(seal.pdfSha256).append('\n')
        }
        Durable.atomicWriteText(File(dir, SEAL), text)
    }

    /**
     * PM-2.2b: parses a seal; any missing or invalid field returns null (a broken or oversized seal
     * is treated as "no seal" — STALE, never KEEP — so recovery falls through to the sweep). Reads
     * are bounded to [MAX_SEAL_BYTES] so a corrupt marker cannot exhaust the heap at startup.
     */
    const val MAX_SEAL_BYTES = 64 * 1024

    fun readSeal(dir: File): Seal? {
        val file = File(dir, SEAL)
        if (!file.isFile || file.length() > MAX_SEAL_BYTES) return null
        val lines = try {
            file.readLines()
        } catch (_: Exception) {
            return null
        }
        if (lines.firstOrNull() != SEAL_HEADER) return null
        val values = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val eq = line.indexOf('=')
            if (eq <= 0) return null
            values[line.substring(0, eq)] = line.substring(eq + 1)
        }
        val token = values["token"]?.takeIf { it.isNotBlank() } ?: return null
        val draft = values["draft"]?.takeIf { it.isNotBlank() } ?: return null
        val draftCreated = values["draftCreated"]?.toLongOrNull() ?: return null
        val replaces = values["replaces"]?.takeIf { it != "-" }
        val pages = values["pages"]?.split(',')?.filter { it.isNotBlank() } ?: return null
        if (pages.isEmpty()) return null
        val pagesSha256 = values["pagesSha256"]?.takeIf { it.length == 64 } ?: return null
        val pdfSha256 = values["pdfSha256"]?.takeIf { it.length == 64 } ?: return null
        return Seal(token, draft, draftCreated, replaces, pages, pagesSha256, pdfSha256)
    }

    internal fun resetForTests() = live.clear()
}
