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
 * decrypted imports, OCR `.txt` exports and the copies handed to the share sheet. Android may
 * leave all of them behind when the process is killed while they are in use, and the next
 * launch never needs them, so they are deleted before any screen is shown.
 *
 * Only app-owned cache directories are touched; the library (`filesDir/documents`), the Vault
 * (`filesDir/vault2`) and user exports are never included.
 */
object PrivacySweep {

    /** Cache directories (relative to cacheDir) that only ever hold plaintext copies. */
    internal val CACHE_DIRS = listOf(
        "vault_open",
        "vault_verify",
        "documents",
        "pdfa",
        "exports"
    )

    /** Cache files (relative to cacheDir) whose names start with a marker. */
    internal val CACHE_FILE_PREFIXES = listOf(
        "decrypted-",
        "preview_",
        "ocr_"
    )

    /** Runs the sweep; returns how many entries were removed (for tests and diagnostics). */
    fun run(context: Context): Int {
        val cache = context.applicationContext.cacheDir ?: return 0
        var removed = 0
        CACHE_DIRS.forEach { name ->
            // `vault_open` is recreated by the Vault when it opens; while unlocked it belongs
            // to the running session and is left alone.
            if (name == "vault_open" && VaultSession.isUnlocked()) return@forEach
            val dir = File(cache, name)
            if (dir.exists() && dir.deleteRecursively()) removed++
        }
        cache.listFiles()?.forEach { file ->
            if (file.isFile && CACHE_FILE_PREFIXES.any { file.name.startsWith(it) }) {
                if (file.delete()) removed++
            }
        }
        return removed
    }
}
