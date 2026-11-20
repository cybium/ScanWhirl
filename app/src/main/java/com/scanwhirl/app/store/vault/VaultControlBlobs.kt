package com.scanwhirl.app.store.vault

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/** Control metadata only, never document payloads. No on-disk format or KDF change. */
internal object VaultControlBlobs {
    // A wrap holds one 32-byte key, <=64 salt bytes and container/framing (<256 bytes).
    // 4 KiB leaves generous version headroom, including the device-bound Keystore wrap.
    const val MAX_WRAP_BYTES = 4096L
    // A million UTF-8 bytes accommodates thousands of normal base64 id/password pairs.
    // Encrypted headroom includes both supported chunk-container versions.
    const val MAX_PASSWORD_PLAIN_BYTES = 1024 * 1024
    const val MAX_PASSWORD_BYTES = 2L * 1024 * 1024
    const val MAX_PASSWORD_ENTRIES = 10_000

    fun rootLimit(name: String): Long? = when (name) {
        "recovery.bin", "passcode.bin" -> MAX_WRAP_BYTES
        "passwords.bin" -> MAX_PASSWORD_BYTES
        // keystore.bin is phone-bound and deliberately excluded by the backup writer.
        else -> null
    }

    /** Preflight before opening; enforce again while reading, even if the file grows. */
    fun read(file: File, limit: Long, open: () -> InputStream = { file.inputStream() }): ByteArray {
        if (!file.isFile || file.length() > limit) throw VaultAuthException("Damaged Vault control file")
        return open().use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), limit - total + 1).toInt())
                if (count < 0) break
                total += count
                if (total > limit) throw VaultAuthException("Damaged Vault control file")
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
    }
}
