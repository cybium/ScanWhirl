package com.scanwhirl.app.store.resultauthority

import android.content.Context
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.Sha256
import com.scanwhirl.app.util.Durable
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Destructive authority is ONLY the original producer's full byte identity.
 * C: stat identities and the old directory-mtime fallback are never trusted.
 *
 * Call matches() exclusively under ScanRepository's PDF -> OCR -> Trash -> Draft -> Meta
 * writer locks, followed by mutation under those same locks.
 */
internal object ResultOutputAuthority {
    private fun revisionFile(context: Context, id: String): File =
        File(File(context.noBackupFilesDir, "result_authority_v1"),
            Sha256.of(id.toByteArray(Charsets.UTF_8)) + ".token")

    /** A private per-document birth/revision token is not backed up with Library files. */
    private fun revision(context: Context, id: String, create: Boolean): String? {
        val file = revisionFile(context, id)
        if (!file.isFile && create) rotateRevision(context, id)
        val value = file.takeIf { it.isFile }?.readText()?.trim()
        return value?.takeIf { it.matches(Regex("[0-9a-f]{32}")) }
    }

    /** Non-result documents need no extra file; old receipts are invalidated before mutations. */
    fun invalidateIfTracked(context: Context, id: String) {
        if (revisionFile(context, id).isFile) rotateRevision(context, id)
    }

    /** Invalidate old results BEFORE Trash, restore or permanent removal can reuse an ID. */
    fun rotateRevision(context: Context, id: String) {
        val file = revisionFile(context, id)
        if (!file.parentFile.isDirectory && !file.parentFile.mkdirs()) {
            throw java.io.IOException("Could not establish result revision store")
        }
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        Durable.atomicWriteText(file, token)
    }

    /**
     * D2 is the producer-time fingerprint of the complete document directory, including the
     * PDF, page JPEGs, OCR, metadata and kept originals. Legacy D: only hashed scan.pdf:
     * it cannot authorize deleting a document whose pages changed behind that PDF.
     */
    fun captureDocs(repo: ScanRepository, docIds: List<String>, create: Boolean = false): String? {
        if (docIds.isEmpty() || docIds.size != docIds.distinct().size) return null
        return try {
            val parts = ArrayList<String>()
            for (id in docIds.sorted()) {
                if (!id.matches(Regex("[A-Za-z0-9_-]{1,96}"))) return null
                val doc = repo.getDocument(id) ?: return null
                val token = revision(repo.appContext(), id, create) ?: return null
                if (!doc.pdfFile.isFile || doc.pdfFile.length() == 0L) return null
                val dir = doc.pdfFile.parentFile ?: return null
                if (dir.absolutePath != dir.canonicalPath ||
                    dir.canonicalPath != File(repo.docsDir, id).canonicalPath) return null
                val files = dir.listFiles()?.sortedBy { it.name } ?: return null
                if (files.isEmpty()) return null
                val digest = MessageDigest.getInstance("SHA-256")
                for (file in files) {
                    if (!file.isFile || file.absolutePath != file.canonicalPath) return null
                    digest.update(file.name.toByteArray(Charsets.UTF_8))
                    digest.update(0)
                    // Saving a Documents copy records export URIs/HMACs in meta.txt.
                    // Those bookkeeping lines are not a change to the Library output.
                    // Every other metadata line (title, tags, category, timestamps) remains
                    // part of the destructive identity, so a renamed/organised doc is kept.
                    val hash = if (file.name == "meta.txt") {
                        val stable = file.readLines().filterNot { line ->
                            line.startsWith("exports=") ||
                                line.startsWith("exportsigs=") ||
                                line.startsWith("exportmade=")
                        }.joinToString("\n", postfix = "\n")
                        Sha256.of(stable.toByteArray(Charsets.UTF_8))
                    } else {
                        Sha256.of(file)
                    }
                    digest.update(hash.toByteArray(Charsets.US_ASCII))
                    digest.update(0)
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                parts += "$id|${doc.createdAt}|$token|$hash"
            }
            "D2:" + parts.joinToString(";")
        } catch (_: Exception) {
            null
        }
    }

    fun matches(
        context: Context,
        repo: ScanRepository,
        docIds: List<String>,
        files: List<File>,
        expected: String?,
        sourceId: String?
    ): Boolean {
        if (expected.isNullOrBlank() || expected.length > 64 * 1024) return false
        if (docIds.size != docIds.distinct().size || files.size != files.distinctBy { it.path }.size) return false
        if (docIds.any { it.isBlank() } || sourceId?.let { it in docIds } == true) return false
        if (docIds.isEmpty() && files.isEmpty()) return false
        return try {
            if (files.isNotEmpty()) {
                // Mixed doc IDs are input documents, never deletion candidates.
                if (!expected.startsWith("F:")) return false
                val candidates = files.sortedBy { it.path }
                if (candidates.any { !safeTemporaryOutput(context, it) }) return false
                val actual = "F:" + candidates.joinToString(";") {
                    "${it.path}|${it.length()}|${Sha256.of(it)}"
                }
                actual == expected
            } else {
                // Legacy D: covered scan.pdf only and is not sufficient for deleting pages.
                if (!expected.startsWith("D2:")) return false
                if (docIds.any { id ->
                        repo.getDocument(id)?.let { it.isVaulted || it.isLocked } != false
                    }) return false
                captureDocs(repo, docIds) == expected
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun safeTemporaryOutput(context: Context, f: File): Boolean {
        // Foreign/source paths, symlinks, directories and picker staging fail closed.
        if (!f.isFile || f.length() <= 0L) return false
        val absolute = f.absoluteFile.path
        val canonical = f.canonicalFile.path
        if (absolute != canonical) return false
        val bases = listOf(
            File(context.cacheDir, "documents"),
            File(context.cacheDir, "form1_out")
        )
        return bases.any { root ->
            val prefix = root.canonicalPath + File.separator
            canonical.startsWith(prefix)
        }
    }
}

/** Deterministic fault seam: invoked under the actual repository writer locks. */
internal object ResultRemovalFaults {
    @Volatile var afterVerified: (() -> Unit)? = null
}
