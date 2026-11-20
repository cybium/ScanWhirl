package com.scanwhirl.app.store.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files

/**
 * VaultCrypto: AES-256-GCM over 64 KiB chunks, plus the PBKDF2 key wraps.
 * A tampered byte anywhere in a chunk must fail the 128-bit tag, never decrypt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VaultCryptoTest {

    private val key = ByteArray(32) { (it * 7 + 1).toByte() }

    @Test
    fun `streaming round trip keeps a multi-chunk file byte-identical`() {
        val dir = Files.createTempDirectory("vaultcrypto").toFile()
        try {
            val plain = ByteArray(64 * 1024 * 2 + 731) { (it % 251).toByte() }
            val src = File(dir, "src.bin").apply { writeBytes(plain) }
            val enc = File(dir, "src.enc")
            val out = File(dir, "out.bin")

            VaultCrypto.encryptFile(src, enc, key)
            assertFalse("no plaintext magic at the start", enc.readBytes().take(2) == plain.take(2))
            VaultCrypto.decryptFile(enc, out, key)

            assertArrayEquals(plain, out.readBytes())
            assertEquals(64 * 1024, VaultCrypto.CHUNK_BYTES)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a tampered byte fails with an auth error and leaves no output`() {
        val dir = Files.createTempDirectory("vaultcrypto").toFile()
        try {
            val src = File(dir, "src.bin").apply { writeBytes(ByteArray(500) { it.toByte() }) }
            val enc = File(dir, "src.enc")
            VaultCrypto.encryptFile(src, enc, key)
            val bytes = enc.readBytes()
            bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x40).toByte()
            enc.writeBytes(bytes)
            val out = File(dir, "out.bin")

            assertThrows(VaultAuthException::class.java) {
                VaultCrypto.decryptFile(enc, out, key)
            }
            assertFalse("a failed decrypt never publishes plaintext", out.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the wrong key fails`() {
        val dir = Files.createTempDirectory("vaultcrypto").toFile()
        try {
            val src = File(dir, "src.bin").apply { writeBytes("secret".toByteArray()) }
            val enc = File(dir, "src.enc")
            VaultCrypto.encryptFile(src, enc, key)
            assertThrows(VaultAuthException::class.java) {
                VaultCrypto.decryptFile(enc, File(dir, "out.bin"), ByteArray(32) { 9 })
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the recovery wrap rejects a wrong code and accepts the right one`() {
        val vaultKey = VaultCrypto.newKey()
        val salt = VaultCrypto.newSalt()
        val code = VaultCrypto.generateRecoveryCode()

        val blob = VaultKeyWrap.seal(vaultKey, code, salt, iterations = 2_000)
        assertArrayEquals(vaultKey, VaultKeyWrap.open(blob, code))
        assertThrows(VaultAuthException::class.java) {
            VaultKeyWrap.open(blob, "AAAAAAAAAAAAAAAAAAAAAAAA")
        }
    }

    @Test
    fun `a wrap with trailing data is refused before deriving a key`() {
        val code = VaultCrypto.generateRecoveryCode()
        val blob = VaultKeyWrap.seal(key, code, VaultCrypto.newSalt(), iterations = 2_000)
        var derivations = 0
        VaultCrypto.derivationProbe = { derivations++ }
        try {
            assertThrows(VaultAuthException::class.java) { VaultKeyWrap.open(blob + 0.toByte(), code) }
            assertThrows(VaultAuthException::class.java) { VaultKeyWrap.open(blob.dropLast(1).toByteArray(), code) }
            val lengthOffset = 5 + Int.SIZE_BYTES + 1 + VaultCrypto.SALT_BYTES
            val incorrectLength = blob.copyOf()
            ByteBuffer.wrap(incorrectLength).apply { putInt(lengthOffset, getInt(lengthOffset) + 1) }
            assertThrows(VaultAuthException::class.java) { VaultKeyWrap.open(incorrectLength, code) }
            assertEquals(0, derivations)
        } finally {
            VaultCrypto.derivationProbe = null
        }
    }

    @Test
    fun `recovery codes are 24 unambiguous characters grouped in fours`() {
        val code = VaultCrypto.generateRecoveryCode()
        assertEquals(24, code.length)
        assertTrue(code.all { it in VaultCrypto.RECOVERY_ALPHABET })
        val grouped = VaultCrypto.formatRecoveryCode(code)
        assertEquals(29, grouped.length)
        assertEquals(code, VaultCrypto.normalizeRecoveryCode(grouped))
        assertEquals(code, VaultCrypto.normalizeRecoveryCode(grouped.lowercase()))
        assertTrue(VaultCrypto.isRecoveryCode(grouped))
        assertFalse(VaultCrypto.isRecoveryCode("ABC"))
    }

    @Test
    fun `the production PBKDF2 iteration count is the design value`() {
        assertEquals(310_000, VaultCrypto.PBKDF2_ITERATIONS)
    }
}
