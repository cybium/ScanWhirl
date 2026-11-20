package com.scanwhirl.app.store.backup

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.store.vault.VaultCrypto
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FullBackupBudgetTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val password = "fictional-password"

    private fun archive(vararg entries: Pair<String, ByteArray>): File {
        val plain = ByteArrayOutputStream()
        DataOutputStream(plain).use { out ->
            entries.forEach { (path, bytes) ->
                out.writeByte(1); out.writeUTF(path); out.writeLong(bytes.size.toLong()); out.write(bytes)
            }
            out.writeByte(0)
        }
        val salt = VaultCrypto.newSalt()
        val key = VaultCrypto.deriveKey(password, salt, 10_000)
        return File(app.cacheDir, "budget-fixture.swbackup").also { file ->
            file.outputStream().use { raw ->
                val out = DataOutputStream(raw)
                out.write("SWFULLB1".toByteArray()); out.writeByte(2); out.writeByte(1)
                out.writeInt(10_000); out.writeByte(salt.size); out.write(salt); out.writeByte(1)
                VaultCrypto.encryptStream(plain.toByteArray().inputStream(), plain.size().toLong(), raw, key)
            }
            key.fill(0)
        }
    }

    private fun snapshot(): Map<String, List<Byte>> =
        app.filesDir.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(app.filesDir).path to it.readBytes().toList()
        }

    private fun refused(
        vararg entries: Pair<String, ByteArray>,
        limits: FullBackup.RestoreLimits = FullBackup.RestoreLimits()
    ) {
        File(app.filesDir, "documents/existing/meta.txt").apply { parentFile!!.mkdirs(); writeText("Keep me\n") }
        File(app.filesDir, "vault2/existing/fixture.enc").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(4, 8, 15)) }
        val before = snapshot()
        val error = runCatching { FullBackup(app, restoreLimits = limits).restore(archive(*entries), password) }.exceptionOrNull()
        assertNotNull("Unsafe archive must be refused", error)
        assertEquals("No library or Vault bytes may change", before, snapshot())
        assertFalse("Scratch must be deleted", app.cacheDir.listFiles().orEmpty().any { it.name.startsWith("restore-") })
    }

    @Test fun `oversized key is refused before it can be loaded as a key`() =
        refused("vault-key.bin" to ByteArray(10 * 1024 * 1024))

    @Test fun `short key is refused too`() = refused("vault-key.bin" to ByteArray(31))

    @Test fun `settings larger than one MiB are refused before publishing documents`() =
        refused("documents/new/meta.txt" to "New\n".toByteArray(), "settings.json" to ByteArray(1024 * 1024 + 1))

    @Test fun `document metadata larger than 64 KiB is refused`() =
        refused("documents/new/meta.txt" to ByteArray(64 * 1024 + 1))

    @Test fun `deep paths are refused before creating directories`() =
        refused(("drafts/" + (1..20).joinToString("/") { "d" } + "/x") to byteArrayOf(1))

    @Test fun `aggregate decrypted bytes stop mid restore without publication`() =
        refused(
            "documents/new/page1.jpg" to ByteArray(80_000),
            "documents/new/page2.jpg" to ByteArray(80_000),
            limits = FullBackup.RestoreLimits(maxBytes = 100_000)
        )

    @Test fun `low space before decryption refuses without publication`() =
        refused("documents/new/meta.txt" to "New\n".toByteArray(),
            limits = FullBackup.RestoreLimits(space = { 0L }))

    @Test fun `space exhaustion during decryption deletes partial scratch`() {
        var probes = 0
        refused("documents/new/page.jpg" to ByteArray(200_000),
            limits = FullBackup.RestoreLimits(space = {
                if (++probes <= 1) Long.MAX_VALUE else 0L
            }))
        assertTrue(probes > 1)
    }

    @Test fun `cancel during decryption deletes partial scratch`() {
        var checks = 0
        refused("documents/new/page.jpg" to ByteArray(200_000),
            limits = FullBackup.RestoreLimits(checkCancelled = {
                if (++checks >= 2) throw java.io.InterruptedIOException("Fictional cancellation")
            }))
        assertTrue(checks >= 2)
    }

    @Test fun `entry count refusal happens before publication`() =
        refused("documents/new/meta.txt" to "New\n".toByteArray(), "settings.json" to "{}".toByteArray(),
            limits = FullBackup.RestoreLimits(maxEntries = 1))

    @Test fun `normal bounded archive still restores`() {
        val source = archive("documents/new/meta.txt" to "New\n".toByteArray(),
            "documents/new/page.jpg" to byteArrayOf(3, 7, 9), "settings.json" to "{}".toByteArray())
        assertEquals(1, FullBackup(app).restore(source, password).documents)
        assertArrayEquals(byteArrayOf(3, 7, 9), File(app.filesDir, "documents/new/page.jpg").readBytes())
    }

    @Test fun `replay independently enforces aggregate budget and deletes nothing outside staging`() {
        val raw = File(app.cacheDir, "raw-archive")
        DataOutputStream(raw.outputStream()).use { out ->
            repeat(2) { i ->
                out.writeByte(1); out.writeUTF("drafts/$i.bin"); out.writeLong(60); out.write(ByteArray(60))
            }
            out.writeByte(0)
        }
        val staging = File(app.cacheDir, "replay-fixture")
        val backup = FullBackup(app, restoreLimits = FullBackup.RestoreLimits(maxBytes = 100))
        val method = FullBackup::class.java.getDeclaredMethod("replay", File::class.java, File::class.java)
        method.isAccessible = true
        val before = snapshot()
        val error = runCatching { method.invoke(backup, raw, staging) }.exceptionOrNull()
        assertTrue(error?.cause is BackupResourceException)
        assertEquals(before, snapshot())
        assertFalse(File(staging, "drafts/1.bin").exists())
        staging.deleteRecursively()
    }
}
