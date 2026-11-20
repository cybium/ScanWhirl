package com.scanwhirl.app.store.vault

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StreamIntegrityTest {
    private val key = ByteArray(32) { (it + 1).toByte() }
    private val plain = ByteArray(VaultCrypto.CHUNK_BYTES * 3 + 37) { (it / 65536 + it % 251).toByte() }

    private fun reject(name: String) {
        val blob = VaultCrypto.encryptBytes(plain, key)
        val other = VaultCrypto.encryptBytes(plain.reversedArray(), key)
        val bad = StreamIntegrityFixture.mutations(blob, other).getValue(name)
        val dir = Files.createTempDirectory("integrity").toFile()
        try {
            val source = File(dir, "encrypted").apply { writeBytes(bad) }
            val dest = File(dir, "plain")
            assertThrows(name, VaultAuthException::class.java) { VaultCrypto.decryptFile(source, dest, key) }
            assertFalse(dest.exists())
            assertFalse(File(dir, "plain.part").exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun swap() = reject("swap")
    @Test fun duplicate() = reject("duplicate")
    @Test fun truncate() = reject("truncate")
    @Test fun trailing() = reject("trailing")
    @Test fun finalFlag() = reject("final flag")
    @Test fun splice() = reject("splice")

    @Test fun roundTrips() {
        for (size in listOf(0, 1, 65536, 131072, plain.size)) {
            val bytes = plain.copyOf(size)
            val encrypted = VaultCrypto.encryptBytes(bytes, key)
            assertEquals(VaultCrypto.encryptedLength(size.toLong()), encrypted.size.toLong())
            assertArrayEquals(bytes, VaultCrypto.decryptBytes(encrypted, key))
        }
    }

    @Test fun legacyStillOpens() {
        assertArrayEquals(plain, VaultCrypto.decryptBytes(StreamIntegrityFixture.legacy(plain, key), key))
        val secret = "fictional recovery secret"
        val salt = ByteArray(16) { it.toByte() }
        val derived = VaultCrypto.deriveKey(secret, salt, 2000)
        val wrapped = StreamIntegrityFixture.legacy(key, derived)
        val bytes = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bytes).apply {
            writeBytes("SWVW1"); writeInt(2000); writeByte(16); write(salt)
            writeInt(wrapped.size); write(wrapped)
        }
        assertArrayEquals(key, VaultKeyWrap.open(bytes.toByteArray(), secret))
    }
}
