package com.scanwhirl.app.util

import android.content.Context
import com.scanwhirl.app.store.vault.VaultSession
import java.io.File

/**
 * Cold-start privacy sweep (security audit): nothing decrypted or shared survives a process
 * death.
 *
 * ScanWhirl keeps several plaintext copies outside the library for a short time: decrypted
 * Vault files in `cacheDir/vault_open`, temporary PDFs for the viewer and the PDF/A export,
 * decrypted imports, OCR `.txt` exports and the copies handed to the share sheet. A backup
 * streams the whole library through `cacheDir/fullbackup-<id>.tmp`, and a restore extracts the
 * decrypted archive into `cacheDir/restore-<id>.tmp` and `cacheDir/restore-<id>/`. Android may
 * leave all of them behind when the process is killed while they are in use, and the next
 * launch never needs them, so they are deleted before any screen is shown — unless a backup or
 * restore is running in this process right now.
 *
 * Outgoing share copies are the exception: a recipient app reads its content:// URI lazily and
 * Android may restart this process to serve it. They are therefore reclaimed by age in
 * [ShareUtils.pruneShareCopies] (after 24 h), not wiped on sight.
 *
 * The capture tools' caches are the other exception: this sweep also runs after a process death,
 * when IdCard/Passport/Business card still need their saved photo to restore the person's work.
 * The stamp editor's saved marks (signature + redaction) are the same kind of restorable state.
 * Entries in [CAPTURE_CACHE_DIRS] are therefore only reclaimed once they are older than
 * [CAPTURE_CACHE_MAX_AGE_MS] (24 h), long past any restore; a real screen exit deletes them at once.
 *
 * Only app-owned cache directories are touched; the library (`filesDir/documents`), the Vault
 * (`filesDir/vault2`) and user exports are never included.
 */
object PrivacySweep {

    /**
     * Age after which an abandoned capture-cache entry may be swept. A cold start following a
     * process death happens within seconds, so 24 h keeps every restorable capture intact while
     * still reclaiming caches from long-abandoned flows. Matches the share-copy retention window.
     */
    internal const val CAPTURE_CACHE_MAX_AGE_MS = 24L * 60 * 60 * 1000

    /**
     * Caches that need to survive a process-death restore and are swept by age only. Besides the
     * T1 capture photos, `stamp_editor` holds the serialised signature/redaction marks written in
     * StampEditorActivity.onSaveInstanceState; a quick process death must still be able to restore
     * that work, so it follows the same 24 h age gate.
     */
    internal val CAPTURE_CACHE_DIRS = setOf(
        "t1_passport", "t1_business_card", "id_card_state", "stamp_editor"
    )

    /** Cache directories (relative to cacheDir) that only ever hold plaintext copies. */
    internal val CACHE_DIRS = listOf(
        "vault_open",
        "vault_verify",
        "pdfa",
        "exports",
        "fullbackup",
        // T1 capture tools: plaintext card/ID photos cached for rotation and process-death
        // restore; swept by age in [CAPTURE_CACHE_DIRS], not on sight.
        "t1_passport",
        "t1_business_card",
        "id_card_state",
        // S2 item 1: serialised stamp-editor marks (signature + redaction), age-gated like the
        // capture caches so a process-death restore within 24 h still finds them.
        "stamp_editor"
    )

    /** Cache files (relative to cacheDir) whose names start with a marker. */
    internal val CACHE_FILE_PREFIXES = listOf(
        "decrypted-",
        "preview_",
        "ocr_"
    )

    /** Cache directories (relative to cacheDir) whose names start with a marker. */
    internal val CACHE_DIR_PREFIXES = listOf(
        "insert_"
    )

    /**
     * Cache names a backup or a restore creates while it runs: the plaintext archive of a
     * backup (`fullbackup-<id>.tmp`), and the decrypted archive plus its extracted document
     * tree of a restore (`restore-<id>.tmp`, `restore-<id>/`). They are only swept when no
     * job is using them: [backupRunning] reads the marker FullBackup holds.
     */
    internal val BACKUP_CACHE_PREFIXES = listOf(
        "fullbackup-",
        "restore-"
    )

    /** Whether a backup or restore is running in this process (injectable for tests). */
    @Volatile
    internal var backupRunning: () -> Boolean = {
        com.scanwhirl.app.store.backup.FullBackup.isRunning
    }

    /** Injectable delete operations: one failing entry must never abort the rest of the sweep. */
    internal var deleteDirectory: (File) -> Boolean = { it.deleteRecursively() }
    internal var deleteFile: (File) -> Boolean = { it.delete() }

    internal fun resetForTests() {
        backupRunning = { com.scanwhirl.app.store.backup.FullBackup.isRunning }
        deleteDirectory = { it.deleteRecursively() }
        deleteFile = { it.delete() }
    }

    /** Runs the sweep; returns how many entries were removed (for tests and diagnostics). */
    fun run(context: Context): Int {
        val cache = context.applicationContext.cacheDir ?: return 0
        // A backup or a restore that is running in this process owns its cache files: sweeping
        // them would break the job it is in the middle of.
        val jobRunning = backupRunning()
        var removed = 0
        // These directories are private seekable picked-file copies, not outgoing shares.
        // At cold start the ownership registry is empty. A repeated sweep leaves live jobs
        // and readers (including a reader retained across rotation) alone.
        listOf("pdf_fd", "pdf_open").forEach { name ->
            File(cache, name).listFiles()?.forEach { file ->
                try {
                    if (BoundedCopy.removeIfUnowned(file)) removed++
                } catch (e: Exception) {
                    // T1: an unremovable entry is left for another launch; keep sweeping.
                }
            }
        }
        CACHE_DIRS.forEach { name ->
            // `vault_open` is recreated by the Vault when it opens; while unlocked it belongs
            // to the running session and is left alone.
            if (name == "vault_open" && VaultSession.isUnlocked()) return@forEach
            // `fullbackup` holds the archive a running backup is writing or sharing.
            if (name == "fullbackup" && jobRunning) return@forEach
            val dir = File(cache, name)
            if (!dir.exists()) return@forEach
            if (name in CAPTURE_CACHE_DIRS) {
                // A process killed mid-flow needs these bytes on the next start: only entries
                // older than a day are abandoned. Each delete stays independent.
                val now = System.currentTimeMillis()
                dir.listFiles()?.forEach { entry ->
                    if (now - entry.lastModified() <= CAPTURE_CACHE_MAX_AGE_MS) return@forEach
                    try {
                        val gone = if (entry.isDirectory) deleteDirectory(entry) else deleteFile(entry)
                        if (gone) removed++
                    } catch (e: Exception) {
                        // Leave this entry for another launch; keep sweeping the others.
                    }
                }
            } else {
                try {
                    if (deleteDirectory(dir)) removed++
                } catch (e: Exception) {
                    // T1: one bad entry must not skip the remaining plaintext copies.
                }
            }
        }
        // `documents` is the FileProvider tree: viewer/batch scratch plus the per-share copies.
        // Everything except the share folder is scratch and is wiped; share copies follow the
        // retention rule so a recipient still reading one keeps its bytes.
        val documents = File(cache, "documents")
        if (documents.isDirectory) {
            documents.listFiles()?.forEach { child ->
                if (child.name == "share") return@forEach
                try {
                    val gone = if (child.isDirectory) deleteDirectory(child) else deleteFile(child)
                    if (gone) removed++
                } catch (e: Exception) {
                    // T1: keep sweeping the other scratch folders.
                }
            }
            removed += try {
                ShareUtils.pruneShareCopies(context)
            } catch (e: Exception) {
                0
            }
        }
        cache.listFiles()?.forEach { file ->
            val plainFile = file.isFile && CACHE_FILE_PREFIXES.any { file.name.startsWith(it) }
            val plainDir = file.isDirectory && CACHE_DIR_PREFIXES.any { file.name.startsWith(it) }
            // The plaintext archive of an interrupted backup (`fullbackup-<id>.tmp`), and the
            // decrypted archive plus its document tree of an interrupted restore
            // (`restore-<id>.tmp`, `restore-<id>/`). A live job keeps its own.
            val backupTemp = BACKUP_CACHE_PREFIXES.any { file.name.startsWith(it) }
            try {
                when {
                    plainFile -> if (deleteFile(file)) removed++
                    plainDir -> if (deleteDirectory(file)) removed++
                    backupTemp && !jobRunning -> {
                        val gone = if (file.isDirectory) deleteDirectory(file) else deleteFile(file)
                        if (gone) removed++
                    }
                }
            } catch (e: Exception) {
                // T1: keep sweeping the remaining cache entries.
            }
        }
        return removed
    }
}
