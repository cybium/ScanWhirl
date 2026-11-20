package com.scanwhirl.app.store.backup

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.store.vault.StreamIntegrityFixture
import com.scanwhirl.app.store.vault.VaultCrypto
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FullBackupIntegrityTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val password = "fictional-backup-password"

    private fun snapshot(): Map<String, List<Byte>> =
        listOf("documents", "vault2").flatMap { name ->
            File(app.filesDir, name).walkTopDown().filter { it.isFile }
                .map { it.relativeTo(app.filesDir).path to it.readBytes().toList() }.toList()
        }.toMap()

    private fun fixtures() {
        for (name in listOf("documents/existing/meta.txt", "vault2/existing/manifest.enc")) {
            File(app.filesDir, name).apply { parentFile!!.mkdirs(); writeText("fictional unchanged $name") }
        }
    }

    private fun archive(): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).apply {
            writeByte(1); writeUTF("documents/new/meta.txt")
            val meta = "Fictional legacy document\n".toByteArray()
            writeLong(meta.size.toLong()); write(meta)
            // Keep the multi-chunk integrity payload in a page, not oversized metadata.
            // This remains a valid archive under the independent restore resource limits.
            writeByte(1); writeUTF("documents/new/page.jpg")
            writeLong(200000); write(ByteArray(200000) { (it / 65536 + it % 251).toByte() })
            writeByte(0)
        }
    }.toByteArray()

    private fun header(salt: ByteArray): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).apply {
            writeBytes("SWFULLB1"); writeByte(2); writeByte(1)
            writeInt(VaultCrypto.PBKDF2_ITERATIONS); writeByte(salt.size); write(salt); writeByte(0)
        }
    }.toByteArray()

    @Test fun everyStreamAttackLeavesLibraryAndVaultByteIdentical() {
        fixtures()
        val before = snapshot()
        // Derive once for the fictional fixture; restore exercises the real production KDF.
        val salt = ByteArray(16) { (it + 7).toByte() }
        val key = VaultCrypto.deriveKey(password, salt)
        val plain = archive()
        val blob = VaultCrypto.encryptBytes(plain, key)
        val other = VaultCrypto.encryptBytes(plain.reversedArray(), key)
        for ((attack, bad) in StreamIntegrityFixture.mutations(blob, other)) {
            val file = File(app.cacheDir, "attack.swbackup").apply { writeBytes(header(salt) + bad) }
            try {
                assertNotNull(attack, runCatching { FullBackup(app).restore(file, password) }.exceptionOrNull())
                assertEquals(attack, before, snapshot())
                assertFalse(File(app.filesDir, "documents/new").exists())
                assertFalse(app.cacheDir.listFiles().orEmpty().any { it.name.startsWith("restore-") })
            } finally { file.delete() }
        }
        key.fill(0)
    }

    @Test fun archiveRequiresEndAndEofEvenWithValidEncryption() {
        fixtures()
        val before = snapshot()
        val salt = ByteArray(16) { (it + 3).toByte() }
        val key = VaultCrypto.deriveKey(password, salt)
        for (bad in listOf(archive().dropLast(1).toByteArray(), archive() + byteArrayOf(1, 2))) {
            val file = File(app.cacheDir, "framing.swbackup").apply {
                writeBytes(header(salt) + VaultCrypto.encryptBytes(bad, key))
            }
            try {
                assertNotNull(runCatching { FullBackup(app).restore(file, password) }.exceptionOrNull())
                assertEquals(before, snapshot())
                assertFalse(File(app.filesDir, "documents/new").exists())
            } finally { file.delete() }
        }
        key.fill(0)
    }

    @Test fun oldWriterArchiveStillRestores() {
        val salt = ByteArray(16) { (it + 9).toByte() }
        val key = VaultCrypto.deriveKey(password, salt)
        val file = File(app.cacheDir, "legacy.swbackup").apply {
            writeBytes(header(salt) + StreamIntegrityFixture.legacy(archive(), key))
        }
        try {
            assertEquals(1, FullBackup(app).restore(file, password).documents)
            assertEquals("Fictional legacy document\n", File(app.filesDir, "documents/new/meta.txt").readText())
            assertEquals(200000, File(app.filesDir, "documents/new/page.jpg").length().toInt())
        } finally { key.fill(0); file.delete() }
    }

    @Test fun realWriterUsesNewStream() {
        fixtures()
        val file = File(app.cacheDir, "writer.swbackup")
        try {
            FullBackup(app).write(file, password, FullBackup.Wrap.PASSWORD)
            val input = DataInputStream(file.inputStream())
            input.use {
                it.skipBytes(8 + 1 + 1 + 4)
                it.skipBytes(it.readUnsignedByte())
                it.readUnsignedByte()
                assertEquals(0x53575643, it.readInt())
                assertEquals(2, it.readUnsignedByte())
            }
        } finally { file.delete() }
    }
}
