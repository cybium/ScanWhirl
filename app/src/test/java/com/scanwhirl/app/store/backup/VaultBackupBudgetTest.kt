package com.scanwhirl.app.store.backup

import com.scanwhirl.app.store.vault.VaultBackup
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataOutputStream
import java.io.File
import java.io.InterruptedIOException

class VaultBackupBudgetTest {
    @get:Rule val folder = TemporaryFolder()

    private fun source(): File = File(folder.root, "fictional.swvault").apply {
        DataOutputStream(outputStream()).use { out ->
            out.write("SWVAULT1".toByteArray()); out.writeInt(2)
            out.writeInt(1); out.writeByte(1); out.writeInt(2)
            repeat(2) {
                out.writeUTF("entry$it"); out.writeInt(1)
                out.writeUTF("manifest.enc"); out.writeLong(60); out.write(ByteArray(60))
            }
            out.writeInt(0)
        }
    }

    private fun refuse(limits: FullBackup.RestoreLimits) {
        val keep = File(folder.root, "existing.enc").apply { writeBytes(byteArrayOf(2, 5, 8)) }
        val staging = File(folder.root, "staging")
        assertNotNull(runCatching { VaultBackup.extract(source(), staging, limits) }.exceptionOrNull())
        assertFalse("Partial extraction must be removed", staging.exists())
        assertArrayEquals(byteArrayOf(2, 5, 8), keep.readBytes())
    }

    @Test fun `aggregate budget refuses a later file and deletes all staging`() =
        refuse(FullBackup.RestoreLimits(maxBytes = 100, space = { Long.MAX_VALUE }))

    @Test fun `low space refuses and deletes staging`() =
        refuse(FullBackup.RestoreLimits(space = { 0L }))

    @Test fun `cancel mid extraction deletes staging`() {
        var checks = 0
        refuse(FullBackup.RestoreLimits(space = { Long.MAX_VALUE }, checkCancelled = {
            if (++checks >= 4) throw InterruptedIOException("Fictional cancellation")
        }))
        assertTrue(checks >= 4)
    }

    @Test fun `normal extraction is unchanged`() {
        val staging = File(folder.root, "staging")
        val result = VaultBackup.extract(source(), staging,
            FullBackup.RestoreLimits(space = { Long.MAX_VALUE }))
        assertEquals(listOf("entry0", "entry1"), result.entryIds)
        assertEquals(60L, File(staging, "entry1/manifest.enc").length())
    }
}
