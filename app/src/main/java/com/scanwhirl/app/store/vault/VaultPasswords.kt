package com.scanwhirl.app.store.vault

import android.content.Context
import android.util.Base64
import com.scanwhirl.app.util.Durable
import java.io.File

/**
 * PDF passwords remembered for a document, for lanes that protect PDFs (design §4.2:
 * "Remember in Vault" / "Use password saved in Vault").
 *
 * A password is stored **encrypted with the Vault key** (AES-256-GCM, the same container as
 * every Vault file), keyed by the document id. It can only be read while the Vault is
 * unlocked: with the key gone from [VaultSession], [save], [get] and [remove] throw
 * [VaultLockedException]. The file lives beside the Vault entries
 * (`filesDir/vault2/passwords.bin`), holds no plaintext, and is deleted by
 * [VaultStore.wipeAll].
 *
 * Contract for callers (documented for lane L3):
 *  - `VaultPasswords.of(context).save(docId, password)` after protecting a PDF;
 *  - `VaultPasswords.of(context).get(docId)` to fill the unlock sheet; `null` = none saved;
 *  - `VaultPasswords.of(context).remove(docId)` when the protection is removed;
 *  - `has(docId)` decides whether "Use password saved in Vault" is offered at all;
 *  - [VaultLockedException] means the Vault is locked (ask the user to unlock it);
 *  - [VaultAuthException] means the stored blob was changed or belongs to another key.
 *
 * The Vault is process-wide ([VaultSession]), so any screen may use this class without
 * holding a `VaultStore` of its own.
 */
class VaultPasswords private constructor(context: Context) {

    companion object {
        const val FILE_NAME = "passwords.bin"

        private const val HEADER = "v1"
        private val LOCK = Any()

        /** The only way in: the store needs nothing but the application context. */
        fun of(context: Context): VaultPasswords = VaultPasswords(context.applicationContext)
    }

    private val context: Context = context.applicationContext

    private val file: File
        get() = File(File(this.context.filesDir, "vault2"), FILE_NAME)

    private fun key(): ByteArray = VaultSession.keyOrNull() ?: throw VaultLockedException()

    /** Stores (or replaces) the password for [documentId]. Requires an unlocked Vault. */
    fun save(documentId: String, password: String) {
        require(documentId.isNotBlank()) { "A document id is required" }
        require(password.isNotEmpty()) { "A password is required" }
        synchronized(LOCK) {
            val current = key()
            val entries = read(current)
            entries[documentId] = password
            write(entries, current)
        }
    }

    /** The saved password for [documentId], or null when none is saved. Requires an unlocked Vault. */
    fun get(documentId: String): String? = synchronized(LOCK) {
        read(key())[documentId]
    }

    /** True when a password is saved for [documentId]. Requires an unlocked Vault. */
    fun has(documentId: String): Boolean = get(documentId) != null

    /** Removes the saved password; true when one was there. Requires an unlocked Vault. */
    fun remove(documentId: String): Boolean = synchronized(LOCK) {
        val current = key()
        val entries = read(current)
        if (entries.remove(documentId) == null) return@synchronized false
        write(entries, current)
        true
    }

    /** How many passwords are saved. Requires an unlocked Vault. */
    fun count(): Int = synchronized(LOCK) { read(key()).size }

    // ------------------------------------------------------------------

    private fun read(key: ByteArray): MutableMap<String, String> {
        val file = file
        if (!file.isFile || file.length() == 0L) return LinkedHashMap()
        val plain = VaultCrypto.decryptBytes(file.readBytes(), key)
        val lines = String(plain, Charsets.UTF_8).lines().filter { it.isNotEmpty() }
        if (lines.firstOrNull() != HEADER) throw VaultAuthException("Unsupported Vault password list")
        val entries = LinkedHashMap<String, String>(lines.size)
        lines.drop(1).forEach { line ->
            val parts = line.split('\t')
            if (parts.size != 2) throw VaultAuthException("Damaged Vault password list")
            entries[decode(parts[0])] = decode(parts[1])
        }
        return entries
    }

    private fun write(entries: Map<String, String>, key: ByteArray) {
        val plain = buildString {
            appendLine(HEADER)
            entries.forEach { (id, password) ->
                appendLine("${encode(id)}\t${encode(password)}")
            }
        }.toByteArray(Charsets.UTF_8)
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

    private fun decode(value: String): String =
        String(Base64.decode(value, Base64.NO_WRAP), Charsets.UTF_8)
}
