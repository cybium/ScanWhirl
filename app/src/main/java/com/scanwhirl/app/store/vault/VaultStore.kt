package com.scanwhirl.app.store.vault

import android.content.Context
import android.util.Base64
import com.scanwhirl.app.store.OpFaults
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.Durable
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.Sha256
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * The v2 Vault (design §4.1): private files encrypted on this phone.
 *
 * Layout under `filesDir/vault2/`:
 *   - `recovery.bin`: the vault key wrapped by the recovery code (PBKDF2). Its presence is
 *     "the Vault is set up"; it is portable, so a `.swvault` backup restores from the code alone.
 *   - `passcode.bin`: the same key wrapped by the 6+ digit passcode.
 *   - `keystore.bin`: the same key wrapped by the auth-bound Android Keystore key; unwrapping it
 *     needs a `BiometricPrompt` CryptoObject (fingerprint/face), see [VaultKeys].
 *   - `<documentId>/`: `manifest.enc` plus one `fNNNN.enc` per source file. Manifests and
 *     every file are AES-256-GCM; nothing under this directory is readable plaintext.
 *   - `passwords.bin`: PDF passwords remembered for documents ([VaultPasswords]), the same
 *     AES-256-GCM container under the vault key.
 *
 * The unlocked key lives in [VaultSession], not in this object, so every screen (the Vault
 * screen, a PDF viewer, [VaultPasswords] for lane L3) sees one lock state and the 2-minute
 * background grace expires the key once for all of them.
 *
 * [addDocument] encrypts every file of a library document into a hidden staging folder,
 * decrypts it again to verify every SHA-256, publishes the entry atomically, then moves the
 * original to Trash and purges it. A fault before the publish leaves the original untouched;
 * a fault after it is finished by [recover] on the next start.
 *
 * Real-device Keystore behaviour is DEVICE-UNPROVEN (Robolectric has no hardware Keystore;
 * tests use the [VaultKeys] seam).
 */
class VaultStore(
    private val context: Context,
    private val repo: ScanRepository,
    private val keys: VaultKeys = AndroidVaultKeys(),
    private val gate: VaultPasscodeGate = VaultPasscodeGate(),
    private val iterations: Int = VaultCrypto.PBKDF2_ITERATIONS
) {

    companion object {
        const val MIN_PASSCODE_LENGTH = 6

        private val LOCK = Any()
        private const val VAULT_DIR = "vault2"
        private const val OPEN_DIR = "vault_open"
        private const val PASSCODE_FILE = "passcode.bin"
        private const val RECOVERY_FILE = "recovery.bin"
        private const val KEYSTORE_FILE = "keystore.bin"
        private const val MANIFEST_FILE = "manifest.enc"
        private const val STAGING_PREFIX = ".incoming-"
        private const val RESTORE_PREFIX = ".restore-"
        private const val MANIFEST_VERSION = "v1"
    }

    data class Setup(val recoveryCode: String)

    data class Entry(
        val id: String,
        val name: String,
        val createdAt: Long,
        val addedAt: Long,
        val pageCount: Int,
        val fileCount: Int,
        val sizeBytes: Long
    )

    /** A decrypted document in `cacheDir/vault_open/<id>`, deleted by [lock]. */
    data class Opened(
        val id: String,
        val name: String,
        val dir: File,
        val pageFiles: List<File>,
        val pdfFile: File
    )

    data class UnlockResult(val ok: Boolean, val pauseMillis: Long = 0, val triesLeft: Int = 0)

    private data class FileRecord(val rel: String, val stored: String, val size: Long, val sha: String)

    private data class Manifest(
        val id: String,
        val name: String,
        val createdAt: Long,
        val addedAt: Long,
        val pageCount: Int,
        /** Relative path of the document PDF inside the entry (its name comes from the document). */
        val pdf: String,
        val files: List<FileRecord>
    ) {
        fun toEntry() = Entry(
            id = id,
            name = name,
            createdAt = createdAt,
            addedAt = addedAt,
            pageCount = pageCount,
            fileCount = files.size,
            sizeBytes = files.sumOf { it.size }
        )
    }

    private val vaultDir: File
        get() = File(context.filesDir, VAULT_DIR)

    private val openDir: File
        get() = File(context.cacheDir, OPEN_DIR)

    // ------------------------------------------------------------------
    // Setup and unlock
    // ------------------------------------------------------------------

    fun isSetUp(): Boolean = File(vaultDir, RECOVERY_FILE).let { it.isFile && it.length() > 0 }

    fun isUnlocked(): Boolean = VaultSession.isUnlocked()

    /**
     * True when the fingerprint path can be attempted: a Keystore wrap exists on this phone,
     * the Keystore opens, and its auth-bound key has not been invalidated. The screen still
     * asks `BiometricManager.canAuthenticate(BIOMETRIC_STRONG)` before offering it.
     */
    fun canUseBiometric(): Boolean =
        File(vaultDir, KEYSTORE_FILE).isFile && keys.isAvailable() && keys.hasKey()

    fun hasLegacyPin(): Boolean = Prefs.hasVaultPin(context)

    fun verifyLegacyPin(pin: String): Boolean = Prefs.verifyVaultPin(context, pin)

    /**
     * First-run setup: one random vault key, wrapped by the passcode and by a new recovery
     * code. The code is returned once (formatted in groups of four); it is never stored.
     */
    fun setup(passcode: String): Setup = synchronized(LOCK) {
        if (isSetUp()) throw IllegalStateException("The Vault is already set up")
        requireValidPasscode(passcode)
        if (!vaultDir.mkdirs() && !vaultDir.isDirectory) throw IOException("Could not open the Vault folder")

        val key = VaultCrypto.newKey()
        val recoveryCode = VaultCrypto.generateRecoveryCode()
        val passBlob = VaultKeyWrap.seal(key, passcode, VaultCrypto.newSalt(), iterations)
        val recoveryBlob = VaultKeyWrap.seal(key, recoveryCode, VaultCrypto.newSalt(), iterations)

        Durable.atomicWrite(File(vaultDir, PASSCODE_FILE), passBlob)
        // Written last: its presence is the "set up" marker.
        Durable.atomicWrite(File(vaultDir, RECOVERY_FILE), recoveryBlob)
        Durable.syncDir(vaultDir)
        VaultSession.open(key)
        Setup(VaultCrypto.formatRecoveryCode(recoveryCode))
    }

    fun unlockWithPasscode(passcode: String): UnlockResult = synchronized(LOCK) {
        val paused = gate.pauseMillis()
        if (paused > 0) return UnlockResult(false, paused, gate.triesLeft())
        if (!isSetUp()) return UnlockResult(false)

        val key = try {
            VaultKeyWrap.open(File(vaultDir, PASSCODE_FILE).readBytes(), passcode)
        } catch (e: Exception) {
            null
        }
        if (key == null) {
            return UnlockResult(false, gate.onWrong(), gate.triesLeft())
        }
        gate.onSuccess()
        VaultSession.open(key)
        UnlockResult(true, triesLeft = gate.triesLeft())
    }

    /** Recovery-code unlock (also after restoring a backup on a new phone). */
    fun unlockWithRecovery(code: String): Boolean = synchronized(LOCK) {
        if (!isSetUp() || !VaultCrypto.isRecoveryCode(code)) return false
        val key = try {
            VaultKeyWrap.open(
                File(vaultDir, RECOVERY_FILE).readBytes(),
                VaultCrypto.normalizeRecoveryCode(code)
            )
        } catch (e: Exception) {
            null
        } ?: return false
        gate.onSuccess()
        VaultSession.open(key)
        true
    }

    /**
     * Prepares the fingerprint unlock: the Keystore decrypt that `BiometricPrompt` must
     * authorize. The wrap file is dropped when the key was invalidated, so the screen falls
     * back to the passcode and the next passcode unlock re-wraps with a fresh key.
     */
    fun beginBiometricUnlock(): VaultKeyChallenge = synchronized(LOCK) {
        val blob = File(vaultDir, KEYSTORE_FILE)
        if (!blob.isFile) throw VaultAuthException("No fingerprint key on this phone")
        try {
            keys.challenge(blob.readBytes())
        } catch (e: VaultKeyInvalidatedException) {
            blob.delete()
            throw e
        }
    }

    /** Completes the fingerprint unlock after the prompt authorized the challenge's cipher. */
    fun completeBiometricUnlock(challenge: VaultKeyChallenge): Boolean = synchronized(LOCK) {
        val key = try {
            challenge.complete()
        } catch (e: VaultAuthException) {
            // The wrap belongs to another key (old alias, reinstall): rebuild it next unlock.
            File(vaultDir, KEYSTORE_FILE).delete()
            return false
        }
        if (key.size != VaultCrypto.KEY_BYTES) return false
        gate.onSuccess()
        VaultSession.open(key)
        true
    }

    /** Drops the key and every decrypted copy. */
    fun lock() = synchronized(LOCK) {
        VaultSession.close()
        openDir.deleteRecursively()
    }

    fun changePasscode(passcode: String) = synchronized(LOCK) {
        val key = requireKey()
        requireValidPasscode(passcode)
        Durable.atomicWrite(
            File(vaultDir, PASSCODE_FILE),
            VaultKeyWrap.seal(key, passcode, VaultCrypto.newSalt(), iterations)
        )
    }

    /** Issues a new recovery code (the old one stops working). Shown once. */
    fun changeRecoveryCode(): String = synchronized(LOCK) {
        val key = requireKey()
        val code = VaultCrypto.generateRecoveryCode()
        Durable.atomicWrite(
            File(vaultDir, RECOVERY_FILE),
            VaultKeyWrap.seal(key, code, VaultCrypto.newSalt(), iterations)
        )
        VaultCrypto.formatRecoveryCode(code)
    }

    // ------------------------------------------------------------------
    // Entries
    // ------------------------------------------------------------------

    fun listEntries(): List<Entry> = synchronized(LOCK) {
        val key = requireKey()
        entryDirs().mapNotNull { dir ->
            try {
                readManifest(dir, key).toEntry()
            } catch (e: Exception) {
                null
            }
        }.sortedByDescending { it.addedAt }
    }

    fun count(): Int = synchronized(LOCK) { entryDirs().size }

    fun totalFiles(): Int = synchronized(LOCK) { listEntries().sumOf { it.fileCount } }

    fun totalBytes(): Long = synchronized(LOCK) { listEntries().sumOf { it.sizeBytes } }

    fun hasEntry(id: String): Boolean = File(vaultDir, id).let { it.isDirectory && File(it, MANIFEST_FILE).isFile }

    /**
     * Encrypts every file of the library document [docId], verifies the encrypted copy,
     * publishes it, then moves the plain original to Trash and purges it.
     */
    fun addDocument(docId: String): Entry = synchronized(LOCK) {
        val key = requireKey()
        val doc = repo.getDocument(docId) ?: throw IOException("This document no longer exists")
        val src = File(repo.docsDir, docId)
        if (!src.isDirectory) throw IOException("This document no longer exists")
        val target = File(vaultDir, docId)
        if (target.exists()) throw IllegalStateException("This document is already in the Vault")
        if (!vaultDir.mkdirs() && !vaultDir.isDirectory) throw IOException("Could not open the Vault folder")

        val staging = File(vaultDir, "$STAGING_PREFIX$docId-${UUID.randomUUID().toString().take(8)}")
        if (!staging.mkdirs()) throw IOException("Could not open the Vault folder")
        var published = false
        var trashed = false

        try {
            OpFaults.at("vault:add:start")
            val files = src.walkTopDown()
                .filter { it.isFile }
                .sortedBy { it.relativeTo(src).invariantSeparatorsPath }
                .mapIndexed { index, file ->
                    val stored = "f%04d.enc".format(index)
                    val digest = Sha256.of(file)
                    VaultCrypto.encryptFile(file, File(staging, stored), key)
                    OpFaults.at("vault:file")
                    FileRecord(
                        rel = file.relativeTo(src).invariantSeparatorsPath,
                        stored = stored,
                        size = file.length(),
                        sha = digest
                    )
                }
                .toList()
            if (files.isEmpty()) throw IOException("This document has no files")

            val manifest = Manifest(
                id = docId,
                name = doc.name,
                createdAt = doc.createdAt,
                addedAt = System.currentTimeMillis(),
                pageCount = doc.pageCount,
                pdf = doc.pdfFile.relativeTo(src).invariantSeparatorsPath,
                files = files
            )
            Durable.atomicWrite(File(staging, MANIFEST_FILE), VaultCrypto.encryptBytes(manifest.encode(), key))
            OpFaults.at("vault:encrypted")

            verifyEntry(staging, manifest, key)
            OpFaults.at("vault:verified")

            if (!staging.renameTo(target)) throw IOException("Could not add this document to the Vault")
            published = true
            OpFaults.at("vault:published")

            val trashKey = repo.moveToTrash(docId)
            trashed = true
            OpFaults.at("vault:trashed")

            // The encrypted entry is verified, so the plaintext Trash copy can go now.
            if (trashKey != null) {
                try {
                    repo.deleteFromTrash(trashKey)
                } catch (_: Exception) {
                }
            }
            manifest.toEntry()
        } catch (e: Exception) {
            if (!published) {
                staging.deleteRecursively()
            } else if (!trashed) {
                // The original is still in the library; drop the half-finished entry.
                target.deleteRecursively()
            }
            // When the move to Trash already happened, the complete entry is kept and
            // recover() finishes the purge.
            throw e
        }
    }

    fun openDocument(id: String): Opened = synchronized(LOCK) {
        val key = requireKey()
        val entryDir = File(vaultDir, id)
        if (!entryDir.isDirectory) throw IOException("This document is not in the Vault")
        val manifest = readManifest(entryDir, key)
        val target = File(openDir, id)
        target.deleteRecursively()
        if (!target.mkdirs()) throw IOException("Could not open this document")

        try {
            val basePath = target.canonicalPath + File.separator
            manifest.files.forEach { record ->
                val dest = File(target, record.rel)
                if (!dest.canonicalPath.startsWith(basePath)) {
                    throw VaultAuthException("Damaged Vault entry")
                }
                dest.parentFile?.mkdirs()
                VaultCrypto.decryptFile(File(entryDir, record.stored), dest, key)
                if (dest.length() != record.size || Sha256.of(dest) != record.sha) {
                    throw VaultAuthException("The Vault copy does not match the original")
                }
            }
        } catch (t: Throwable) {
            target.deleteRecursively()
            throw t
        }

        val pageFiles = manifest.files
            .filter { it.rel.startsWith("page_") && it.rel.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png") }
            .sortedBy { it.rel }
            .map { File(target, it.rel) }
        val pdf = File(target, manifest.pdf)
        Opened(id = id, name = manifest.name, dir = target, pageFiles = pageFiles, pdfFile = pdf)
    }

    /** Removes the encrypted entry. The plaintext was already purged when it was added. */
    fun removeFromVault(id: String) = synchronized(LOCK) {
        requireKey()
        entryDir(id).deleteRecursively()
        File(openDir, id).deleteRecursively()
    }

    // ------------------------------------------------------------------
    // Legacy PIN migration
    // ------------------------------------------------------------------

    /**
     * One confirmed migration: the old 4-digit PIN is verified by the caller, then every
     * legacy vault-flagged document is encrypted into the new Vault. A failure puts the
     * document back in the legacy vault list, never loses it.
     */
    fun migrateLegacyVault(): Int = synchronized(LOCK) {
        requireKey()
        var moved = 0
        repo.listVaultDocuments().forEach { doc ->
            repo.setVaulted(doc.id, false)
            try {
                addDocument(doc.id)
                moved++
            } catch (e: Exception) {
                if (repo.getDocument(doc.id) != null) repo.setVaulted(doc.id, true)
                throw e
            }
        }
        moved
    }

    // ------------------------------------------------------------------
    // Backup / restore, startup recovery
    // ------------------------------------------------------------------

    /** Writes a `.swvault` file: encrypted entries plus the key wrapped by the recovery code. */
    fun exportBackup(dest: File): File = synchronized(LOCK) {
        if (!isSetUp()) throw IllegalStateException("Set up the Vault first")
        val recovery = File(vaultDir, RECOVERY_FILE).readBytes()
        // Remembered PDF passwords travel with the backup (still encrypted under the vault key).
        val extras = listOf(File(vaultDir, VaultPasswords.FILE_NAME)).filter { it.isFile }
        VaultBackup.write(dest, recovery, entryDirs(), extras)
        dest
    }

    /**
     * Restores a backup on this phone with the recovery code alone. It leaves the Vault
     * unlocked so the user can set a new passcode; no old passcode or Keystore key is needed.
     */
    fun importBackup(src: File, recoveryCode: String): List<Entry> = synchronized(LOCK) {
        if (!VaultCrypto.isRecoveryCode(recoveryCode)) throw VaultAuthException("That recovery code is not valid")
        if (entryDirs().isNotEmpty()) throw IllegalStateException("This Vault already has files in it")

        vaultDir.mkdirs()
        val staging = File(vaultDir, "$RESTORE_PREFIX${UUID.randomUUID().toString().take(8)}")
        try {
            val contents = VaultBackup.extract(src, staging)
            val key = VaultKeyWrap.open(contents.recoveryBlob, VaultCrypto.normalizeRecoveryCode(recoveryCode))

            val verified = contents.entryIds.map { id ->
                val dir = File(staging, id)
                val manifest = readManifest(dir, key)
                verifyEntry(dir, manifest, key)
                manifest
            }
            // The remembered passwords are encrypted with the same key: prove they open before
            // publishing them, so a damaged blob fails the restore instead of the first unlock.
            contents.extras.forEach { extra ->
                if (extra.name == VaultPasswords.FILE_NAME) VaultPasswords.verifyFile(extra, key)
            }

            val moved = ArrayList<File>()
            try {
                verified.forEach { manifest ->
                    val from = File(staging, manifest.id)
                    val to = File(vaultDir, manifest.id)
                    if (to.exists()) throw IOException("This document is already in the Vault")
                    if (!from.renameTo(to)) throw IOException("Could not restore this document")
                    moved += to
                }
                contents.extras.forEach { extra ->
                    val to = File(vaultDir, extra.name)
                    if (extra.name == VaultPasswords.FILE_NAME && to.exists()) to.delete()
                    if (!extra.renameTo(to)) throw IOException("Could not restore the Vault settings")
                }
                // Portable state: the key wrapped by the code. The fingerprint wrap is created
                // separately (it needs a prompt), by beginKeystoreWrap/completionKeystoreWrap.
                Durable.atomicWrite(File(vaultDir, RECOVERY_FILE), contents.recoveryBlob)
                File(vaultDir, PASSCODE_FILE).delete()
            } catch (e: Exception) {
                moved.forEach { it.deleteRecursively() }
                throw e
            }

            VaultSession.open(key)
            listEntries()
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * Startup recovery, safe while locked:
     *  - drops abandoned staging folders;
     *  - finishes an add that died after the encrypted entry was published: the library
     *    original is moved to Trash and purged (the verified encrypted entry is the copy);
     *  - purges a Trash copy of anything now in the Vault, so no plaintext outlives it;
     *  - wipes decrypted cache copies while the Vault is locked.
     */
    fun recover() = synchronized(LOCK) {
        if (!isUnlocked()) openDir.deleteRecursively()
        vaultDir.listFiles()?.forEach { f ->
            if (f.isDirectory && (f.name.startsWith(STAGING_PREFIX) || f.name.startsWith(RESTORE_PREFIX))) {
                f.deleteRecursively()
            }
        }
        if (!isSetUp()) return@synchronized

        entryDirs().forEach { dir ->
            val id = dir.name
            if (File(repo.docsDir, id).exists()) {
                try {
                    val key = repo.moveToTrash(id)
                    if (key != null) repo.deleteFromTrash(key)
                } catch (_: Exception) {
                }
            }
        }
        repo.listTrash().forEach { item ->
            if (File(vaultDir, item.originalId).isDirectory) {
                try {
                    repo.deleteFromTrash(item.key)
                } catch (_: Exception) {
                }
            }
        }
    }

    /** Removes every Vault file and key (used by "remove all" and by restore tests). */
    fun wipeAll() = synchronized(LOCK) {
        VaultSession.close()
        vaultDir.deleteRecursively()
        openDir.deleteRecursively()
        File(context.cacheDir, "vault_verify").deleteRecursively()
        try {
            keys.delete()
        } catch (_: Exception) {
        }
        gate.reset()
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun requireKey(): ByteArray =
        VaultSession.keyOrNull() ?: throw VaultLockedException()

    private fun requireValidPasscode(passcode: String) {
        require(passcode.length >= MIN_PASSCODE_LENGTH && passcode.all { it.isDigit() }) {
            "The passcode needs at least $MIN_PASSCODE_LENGTH digits"
        }
    }

    /**
     * Prepares the fingerprint key wrap: an encrypt cipher the prompt must authorize. Called at
     * setup, after an unlock that found no usable key, and after a restore. A per-use auth-bound
     * Keystore key cannot encrypt without a fresh authentication, so the wrap is always created
     * through the prompt ([completeKeystoreWrap]) and never silently.
     */
    fun beginKeystoreWrap(): VaultKeyChallenge = synchronized(LOCK) {
        keys.wrapChallenge(requireKey())
    }

    /** Stores the wrap after the prompt authorized [challenge]; false when it could not be written. */
    fun completeKeystoreWrap(challenge: VaultKeyChallenge): Boolean = synchronized(LOCK) {
        val blob = try {
            challenge.complete()
        } catch (e: Exception) {
            return false
        }
        if (blob.isEmpty()) return false
        return try {
            vaultDir.mkdirs()
            Durable.atomicWrite(File(vaultDir, KEYSTORE_FILE), blob)
            true
        } catch (e: Exception) {
            false
        }
    }
    private fun entryDirs(): List<File> =
        vaultDir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") && File(it, MANIFEST_FILE).isFile }
            .orEmpty()
            .sortedByDescending { it.lastModified() }

    /** What happened to one backup entry during [restoreEntryFromBackup]. */
    data class RestoredEntry(val id: String?, val name: String?, val skipped: Boolean)

    /**
     * Re-encrypts one Vault entry from a full backup into this phone's Vault.
     *
     * The entry in the backup is encrypted under the backup device's Vault key ([oldKey]).
     * Every file is decrypted with that key and encrypted again with the current key (fresh
     * random IVs per 64 KiB chunk), the manifest is rewritten under the current key, and the
     * result is verified against the recorded sizes and SHA-256 digests with the same check
     * [addDocument] uses before it is renamed into place. Plaintext only exists in
     * `cacheDir/vault_verify`, which [lock] and the cold-start sweep delete.
     *
     * A missing current key throws [VaultLockedException] (the caller unlocks first). An entry
     * whose id already exists is skipped; an entry whose name already exists gets
     * " (restored)".
     */
    fun restoreEntryFromBackup(source: File, oldKey: ByteArray): RestoredEntry = synchronized(LOCK) {
        val key = requireKey()
        val sourceManifest = readManifest(source, oldKey)
        val target = File(vaultDir, sourceManifest.id)
        if (!isSafeEntryId(sourceManifest.id) || target.exists()) {
            return RestoredEntry(null, null, true)
        }
        val taken = entryDirs().mapNotNull { dir ->
            runCatching { readManifest(dir, key).name }.getOrNull()
        }
        val name = if (sourceManifest.name in taken) "${sourceManifest.name} (restored)" else sourceManifest.name
        val manifest = sourceManifest.copy(name = name)

        val work = File(File(context.cacheDir, "vault_verify"), UUID.randomUUID().toString())
        val staging = File(vaultDir, "$STAGING_PREFIX${manifest.id}-${UUID.randomUUID().toString().take(8)}")
        if (!work.mkdirs() || !staging.mkdirs()) {
            work.deleteRecursively()
            staging.deleteRecursively()
            throw IOException("Could not restore this Vault entry")
        }
        var published = false
        try {
            val plain = File(work, "plain.bin")
            manifest.files.forEach { record ->
                val from = File(source, record.stored)
                if (!from.isFile) throw VaultAuthException("The backup's Vault entry is incomplete")
                plain.delete()
                VaultCrypto.decryptFile(from, plain, oldKey)
                if (plain.length() != record.size || Sha256.of(plain) != record.sha) {
                    throw VaultAuthException("The backup's Vault entry does not match its manifest")
                }
                VaultCrypto.encryptFile(plain, File(staging, record.stored), key)
                plain.delete()
            }
            Durable.atomicWrite(File(staging, MANIFEST_FILE), VaultCrypto.encryptBytes(manifest.encode(), key))
            verifyEntry(staging, manifest, key)
            if (!staging.renameTo(target)) throw IOException("Could not restore this Vault entry")
            published = true
            RestoredEntry(manifest.id, manifest.name, false)
        } finally {
            if (!published) staging.deleteRecursively()
            work.deleteRecursively()
        }
    }

    private fun isSafeEntryId(id: String): Boolean =
        id.isNotEmpty() && id.length <= 64 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    private fun entryDir(id: String): File = File(vaultDir, id)

    private fun verifyEntry(entryDir: File, manifest: Manifest, key: ByteArray) {
        val verifyRoot = File(context.cacheDir, "vault_verify")
        val work = File(verifyRoot, UUID.randomUUID().toString())
        work.mkdirs()
        try {
            val check = File(work, "check.bin")
            manifest.files.forEach { record ->
                check.delete()
                VaultCrypto.decryptFile(File(entryDir, record.stored), check, key)
                if (check.length() != record.size || Sha256.of(check) != record.sha) {
                    throw VaultAuthException("The Vault copy does not match the original")
                }
            }
        } finally {
            work.deleteRecursively()
        }
    }

    private fun readManifest(entryDir: File, key: ByteArray): Manifest {
        val blob = File(entryDir, MANIFEST_FILE)
        if (!blob.isFile) throw VaultAuthException("Damaged Vault entry")
        return parseManifest(VaultCrypto.decryptBytes(blob.readBytes(), key), entryDir.name)
    }

    private fun Manifest.encode(): ByteArray = buildString {
        appendLine(MANIFEST_VERSION)
        appendLine("id\t$id")
        appendLine("name\t${b64(name)}")
        appendLine("created\t$createdAt")
        appendLine("added\t$addedAt")
        appendLine("pages\t$pageCount")
        appendLine("pdf\t${b64(pdf)}")
        files.forEach { record ->
            appendLine("file\t${b64(record.rel)}\t${record.stored}\t${record.size}\t${record.sha}")
        }
    }.toByteArray(Charsets.UTF_8)

    private fun b64(value: String): String =
        Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun parseManifest(bytes: ByteArray, expectedId: String): Manifest {
        val lines = String(bytes, Charsets.UTF_8).lines().filter { it.isNotEmpty() }
        if (lines.firstOrNull() != MANIFEST_VERSION) throw VaultAuthException("Unsupported Vault entry")
        var id: String? = null
        var name: String? = null
        var created = 0L
        var added = 0L
        var pages = 0
        var pdf: String? = null
        val files = ArrayList<FileRecord>()
        lines.drop(1).forEach { line ->
            val parts = line.split('\t')
            when (parts[0]) {
                "id" -> id = parts.getOrNull(1)
                "name" -> name = parts.getOrNull(1)?.let { decode(it) }
                "created" -> created = parts.getOrNull(1)?.toLongOrNull() ?: 0L
                "added" -> added = parts.getOrNull(1)?.toLongOrNull() ?: 0L
                "pages" -> pages = parts.getOrNull(1)?.toIntOrNull() ?: 0
                "pdf" -> pdf = parts.getOrNull(1)?.let { decode(it) }
                "file" -> {
                    if (parts.size != 5) throw VaultAuthException("Damaged Vault entry")
                    files += FileRecord(
                        rel = decode(parts[1]),
                        stored = parts[2],
                        size = parts[3].toLongOrNull() ?: throw VaultAuthException("Damaged Vault entry"),
                        sha = parts[4]
                    )
                }
            }
        }
        val parsedId = id ?: throw VaultAuthException("Damaged Vault entry")
        if (parsedId != expectedId) throw VaultAuthException("Damaged Vault entry")
        return Manifest(
            id = parsedId,
            name = name ?: parsedId,
            createdAt = created,
            addedAt = added,
            pageCount = pages,
            pdf = pdf ?: throw VaultAuthException("Damaged Vault entry"),
            files = files
        )
    }

    private fun decode(value: String): String =
        String(Base64.decode(value, Base64.NO_WRAP), Charsets.UTF_8)
}
