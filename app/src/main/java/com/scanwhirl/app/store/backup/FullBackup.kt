package com.scanwhirl.app.store.backup

import android.content.Context
import android.util.Log
import com.scanwhirl.app.BuildConfig
import com.scanwhirl.app.R
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.store.vault.VaultAuthException
import com.scanwhirl.app.store.vault.VaultCrypto
import com.scanwhirl.app.store.vault.VaultKeyWrap
import com.scanwhirl.app.store.vault.VaultSession
import com.scanwhirl.app.store.vault.VaultStore
import com.scanwhirl.app.util.Durable
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
class FullBackup(
    private val context: Context,
    /** Injected by tests; the app uses the real one so entries land in this phone's Vault. */
    vaultOverride: VaultStore? = null,
    private val restoreLimits: RestoreLimits = RestoreLimits()
) {

    /** Resource limits, not format limits. Overrides make small deterministic refusal tests possible. */
    data class RestoreLimits(
        val maxBytes: Long = MAX_ARCHIVE_BYTES,
        val maxEntries: Int = 200_000,
        val maxDepth: Int = 8,
        val space: (File) -> Long = com.scanwhirl.app.util.BoundedCopy::availableSpace,
        val checkCancelled: () -> Unit = {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Restore cancelled")
        }
    )

    private val vault: VaultStore by lazy {
        vaultOverride ?: run {
            val app = context.applicationContext as? ScanWhirlApp
            VaultStore(context, app?.repository ?: com.scanwhirl.app.store.ScanRepository(context))
        }
    }

    enum class Wrap { PASSWORD, RECOVERY_CODE }

    data class Header(
        val wrap: Wrap,
        val iterations: Int,
        val salt: ByteArray,
        /** True when the archive carries the backup Vault's key, so entries can be re-encrypted. */
        val hasVaultKey: Boolean
    )

    data class Report(
        val documents: Int,
        val skipped: Int,
        val vaultEntries: Int,
        val vaultReencrypted: Int,
        val extras: Int,
        val vaultLocked: Boolean,
        /**
         * Plain sentences for the user, shown after the counts. A write fills this when
         * something could not be included (today: the Vault during a pending setup), a restore
         * when part of the archive had to wait or was left out.
         */
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
    fun write(target: File, secret: String, wrap: Wrap): Report {
        when (wrap) {
            Wrap.PASSWORD -> require(secret.length >= MIN_PASSWORD) {
                "The backup password needs at least $MIN_PASSWORD characters"
            }
            Wrap.RECOVERY_CODE -> require(VaultCrypto.isRecoveryCode(secret)) {
                "That is not a Vault recovery code"
            }
        }
        return withActiveJob { writeWhileActive(target, secret, wrap) }
    }

    private fun writeWhileActive(target: File, secret: String, wrap: Wrap): Report {
        val plain = File(context.cacheDir, "fullbackup-${UUID.randomUUID().toString().take(8)}.tmp")
        try {
            // Review VAULT-F1: a Vault whose one-time recovery code has not passed its check is
            // unlocked by setup(), so it used to export its encrypted files *and* the key that
            // opens them. Whoever could read the archive then needed only the backup password
            // they chose, not the code the owner may not have written down. While pending, the
            // Vault is left out and the report says so; the library still backs up.
            val vaultPending = vault.isConfirmationPending()
            val vaultKey = if (vaultPending) null else vaultKeyFor(secret, wrap)
            try {
                DataOutputStream(BufferedOutputStream(FileOutputStream(plain), BUFFER)).use { out ->
                    writeEntry(out, "manifest.json", manifest().toByteArray(Charsets.UTF_8))
                    copyTree(out, docsDir, "documents")
                    copyTree(out, sigDir, "signatures")
                    copyTree(out, stampDir, "stamps")
                    copyTree(out, draftDir, "drafts")
                    if (!vaultPending) copyVault(out)
                    if (vaultKey != null) writeEntry(out, VAULT_KEY_ENTRY, vaultKey)
                    writeEntry(out, "settings.json", settings().toByteArray(Charsets.UTF_8))
                    out.writeByte(END)
                }
                val salt = VaultCrypto.newSalt()
                val key = secretKey(secret, wrap, salt, VaultCrypto.PBKDF2_ITERATIONS)
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
                        // Version 2: says whether VAULT_KEY_ENTRY is inside, so restore knows
                        // it can merge the Vault without decrypting the whole file first.
                        out.writeByte(if (vaultKey != null) 1 else 0)
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
                return Report(
                    documents = countEntries(docsDir),
                    skipped = 0,
                    vaultEntries = if (vaultPending) 0 else countVaultEntries(),
                    vaultReencrypted = 0,
                    extras = countFiles(sigDir) + countFiles(stampDir) + countFiles(draftDir),
                    vaultLocked = false,
                    warnings = if (vaultPending) {
                        listOf(context.getString(R.string.vault_pending_backup_omitted))
                    } else {
                        emptyList()
                    }
                )
            } finally {
                vaultKey?.fill(0)
            }
        } finally {
            plain.delete()
        }
    }

    /**
     * The key for the outer container. The recovery code is normalised first, so a code typed
     * with or without spaces (and in either case) opens the same file: the device QA found a
     * backup written with the formatted code and a restore that derived from the normalised one,
     * which produced different keys.
     */
    private fun secretKey(secret: String, wrap: Wrap, salt: ByteArray, iterations: Int): ByteArray {
        val normalized = if (wrap == Wrap.RECOVERY_CODE) VaultCrypto.normalizeRecoveryCode(secret) else secret
        return VaultCrypto.deriveKey(normalized, salt, iterations)
    }

    /** Where the UI writes an archive before saving or sharing it (covered by the FileProvider). */
    fun newArchiveFile(): File {
        val dir = File(context.cacheDir, "fullbackup").apply { mkdirs() }
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd-HHmm", java.util.Locale.US).format(java.util.Date())
        return File(dir, "ScanWhirl-$stamp.$EXTENSION")
    }

    /**
     * The backup device's Vault key, so a restore can re-encrypt its entries under this phone's
     * key. Available when the Vault is unlocked, or when the backup is protected by the recovery
     * code (which unwraps the same `recovery.bin` the Vault itself uses). A password backup of a
     * locked Vault carries no key: its entries can only go into an empty Vault.
     */
    private fun vaultKeyFor(secret: String, wrap: Wrap): ByteArray? {
        if (!File(vaultDir, "recovery.bin").isFile) return null
        VaultSession.keyOrNull()?.let { return it.copyOf() }
        if (wrap != Wrap.RECOVERY_CODE) return null
        val code = VaultCrypto.normalizeRecoveryCode(secret)
        return runCatching {
            VaultKeyWrap.open(File(vaultDir, "recovery.bin").readBytes(), code)
        }.getOrNull()
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
            val hasVaultKey = if (version >= 2) input.readUnsignedByte() == 1 else false
            return Header(wrap, iterations, salt, hasVaultKey)
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
        return withActiveJob { restoreWhileActive(source, secret, header) }
    }

    private fun restoreWhileActive(source: File, secret: String, header: Header): Report {
        val key = secretKey(secret, header.wrap, header.salt, header.iterations)
        val plain = File(context.cacheDir, "restore-${UUID.randomUUID().toString().take(8)}.tmp")
        val staging = File(context.cacheDir, "restore-${UUID.randomUUID().toString().take(8)}")
        try {
            // Decrypt the whole container first: a bad tag fails before any file is touched.
            BufferedInputStream(FileInputStream(source), BUFFER).use { file ->
                DataInputStream(file).use { input ->
                    val magic = ByteArray(MAGIC.size).also { input.readFully(it) }
                    if (!magic.contentEquals(MAGIC)) throw VaultAuthException("Not a ScanWhirl backup file")
                    val version = input.readUnsignedByte()
                    input.readUnsignedByte()
                    input.readInt()
                    val saltLength = input.readUnsignedByte()
                    var skip = saltLength.toLong()
                    while (skip > 0) {
                        val skipped = input.skip(skip)
                        if (skipped <= 0) throw VaultAuthException("Damaged backup header")
                        skip -= skipped
                    }
                    if (version >= 2) input.readUnsignedByte()
                    val budget = BackupRestoreBudget(context, context.cacheDir, restoreLimits)
                    BufferedOutputStream(FileOutputStream(plain), BUFFER).use { out ->
                        // Authentication still finishes before replay/publication, but an attacker
                        // cannot grow this plaintext scratch file beyond the running budget.
                        try {
                            VaultCrypto.decryptStream(file, budget.output(out), key)
                        } catch (error: VaultAuthException) {
                            // Crypto deliberately wraps IO failures. Preserve our resource refusal
                            // so the person hears "free space/limits", not "wrong password".
                            val cause = error.cause
                            if (cause is BackupResourceException) throw cause
                            throw error
                        }
                    }
                }
            }
            replay(plain, staging)
            val oldVaultKey = staging.resolve(VAULT_KEY_ENTRY).takeIf { it.isFile }?.let {
                if (it.length() != 32L) throw VaultAuthException("Damaged backup key")
                ByteArray(32).also { bytes -> DataInputStream(it.inputStream()).use { input -> input.readFully(bytes) } }
            }
            staging.resolve(VAULT_KEY_ENTRY).delete()
            try {
                restoreLimits.checkCancelled()
                return applyStaging(staging, oldVaultKey)
            } finally {
                oldVaultKey?.fill(0)
            }
        } finally {
            key.fill(0)
            plain.delete()
            staging.deleteRecursively()
        }
    }

    /** True when this backup can merge Vault entries but this phone's Vault is locked. */
    fun needsVaultUnlock(file: File): Boolean =
        header(file).hasVaultKey && vault.isSetUp() && !vault.isUnlocked() &&
            // A pending Vault defers the merge anyway (VAULT-F1): asking to unlock it first
            // would open the Vault screen and change nothing.
            !vault.isConfirmationPending()

    /** Number of days since the last backup, or null when none was ever made. */
    fun daysSinceLastBackup(now: Long = System.currentTimeMillis()): Long? {        val last = prefs.getLong(PREF_LAST_BACKUP, 0L)
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

    // Only portable user choices from Prefs, with the same types on both sides. Entitlement,
    // billing, Vault/security policy, QA flags, local URIs and internal markers are device-local.
    private enum class SettingType { TEXT, TOGGLE }

    private val portableSettings get() = mapOf(
        "theme_mode" to SettingType.TEXT,
        "font_style" to SettingType.TEXT,
        "default_viewer" to SettingType.TEXT,
        "sort_mode" to SettingType.TEXT,
        "library_sort" to SettingType.TEXT,
        "locale_tag" to SettingType.TEXT,
        "ocr_language" to SettingType.TEXT,
        "accent_theme" to SettingType.TEXT,
        "quick_tools" to SettingType.TEXT,
        "auto_enhance_scan" to SettingType.TOGGLE,
        "auto_copy_to_documents" to SettingType.TOGGLE,
        "make_searchable" to SettingType.TOGGLE,
        "gallery_auto_crop" to SettingType.TOGGLE
    )

    private fun settings(): String = JSONObject().apply {
        val values = prefs.all
        portableSettings.forEach { (key, type) ->
            val value = values[key]
            when (type) {
                SettingType.TEXT -> if (value is String) put(key, value) else Unit
                SettingType.TOGGLE -> if (value is Boolean) put(key, value) else Unit
            }
        }
    }.toString()

    /**
     * How many document/entry folders a tree holds. The full-backup report counts these (a
     * document, a Vault entry), never the raw page files.
     */
    private fun countEntries(root: File): Int =
        root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }?.size ?: 0

    /** Files under [root] that the backup would copy (the same filter [copyTree] uses). */
    private fun countFiles(root: File): Int {
        if (!root.isDirectory) return 0
        return root.walkTopDown()
            .onEnter { !it.name.startsWith(".") }
            .count { it.isFile && !it.name.startsWith(".") }
    }

    /** Vault entry folders (a directory carrying an encrypted manifest), not raw file count. */
    private fun countVaultEntries(): Int =
        vaultDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") && File(f, "manifest.enc").isFile }
            ?.size ?: 0

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

    @androidx.annotation.VisibleForTesting
    internal fun writeFileEntryForTest(out: DataOutputStream, path: String, file: File) =
        writeFileEntry(out, path, file)

    /** Streams one file in 64 KiB pieces: a 5 MB page never sits in memory. */
    private fun writeFileEntry(out: DataOutputStream, path: String, file: File) {
        out.writeByte(ENTRY)
        out.writeUTF(path)
        // F4 (found by the MiniMax calibration): the length is declared before the bytes, so
        // exactly that many bytes must follow. A file that grows or shrinks while the backup runs
        // (OCR text saved in the background, a page edit) used to corrupt the archive's framing
        // silently: the backup looked fine and could not be restored. Now it fails honestly.
        val declared = file.length()
        out.writeLong(declared)
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER)
            var left = declared
            while (left > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (read < 0) throw java.io.IOException("A file changed while the backup was being written: $path")
                out.write(buffer, 0, read)
                left -= read
            }
            if (input.read() >= 0) {
                throw java.io.IOException("A file changed while the backup was being written: $path")
            }
        }
    }

    private fun replay(archive: File, staging: File) {
        val budget = BackupRestoreBudget(context, context.cacheDir, restoreLimits)
        DataInputStream(BufferedInputStream(FileInputStream(archive), BUFFER)).use { input ->
            var entries = 0
            while (true) {
                restoreLimits.checkCancelled()
                when (val tag = input.readUnsignedByte()) {
                    END -> {
                        if (input.read() != -1) throw VaultAuthException("Trailing backup archive data")
                        return
                    }
                    ENTRY -> {
                        if (++entries > restoreLimits.maxEntries) throw BackupResourceException(context)
                        val path = input.readUTF()
                        val size = input.readLong()
                        if (size < 0) throw VaultAuthException("Damaged backup entry")
                        if (size > MAX_ENTRY_BYTES) throw BackupResourceException(context)
                        val segments = path.split('/')
                        if (path.length > 1024 || segments.size > restoreLimits.maxDepth ||
                            segments.any { it.isEmpty() || it == "." }) {
                            throw BackupResourceException(context)
                        }
                        // Validate lengths BEFORE making a file or allocating key/JSON/text arrays.
                        val cap = when {
                            path == VAULT_KEY_ENTRY -> 32L
                            path == "settings.json" || path == "manifest.json" -> MAX_SETTINGS_BYTES
                            path.endsWith("/meta.txt") -> MAX_META_BYTES
                            else -> MAX_ENTRY_BYTES
                        }
                        if (size > cap || (path == VAULT_KEY_ENTRY && size != 32L)) {
                            throw BackupResourceException(context)
                        }
                        budget.requireFits(size)
                        val target = safeTarget(staging, path)
                        // Check the private filesystem rather than retaining all attacker-supplied
                        // names in a potentially very large in-memory duplicate set.
                        if (target.exists()) throw VaultAuthException("Duplicate backup entry")
                        target.parentFile?.mkdirs()
                        BufferedOutputStream(FileOutputStream(target), BUFFER).use { out ->
                            copyExact(input, budget.output(out), size)
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

    private fun applyStaging(staging: File, oldVaultKey: ByteArray?): Report {
        var documents = 0
        var skipped = 0
        var vaultEntries = 0
        var vaultReencrypted = 0
        var extras = 0
        var vaultLocked = false
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
                    val lines = readBoundedText(meta, MAX_META_BYTES).reader().readLines().toMutableList()
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

        // 1b. Legacy Vault flags (B1). `vault_pin_sha256` is device state and deliberately never
        //     travels with a backup, so a restored `vault.flag` would hide its document from every
        //     screen with no PIN that could open it: unreachable, not restored. Follow the path the
        //     app already has for legacy documents (VaultStore.migrateLegacyVault):
        //       - a phone that still has the old PIN keeps its flags: its legacy Vault screen can
        //         open and migrate them;
        //       - the v2 Vault is set up and unlocked: migrate them into it now;
        //       - the v2 Vault is set up but locked: KEEP the flags (these documents were hidden by
        //         the user; exposing them in the open library would be a privacy regression) and
        //         migrate them on the next successful unlock (VaultStore);
        //       - no v2 Vault on this phone: nothing could ever open them, so clear the flag and
        //         say plainly that they are in the library now.
        val legacyFlagged = legacyVaultFlagged()
        if (legacyFlagged.isNotEmpty() && !vault.hasLegacyPin()) {
            when {
                !vault.isSetUp() -> {
                    warnings.add(quantity(R.plurals.backup_legacy_vault_visible, legacyFlagged.size))
                    clearLegacyVaultFlags()
                }
                vault.isUnlocked() -> {
                    runCatching { vault.migrateLegacyVault() }
                    val remaining = legacyVaultFlagged()
                    if (remaining.isNotEmpty()) {
                        // A migration failure keeps them hidden; the next unlock retries it.
                        warnings.add(quantity(R.plurals.backup_legacy_vault_pending, remaining.size))
                    }
                }
                else -> warnings.add(quantity(R.plurals.backup_legacy_vault_pending, legacyFlagged.size))
            }
        }

        // 2. Vault: adopt the backup's identity into an empty Vault, or re-encrypt entry by entry
        //    into this phone's Vault with its own key.
        val restoredVault = staging.resolve("vault2")
        if (restoredVault.isDirectory && oldVaultKey == null) {
            // S6A: password-only backups of a locked Vault cannot authenticate its contents.
            // Skip the ENTIRE Vault part, including wraps/settings, before touching vaultDir.
            warnings.add(context.getString(R.string.backup_vault_unverifiable_skipped))
        } else if (restoredVault.isDirectory) {
            val checkedVaultKey = checkNotNull(oldVaultKey)
            val targetSetUp = File(vaultDir, "recovery.bin").let { it.isFile && it.length() > 0 }
            if (!targetSetUp) {
                vaultDir.mkdirs()
                // Leftovers of a setup that never published its "set up" blob belong to no Vault
                // and must never shadow the backup's own wraps (review MUST-FIX 2).
                listOf("passcode.bin", "keystore.bin").forEach { name ->
                    File(vaultDir, name).delete()
                    File(vaultDir, "$name.tmp").delete()
                }
                var rejected = 0
                restoredVault.listFiles()?.sortedBy { it.name }?.forEach { from ->
                    val to = File(vaultDir, from.name)
                    if (to.exists()) return@forEach
                    if (from.name.startsWith(".")) return@forEach
                    if (from.isDirectory && !isSafeId(from.name)) return@forEach
                    val wasDirectory = from.isDirectory
                    if (wasDirectory) {
                        // S5B (red-team): an adopted entry is checked before it is published,
                        // exactly like a merged one: manifest, contained paths and every declared
                        // file authenticated against its size and digest. A damaged or crafted
                        // entry is skipped and reported, never counted as restored.
                        val ok = runCatching { vault.validateStagedEntryForAdoption(from, checkedVaultKey) }.isSuccess
                        if (!ok) {
                            rejected++
                            return@forEach
                        }
                    }
                    if (from.renameTo(to)) {
                        if (wasDirectory) vaultEntries++ else extras++
                    }
                }
                if (rejected > 0) {
                    warnings.add(context.getString(R.string.backup_vault_entries_rejected, rejected))
                }
                // The restore typed the backup's recovery code, so the adopted Vault is known:
                // a pending marker left by an interrupted setup is not owed any more.
                if (File(vaultDir, "recovery.bin").isFile) {
                    runCatching { vault.markConfirmationPassed() }
                }
            } else if (vault.isConfirmationPending()) {
                // Review VAULT-F1: setup() unlocks a Vault whose recovery code has not passed its
                // four-character check, so a full restore used to publish verified entries into a
                // Vault that is not finished. Nothing is published; the library part is restored
                // and the report says the Vault part waits for the same code check as everything
                // else. The archive is not touched, so restoring it again afterwards merges.
                warnings.add(context.getString(R.string.vault_pending_restore_deferred))
            } else if (!vault.isUnlocked()) {
                vaultLocked = true
            } else {
                restoredVault.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
                    ?.sortedBy { it.name }
                    ?.forEach { dir ->
                        runCatching { vault.restoreEntryFromBackup(dir, checkedVaultKey) }
                            .onSuccess { result ->
                                if (result.skipped) skipped++ else vaultReencrypted++
                            }
                            .onFailure {
                                // F-05: log the category only; the person reads a stable sentence.
                                Log.w(TAG, "vault entry restore failed")
                                warnings.add(context.getString(R.string.backup_restore_vault_entry_failed))
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

        // 4. Settings: only known portable preferences, and never replace a choice on this phone.
        val settingsFile = staging.resolve("settings.json")
        if (settingsFile.isFile) {
            runCatching {
                val json = JSONObject(readBoundedText(settingsFile, MAX_SETTINGS_BYTES))
                val edit = prefs.edit()
                portableSettings.forEach { (key, type) ->
                    if (prefs.contains(key)) return@forEach
                    val value = json.opt(key)
                    when (type) {
                        SettingType.TEXT -> if (value is String) edit.putString(key, value) else Unit
                        SettingType.TOGGLE -> if (value is Boolean) edit.putBoolean(key, value) else Unit
                    }
                }
                edit.apply()
            }.onFailure {
                Log.w(TAG, "settings restore failed")
                warnings.add(context.getString(R.string.backup_restore_settings_failed))
            }
        }

        return Report(documents, skipped, vaultEntries, vaultReencrypted, extras, vaultLocked, warnings)
    }

    private fun nameOf(meta: File): String = try {
        readBoundedText(meta, MAX_META_BYTES).lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }
            ?: meta.parentFile?.name.orEmpty()
    } catch (_: IOException) {
        meta.parentFile?.name.orEmpty()
    }

    /** Also bound the reads themselves, even if a local file changes after replay validation. */
    private fun readBoundedText(file: File, cap: Long): String {
        if (file.length() > cap) throw BackupResourceException(context)
        val bytes = java.io.ByteArrayOutputStream()
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), cap - total + 1).toInt())
                if (count < 0) break
                if (count.toLong() > cap - total) throw BackupResourceException(context)
                bytes.write(buffer, 0, count)
                total += count
            }
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    private fun freeId(): String {
        while (true) {
            val id = UUID.randomUUID().toString().take(8)
            if (!File(docsDir, id).exists() && !File(vaultDir, id).exists()) return id
        }
    }

    private fun isSafeId(id: String): Boolean =
        id.isNotEmpty() && id.length <= 64 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    /** Documents still flagged as legacy Vault items in the library. */
    private fun legacyVaultFlagged(): List<File> =
        docsDir.listFiles { f -> f.isDirectory && File(f, VAULT_FLAG).isFile }.orEmpty().toList()

    /** One warning sentence with its count (Arabic digits, English/Hindi plural rules). */
    private fun quantity(id: Int, count: Int): String =
        context.resources.getQuantityString(id, count, count)

    /**
     * Drops the legacy flag from documents nothing can open any more. The content stays in the
     * library; only the "hide me" marker goes, so the document is reachable again.
     */
    private fun clearLegacyVaultFlags() {
        legacyVaultFlagged().forEach { File(it, VAULT_FLAG).delete() }
    }

    companion object {
        const val MIN_PASSWORD = 10
        private const val TAG = "FullBackup"
        const val PREF_LAST_BACKUP = "full_backup_last_at"
        const val PREF_REMIND = "full_backup_remind"
        /** The non-secret "Vault setup not checked yet" marker (Prefs); device state only. */
        private const val PREF_VAULT_PENDING = "vault_setup_confirm_pending"
        const val EXTENSION = "swbackup"
        const val MIME = "application/octet-stream"
        /**
         * A single picked backup is limited to 2 GiB (larger than the PDF import cap).
         * Restore needs the encrypted ingress, authenticated plaintext and staged files at once;
         * an unbounded archive is not safe on a budget phone. This does not change crypto framing.
         */
        const val MAX_ARCHIVE_BYTES = 2L * 1024 * 1024 * 1024
        internal const val MAX_SETTINGS_BYTES = 1024L * 1024
        internal const val MAX_META_BYTES = 64L * 1024

        private val MAGIC = "SWFULLB1".toByteArray(Charsets.UTF_8)
        private const val VERSION = 2
        private const val MIN_VERSION = 1
        private const val ENTRY = 1
        internal const val VAULT_KEY_ENTRY = "vault-key.bin"
        private const val VAULT_FLAG = "vault.flag"

        /**
         * How many backup writes/restores this process is running. The cold-start privacy sweep
         * never deletes the cache files of a running job (`fullbackup-<id>.tmp`,
         * `restore-<id>.tmp`, `restore-<id>/`), so a sweep fired while one is active cannot
         * break it.
         */
        private val activeJobs = java.util.concurrent.atomic.AtomicInteger(0)

        internal val isRunning: Boolean get() = activeJobs.get() > 0

        private fun <T> withActiveJob(block: () -> T): T {
            activeJobs.incrementAndGet()
            try {
                return block()
            } finally {
                activeJobs.decrementAndGet()
            }
        }
        private const val END = 0
        private const val BUFFER = 64 * 1024
        private const val MAX_ENTRY_BYTES = 512L * 1024 * 1024
    }
}

/** Running accounting for scratch output, including before each decrypted chunk reaches disk. */
internal class BackupRestoreBudget(
    private val context: Context?,
    private val root: File,
    private val limits: FullBackup.RestoreLimits
) {
    private var written = 0L

    fun requireFits(bytes: Long) {
        limits.checkCancelled()
        if (bytes < 0 || bytes > limits.maxBytes - written) {
            throw BackupResourceException(context)
        }
        if (limits.space(root) < HEADROOM + minOf(bytes, 64L * 1024)) {
            throw BackupResourceException(context, noSpace = true)
        }
    }

    fun output(out: java.io.OutputStream): java.io.OutputStream = object : java.io.OutputStream() {
        override fun write(value: Int) {
            requireFits(1)
            out.write(value)
            written++
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            requireFits(length.toLong())
            out.write(bytes, offset, length)
            written += length
        }
        override fun flush() = out.flush()
    }

    companion object {
        // Beyond ingress's shared 16 MiB reserve, leave 64 MiB during scratch expansion.
        const val HEADROOM = 64L * 1024 * 1024
    }
}

class BackupResourceException(context: Context?, val noSpace: Boolean = false) : IOException(
    context?.getString(if (noSpace) R.string.s6c_backup_no_space else R.string.s6c_backup_limits)
)
