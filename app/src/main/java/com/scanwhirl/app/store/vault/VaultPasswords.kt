package com.scanwhirl.app.store.vault

import android.content.Context
import android.util.Base64
import com.scanwhirl.app.util.Durable
import com.scanwhirl.app.util.Prefs
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * PDF passwords remembered for a document, for lanes that protect PDFs (design §4.2:
 * "Remember in Vault" / "Use password saved in Vault").
 *
 * A password is stored **encrypted with the Vault key** (AES-256-GCM, the same container as
 * every Vault file), keyed by the document id. It can only be read while the Vault is
 * unlocked: with the key gone from [VaultSession], [save], [get] and [remove] throw
 * [VaultLockedException]. The file lives beside the Vault entries
 * (`filesDir/vault2/passwords.bin`), holds no plaintext, travels inside a `.swvault` backup
 * (where only the recovery code can open it), and is deleted by [VaultStore.wipeAll].
 *
 * Contract for callers (documented for lane L3):
 *  - `VaultPasswords.of(context).save(docId, password)` after protecting a PDF;
 *  - `VaultPasswords.of(context).get(docId)` to fill the unlock sheet; `null` = none saved;
 *  - `VaultPasswords.of(context).remove(docId)` when the protection is removed;
 *  - `has(docId)` decides whether "Use password saved in Vault" is offered at all;
 *  - [VaultLockedException] means the Vault is locked (ask the user to unlock it);
 *  - [VaultAuthException] means the stored blob was changed or belongs to another key;
 *  - [VaultPendingException] means the Vault's one-time recovery code has not passed its
 *    four-character check yet: nothing is stored or returned under that unfinished key
 *    (review VAULT-F1), so a retry after the setup finishes simply saves it.
 *
 * The Vault is process-wide ([VaultSession]), so any screen may use this class without
 * holding a `VaultStore` of its own.
 */
class VaultPasswords private constructor(context: Context) {

    companion object {
        const val FILE_NAME = "passwords.bin"
        private const val VAULT_DIR = "vault2"
        private const val RECOVERY_FILE = "recovery.bin"

        private const val HEADER = "v1"
        private val LOCK = Any()

        /** The only way in: the store needs nothing but the application context. */
        fun of(context: Context): VaultPasswords = VaultPasswords(context.applicationContext)

        /**
         * Restore check: proves [file] opens with [key] (a `.swvault` backup carries it).
         * Throws [VaultAuthException] when the blob was changed or belongs to another key.
         */
        internal fun verifyFile(file: File, key: ByteArray) {
            if (!file.isFile || file.length() == 0L) return
            readEntries(file, key)
        }

        private fun parse(plain: ByteArray): MutableMap<String, String> {
            if (plain.size > VaultControlBlobs.MAX_PASSWORD_PLAIN_BYTES) throw VaultAuthException("Damaged Vault password list")
            val lines = String(plain, Charsets.UTF_8).lineSequence().filter { it.isNotEmpty() }.iterator()
            if (!lines.hasNext() || lines.next() != HEADER) throw VaultAuthException("Unsupported Vault password list")
            val entries = LinkedHashMap<String, String>()
            var count = 0
            lines.forEach { line ->
                if (++count > VaultControlBlobs.MAX_PASSWORD_ENTRIES) throw VaultAuthException("Damaged Vault password list")
                val parts = line.split('\t')
                if (parts.size != 2) throw VaultAuthException("Damaged Vault password list")
                entries[decode(parts[0])] = decode(parts[1])
            }
            return entries
        }

        private fun readEntries(file: File, key: ByteArray): MutableMap<String, String> {
            val encrypted = VaultControlBlobs.read(file, VaultControlBlobs.MAX_PASSWORD_BYTES)
            val plain = ByteArrayOutputStream()
            // Refuse output BEFORE buffering it; do not trust the encrypted header's length.
            val bounded = object : OutputStream() {
                override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    if (length > VaultControlBlobs.MAX_PASSWORD_PLAIN_BYTES - plain.size()) {
                        throw VaultAuthException("Damaged Vault password list")
                    }
                    plain.write(bytes, offset, length)
                }
            }
            VaultCrypto.decryptStream(encrypted.inputStream(), bounded, key)
            val bytes = plain.toByteArray()
            return try { parse(bytes) } finally { bytes.fill(0) }
        }

        private fun decode(value: String): String =
            String(Base64.decode(value, Base64.NO_WRAP), Charsets.UTF_8)
    }

    private val context: Context = context.applicationContext

    private val file: File
        get() = File(File(this.context.filesDir, VAULT_DIR), FILE_NAME)

    /**
     * Review VAULT-F1: `setup()` unlocks the Vault before the one-time recovery code has passed
     * its four-character check, so [VaultSession] alone said "unlocked" for a Vault that is not
     * finished. Nothing may be written or handed back under that key, hence the marker check.
     * Fail closed on an unreadable state: refuse rather than store a password under a setup
     * whose state cannot be read.
     */
    private fun pending(): Boolean = runCatching {
        File(File(this.context.filesDir, VAULT_DIR), RECOVERY_FILE)
            .let { it.isFile && it.length() > 0 } && Prefs.vaultSetupConfirmPending(this.context)
    }.getOrDefault(true)

    private fun key(): ByteArray = VaultSession.keyOrNull() ?: throw VaultLockedException()

    /** Stores (or replaces) the password for [documentId]. Requires a finished, unlocked Vault. */
    fun save(documentId: String, password: String) {
        require(documentId.isNotBlank()) { "A document id is required" }
        require(password.isNotEmpty()) { "A password is required" }
        synchronized(LOCK) {
            // The lock is reported first (callers open the Unlock screen for it); an unfinished
            // setup is refused after that, so no password is stored under a key that is not final.
            val current = key()
            if (pending()) throw VaultPendingException()
            val entries = read(current)
            entries[documentId] = password
            write(entries, current)
        }
    }

    /** The saved password for [documentId], or null when none is saved. Requires a finished, unlocked Vault. */
    fun get(documentId: String): String? = synchronized(LOCK) {
        val current = key()
        if (pending()) return@synchronized null
        read(current)[documentId]
    }

    /** True when a password is saved for [documentId]. Requires a finished, unlocked Vault. */
    fun has(documentId: String): Boolean = get(documentId) != null

    /** Removes the saved password; true when one was there. Requires a finished, unlocked Vault. */
    fun remove(documentId: String): Boolean = synchronized(LOCK) {
        val current = key()
        if (pending()) return@synchronized false
        val entries = read(current)
        if (entries.remove(documentId) == null) return@synchronized false
        write(entries, current)
        true
    }

    /** How many passwords are saved. Requires a finished, unlocked Vault. */
    fun count(): Int = synchronized(LOCK) {
        val current = key()
        if (pending()) return@synchronized 0
        read(current).size
    }

    // ------------------------------------------------------------------

    private fun read(key: ByteArray): MutableMap<String, String> {
        val file = file
        if (!file.isFile || file.length() == 0L) return LinkedHashMap()
        return readEntries(file, key)
    }

    private fun write(entries: Map<String, String>, key: ByteArray) {
        if (entries.size > VaultControlBlobs.MAX_PASSWORD_ENTRIES) throw VaultAuthException("Damaged Vault password list")
        val plain = buildString {
            appendLine(HEADER)
            entries.forEach { (id, password) ->
                appendLine("${encode(id)}\t${encode(password)}")
            }
        }.toByteArray(Charsets.UTF_8)
        if (plain.size > VaultControlBlobs.MAX_PASSWORD_PLAIN_BYTES) {
            plain.fill(0)
            throw VaultAuthException("Damaged Vault password list")
        }
        val encrypted = VaultCrypto.encryptBytes(plain, key)
        plain.fill(0)
        val target = file
        target.parentFile?.let { dir ->
            dir.mkdirs()
            Durable.atomicWrite(target, encrypted)
            Durable.syncDir(dir)
        } ?: Durable.atomicWrite(target, encrypted)
    }

    private fun encode(value: String): String =
        Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
}
