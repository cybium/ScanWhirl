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
    // Producer-only file ledger. No intent extra, cheapIdentity() or ordinary caller can
    // create an entry: only captureOutputIdentityBlocking (the real producer's IO capture)
    // calls recordFileProducer after the bytes are published.
    private const val FILE_LEDGER_SUBDIR = "result_authority_v1/files"
    private const val FILE_LEDGER_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L
    private const val FILE_LEDGER_MAX_ENTRIES = 512
    private val UUID_RUN =
        Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    private fun fileLedgerDir(context: Context): File = File(context.noBackupFilesDir, FILE_LEDGER_SUBDIR)

    /** Entry key = SHA-256 of the exact F: identity string, never of a caller-supplied path. */
    private fun fileLedgerFile(context: Context, identity: String): File =
        File(fileLedgerDir(context), Sha256.of(identity.toByteArray(Charsets.UTF_8)) + ".txt")

    private fun ledgerCreated(file: File): Long? = try {
        Regex("^created=(\\d+)$", RegexOption.MULTILINE)
            .find(file.readText())?.groupValues?.get(1)?.toLongOrNull()
    } catch (_: Exception) {
        null
    }

    /**
     * Producer-time issuance. Called only from captureOutputIdentityBlocking for file results.
     * Returns false when the ledger cannot be written; the identity may still display, but
     * destructive removal then fails closed because no entry exists.
     */
    fun recordFileProducer(context: Context, identity: String): Boolean {
        if (!identity.startsWith("F:")) return false
        return try {
            val file = fileLedgerFile(context, identity)
            val dir = file.parentFile ?: return false
            if (!dir.isDirectory && !dir.mkdirs()) return false
            val digest = Sha256.of(identity.toByteArray(Charsets.UTF_8))
            Durable.atomicWriteText(
                file,
                "v1\ncreated=${System.currentTimeMillis()}\nlen=${identity.length}\nhash=$digest\n"
            )
            pruneFileLedger(context)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Bounded store: expire entries after seven days and cap the count at FILE_LEDGER_MAX_ENTRIES. */
    private fun pruneFileLedger(context: Context) {
        try {
            val now = System.currentTimeMillis()
            val keep = ArrayList<Pair<File, Long>>()
            fileLedgerDir(context).listFiles().orEmpty().filter { it.isFile }.forEach { file ->
                val created = ledgerCreated(file)
                if (created == null || now - created > FILE_LEDGER_MAX_AGE_MS) file.delete()
                else keep += file to created
            }
            if (keep.size > FILE_LEDGER_MAX_ENTRIES) {
                keep.sortedBy { it.second }
                    .take(keep.size - FILE_LEDGER_MAX_ENTRIES)
                    .forEach { it.first.delete() }
            }
        } catch (_: Exception) {
            // A failed sweep never grants authority; validity is checked independently below.
        }
    }

    /** An entry is usable only when it is fresh and its length/hash match the exact identity. */
    fun fileLedgerEntryValid(context: Context, identity: String): Boolean {
        val file = fileLedgerFile(context, identity)
        if (!file.isFile) return false
        return try {
            val text = file.readText()
            val created = Regex("^created=(\\d+)$", RegexOption.MULTILINE).find(text)
                ?.groupValues?.get(1)?.toLongOrNull() ?: return false
            val len = Regex("^len=(\\d+)$", RegexOption.MULTILINE).find(text)
                ?.groupValues?.get(1)?.toIntOrNull() ?: return false
            val hash = Regex("^hash=([0-9a-f]{64})$", RegexOption.MULTILINE).find(text)
                ?.groupValues?.get(1) ?: return false
            if (len != identity.length ||
                hash != Sha256.of(identity.toByteArray(Charsets.UTF_8))
            ) return false
            val age = System.currentTimeMillis() - created
            age in 0L..FILE_LEDGER_MAX_AGE_MS
        } catch (_: Exception) {
            false
        }
    }

    /** One-shot: consumed only after the whole verified batch was unlinked. */
    fun consumeFileLedger(context: Context, identity: String) {
        if (!identity.startsWith("F:")) return
        runCatching { fileLedgerFile(context, identity).delete() }
    }

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
                // Two independent gates: the receipt must be one a real producer issued, and
                // every named path must still be a run-unique published output. A valid F:
                // string alone (forged, legacy, or captured on an arbitrary cache file) has
                // no ledger entry and is refused.
                if (!fileLedgerEntryValid(context, expected)) return false
                val candidates = files.sortedBy { it.path }
                if (candidates.any { !safeTemporaryOutput(context, it) }) return false
                // Residual (bounded, NOT closed): another in-app writer that ignores the
                // repository writer lock can still replace a file's bytes between this SHA
                // recheck and the later unlink. Run-unique dirs and the one-shot ledger make
                // this window narrow, but no lease/framework serializes such a writer here.
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

    /**
     * Only canonical, real (non-symlink) regular files inside a run-unique producer output
     * directory. Files directly in the cache/documents root, the cache root, source/staging
     * dirs, picker/Saf dirs and directories are all refused. The SHA recheck stays in matches().
     */
    private fun safeTemporaryOutput(context: Context, f: File): Boolean {
        if (!f.isFile || f.length() <= 0L) return false
        val absolute = f.absoluteFile.path
        val canonical = f.canonicalFile.path
        if (absolute != canonical) return false
        val sep = File.separator
        val docs = File(context.cacheDir, "documents").canonicalPath + sep
        val form = File(context.cacheDir, "form1_out").canonicalPath + sep
        val inDocs = canonical.startsWith(docs)
        val inForm = !inDocs && canonical.startsWith(form)
        if (!inDocs && !inForm) return false
        val parts = (if (inDocs) canonical.removePrefix(docs) else canonical.removePrefix(form))
            .split(sep)
        // At least one run directory between the root and the file.
        if (parts.size < 2) return false
        return if (inDocs) {
            // Published producers: documents/tools/<run>/<file> and documents/share/<run>/<file>.
            // Source/staging trees (resize_input, resize, preview_*) are never deletion candidates.
            parts.size >= 3 && (parts[0] == "tools" || parts[0] == "share")
        } else {
            // FormReady publishes into form1_out/<uuid>/<final name>.jpg, one dir per run,
            // so an old receipt cannot delete another run's similarly named output.
            parts.size == 2 && UUID_RUN.matches(parts[0])
        }
    }
}

/** Deterministic fault seam: invoked under the actual repository writer locks. */
internal object ResultRemovalFaults {
    @Volatile var afterVerified: (() -> Unit)? = null
}
