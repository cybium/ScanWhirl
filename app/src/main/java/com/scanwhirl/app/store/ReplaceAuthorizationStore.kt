package com.scanwhirl.app.store

import android.content.Context
import com.scanwhirl.app.util.Durable
import java.io.File

/**
 * Lane CR — single-use, result-bound authorization for Replace original.
 *
 * Issued when the RESULT is produced (source id + output id + both full byte
 * identities + tool provenance). Revalidated inside the commit lock and
 * consumed before any move; a replay or a changed document is refused.
 */
class ReplaceAuthorizationStore(private val context: Context) {

    data class Authorization(
        val id: String,
        val sourceId: String,
        val outputId: String,
        val toolId: String,
        val sourceFingerprint: String,
        val outputFingerprint: String,
        val consumed: Boolean,
        val createdAt: Long
    )

    private fun dir(): File = File(context.filesDir, "replace_auth").apply { mkdirs() }

    private fun fileFor(id: String): File {
        val safe = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(80)
        require(safe.isNotEmpty()) { "authorization id required" }
        return File(dir(), "$safe.auth")
    }

    fun issue(
        id: String,
        sourceId: String,
        outputId: String,
        toolId: String,
        sourceFingerprint: String,
        outputFingerprint: String
    ): Authorization = synchronized(lock) {
        check(!fileFor(id).exists()) { "Authorization id already used" }
        ReplaceFaults.at("before-authorization-write")
        val auth = Authorization(id, sourceId, outputId, toolId,
            sourceFingerprint, outputFingerprint, false, System.currentTimeMillis())
        write(auth)
        auth
    }

    fun load(id: String): Authorization? {
        val f = fileFor(id)
        if (!f.isFile) return null
        val lines = runCatching { f.readLines() }.getOrNull() ?: return null
        if (lines.firstOrNull() != HEADER) return null
        val v = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val at = line.indexOf('=')
            if (at > 0) v[line.substring(0, at)] = line.substring(at + 1)
        }
        return Authorization(
            id = v["id"].orEmpty(),
            sourceId = v["sourceId"].orEmpty(),
            outputId = v["outputId"].orEmpty(),
            toolId = v["toolId"].orEmpty(),
            sourceFingerprint = v["sourceFingerprint"].orEmpty(),
            outputFingerprint = v["outputFingerprint"].orEmpty(),
            consumed = v["consumed"]?.toBooleanStrictOrNull() ?: true,
            createdAt = v["createdAt"]?.toLongOrNull() ?: 0L
        ).takeIf { it.id == id && it.createdAt > System.currentTimeMillis() - MAX_AGE_MS }
    }

    /** Atomic in-process single-use: only the caller that flips this proceeds. */
    fun consume(id: String): Boolean = synchronized(lock) {
        val current = load(id) ?: return@synchronized false
        if (current.consumed) return@synchronized false
        write(current.copy(consumed = true))
        true
    }

    fun drop(id: String) {
        synchronized(lock) { fileFor(id).delete() }
    }

    fun prune() = synchronized(lock) {
        dir().listFiles { f -> f.isFile && f.name.endsWith(".auth") }.orEmpty().forEach { file ->
            val auth = load(file.name.removeSuffix(".auth"))
            if (auth == null || auth.consumed) file.delete()
        }
    }

    private fun write(auth: Authorization) {
        Durable.atomicWriteText(fileFor(auth.id), buildString {
            append(HEADER).append('\n')
            append("id=").append(auth.id).append('\n')
            append("sourceId=").append(auth.sourceId).append('\n')
            append("outputId=").append(auth.outputId).append('\n')
            append("toolId=").append(auth.toolId).append('\n')
            append("sourceFingerprint=").append(auth.sourceFingerprint).append('\n')
            append("outputFingerprint=").append(auth.outputFingerprint).append('\n')
            append("consumed=").append(auth.consumed).append('\n')
            append("createdAt=").append(auth.createdAt).append('\n')
        })
    }

    private companion object {
        const val HEADER = "scanwhirl-replace-auth-v1"
        const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
        val lock = Any()
    }
}
