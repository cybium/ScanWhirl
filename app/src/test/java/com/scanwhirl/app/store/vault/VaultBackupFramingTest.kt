package com.scanwhirl.app.store.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** Standalone Vault backups must never publish mismatched file-length framing. */
class VaultBackupFramingTest {
    @get:Rule val temp = TemporaryFolder()

    private fun changingLength(real: File, declared: Long) = object : File(real.path) {
        private var reads = 0
        override fun length(): Long = if (reads++ == 0) declared else real.length()
    }

    private fun write(dest: File, file: File, extra: Boolean) {
        if (extra) {
            VaultBackup.write(dest, byteArrayOf(1), emptyList(), listOf(file))
        } else {
            val dir = object : File(file.parent) {
                override fun listFiles(): Array<File> = arrayOf(file)
            }
            VaultBackup.write(dest, byteArrayOf(1), listOf(dir))
        }
    }

    private fun assertMismatch(extra: Boolean, actual: Int, declared: Long, existing: Boolean = false) {
        val dir = temp.newFolder("entry")
        val real = File(dir, if (extra) "passwords.bin" else "manifest.enc")
            .apply { writeBytes(ByteArray(actual) { 7 }) }
        val source = real.readBytes()
        val dest = File(temp.root, "backup.swvault")
        val previous = byteArrayOf(9, 8, 7)
        if (existing) dest.writeBytes(previous)
        val failure = runCatching { write(dest, changingLength(real, declared), extra) }.exceptionOrNull()
        assertTrue("expected IOException, got $failure", failure is IOException)
        assertFalse("partial backup must be removed", File(temp.root, "${dest.name}.part").exists())
        if (existing) assertArrayEquals(previous, dest.readBytes())
        else assertFalse("failed backup must not be published", dest.exists())
        assertArrayEquals("source must be untouched", source, real.readBytes())
    }

    @Test fun `entry growing between length reads fails and deletes partial output`() =
        assertMismatch(extra = false, actual = 10_000, declared = 4_000)

    @Test fun `entry shrinking between length reads fails and deletes partial output`() =
        assertMismatch(extra = false, actual = 3_000, declared = 9_000)

    @Test fun `extra growing between length reads fails and deletes partial output`() =
        assertMismatch(extra = true, actual = 10_000, declared = 4_000)

    @Test fun `extra shrinking between length reads fails and deletes partial output`() =
        assertMismatch(extra = true, actual = 3_000, declared = 9_000)

    @Test fun `failed entry backup preserves a previous destination`() =
        assertMismatch(extra = false, actual = 10_000, declared = 4_000, existing = true)

    @Test fun `failed extra backup preserves a previous destination`() =
        assertMismatch(extra = true, actual = 10_000, declared = 4_000, existing = true)

    @Test fun `unchanged entry and extra round trip across copy buffers`() {
        val dir = temp.newFolder("entry")
        val entry = File(dir, "manifest.enc").apply { writeBytes(ByteArray(150_000) { (it % 251).toByte() }) }
        val extra = File(temp.root, "passwords.bin").apply { writeBytes(ByteArray(140_000) { 13 }) }
        val dest = File(temp.root, "backup.swvault")
        val recovery = byteArrayOf(1, 2, 3)
        VaultBackup.write(dest, recovery, listOf(dir), listOf(extra))
        val staging = File(temp.root, "restored")
        val contents = VaultBackup.extract(dest, staging)
        assertArrayEquals(recovery, contents.recoveryBlob)
        assertEquals(listOf("entry"), contents.entryIds)
        assertArrayEquals(entry.readBytes(), File(staging, "entry/manifest.enc").readBytes())
        assertArrayEquals(extra.readBytes(), contents.extras.single().readBytes())
        assertFalse(File(temp.root, "${dest.name}.part").exists())
    }
}
