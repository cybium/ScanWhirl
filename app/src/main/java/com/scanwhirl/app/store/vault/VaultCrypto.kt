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
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** The Vault key did not decrypt: a wrong code/passcode, or bytes changed on disk (GCM tag). */
open class VaultAuthException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The Vault has no key in memory; unlock it first. */
class VaultLockedException : IllegalStateException("The Vault is locked")

/**
 * AES-256-GCM file encryption for the Vault.
 *
 * Files are encrypted in bounded 64 KiB chunks (The design's streaming rule), so
 * opening a large scan never needs the whole file in memory. Each chunk has a fresh random
 * 96-bit IV and 128-bit tag. New streams authenticate the header, stream identity, chunk
 * order and final marker. File callers stage plaintext until the entire stream validates;
 * stream callers MUST likewise stage output, which is provisional until successful return.
 * Legacy v1 remains readable but is NOT order-authenticated.
 */
object VaultCrypto {

    const val CHUNK_BYTES = 64 * 1024
    const val KEY_BYTES = 32
    const val IV_BYTES = 12
    const val SALT_BYTES = 16
    const val TAG_BITS = 128

    /** PBKDF2-HMAC-SHA256 work factor from the design (RFC 8018). */
    const val PBKDF2_ITERATIONS = 310_000

    /** Test seam: called at the start of every derivation (thread probe). */
    @Volatile
    internal var derivationProbe: (() -> Unit)? = null

    /**
     * 32 characters with no look-alikes (no I, O, 0 or 1). 24 characters carry 120 bits.
     */
    const val RECOVERY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    const val RECOVERY_LENGTH = 24

    private const val MAGIC = 0x53575643 // "SWVC"
    private const val LEGACY_VERSION = 1
    private const val FORMAT_VERSION = 2
    private const val STREAM_ID_BYTES = 16

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
        // Test seam: the Vault screen must never run this on the main thread (C02).
        derivationProbe?.invoke()
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

    fun decryptBytes(blob: ByteArray, key: ByteArray, expectedLength: Long? = null): ByteArray {
        val out = ByteArrayOutputStream()
        decrypt(ByteArrayInputStream(blob), out, key, expectedLength)
        return out.toByteArray()
    }

    internal fun encryptedLength(plainLength: Long): Long {
        val chunks = if (plainLength == 0L) 1 else (plainLength - 1) / CHUNK_BYTES + 1
        return 33 + plainLength + chunks * (8 + 1 + 1 + IV_BYTES + 4 + TAG_BITS / 8)
    }

    /** Size preflight only; the decryptor still validates the declared format and authentication. */
    internal fun isEncryptedLength(actual: Long, plainLength: Long, version: Int): Boolean {
        val legacyChunks = if (plainLength == 0L) 0 else (plainLength - 1) / CHUNK_BYTES + 1
        val legacyLength = 17 + plainLength + legacyChunks * (1 + IV_BYTES + 4 + TAG_BITS / 8)
        return when (version) {
            LEGACY_VERSION -> actual == legacyLength
            FORMAT_VERSION -> actual == encryptedLength(plainLength)
            else -> false
        }
    }

    internal fun isEncryptedFileLength(file: File, plainLength: Long): Boolean =
        DataInputStream(FileInputStream(file)).use { input ->
            try {
                input.readInt() == MAGIC && isEncryptedLength(file.length(), plainLength, input.readUnsignedByte())
            } catch (_: IOException) { false }
        }

    /**
     * Streams [totalLength] bytes of [input] into [output] with the same chunked AES-256-GCM
     * container [encryptFile] uses (one random IV and one 128-bit tag per 64 KiB chunk), so a
     * backup of a large library costs one chunk of memory. The caller owns the destination.
     */
    fun encryptStream(input: InputStream, totalLength: Long, output: OutputStream, key: ByteArray) =
        encrypt(input, totalLength, output, key)

    /** Validates new streams through final+EOF; legacy v1 lacks order authentication. Stage output. */
    fun decryptStream(input: InputStream, output: OutputStream, key: ByteArray) =
        decrypt(input, output, key)

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

    fun decryptFile(src: File, dest: File, key: ByteArray, expectedLength: Long? = null) {
        val part = File(dest.parentFile, "${dest.name}.part")
        try {
            part.parentFile?.mkdirs()
            FileOutputStream(part).use { fos ->
                FileInputStream(src).use { fis -> decrypt(fis, fos, key, expectedLength) }
                Durable.syncStream(fos, part)
            }
            if (!part.renameTo(dest)) throw IOException("Could not write ${dest.name}")
        } catch (e: Exception) {
            part.delete()
            dest.delete()
            throw e
        }
    }

    /** Authenticates an entry file and checks its digest without writing any plaintext to disk. */
    internal fun verifyFile(src: File, key: ByteArray, expectedLength: Long, expectedSha: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        val output = object : OutputStream() {
            override fun write(b: Int) = digest.update(b.toByte())
            override fun write(b: ByteArray, off: Int, len: Int) = digest.update(b, off, len)
        }
        FileInputStream(src).use { decrypt(it, output, key, expectedLength) }
        val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
        if (actualSha != expectedSha) throw VaultAuthException("The Vault copy does not match the original")
    }

    // ------------------------------------------------------------------

    private fun encrypt(input: InputStream, totalLength: Long, output: OutputStream, key: ByteArray) {
        require(totalLength >= 0)
        val out = DataOutputStream(output)
        val streamId = ByteArray(STREAM_ID_BYTES).also { random.nextBytes(it) }
        val header = streamHeader(totalLength, streamId)
        out.write(header)

        val buffer = ByteArray(CHUNK_BYTES)
        var remaining = totalLength
        var index = 0L
        do {
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
            val final = remaining == read.toLong()
            cipher.updateAAD(chunkAad(header, index, final))
            val encrypted = cipher.doFinal(buffer, 0, read)
            out.writeLong(index++)
            out.writeByte(if (final) 1 else 0)
            out.writeByte(IV_BYTES)
            out.write(iv)
            out.writeInt(encrypted.size)
            out.write(encrypted)
            remaining -= read
        } while (remaining > 0)
        if (input.read() != -1) throw IOException("Source grew while encrypting")
        out.flush()
    }

    private fun streamHeader(length: Long, streamId: ByteArray): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).apply {
                writeInt(MAGIC); writeByte(FORMAT_VERSION); writeInt(CHUNK_BYTES)
                writeLong(length); write(streamId)
            }
        }.toByteArray()

    private fun chunkAad(header: ByteArray, index: Long, final: Boolean): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).apply {
                write(header); writeLong(index); writeByte(if (final) 1 else 0)
            }
        }.toByteArray()

    private fun decrypt(input: InputStream, output: OutputStream, key: ByteArray, expectedLength: Long? = null) {
        val source = DataInputStream(input)
        try {
            if (source.readInt() != MAGIC) throw VaultAuthException("Not an encrypted Vault file")
            val version = source.readUnsignedByte()
            if (version != FORMAT_VERSION && version != LEGACY_VERSION) throw VaultAuthException("Unsupported Vault file")
            if (source.readInt() != CHUNK_BYTES) throw VaultAuthException("Unsupported Vault chunk size")
            var remaining = source.readLong()
            if (remaining < 0 || (expectedLength != null && remaining != expectedLength)) {
                throw VaultAuthException("Damaged Vault file")
            }
            if (version == FORMAT_VERSION) {
                val streamId = ByteArray(STREAM_ID_BYTES).also { source.readFully(it) }
                decryptOrdered(source, output, key, remaining, streamHeader(remaining, streamId))
                return
            }

            // Compatibility only: original v1 chunks were independent, not order-authenticated.
            // Preserve old behaviour for existing Vault files and outer-v2/legacy backups.
            while (remaining > 0) {
                val ivLength = source.readUnsignedByte()
                if (ivLength != IV_BYTES) throw VaultAuthException("Damaged Vault IV")
                val iv = ByteArray(ivLength).also { source.readFully(it) }
                val cipherLength = source.readInt()
                if (cipherLength < TAG_BITS / 8 || cipherLength > CHUNK_BYTES + TAG_BITS / 8) {
                    throw VaultAuthException("Damaged Vault chunk")
                }
                if (expectedLength != null && cipherLength.toLong() != minOf(remaining, CHUNK_BYTES.toLong()) + TAG_BITS / 8) {
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
            if (expectedLength != null && source.read() != -1) throw VaultAuthException("Damaged Vault file")
            output.flush()
        } catch (e: VaultAuthException) {
            throw e
        } catch (e: IOException) {
            throw VaultAuthException("The Vault file is incomplete", e)
        }
    }

    private fun decryptOrdered(source: DataInputStream, output: OutputStream, key: ByteArray,
                               totalLength: Long, header: ByteArray) {
        var remaining = totalLength
        var index = 0L
        do {
            if (source.readLong() != index) throw VaultAuthException("Damaged Vault chunk order")
            val flag = source.readUnsignedByte()
            if (flag !in 0..1) throw VaultAuthException("Damaged Vault final marker")
            val final = flag == 1
            val wanted = minOf(remaining, CHUNK_BYTES.toLong()).toInt()
            if (final != (remaining <= CHUNK_BYTES)) throw VaultAuthException("Damaged Vault final marker")
            if (source.readUnsignedByte() != IV_BYTES) throw VaultAuthException("Damaged Vault IV")
            val iv = ByteArray(IV_BYTES).also { source.readFully(it) }
            val size = source.readInt()
            if (size != wanted + TAG_BITS / 8) throw VaultAuthException("Damaged Vault chunk")
            val encrypted = ByteArray(size).also { source.readFully(it) }
            val plain = try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
                cipher.updateAAD(chunkAad(header, index, final))
                cipher.doFinal(encrypted)
            } catch (e: GeneralSecurityException) {
                throw VaultAuthException("The Vault file was changed or the key is wrong", e)
            }
            remaining -= plain.size
            if (final && source.read() != -1) throw VaultAuthException("Trailing Vault data")
            output.write(plain)
            index++
        } while (remaining > 0)
        output.flush()
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

    /**
     * R03 NEW-1: the structural checks of [open] (size, magic, iterations, salt and wrapped
     * lengths) without deriving a key. A wrap that fails here can never open with any secret,
     * so a restore must not adopt it as this phone's only recovery wrap.
     */
    fun isWellFormed(blob: ByteArray): Boolean = try {
        parse(blob)
        true
    } catch (e: VaultAuthException) {
        false
    }

    fun open(blob: ByteArray, secret: String): ByteArray {
        val (iterations, salt, wrapped) = parse(blob)
        val derived = VaultCrypto.deriveKey(secret, salt, iterations)
        return try {
            VaultCrypto.decryptBytes(wrapped, derived, VaultCrypto.KEY_BYTES.toLong())
        } catch (e: VaultAuthException) {
            throw e
        } catch (e: IOException) {
            throw VaultAuthException("The Vault key file is incomplete", e)
        } finally {
            derived.fill(0)
        }
    }

    private fun parse(blob: ByteArray): Triple<Int, ByteArray, ByteArray> {
        if (blob.size > VaultControlBlobs.MAX_WRAP_BYTES) throw VaultAuthException("Damaged Vault key file")
        val data = DataInputStream(ByteArrayInputStream(blob))
        return try {
            val magic = ByteArray(MAGIC.size).also { data.readFully(it) }
            if (!magic.contentEquals(MAGIC)) throw VaultAuthException("Not a Vault key file")
            val iterations = data.readInt()
            if (iterations !in 1..10_000_000) throw VaultAuthException("Damaged Vault key file")
            val saltLength = data.readUnsignedByte()
            if (saltLength !in 8..64) throw VaultAuthException("Damaged Vault key file")
            // ByteArrayInputStream.available() is exact. A wrap always carries one 32-byte key.
            val expectedWrappedLength = VaultCrypto.encryptedLength(VaultCrypto.KEY_BYTES.toLong()).toInt()
            val legacyWrappedLength = 17 + VaultCrypto.KEY_BYTES + 1 + VaultCrypto.IV_BYTES + 4 + VaultCrypto.TAG_BITS / 8
            val remaining = data.available()
            if (remaining != saltLength + Int.SIZE_BYTES + expectedWrappedLength &&
                remaining != saltLength + Int.SIZE_BYTES + legacyWrappedLength) {
                throw VaultAuthException("Damaged Vault key file")
            }
            val salt = ByteArray(saltLength).also { data.readFully(it) }
            val wrappedLength = data.readInt()
            if ((wrappedLength != expectedWrappedLength && wrappedLength != legacyWrappedLength) ||
                data.available() != wrappedLength) throw VaultAuthException("Damaged Vault key file")
            val wrapped = ByteArray(wrappedLength).also { data.readFully(it) }
            Triple(iterations, salt, wrapped)
        } catch (e: VaultAuthException) {
            throw e
        } catch (e: IOException) {
            throw VaultAuthException("The Vault key file is incomplete", e)
        }
    }
}
