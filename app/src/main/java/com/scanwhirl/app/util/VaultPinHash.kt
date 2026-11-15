package com.scanwhirl.app.util

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The legacy 4-digit PIN hash (security audit M-3): salted PBKDF2-HMAC-SHA256 instead of a bare
 * SHA-256 digest, with the old format still verifiable and upgraded on first success.
 *
 * Format: `pbkdf2$<iterations>$<saltBase64>$<hashBase64>` (SHA-256, 16-byte salt, 32-byte hash).
 * A wrong PIN takes the same work as a right one; nothing here is ever logged.
 */
internal object VaultPinHash {

    const val ITERATIONS = 120_000

    private const val PREFIX = "pbkdf2"
    private const val SALT_BYTES = 16
    private const val HASH_BYTES = 32
    private val RANDOM = SecureRandom()

    fun create(pin: String): String {
        val salt = ByteArray(SALT_BYTES).also { RANDOM.nextBytes(it) }
        val hash = derive(pin, salt, ITERATIONS)
        return "$PREFIX\$$ITERATIONS\$${b64(salt)}\$${b64(hash)}"
    }

    /** True for a value written by an older build (`sha256("scanwhirl_vault:$pin")`). */
    fun isLegacy(stored: String): Boolean = !stored.startsWith("$PREFIX\$")

    fun verify(stored: String, pin: String): Boolean {
        if (isLegacy(stored)) return constantTimeEquals(stored, legacySha256(pin))
        val parts = stored.split('$')
        if (parts.size != 4) return false
        val iterations = parts[1].toIntOrNull()?.takeIf { it in 1..2_000_000 } ?: return false
        val salt = runCatching { Base64.decode(parts[2], Base64.NO_WRAP) }.getOrNull() ?: return false
        val expected = runCatching { Base64.decode(parts[3], Base64.NO_WRAP) }.getOrNull() ?: return false
        if (salt.size < 8 || expected.size != HASH_BYTES) return false
        return constantTimeEquals(expected, derive(pin, salt, iterations))
    }

    /** The exact legacy function, kept so old prefs keep working. */
    fun legacySha256(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("scanwhirl_vault:$pin".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, HASH_BYTES * 8)
        try {
            val algorithm = if (android.os.Build.VERSION.SDK_INT >= 26) {
                "PBKDF2WithHmacSHA256"
            } else {
                "PBKDF2WithHmacSHA1"
            }
            return SecretKeyFactory.getInstance(algorithm).generateSecret(spec).encoded.copyOf(HASH_BYTES)
        } finally {
            spec.clearPassword()
        }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean =
        MessageDigest.isEqual(a, b)

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
}
