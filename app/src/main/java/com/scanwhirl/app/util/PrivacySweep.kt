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
 * Only app-owned cache directories are touched; the library (`filesDir/documents`), the Vault
 * (`filesDir/vault2`) and user exports are never included.
 */
object PrivacySweep {

    /** Cache directories (relative to cacheDir) that only ever hold plaintext copies. */
    internal val CACHE_DIRS = listOf(
        "vault_open",
        "vault_verify",
        "pdfa",
        "exports",
        "fullbackup"
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

    internal fun resetForTests() {
        backupRunning = { com.scanwhirl.app.store.backup.FullBackup.isRunning }
    }

    /** Runs the sweep; returns how many entries were removed (for tests and diagnostics). */
    fun run(context: Context): Int {
        val cache = context.applicationContext.cacheDir ?: return 0
        // A backup or a restore that is running in this process owns its cache files: sweeping
        // them would break the job it is in the middle of.
        val jobRunning = backupRunning()
        var removed = 0
        CACHE_DIRS.forEach { name ->
            // `vault_open` is recreated by the Vault when it opens; while unlocked it belongs
            // to the running session and is left alone.
            if (name == "vault_open" && VaultSession.isUnlocked()) return@forEach
            // `fullbackup` holds the archive a running backup is writing or sharing.
            if (name == "fullbackup" && jobRunning) return@forEach
            val dir = File(cache, name)
            if (dir.exists() && dir.deleteRecursively()) removed++
        }
        // `documents` is the FileProvider tree: viewer/batch scratch plus the per-share copies.
        // Everything except the share folder is scratch and is wiped; share copies follow the
        // retention rule so a recipient still reading one keeps its bytes.
        val documents = File(cache, "documents")
        if (documents.isDirectory) {
            documents.listFiles()?.forEach { child ->
                if (child.name == "share") return@forEach
                val gone = if (child.isDirectory) child.deleteRecursively() else child.delete()
                if (gone) removed++
            }
            removed += ShareUtils.pruneShareCopies(context)
        }
        cache.listFiles()?.forEach { file ->
            val plainFile = file.isFile && CACHE_FILE_PREFIXES.any { file.name.startsWith(it) }
            val plainDir = file.isDirectory && CACHE_DIR_PREFIXES.any { file.name.startsWith(it) }
            // The plaintext archive of an interrupted backup (`fullbackup-<id>.tmp`), and the
            // decrypted archive plus its document tree of an interrupted restore
            // (`restore-<id>.tmp`, `restore-<id>/`). A live job keeps its own.
            val backupTemp = BACKUP_CACHE_PREFIXES.any { file.name.startsWith(it) }
            when {
                plainFile -> if (file.delete()) removed++
                plainDir -> if (file.deleteRecursively()) removed++
                backupTemp && !jobRunning -> {
                    if (file.isDirectory) {
                        if (file.deleteRecursively()) removed++
                    } else if (file.delete()) {
                        removed++
                    }
                }
            }
        }
        return removed
    }
}
