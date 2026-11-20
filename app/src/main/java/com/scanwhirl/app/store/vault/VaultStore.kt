package com.scanwhirl.app.store.vault

import android.content.Context
import android.util.Base64
import com.scanwhirl.app.store.OpFaults
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.util.Durable
import com.scanwhirl.app.util.PdfProtection
import com.scanwhirl.app.util.Prefs
import com.scanwhirl.app.util.Sha256
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
        private const val ADDING_PREFIX = ".adding-"
        private const val MANIFEST_VERSION = "v1"
        // VaultBackup allows 10,000 files per entry, one of which is the manifest.
        internal const val MAX_ENTRY_RECORDS = 9_999
        internal const val MAX_MANIFEST_BYTES = 8L * 1024 * 1024
        internal const val MAX_RECORD_BYTES = 1024L * 1024 * 1024
        private val STORED_NAME = Regex("[A-Za-z0-9._-]{1,255}")
        private val SHA256 = Regex("[a-f0-9]{64}")

        internal fun isSafeEntryId(id: String): Boolean =
            id.isNotEmpty() && id.length <= 64 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }

        internal fun isSafeStoredName(name: String): Boolean =
            STORED_NAME.matches(name) && !name.startsWith('.') &&
                !name.endsWith('.') && !name.endsWith(".part", ignoreCase = true) &&
                !name.equals(MANIFEST_FILE, ignoreCase = true) &&
                !name.equals("$MANIFEST_FILE.tmp", ignoreCase = true)

        /** Reject aliases as well as escapes; record names must identify distinct actual files. */
        internal fun containedFile(root: File, relative: String): File {
            val base = root.canonicalFile
            val child = File(base, relative).absoluteFile
            if (!child.path.startsWith(base.path + File.separator) || child.canonicalFile != child) {
                throw VaultAuthException("Damaged Vault entry")
            }
            return child
        }
    }

    data class Setup(val recoveryCode: String) {
        // LOW (T18 review): a data class toString prints every field. The one-time code must
        // never appear in a log, a crash report or a debugging screenshot.
        override fun toString(): String = "Setup(recoveryCode=<redacted>)"
    }

    /**
     * A new recovery code that is sealed but NOT written yet (review H2). The old wrap stays
     * active until [commitRecoveryCode] runs after the four-character check, so abandoning the
     * flow (or losing this object with the process) can never lock the user out.
     *
     * [preparedFor] is the non-secret identity of the Vault that sealed it (review MUST-FIX 1):
     * a candidate can only ever be written into that same Vault, never into one restored later.
     */
    class RecoveryReplacement internal constructor(
        /** Grouped for display ("ABCD EFGH ..."). Shown once, never stored. */
        val code: String,
        internal val blob: ByteArray,
        internal val preparedFor: String?
    ) {
        override fun toString(): String = "RecoveryReplacement(code=<redacted>)"
    }

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
        /** Declared PDF member; legacy page-only documents have no PDF. */
        val pdf: String?,
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

    /**
     * The non-secret pending-completion marker (review H1): a Vault exists whose one-time
     * recovery code has not passed its four-character check yet. Authentication and
     * recovery-code (re)enrollment stay available; normal use and every migration wait.
     */
    fun isConfirmationPending(): Boolean = isSetUp() && Prefs.vaultSetupConfirmPending(context)

    /** The four-character check passed for the code that is active now. */
    fun markConfirmationPassed() {
        if (!isConfirmationPending()) return
        // Fail closed (review MUST-FIX 2): a marker that cannot be cleared must never be
        // reported as a finished setup.
        if (!Prefs.setVaultSetupConfirmPending(context, false)) {
            throw IOException("Could not save the Vault setup state")
        }
    }

    /**
     * Non-secret identity of the active recovery wrap (SHA-256 of `recovery.bin`), or null when
     * this phone has no Vault. It changes when the Vault is re-created or restored, so a code
     * sealed for one Vault can never be written into another (review MUST-FIX 1).
     */
    fun vaultIdentity(): String? = synchronized(LOCK) {
        val file = File(vaultDir, RECOVERY_FILE)
        if (!file.isFile || file.length() == 0L) null else Sha256.of(file)
    }

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
        // A setup that died before its "set up" blob belongs to no Vault: its leftovers must
        // never be published as, or shadow, a passcode wrap (review MUST-FIX 2).
        clearIncompleteProvisioning()

        val key = VaultCrypto.newKey()
        val recoveryCode = VaultCrypto.generateRecoveryCode()
        val passBlob = VaultKeyWrap.seal(key, passcode, VaultCrypto.newSalt(), iterations)
        val recoveryBlob = VaultKeyWrap.seal(key, recoveryCode, VaultCrypto.newSalt(), iterations)

        Durable.atomicWrite(File(vaultDir, PASSCODE_FILE), passBlob)
        // Non-secret pending marker BEFORE the "set up" blob: a Vault can never exist with an
        // unconfirmed recovery code and no marker, even if the process dies right here. It is
        // cleared only by markConfirmationPassed() after the four-character check.
        if (!Prefs.setVaultSetupConfirmPending(context, true)) {
            // Fail closed: without the marker the check could be skipped, so nothing is published.
            File(vaultDir, PASSCODE_FILE).delete()
            throw IOException("Could not start the Vault setup")
        }
        try {
            // Written last: its presence is the "set up" marker.
            Durable.atomicWrite(File(vaultDir, RECOVERY_FILE), recoveryBlob)
            Durable.syncDir(vaultDir)
        } catch (e: Exception) {
            // The transaction did not complete (a write failed): leave nothing that looks
            // provisioned. A real process kill cannot run this, so recover() and the backup
            // restore also clear such leftovers (review MUST-FIX 2).
            File(vaultDir, PASSCODE_FILE).delete()
            throw e
        }
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
        migrateRestoredLegacy()
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
        finishRecoveryUnlock(key)
    }

    /**
     * The backup code sheet checks a snapshot off-main, before it accepts any unlock effects.
     * [accept] runs after derivation and can refuse a dismissed sheet. Neither the session nor
     * the attempt gate is touched by a wrong code, a cancelled check, or a replaced recovery wrap.
     */
    internal suspend fun unlockWithRecoveryForBackup(code: String, accept: suspend () -> Boolean): Boolean {
        if (!VaultCrypto.isRecoveryCode(code)) return false
        val file = File(vaultDir, RECOVERY_FILE)
        val blob = synchronized(LOCK) { runCatching { file.readBytes() }.getOrNull() } ?: return false
        val key = try {
            VaultKeyWrap.open(blob, VaultCrypto.normalizeRecoveryCode(code))
        } catch (_: VaultAuthException) {
            return false
        }
        try {
            if (!accept()) return false
            val jobContext = currentCoroutineContext()
            return synchronized(LOCK) {
                if (!file.isFile || !file.readBytes().contentEquals(blob)) return@synchronized false
                // Commit point. The following existing unlock transaction must not be interrupted.
                jobContext.ensureActive()
                finishRecoveryUnlock(key)
            }
        } finally {
            key.fill(0)
        }
    }

    private fun finishRecoveryUnlock(key: ByteArray): Boolean {
        gate.onSuccess()
        VaultSession.open(key)
        migrateRestoredLegacy()
        return true
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
        migrateRestoredLegacy()
        true
    }

    /** Drops the key and every decrypted copy. */
    fun lock() = synchronized(LOCK) {
        VaultSession.close()
        openDir.deleteRecursively()
    }

    fun changePasscode(passcode: String) = synchronized(LOCK) {
        val key = requireKey()
        requireCompleted()
        requireValidPasscode(passcode)
        Durable.atomicWrite(
            File(vaultDir, PASSCODE_FILE),
            VaultKeyWrap.seal(key, passcode, VaultCrypto.newSalt(), iterations)
        )
    }

    /**
     * Seals a new recovery code without writing it (review H2). The old wrap stays active until
     * [commitRecoveryCode] runs after the four-character check, so an abandoned flow leaves the
     * old code working. Allowed while the setup check is pending: this is the (re)enrollment.
     */
    fun prepareRecoveryCode(): RecoveryReplacement = synchronized(LOCK) {
        val key = requireKey()
        val code = VaultCrypto.generateRecoveryCode()
        RecoveryReplacement(
            VaultCrypto.formatRecoveryCode(code),
            VaultKeyWrap.seal(key, code, VaultCrypto.newSalt(), iterations),
            vaultIdentity()
        )
    }

    /**
     * Atomically replaces the recovery wrap with [replacement] (same format, no migration).
     * The candidate must have been sealed by THIS Vault: a code prepared before a re-create or
     * a restore is rejected under the lock instead of overwriting the new Vault's wrap.
     */
    fun commitRecoveryCode(replacement: RecoveryReplacement) = synchronized(LOCK) {
        requireKey()
        if (vaultIdentity() != replacement.preparedFor) throw VaultStaleReplacementException()
        Durable.atomicWrite(File(vaultDir, RECOVERY_FILE), replacement.blob)
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
        requireCompleted()
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
                pdf = doc.pdfFile.takeIf { it.isFile }?.relativeTo(src)?.invariantSeparatorsPath,
                files = files
            )
            validateManifest(manifest)
            writeManifest(staging, manifest, key)
            OpFaults.at("vault:encrypted")

            verifyEntry(staging, manifest, key)
            OpFaults.at("vault:verified")

            // S6A: local provenance, fsynced BEFORE publication. Backup restore never creates
            // this marker; a matching entry id alone is not authority to delete an original.
            val pending = buildString {
                appendLine("v1")
                appendLine(docId)
                staging.listFiles()!!.sortedBy { it.name }.forEach { file ->
                    appendLine("${file.name}\t${file.length()}")
                }
            }
            Durable.atomicWriteText(File(vaultDir, "$ADDING_PREFIX$docId"), pending)
            Durable.syncDir(vaultDir)
            if (!staging.renameTo(target)) throw IOException("Could not add this document to the Vault")
            published = true
            Durable.syncDir(vaultDir)
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
            clearCompletedAdd(docId)
            manifest.toEntry()
        } catch (e: Exception) {
            if (!published) {
                staging.deleteRecursively()
            } else if (!trashed) {
                // The original is still in the library; drop the half-finished entry.
                target.deleteRecursively()
            }
            if (!target.exists()) File(vaultDir, "$ADDING_PREFIX$docId").delete()
            // When the move to Trash already happened, the complete entry is kept and
            // recover() finishes the purge.
            throw e
        }
    }

    fun openDocument(id: String): Opened = synchronized(LOCK) {
        val key = requireKey()
        requireCompleted()
        if (!isSafeEntryId(id)) throw VaultAuthException("Damaged Vault entry")
        val entryDir = containedFile(vaultDir, id)
        if (!entryDir.isDirectory) throw IOException("This document is not in the Vault")
        val manifest = readManifest(entryDir, key)
        val pdfMember = manifest.pdf ?: throw VaultAuthException("Damaged Vault entry")
        val sources = resolveEntryFiles(entryDir, manifest)
        val target = containedFile(openDir, id)
        val destinations = manifest.files.map { record ->
            containedFile(target, "${record.rel}.part")
            containedFile(target, record.rel)
        }
        val pdf = containedFile(target, pdfMember)
        target.deleteRecursively()
        if (!target.mkdirs()) throw IOException("Could not open this document")

        try {
            manifest.files.forEachIndexed { index, record ->
                val dest = destinations[index]
                dest.parentFile?.mkdirs()
                VaultCrypto.decryptFile(sources[index], dest, key, record.size)
                if (Sha256.of(dest) != record.sha) {
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
            .map { containedFile(target, it.rel) }
        Opened(id = id, name = manifest.name, dir = target, pageFiles = pageFiles, pdfFile = pdf)
    }

    /** Removes the encrypted entry. The plaintext was already purged when it was added. */
    fun removeFromVault(id: String) = synchronized(LOCK) {
        requireKey()
        requireCompleted()
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
        // Review H1: a migration is one-way. It never runs while the one-time code's
        // four-character check is still owed, on the manual path or the automatic one.
        requireCompleted()
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

    /**
     * A Full Backup restored while this Vault was locked keeps the restored legacy-PIN documents
     * flagged and hidden on purpose: they were hidden by the user, so showing them in the open
     * library would be worse than waiting. This runs after every successful unlock and moves them
     * in through [migrateLegacyVault], so the restore sheet's promise ("will move into your Vault
     * the next time you open it") holds. A phone that still has the old PIN is left to its legacy
     * Vault screen, and a failure leaves the flags for the next unlock.
     */
    private fun migrateRestoredLegacy() {
        // Review H1: restored legacy files stay hidden until the current recovery code's check
        // has passed; a silent migration here would run without the user's acknowledgement.
        if (isConfirmationPending()) return
        if (hasLegacyPin()) return
        if (repo.listVaultDocuments().isEmpty()) return
        runCatching { migrateLegacyVault() }
    }

    // ------------------------------------------------------------------
    // Backup / restore, startup recovery
    // ------------------------------------------------------------------

    /** Writes a `.swvault` file: encrypted entries plus the key wrapped by the recovery code. */
    fun exportBackup(dest: File): File = synchronized(LOCK) {
        if (!isSetUp()) throw IllegalStateException("Set up the Vault first")
        requireCompleted()
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
        if (isConfirmationPending()) throw VaultPendingException()
        if (entryDirs().isNotEmpty()) throw IllegalStateException("This Vault already has files in it")

        vaultDir.mkdirs()
        val staging = File(vaultDir, "$RESTORE_PREFIX${UUID.randomUUID().toString().take(8)}")
        try {
            val contents = VaultBackup.extract(src, staging)
            val key = VaultKeyWrap.open(contents.recoveryBlob, VaultCrypto.normalizeRecoveryCode(recoveryCode))

            val verified = contents.entryIds.map { id ->
                val dir = containedFile(staging, id)
                val manifest = readManifest(dir, key)
                verifyEntry(dir, manifest, key, adopting = true)
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
                    val from = containedFile(staging, manifest.id)
                    val to = containedFile(vaultDir, manifest.id)
                    if (to.exists()) throw IOException("This document is already in the Vault")
                    if (!from.renameTo(to)) throw IOException("Could not restore this document")
                    moved += to
                }
                contents.extras.forEach { extra ->
                    val to = containedFile(vaultDir, extra.name)
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
     *  - finishes only locally marked adds whose published encrypted files are complete;
     *  - wipes decrypted cache copies while the Vault is locked.
     */
    fun recover() = synchronized(LOCK) {
        if (!isUnlocked()) openDir.deleteRecursively()
        vaultDir.listFiles()?.forEach { f ->
            if (f.isDirectory && (f.name.startsWith(STAGING_PREFIX) || f.name.startsWith(RESTORE_PREFIX))) {
                f.deleteRecursively()
            }
        }
        if (!isSetUp()) {
            // An interrupted provisioning transaction: its wraps belong to no Vault and must
            // never shadow a later (or restored) passcode wrap (review MUST-FIX 2).
            clearIncompleteProvisioning()
            return@synchronized
        }

        // S6A: restored/adopted entries and legacy (pre-marker) entries have no deletion
        // authority. A legacy Library original may remain: a privacy leftover is preferable
        // to irreversible data loss while locked and unable to authenticate the manifest.
        vaultDir.listFiles()?.filter { it.isFile && it.name.startsWith(ADDING_PREFIX) }?.forEach { marker ->
            val id = marker.name.removePrefix(ADDING_PREFIX)
            if (!validPendingAdd(marker, id)) {
                // Fail closed permanently; a later restore must not revive stale authority.
                marker.delete()
                return@forEach
            }
            try {
                if (File(repo.docsDir, id).exists()) {
                    val key = repo.moveToTrash(id)
                    if (key != null) repo.deleteFromTrash(key)
                }
                repo.listTrash().filter { it.originalId == id }.forEach { item ->
                    repo.deleteFromTrash(item.key)
                }
                clearCompletedAdd(id)
            } catch (_: Exception) {
                // Keep the local marker so an interrupted purge can be retried.
            }
        }
    }

    /** Structural validation is possible while locked; the marker was created after verification. */
    private fun validPendingAdd(marker: File, id: String): Boolean = runCatching {
        if (!isSafeEntryId(id) || marker.length() !in 1..(1024L * 1024) ||
            containedFile(vaultDir, marker.name) != marker.absoluteFile
        ) return@runCatching false
        val lines = marker.readLines()
        if (lines.size !in 4..(MAX_ENTRY_RECORDS + 3) || lines[0] != "v1" || lines[1] != id) {
            return@runCatching false
        }
        val dir = containedFile(vaultDir, id)
        if (!dir.isDirectory) return@runCatching false
        val names = HashSet<String>()
        for (line in lines.drop(2)) {
            val parts = line.split('\t')
            if (parts.size != 2) return@runCatching false
            val name = parts[0]
            if ((name != MANIFEST_FILE && !isSafeStoredName(name)) || !names.add(name)) {
                return@runCatching false
            }
            val size = parts[1].toLongOrNull() ?: return@runCatching false
            val limit = if (name == MANIFEST_FILE) MAX_MANIFEST_BYTES else MAX_RECORD_BYTES
            val file = containedFile(dir, name)
            if (size !in 1..VaultCrypto.encryptedLength(limit) || !file.isFile || file.length() != size) {
                return@runCatching false
            }
        }
        MANIFEST_FILE in names && dir.list()?.toSet() == names
    }.getOrDefault(false)

    private fun clearCompletedAdd(id: String) {
        if (!File(repo.docsDir, id).exists() && repo.listTrash().none { it.originalId == id }) {
            File(vaultDir, "$ADDING_PREFIX$id").delete()
            Durable.syncDir(vaultDir)
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

    /**
     * Review H1: the Vault is not usable while its one-time recovery code's four-character
     * check is still owed. Only authentication and code (re)enrollment + confirmation are
     * allowed, so normal use never starts on a code the user may not have written down.
     */
    private fun requireCompleted() {
        if (isConfirmationPending()) throw VaultPendingException()
    }

    private fun requireValidPasscode(passcode: String) {
        require(passcode.length >= MIN_PASSCODE_LENGTH && passcode.all { it.isDigit() }) {
            "The passcode needs at least $MIN_PASSCODE_LENGTH digits"
        }
    }

    /**
     * Deletes the wraps of a setup that never published its "set up" blob. Only when this phone
     * has no Vault: a complete Vault is never touched.
     */
    private fun clearIncompleteProvisioning() {
        if (isSetUp()) return
        listOf(PASSCODE_FILE, KEYSTORE_FILE).forEach { name ->
            File(vaultDir, name).delete()
            File(vaultDir, "$name.tmp").delete()
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

    /** Check all declared files before Full Backup adopts a staging directory as-is. */
    internal fun validateStagedEntryForAdoption(source: File, oldKey: ByteArray) = synchronized(LOCK) {
        verifyEntry(source, readManifest(source, oldKey), oldKey, adopting = true)
    }

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
        val sources = resolveEntryFiles(source, sourceManifest)
        val target = containedFile(vaultDir, sourceManifest.id)
        if (target.exists()) {
            return RestoredEntry(null, null, true)
        }
        val taken = entryDirs().mapNotNull { dir ->
            runCatching { readManifest(dir, key).name }.getOrNull()
        }
        val name = if (sourceManifest.name in taken) "${sourceManifest.name} (restored)" else sourceManifest.name
        val manifest = sourceManifest.copy(name = name)

        val workRoot = containedFile(context.cacheDir, "vault_verify")
        val work = containedFile(workRoot, UUID.randomUUID().toString())
        val staging = containedFile(vaultDir, "$STAGING_PREFIX${manifest.id}-${UUID.randomUUID().toString().take(8)}")
        val plain = containedFile(work, "plain.bin")
        containedFile(work, "plain.bin.part")
        val destinations = manifest.files.map { record ->
            containedFile(staging, "${record.stored}.part")
            containedFile(staging, record.stored)
        }
        if (!work.mkdirs() || !staging.mkdirs()) {
            work.deleteRecursively()
            staging.deleteRecursively()
            throw IOException("Could not restore this Vault entry")
        }
        var published = false
        try {
            manifest.files.forEachIndexed { index, record ->
                plain.delete()
                VaultCrypto.decryptFile(sources[index], plain, oldKey, record.size)
                if (Sha256.of(plain) != record.sha) {
                    throw VaultAuthException("The backup's Vault entry does not match its manifest")
                }
                VaultCrypto.encryptFile(plain, destinations[index], key)
                plain.delete()
            }
            writeManifest(staging, manifest, key)
            verifyEntry(staging, manifest, key)
            if (!staging.renameTo(target)) throw IOException("Could not restore this Vault entry")
            published = true
            RestoredEntry(manifest.id, manifest.name, false)
        } finally {
            if (!published) staging.deleteRecursively()
            work.deleteRecursively()
        }
    }

    private fun entryDir(id: String): File = File(vaultDir, id)

    private fun resolveEntryFiles(entryDir: File, manifest: Manifest): List<File> =
        manifest.files.map { record ->
            containedFile(entryDir, record.stored).also { file ->
                if (!file.isFile || !VaultCrypto.isEncryptedFileLength(file, record.size)) {
                    throw VaultAuthException("The Vault copy does not match the original")
                }
            }
        }

    private fun verifyEntry(entryDir: File, manifest: Manifest, key: ByteArray, adopting: Boolean = false) {
        val sources = resolveEntryFiles(entryDir, manifest)
        if (adopting) {
            val declared = manifest.files.mapTo(mutableSetOf(MANIFEST_FILE)) { it.stored }
            if (entryDir.list()?.toSet() != declared) throw VaultAuthException("Damaged Vault entry")
        }
        manifest.files.forEachIndexed { index, record ->
            VaultCrypto.verifyFile(sources[index], key, record.size, record.sha)
        }
    }

    private fun readManifest(entryDir: File, key: ByteArray): Manifest {
        val dir = containedFile(entryDir.absoluteFile.parentFile ?: throw VaultAuthException("Damaged Vault entry"), entryDir.name)
        val blob = containedFile(dir, MANIFEST_FILE)
        if (!blob.isFile || blob.length() !in 1..VaultCrypto.encryptedLength(MAX_MANIFEST_BYTES)) {
            throw VaultAuthException("Damaged Vault entry")
        }
        val encrypted = blob.readBytes()
        val plain = VaultCrypto.decryptBytes(encrypted, key)
        if (!VaultCrypto.isEncryptedLength(encrypted.size.toLong(), plain.size.toLong(), encrypted[4].toInt())) {
            throw VaultAuthException("Damaged Vault entry")
        }
        return parseManifest(plain, entryDir.name)
    }

    private fun writeManifest(entryDir: File, manifest: Manifest, key: ByteArray) {
        val bytes = manifest.encode()
        if (bytes.size > MAX_MANIFEST_BYTES) throw VaultAuthException("Damaged Vault entry")
        containedFile(entryDir, "$MANIFEST_FILE.tmp")
        Durable.atomicWrite(containedFile(entryDir, MANIFEST_FILE), VaultCrypto.encryptBytes(bytes, key))
    }

    private fun Manifest.encode(): ByteArray = buildString {
        appendLine(MANIFEST_VERSION)
        appendLine("id\t$id")
        appendLine("name\t${b64(name)}")
        appendLine("created\t$createdAt")
        appendLine("added\t$addedAt")
        appendLine("pages\t$pageCount")
        appendLine("pdf\t${b64(pdf ?: PdfProtection.PDF_FILE)}")
        files.forEach { record ->
            appendLine("file\t${b64(record.rel)}\t${record.stored}\t${record.size}\t${record.sha}")
        }
    }.toByteArray(Charsets.UTF_8)

    private fun b64(value: String): String =
        Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun parseManifest(bytes: ByteArray, expectedId: String): Manifest {
        if (bytes.size > MAX_MANIFEST_BYTES) throw VaultAuthException("Damaged Vault entry")
        val lines = String(bytes, Charsets.UTF_8).lineSequence().iterator()
        if (!lines.hasNext() || lines.next() != MANIFEST_VERSION) throw VaultAuthException("Unsupported Vault entry")
        var id: String? = null
        var name: String? = null
        var created = 0L
        var added = 0L
        var pages = 0
        var pdf: String? = null
        val files = ArrayList<FileRecord>()
        val fields = HashSet<String>()
        lines.forEach { line ->
            if (line.isEmpty()) return@forEach
            val parts = line.split('\t', limit = 6)
            if (parts[0] != "file" && (parts.size != 2 || !fields.add(parts[0]))) {
                throw VaultAuthException("Damaged Vault entry")
            }
            when (parts[0]) {
                "id" -> id = parts.getOrNull(1)
                "name" -> name = parts.getOrNull(1)?.let { decode(it) }
                "created" -> created = parts.getOrNull(1)?.toLongOrNull() ?: 0L
                "added" -> added = parts.getOrNull(1)?.toLongOrNull() ?: 0L
                "pages" -> pages = parts.getOrNull(1)?.toIntOrNull() ?: 0
                "pdf" -> pdf = parts.getOrNull(1)?.let { decode(it) }
                "file" -> {
                    if (parts.size != 5 || files.size >= MAX_ENTRY_RECORDS) throw VaultAuthException("Damaged Vault entry")
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
        val pdfField = pdf ?: throw VaultAuthException("Damaged Vault entry")
        // V1 writers used ScanRepository's fixed scan.pdf path even for page-only documents.
        // Normalize that one legacy placeholder to absence, never to a usable path.
        val pdfMember = pdfField.takeUnless {
            it == PdfProtection.PDF_FILE && files.none { file -> file.rel == PdfProtection.PDF_FILE }
        }
        return Manifest(
            id = parsedId,
            name = name ?: parsedId,
            createdAt = created,
            addedAt = added,
            pageCount = pages,
            pdf = pdfMember,
            files = files
        ).also(::validateManifest)
    }

    private fun validateManifest(manifest: Manifest) {
        val stored = HashSet<String>()
        val relatives = HashSet<String>()
        val directories = HashSet<String>()
        if (!isSafeEntryId(manifest.id) || manifest.files.size !in 1..MAX_ENTRY_RECORDS) {
            throw VaultAuthException("Damaged Vault entry")
        }
        manifest.files.forEach { record ->
            val segments = record.rel.split('/', limit = 33)
            if (!isSafeStoredName(record.stored) || !stored.add(record.stored.lowercase()) ||
                record.rel.length !in 1..4096 || record.rel.any { it == '\\' || it == ':' || it.code < 32 } ||
                segments.size > 32 || segments.any { it.isEmpty() || it == "." || it == ".." || it.length > 255 } ||
                !relatives.add(record.rel) || record.size !in 0..MAX_RECORD_BYTES || !SHA256.matches(record.sha)
            ) {
                throw VaultAuthException("Damaged Vault entry")
            }
            var slash = record.rel.indexOf('/')
            while (slash != -1) {
                directories.add(record.rel.substring(0, slash))
                slash = record.rel.indexOf('/', slash + 1)
            }
        }
        val validPdf = manifest.pdf?.let { it in relatives } ?: manifest.files.any {
            '/' !in it.rel && it.size > 0 && (it.rel.endsWith(".jpg", true) || it.rel.endsWith(".jpeg", true))
        }
        if (!validPdf || relatives.any {
                it in directories || "$it.part" in relatives || "$it.part" in directories
            }
        ) {
            throw VaultAuthException("Damaged Vault entry")
        }
    }

    private fun decode(value: String): String = try {
        String(Base64.decode(value, Base64.NO_WRAP), Charsets.UTF_8)
    } catch (e: IllegalArgumentException) {
        throw VaultAuthException("Damaged Vault entry", e)
    }
}

/**
 * The Vault exists but its one-time recovery code has not passed the four-character check yet
 * (review H1). Normal use and every migration wait; authentication and code re-enrollment work.
 */
class VaultPendingException : IllegalStateException("Finish setting up the Vault first")

/**
 * A prepared recovery-code candidate belongs to a different Vault than the one on this phone now
 * (a re-create or a restore happened after it was sealed). It is rejected, never written.
 */
class VaultStaleReplacementException : IllegalStateException("This code belongs to a different Vault")
