package com.scanwhirl.app.store.backup

import android.content.Context
import com.scanwhirl.app.BuildConfig
import com.scanwhirl.app.store.vault.VaultAuthException
import com.scanwhirl.app.store.vault.VaultCrypto
import com.scanwhirl.app.util.Durable
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/**
 * Full Backup 2.0: one encrypted file with the whole app.
 *
 * The `.swvault` container only carries the Vault. This one carries the library (pages, PDFs,
 * OCR text and geometry, metadata, drafts), the Vault (still encrypted under the Vault key), the
 * signatures and stamps, and the settings. It is a versioned AES-256-GCM container written by
 * [VaultCrypto]'s chunked format, so it streams: a 2000-page library costs 64 KiB of memory, not
 * 2 GB.
 *
 * Layout:
 * ```
 * "SWFULLB1" | byte version | byte wrap (1 password, 2 recovery code)
 *            | int pbkdf2 iterations | byte salt length | salt
 *            | VaultCrypto chunked AES-256-GCM stream
 * ```
 * Inside the encrypted stream, one entry after another:
 * ```
 * byte 1 | UTF path | long size | size bytes          (path uses '/' and is validated on read)
 * byte 0                                              (end)
 * ```
 * Entries: `manifest.json`, `documents/<id>/...`, `signatures/...`, `stamps/...`, `drafts/...`,
 * `vault2/...` (the keystore wrap is skipped: the key lives in this phone's Keystore),
 * `settings.json`.
 *
 * The secret is either a backup password (>= [MIN_PASSWORD] characters) or the Vault recovery
 * code; the header records which, so restore asks for the right one without guessing.
 */
class FullBackup(private val context: Context) {

    enum class Wrap { PASSWORD, RECOVERY_CODE }

    data class Header(val wrap: Wrap, val iterations: Int, val salt: ByteArray)

    data class Report(
        val documents: Int,
        val skipped: Int,
        val vaultEntries: Int,
        val extras: Int,
        val warnings: List<String>
    )

    private val docsDir: File get() = File(context.filesDir, "documents")
    private val vaultDir: File get() = File(context.filesDir, "vault2")
    private val sigDir: File get() = File(context.filesDir, "signatures")
    private val stampDir: File get() = File(context.filesDir, "stamps")
    private val draftDir: File get() = File(context.filesDir, "drafts")
    private val prefs get() = context.getSharedPreferences("scanwhirl_prefs", 0)

    // ------------------------------------------------------------------
    // Write
    // ------------------------------------------------------------------

    /**
     * Writes the whole app into [target]. [secret] is either a password (>= 10 characters) or the
     * formatted Vault recovery code; [wrap] says which and is recorded in the header.
     */
    fun write(target: File, secret: String, wrap: Wrap) {
        when (wrap) {
            Wrap.PASSWORD -> require(secret.length >= MIN_PASSWORD) {
                "The backup password needs at least $MIN_PASSWORD characters"
            }
            Wrap.RECOVERY_CODE -> require(VaultCrypto.isRecoveryCode(secret)) {
                "That is not a Vault recovery code"
            }
        }
        val plain = File(context.cacheDir, "fullbackup-${UUID.randomUUID().toString().take(8)}.tmp")
        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(plain), BUFFER)).use { out ->
                writeEntry(out, "manifest.json", manifest().toByteArray(Charsets.UTF_8))
                copyTree(out, docsDir, "documents")
                copyTree(out, sigDir, "signatures")
                copyTree(out, stampDir, "stamps")
                copyTree(out, draftDir, "drafts")
                copyVault(out)
                writeEntry(out, "settings.json", settings().toByteArray(Charsets.UTF_8))
                out.writeByte(END)
            }
            val salt = VaultCrypto.newSalt()
            val key = VaultCrypto.deriveKey(secret, salt, VaultCrypto.PBKDF2_ITERATIONS)
            target.parentFile?.mkdirs()
            val part = File(target.parentFile, target.name + ".part")
            try {
                BufferedOutputStream(FileOutputStream(part), BUFFER).use { raw ->
                    val out = DataOutputStream(raw)
                    out.write(MAGIC)
                    out.writeByte(VERSION)
                    out.writeByte(wrap.ordinal + 1)
                    out.writeInt(VaultCrypto.PBKDF2_ITERATIONS)
                    out.writeByte(salt.size)
                    out.write(salt)
                    out.flush()
                    FileInputStream(plain).use { input ->
                        VaultCrypto.encryptStream(input, plain.length(), raw, key)
                    }
                }
                Durable.syncFile(part)
                if (!part.renameTo(target)) throw IOException("Could not write the backup file")
                Durable.syncDir(target.parentFile)
            } catch (e: Exception) {
                part.delete()
                target.delete()
                throw e
            }
        } finally {
            plain.delete()
        }
    }

    /** Reads just the plaintext header, to know which secret the file asks for. */
    fun header(file: File): Header {
        DataInputStream(BufferedInputStream(FileInputStream(file), BUFFER)).use { input ->
            val magic = ByteArray(MAGIC.size).also { input.readFully(it) }
            if (!magic.contentEquals(MAGIC)) throw VaultAuthException("Not a ScanWhirl backup file")
            val version = input.readUnsignedByte()
            if (version !in MIN_VERSION..VERSION) throw VaultAuthException("Unsupported backup version")
            val wrap = when (input.readUnsignedByte()) {
                1 -> Wrap.PASSWORD
                2 -> Wrap.RECOVERY_CODE
                else -> throw VaultAuthException("Unsupported backup secret")
            }
            val iterations = input.readInt()
            if (iterations !in 10_000..2_000_000) throw VaultAuthException("Damaged backup header")
            val saltLength = input.readUnsignedByte()
            if (saltLength !in 8..64) throw VaultAuthException("Damaged backup header")
            val salt = ByteArray(saltLength).also { input.readFully(it) }
            return Header(wrap, iterations, salt)
        }
    }

    // ------------------------------------------------------------------
    // Restore
    // ------------------------------------------------------------------

    /**
     * Restores everything in [source] into the app, merging instead of overwriting:
     * a document whose id already exists is skipped, a document whose *name* already exists is
     * restored under a new id as "<name> (restored)", Vault entries only go in when this phone's
     * Vault is not set up yet (a different Vault key cannot be merged), and extra files
     * (signatures, stamps, drafts) are added only when missing. Nothing is published until the
     * whole file decrypted: a wrong secret or a corrupted byte throws [VaultAuthException] and
     * leaves the library untouched.
     */
    fun restore(source: File, secret: String): Report {
        val header = header(source)
        val expected = when (header.wrap) {
            Wrap.PASSWORD -> secret.length >= MIN_PASSWORD
            Wrap.RECOVERY_CODE -> VaultCrypto.isRecoveryCode(secret)
        }
        if (!expected) {
            throw VaultAuthException(
                if (header.wrap == Wrap.PASSWORD) "This backup needs its password"
                else "This backup needs the Vault recovery code"
            )
        }
        val key = VaultCrypto.deriveKey(secret, header.salt, header.iterations)
        val plain = File(context.cacheDir, "restore-${UUID.randomUUID().toString().take(8)}.tmp")
        val staging = File(context.cacheDir, "restore-${UUID.randomUUID().toString().take(8)}")
        try {
            // Decrypt the whole container first: a bad tag fails before any file is touched.
            BufferedInputStream(FileInputStream(source), BUFFER).use { file ->
                DataInputStream(file).use { input ->
                    val magic = ByteArray(MAGIC.size).also { input.readFully(it) }
                    if (!magic.contentEquals(MAGIC)) throw VaultAuthException("Not a ScanWhirl backup file")
                    input.readUnsignedByte()
                    input.readUnsignedByte()
                    input.readInt()
                    val saltLength = input.readUnsignedByte()
                    var skip = saltLength.toLong()
                    while (skip > 0) {
                        val skipped = input.skip(skip)
                        if (skipped <= 0) throw VaultAuthException("Damaged backup header")
                        skip -= skipped
                    }
                    BufferedOutputStream(FileOutputStream(plain), BUFFER).use { out ->
                        VaultCrypto.decryptStream(file, out, key)
                    }
                }
            }
            replay(plain, staging)
            return applyStaging(staging)
        } finally {
            plain.delete()
            staging.deleteRecursively()
        }
    }

    /** Number of days since the last backup, or null when none was ever made. */
    fun daysSinceLastBackup(now: Long = System.currentTimeMillis()): Long? {
        val last = prefs.getLong(PREF_LAST_BACKUP, 0L)
        if (last <= 0L) return null
        return ((now - last) / 86_400_000L).coerceAtLeast(0L)
    }

    fun markBackedUp(now: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(PREF_LAST_BACKUP, now).apply()
    }

    // ------------------------------------------------------------------
    // Archive internals
    // ------------------------------------------------------------------

    private fun manifest(): String = JSONObject().apply {
        put("app", "ScanWhirl")
        put("format", VERSION)
        put("createdAt", System.currentTimeMillis())
        put("appVersion", BuildConfig.VERSION_NAME)
        put("documents", docsDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }?.size ?: 0)
    }.toString()

    private fun settings(): String = JSONObject().apply {
        prefs.all.forEach { (key, value) ->
            // The legacy PIN hash is device state, not a setting; never carry it.
            if (key == "vault_pin_sha256") return@forEach
            when (value) {
                is String -> put(key, value)
                is Boolean -> put(key, value)
                is Int -> put(key, value)
                is Long -> put(key, value)
                is Float -> put(key, value.toDouble())
                is Set<*> -> put(key, JSONArray(value.toList()))
            }
        }
    }.toString()

    private fun copyTree(out: DataOutputStream, root: File, prefix: String) {
        if (!root.isDirectory) return
        val files = root.walkTopDown()
            .onEnter { it.name != ".git" }
            .filter { it.isFile }
            .filterNot { it.name.startsWith(".") }
            .sortedBy { it.path }
        for (file in files) {
            val relative = file.relativeTo(root).invariantSeparatorsPath
            writeFileEntry(out, "$prefix/$relative", file)
        }
    }

    private fun copyVault(out: DataOutputStream) {
        if (!vaultDir.isDirectory) return
        val files = vaultDir.walkTopDown()
            .onEnter { !it.name.startsWith(".") }
            .filter { it.isFile }
            // The fingerprint wrap is bound to this phone's Keystore; restoring it would be dead
            // weight and could shadow a working wrap.
            .filterNot { it.name == "keystore.bin" }
            .sortedBy { it.path }
        for (file in files) {
            val relative = file.relativeTo(vaultDir).invariantSeparatorsPath
            writeFileEntry(out, "vault2/$relative", file)
        }
    }

    private fun writeEntry(out: DataOutputStream, path: String, bytes: ByteArray) {
        out.writeByte(ENTRY)
        out.writeUTF(path)
        out.writeLong(bytes.size.toLong())
        out.write(bytes)
    }

    /** Streams one file in 64 KiB pieces: a 5 MB page never sits in memory. */
    private fun writeFileEntry(out: DataOutputStream, path: String, file: File) {
        out.writeByte(ENTRY)
        out.writeUTF(path)
        out.writeLong(file.length())
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
            }
        }
    }

    private fun replay(archive: File, staging: File) {
        DataInputStream(BufferedInputStream(FileInputStream(archive), BUFFER)).use { input ->
            var entries = 0
            while (true) {
                when (val tag = input.readUnsignedByte()) {
                    END -> return
                    ENTRY -> {
                        if (++entries > MAX_ENTRIES) throw VaultAuthException("The backup has too many files")
                        val path = input.readUTF()
                        val size = input.readLong()
                        if (size < 0 || size > MAX_ENTRY_BYTES) throw VaultAuthException("Damaged backup entry")
                        val target = safeTarget(staging, path)
                        target.parentFile?.mkdirs()
                        BufferedOutputStream(FileOutputStream(target), BUFFER).use { out ->
                            copyExact(input, out, size)
                        }
                    }
                    else -> throw VaultAuthException("Damaged backup (tag $tag)")
                }
            }
        }
    }

    /** Rejects absolute paths, `..` and anything that would land outside [root]. */
    private fun safeTarget(root: File, path: String): File {
        if (path.isBlank() || path.startsWith("/") || path.contains('\\') || path.contains("..")) {
            throw VaultAuthException("Damaged backup entry name")
        }
        val target = File(root, path)
        val rootPath = root.canonicalPath + File.separator
        if (!target.canonicalPath.startsWith(rootPath)) throw VaultAuthException("Damaged backup entry name")
        return target
    }

    private fun copyExact(input: DataInputStream, out: java.io.OutputStream, size: Long) {
        val buffer = ByteArray(BUFFER)
        var remaining = size
        while (remaining > 0) {
            val wanted = minOf(remaining, buffer.size.toLong()).toInt()
            val read = input.read(buffer, 0, wanted)
            if (read < 0) throw VaultAuthException("The backup file is incomplete")
            out.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun applyStaging(staging: File): Report {
        var documents = 0
        var skipped = 0
        var vaultEntries = 0
        var extras = 0
        val warnings = mutableListOf<String>()

        // 1. Documents.
        docsDir.mkdirs()
        val existingNames = docsDir.listFiles { f -> f.isDirectory }
            .orEmpty()
            .mapNotNull { d -> File(d, "meta.txt").takeIf { it.isFile }?.let { nameOf(it) } }
            .toMutableSet()
        val restored = staging.resolve("documents")
        restored.listFiles { f -> f.isDirectory }?.sortedBy { it.name }?.forEach { dir ->
            val id = dir.name
            if (!isSafeId(id)) { skipped++; return@forEach }
            if (File(docsDir, id).exists()) { skipped++; return@forEach }
            val name = File(dir, "meta.txt").takeIf { it.isFile }?.let { nameOf(it) } ?: id
            var targetId = id
            if (name in existingNames) {
                targetId = freeId()
                val meta = File(dir, "meta.txt")
                if (meta.isFile) {
                    val lines = meta.readLines().toMutableList()
                    if (lines.isNotEmpty()) lines[0] = "$name (restored)"
                    Durable.atomicWriteText(meta, lines.joinToString("\n") + "\n")
                }
            }
            if (dir.renameTo(File(docsDir, targetId))) {
                documents++
                existingNames.add(if (targetId == id) name else "$name (restored)")
            } else {
                skipped++
            }
        }

        // 2. Vault: only into an empty Vault (a different Vault key cannot be merged).
        val restoredVault = staging.resolve("vault2")
        if (restoredVault.isDirectory) {
            val targetSetUp = File(vaultDir, "recovery.bin").let { it.isFile && it.length() > 0 }
            if (targetSetUp) {
                warnings.add("The Vault on this phone was kept; its backup entries were not merged.")
            } else {
                vaultDir.mkdirs()
                restoredVault.listFiles()?.sortedBy { it.name }?.forEach { from ->
                    val to = File(vaultDir, from.name)
                    if (to.exists()) return@forEach
                    if (from.name.startsWith(".")) return@forEach
                    if (from.isDirectory && !isSafeId(from.name)) return@forEach
                    val wasDirectory = from.isDirectory
                    if (from.renameTo(to)) {
                        if (wasDirectory) vaultEntries++ else extras++
                    }
                }
            }
        }

        // 3. Signatures, stamps, drafts: add what is missing.
        listOf("signatures" to sigDir, "stamps" to stampDir, "drafts" to draftDir).forEach { (name, target) ->
            val from = staging.resolve(name)
            if (!from.isDirectory) return@forEach
            target.mkdirs()
            from.walkTopDown().filter { it.isFile }.forEach { file ->
                val relative = file.relativeTo(from).invariantSeparatorsPath
                val to = File(target, relative)
                if (to.exists()) return@forEach
                to.parentFile?.mkdirs()
                if (file.renameTo(to)) extras++
            }
        }

        // 4. Settings: only keys this phone does not have (a fresh install gets them all).
        val settingsFile = staging.resolve("settings.json")
        if (settingsFile.isFile) {
            runCatching {
                val json = JSONObject(settingsFile.readText())
                val edit = prefs.edit()
                json.keys().forEach { key ->
                    if (key == "vault_pin_sha256" || prefs.contains(key)) return@forEach
                    when (val value = json.get(key)) {
                        is Boolean -> edit.putBoolean(key, value)
                        is Int -> edit.putInt(key, value)
                        is Long -> edit.putLong(key, value)
                        is Double -> edit.putFloat(key, value.toFloat())
                        is String -> edit.putString(key, value)
                        is JSONArray -> edit.putStringSet(
                            key, (0 until value.length()).map { value.getString(it) }.toSet()
                        )
                        else -> Unit
                    }
                }
                edit.apply()
            }.onFailure { warnings.add("Settings could not be restored.") }
        }

        return Report(documents, skipped, vaultEntries, extras, warnings)
    }

    private fun nameOf(meta: File): String = try {
        meta.readLines().firstOrNull()?.takeIf { it.isNotBlank() } ?: meta.parentFile?.name.orEmpty()
    } catch (_: IOException) {
        meta.parentFile?.name.orEmpty()
    }

    private fun freeId(): String {
        while (true) {
            val id = UUID.randomUUID().toString().take(8)
            if (!File(docsDir, id).exists() && !File(vaultDir, id).exists()) return id
        }
    }

    private fun isSafeId(id: String): Boolean =
        id.isNotEmpty() && id.length <= 64 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    companion object {
        const val MIN_PASSWORD = 10
        const val PREF_LAST_BACKUP = "full_backup_last_at"
        const val PREF_REMIND = "full_backup_remind"
        const val EXTENSION = "swbackup"
        const val MIME = "application/octet-stream"

        private val MAGIC = "SWFULLB1".toByteArray(Charsets.UTF_8)
        private const val VERSION = 1
        private const val MIN_VERSION = 1
        private const val ENTRY = 1
        private const val END = 0
        private const val BUFFER = 64 * 1024
        private const val MAX_ENTRIES = 200_000
        private const val MAX_ENTRY_BYTES = 512L * 1024 * 1024
    }
}
