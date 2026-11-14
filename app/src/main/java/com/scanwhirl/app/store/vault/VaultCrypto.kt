package com.scanwhirl.app.store.vault

import android.os.Build
import com.scanwhirl.app.util.Durable
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** The Vault key did not decrypt: a wrong code/passcode, or bytes changed on disk (GCM tag). */
class VaultAuthException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The Vault has no key in memory; unlock it first. */
class VaultLockedException : IllegalStateException("The Vault is locked")

/**
 * AES-256-GCM file encryption for the Vault.
 *
 * Files are encrypted in independent 64 KiB chunks (The design's streaming rule), so
 * opening a large scan never needs the whole file in memory. Each chunk has a fresh random
 * 96-bit IV and the 128-bit authenticated tag; a changed byte anywhere fails the tag and
 * no plaintext is published. The container header is deliberately not secret.
 */
object VaultCrypto {

    const val CHUNK_BYTES = 64 * 1024
    const val KEY_BYTES = 32
    const val IV_BYTES = 12
    const val SALT_BYTES = 16
    const val TAG_BITS = 128

    /** PBKDF2-HMAC-SHA256 work factor from the design (RFC 8018). */
    const val PBKDF2_ITERATIONS = 310_000

    /**
     * 32 characters with no look-alikes (no I, O, 0 or 1). 24 characters carry 120 bits.
     */
    const val RECOVERY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    const val RECOVERY_LENGTH = 24

    private const val MAGIC = 0x53575643 // "SWVC"
    private const val FORMAT_VERSION = 1

    private val random = SecureRandom()

    fun newKey(): ByteArray = ByteArray(KEY_BYTES).also { random.nextBytes(it) }

    fun newSalt(): ByteArray = ByteArray(SALT_BYTES).also { random.nextBytes(it) }

    fun generateRecoveryCode(): String = buildString(RECOVERY_LENGTH) {
        repeat(RECOVERY_LENGTH) { append(RECOVERY_ALPHABET[random.nextInt(RECOVERY_ALPHABET.length)]) }
    }

    /** "ABCD EFGH IJKL MNOP QRST UVWX" for the one-time setup screen. */
    fun formatRecoveryCode(code: String): String {
        val clean = normalizeRecoveryCode(code)
        return clean.chunked(4).joinToString(" ")
    }

    /** Uppercase and drop spaces/dashes, so typing the grouped code back always works. */
    fun normalizeRecoveryCode(input: String): String =
        input.uppercase().filter { it.isLetterOrDigit() }

    fun isRecoveryCode(input: String): Boolean {
        val clean = normalizeRecoveryCode(input)
        return clean.length == RECOVERY_LENGTH && clean.all { it in RECOVERY_ALPHABET }
    }

    /** PBKDF2-HMAC-SHA256 (API 26+), the design's HMAC-SHA1 fallback on API 24-25. */
    fun deriveKey(secret: String, salt: ByteArray, iterations: Int = PBKDF2_ITERATIONS): ByteArray {
        val algorithm =
            if (Build.VERSION.SDK_INT >= 26) "PBKDF2WithHmacSHA256" else "PBKDF2WithHmacSHA1"
        val spec = PBEKeySpec(secret.toCharArray(), salt, iterations, 256)
        try {
            return SecretKeyFactory.getInstance(algorithm).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun encryptBytes(plain: ByteArray, key: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        encrypt(ByteArrayInputStream(plain), plain.size.toLong(), out, key)
        return out.toByteArray()
    }

    fun decryptBytes(blob: ByteArray, key: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        decrypt(ByteArrayInputStream(blob), out, key)
        return out.toByteArray()
    }

    fun encryptFile(src: File, dest: File, key: ByteArray) {
        val part = File(dest.parentFile, "${dest.name}.part")
        try {
            part.parentFile?.mkdirs()
            FileOutputStream(part).use { fos ->
                FileInputStream(src).use { fis -> encrypt(fis, src.length(), fos, key) }
                Durable.syncStream(fos, part)
            }
            if (!part.renameTo(dest)) throw IOException("Could not write ${dest.name}")
        } catch (e: Exception) {
            part.delete()
            throw e
        }
    }

    fun decryptFile(src: File, dest: File, key: ByteArray) {
        val part = File(dest.parentFile, "${dest.name}.part")
        try {
            part.parentFile?.mkdirs()
            FileOutputStream(part).use { fos ->
                FileInputStream(src).use { fis -> decrypt(fis, fos, key) }
                Durable.syncStream(fos, part)
            }
            if (!part.renameTo(dest)) throw IOException("Could not write ${dest.name}")
        } catch (e: Exception) {
            part.delete()
            dest.delete()
            throw e
        }
    }

    // ------------------------------------------------------------------

    private fun encrypt(input: InputStream, totalLength: Long, output: OutputStream, key: ByteArray) {
        val out = DataOutputStream(output)
        out.writeInt(MAGIC)
        out.writeByte(FORMAT_VERSION)
        out.writeInt(CHUNK_BYTES)
        out.writeLong(totalLength)

        val buffer = ByteArray(CHUNK_BYTES)
        var remaining = totalLength
        while (remaining > 0) {
            val wanted = minOf(remaining, CHUNK_BYTES.toLong()).toInt()
            var read = 0
            while (read < wanted) {
                val n = input.read(buffer, read, wanted - read)
                if (n < 0) throw IOException("Source ended early")
                read += n
            }
            val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            val encrypted = cipher.doFinal(buffer, 0, read)
            out.writeByte(IV_BYTES)
            out.write(iv)
            out.writeInt(encrypted.size)
            out.write(encrypted)
            remaining -= read
        }
        out.flush()
    }

    private fun decrypt(input: InputStream, output: OutputStream, key: ByteArray) {
        val source = DataInputStream(input)
        try {
            if (source.readInt() != MAGIC) throw VaultAuthException("Not an encrypted Vault file")
            if (source.readUnsignedByte() != FORMAT_VERSION) throw VaultAuthException("Unsupported Vault file")
            if (source.readInt() != CHUNK_BYTES) throw VaultAuthException("Unsupported Vault chunk size")
            var remaining = source.readLong()
            if (remaining < 0) throw VaultAuthException("Damaged Vault file")

            while (remaining > 0) {
                val ivLength = source.readUnsignedByte()
                if (ivLength != IV_BYTES) throw VaultAuthException("Damaged Vault IV")
                val iv = ByteArray(ivLength).also { source.readFully(it) }
                val cipherLength = source.readInt()
                if (cipherLength < TAG_BITS / 8 || cipherLength > CHUNK_BYTES + TAG_BITS / 8) {
                    throw VaultAuthException("Damaged Vault chunk")
                }
                val encrypted = ByteArray(cipherLength).also { source.readFully(it) }
                val plain = try {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
                    cipher.doFinal(encrypted)
                } catch (e: AEADBadTagException) {
                    throw VaultAuthException("The Vault file was changed or the key is wrong", e)
                } catch (e: GeneralSecurityException) {
                    throw VaultAuthException("The Vault file could not be opened", e)
                }
                if (plain.size > remaining) throw VaultAuthException("Damaged Vault file")
                output.write(plain)
                remaining -= plain.size
            }
            output.flush()
        } catch (e: VaultAuthException) {
            throw e
        } catch (e: IOException) {
            throw VaultAuthException("The Vault file is incomplete", e)
        }
    }
}

/**
 * The Vault key wrapped by a secret (recovery code or passcode). The blob carries its own
 * salt and iteration count, so a `.swvault` backup restores on a new phone from the code alone.
 */
object VaultKeyWrap {

    private val MAGIC = byteArrayOf('S'.code.toByte(), 'W'.code.toByte(), 'V'.code.toByte(), 'W'.code.toByte(), '1'.code.toByte())

    fun seal(vaultKey: ByteArray, secret: String, salt: ByteArray, iterations: Int): ByteArray {
        val derived = VaultCrypto.deriveKey(secret, salt, iterations)
        val wrapped = try {
            VaultCrypto.encryptBytes(vaultKey, derived)
        } finally {
            derived.fill(0)
        }
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.write(MAGIC)
            data.writeInt(iterations)
            data.writeByte(salt.size)
            data.write(salt)
            data.writeInt(wrapped.size)
            data.write(wrapped)
        }
        return out.toByteArray()
    }

    fun open(blob: ByteArray, secret: String): ByteArray {
        val data = DataInputStream(ByteArrayInputStream(blob))
        return try {
            val magic = ByteArray(MAGIC.size).also { data.readFully(it) }
            if (!magic.contentEquals(MAGIC)) throw VaultAuthException("Not a Vault key file")
            val iterations = data.readInt()
            if (iterations !in 1..10_000_000) throw VaultAuthException("Damaged Vault key file")
            val saltLength = data.readUnsignedByte()
            if (saltLength !in 8..64) throw VaultAuthException("Damaged Vault key file")
            val salt = ByteArray(saltLength).also { data.readFully(it) }
            val wrappedLength = data.readInt()
            if (wrappedLength <= 0) throw VaultAuthException("Damaged Vault key file")
            val wrapped = ByteArray(wrappedLength).also { data.readFully(it) }

            val derived = VaultCrypto.deriveKey(secret, salt, iterations)
            try {
                VaultCrypto.decryptBytes(wrapped, derived)
            } finally {
                derived.fill(0)
            }
        } catch (e: VaultAuthException) {
            throw e
        } catch (e: IOException) {
            throw VaultAuthException("The Vault key file is incomplete", e)
        }
    }
}
