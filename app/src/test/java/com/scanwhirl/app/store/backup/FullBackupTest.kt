package com.scanwhirl.app.store.backup

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.store.vault.FakeVaultKeys
import com.scanwhirl.app.store.vault.VaultAuthException
import com.scanwhirl.app.store.vault.VaultCrypto
import com.scanwhirl.app.store.vault.VaultPasscodeGate
import com.scanwhirl.app.store.vault.VaultSession
import com.scanwhirl.app.store.vault.VaultStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile

/**
 * Full Backup 2.0: one AES-256-GCM file with the library, the Vault, signatures/stamps and
 * settings, opened by either a backup password or the Vault recovery code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FullBackupTest {

    private lateinit var app: Application
    private lateinit var backup: FullBackup
    private lateinit var out: File
    private val password = "correct horse battery"

    private val docsDir get() = File(app.filesDir, "documents")
    private val vaultDir get() = File(app.filesDir, "vault2")

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        backup = FullBackup(app)
        out = File(app.cacheDir, "backup-${System.nanoTime()}.swbackup")
        listOf("documents", "vault2", "signatures", "stamps", "drafts").forEach {
            File(app.filesDir, it).deleteRecursively()
        }
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
    }

    @After
    fun tearDown() {
        out.delete()
        listOf("documents", "vault2", "signatures", "stamps", "drafts").forEach {
            File(app.filesDir, it).deleteRecursively()
        }
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
    }

    private fun doc(id: String, name: String, pages: Int = 2, category: String = "Other"): File {
        val dir = File(docsDir, id).apply { mkdirs() }
        File(dir, "meta.txt").writeText("$name\n${System.currentTimeMillis()}\n$category\n")
        File(dir, "scan.pdf").writeBytes(byteArrayOf(37, 80, 68, 70))
        repeat(pages) { p ->
            File(dir, "page_%03d.jpg".format(p)).writeBytes(ByteArray(2048) { (p + it).toByte() })
        }
        File(dir, "ocr.txt").writeText("#scanwhirl-ocr-v2\npage_000.jpg\t${b64("text of $name")}\n")
        return dir
    }

    private fun b64(s: String): String =
        android.util.Base64.encodeToString(s.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)

    @Test
    fun `round trip restores the library, the vault, signatures and settings`() {
        doc("doc_a", "Receipts", pages = 3, category = "Receipts")
        File(app.filesDir, "signatures").mkdirs()
        File(app.filesDir, "signatures/my-signature.png").writeBytes(byteArrayOf(1, 2, 3))
        vaultDir.mkdirs()
        File(vaultDir, "recovery.bin").writeBytes(byteArrayOf(9, 9, 9))
        File(vaultDir, "entry1").mkdirs()
        File(vaultDir, "entry1/manifest.enc").writeBytes(byteArrayOf(4, 5, 6))
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().putString("ocr_language", "hi").commit()

        backup.write(out, password, FullBackup.Wrap.PASSWORD)
        assertTrue("the backup file exists", out.length() > 0)

        // Wipe everything a new phone would not have.
        File(app.filesDir, "documents").deleteRecursively()
        File(app.filesDir, "signatures").deleteRecursively()
        File(app.filesDir, "vault2").deleteRecursively()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()

        val report = backup.restore(out, password)
        assertEquals(1, report.documents)
        assertEquals(1, report.vaultEntries)
        assertTrue("signatures restored", File(app.filesDir, "signatures/my-signature.png").isFile)
        assertTrue(File(app.filesDir, "vault2/entry1/manifest.enc").isFile)
        assertTrue(File(app.filesDir, "vault2/recovery.bin").isFile)
        assertEquals("hi", app.getSharedPreferences("scanwhirl_prefs", 0).getString("ocr_language", null))

        val restored = File(docsDir, "doc_a")
        assertEquals("Receipts", File(restored, "meta.txt").readLines().first())
        assertEquals(3, restored.listFiles()!!.count { it.name.startsWith("page_") })
        assertTrue(File(restored, "scan.pdf").readBytes().contentEquals(byteArrayOf(37, 80, 68, 70)))
        assertTrue(File(restored, "ocr.txt").readText().contains("scanwhirl-ocr-v2"))
    }

    @Test
    fun `the header records which secret opens the file`() {
        doc("doc_b", "Password doc")
        backup.write(out, password, FullBackup.Wrap.PASSWORD)
        assertEquals(FullBackup.Wrap.PASSWORD, backup.header(out).wrap)
        assertTrue(backup.header(out).iterations >= 310_000)

        val code = VaultCrypto.generateRecoveryCode()
        out.delete()
        backup.write(out, VaultCrypto.formatRecoveryCode(code), FullBackup.Wrap.RECOVERY_CODE)
        assertEquals(FullBackup.Wrap.RECOVERY_CODE, backup.header(out).wrap)

        // A password backup cannot be opened with a recovery code, and the other way round.
        out.delete()
        backup.write(out, password, FullBackup.Wrap.PASSWORD)
        try {
            backup.restore(out, code)
            fail("a password backup accepted a recovery code")
        } catch (_: VaultAuthException) {
        }
    }

    @Test
    fun `a recovery-code backup restores with the code and not with a password`() {
        doc("doc_c", "Code doc")
        val code = VaultCrypto.formatRecoveryCode(VaultCrypto.generateRecoveryCode())
        backup.write(out, code, FullBackup.Wrap.RECOVERY_CODE)
        File(docsDir, "doc_c").deleteRecursively()

        try {
            backup.restore(out, "another password")
            fail("a recovery-code backup accepted a password")
        } catch (_: VaultAuthException) {
        }
        assertFalse("nothing was written", File(docsDir, "doc_c").exists())

        assertEquals(1, backup.restore(out, code).documents)
        assertTrue(File(docsDir, "doc_c").isDirectory)
    }

    @Test
    fun `a wrong password changes nothing`() {
        doc("doc_d", "Secret")
        backup.write(out, password, FullBackup.Wrap.PASSWORD)
        File(docsDir, "doc_d").deleteRecursively()

        try {
            backup.restore(out, "not the password")
            fail("a wrong password was accepted")
        } catch (_: VaultAuthException) {
        }
        assertFalse(File(docsDir, "doc_d").exists())
    }

    @Test
    fun `restore merges without duplicates and renames a name conflict`() {
        doc("doc_same", "Same name")
        doc("doc_other", "Same name")
        backup.write(out, password, FullBackup.Wrap.PASSWORD)

        // Keep doc_same, drop doc_other: restoring must skip the first and add the second as
        // "<name> (restored)" under a new id.
        File(docsDir, "doc_other").deleteRecursively()
        val report = backup.restore(out, password)

        assertEquals(1, report.documents)
        assertEquals(1, report.skipped)
        assertEquals(
            listOf("Same name", "Same name (restored)"),
            docsDir.listFiles()!!.map { File(it, "meta.txt").readLines().first() }.sorted()
        )
        assertTrue("the original keeps its id", File(docsDir, "doc_same").isDirectory)
        assertNotEquals(
            "the restored copy is not under the old id",
            "doc_other",
            docsDir.listFiles()!!.first { File(it, "meta.txt").readLines().first() == "Same name (restored)" }.name
        )
    }

    @Test
    fun `a corrupted backup fails safely`() {
        doc("doc_e", "Fragile")
        backup.write(out, password, FullBackup.Wrap.PASSWORD)
        File(docsDir, "doc_e").deleteRecursively()

        RandomAccessFile(out, "rw").use { raf ->
            val at = (raf.length() - 40).coerceAtLeast(1)
            raf.seek(at)
            val old = raf.readByte()
            raf.seek(at)
            raf.writeByte(old.toInt() xor 0x5A)
        }

        try {
            backup.restore(out, password)
            fail("a corrupted backup was accepted")
        } catch (_: VaultAuthException) {
        }
        assertFalse("nothing was restored", File(docsDir, "doc_e").exists())
    }

    @Test
    fun `a long library streams instead of loading every page`() {
        val pages = 120
        doc("doc_big", "Textbook", pages = pages)
        backup.write(out, password, FullBackup.Wrap.PASSWORD)
        File(docsDir, "doc_big").deleteRecursively()

        val before = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        val report = backup.restore(out, password)
        val after = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

        assertEquals(1, report.documents)
        assertEquals(pages + 3, File(docsDir, "doc_big").listFiles()!!.size)
        // 120 x 2 KiB pages + the archive: a buffered implementation stays far below 64 MiB.
        assertTrue("restore grew the heap by ${(after - before) / 1024 / 1024} MiB", after - before < 64L * 1024 * 1024)
    }

    @Test
    fun `vault entries are re-encrypted into an existing vault`() {
        val oldVault = vaultStore()
        val setup = oldVault.setup("246810")
        val docDir = File(docsDir, "doc_vault").apply { mkdirs() }
        File(docDir, "meta.txt").writeText("Bank statement\n${System.currentTimeMillis()}\nOther\n")
        File(docDir, "scan.pdf").writeBytes(ByteArray(4096) { 7 })
        File(docDir, "page_000.jpg").writeBytes(ByteArray(1024) { 3 })
        oldVault.addDocument("doc_vault")
        assertEquals(1, oldVault.listEntries().size)

        // A locked Vault, a recovery-code backup: the key has to come out of recovery.bin.
        VaultSession.close()
        backup.write(out, setup.recoveryCode, FullBackup.Wrap.RECOVERY_CODE)
        assertTrue("the file carries the Vault key", backup.header(out).hasVaultKey)

        // A different phone: new Vault key, same folders.
        File(app.filesDir, "vault2").deleteRecursively()
        val newVault = vaultStore()
        newVault.setup("135790")
        val onNewPhone = FullBackup(app, newVault)

        val report = onNewPhone.restore(out, setup.recoveryCode)
        assertEquals("one entry re-encrypted", 1, report.vaultReencrypted)
        assertFalse("nothing was skipped", report.vaultLocked)
        val entries = newVault.listEntries()
        assertEquals(1, entries.size)
        assertEquals("Bank statement", entries.single().name)
        assertEquals("the entry opens under the new key", 1, entries.single().pageCount)
    }

    @Test
    fun `a locked vault is skipped with a message and a second restore merges it`() {
        val oldVault = vaultStore()
        val setup = oldVault.setup("246810")
        doc("doc_lib", "Library doc")
        val docDir = File(docsDir, "doc_merge").apply { mkdirs() }
        File(docDir, "meta.txt").writeText("Insurance\n${System.currentTimeMillis()}\nOther\n")
        File(docDir, "scan.pdf").writeBytes(ByteArray(2048) { 1 })
        oldVault.addDocument("doc_merge")
        VaultSession.close()
        backup.write(out, setup.recoveryCode, FullBackup.Wrap.RECOVERY_CODE)

        File(app.filesDir, "vault2").deleteRecursively()
        val newVault = vaultStore()
        newVault.setup("135790")
        VaultSession.close()
        val onNewPhone = FullBackup(app, newVault)
        File(docsDir, "doc_merge").deleteRecursively()
        File(docsDir, "doc_lib").deleteRecursively()

        val first = onNewPhone.restore(out, setup.recoveryCode)
        assertTrue("the restore says the Vault was locked", first.vaultLocked)
        assertTrue(
            "nothing was merged into the locked Vault",
            File(app.filesDir, "vault2").listFiles().orEmpty().none { it.isDirectory }
        )
        assertEquals("everything else was restored", 1, first.documents)

        assertTrue(newVault.unlockWithPasscode("135790").ok)
        val second = onNewPhone.restore(out, setup.recoveryCode)
        assertEquals(1, second.vaultReencrypted)
        assertEquals("documents are not duplicated", 0, second.documents)
        assertEquals(1, second.skipped)
        assertEquals("Insurance", newVault.listEntries().single().name)
    }

    @Test
    fun `a tampered vault entry fails safely without publishing`() {
        val oldVault = vaultStore()
        oldVault.setup("246810")
        val docDir = File(docsDir, "doc_tamper").apply { mkdirs() }
        File(docDir, "meta.txt").writeText("Tamper me\n${System.currentTimeMillis()}\nOther\n")
        File(docDir, "scan.pdf").writeBytes(ByteArray(1024) { 2 })
        oldVault.addDocument("doc_tamper")
        val oldKey = VaultSession.keyOrNull()!!.copyOf()

        // Copy the entry out, flip one byte of an encrypted file, and try to re-encrypt it.
        val source = File(app.cacheDir, "tampered-entry").apply { deleteRecursively(); mkdirs() }
        File(app.filesDir, "vault2/doc_tamper").copyRecursively(source)
        val stored = source.listFiles()!!.first { it.name.endsWith(".enc") && it.name != "manifest.enc" }
        val bytes = stored.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x5A).toByte()
        stored.writeBytes(bytes)

        File(app.filesDir, "vault2").deleteRecursively()
        val newVault = vaultStore()
        newVault.setup("135790")
        try {
            newVault.restoreEntryFromBackup(source, oldKey)
            fail("a tampered entry was accepted")
        } catch (_: VaultAuthException) {
        } catch (_: IllegalStateException) {
            // A bad chunk can also surface as a locked/short entry; either way nothing publishes.
        }
        assertTrue("nothing was published", newVault.listEntries().isEmpty())
        assertTrue(
            "no staging left behind",
            File(app.filesDir, "vault2").listFiles().orEmpty().none { it.name.startsWith(".") }
        )
    }

    @Test
    fun `restoring a large vault entry streams`() {
        val oldVault = vaultStore()
        val setup = oldVault.setup("246810")
        val docDir = File(docsDir, "doc_big_vault").apply { mkdirs() }
        File(docDir, "meta.txt").writeText("Big vault doc\n${System.currentTimeMillis()}\nOther\n")
        val page = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }
        File(docDir, "page_000.jpg").writeBytes(page)
        oldVault.addDocument("doc_big_vault")
        VaultSession.close()
        backup.write(out, setup.recoveryCode, FullBackup.Wrap.RECOVERY_CODE)

        File(app.filesDir, "vault2").deleteRecursively()
        val newVault = vaultStore()
        newVault.setup("135790")
        val before = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        val report = FullBackup(app, newVault).restore(out, setup.recoveryCode)
        val after = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

        assertEquals(1, report.vaultReencrypted)
        assertEquals("Big vault doc", newVault.listEntries().single().name)
        assertTrue("heap grew ${(after - before) / 1024 / 1024} MiB", after - before < 96L * 1024 * 1024)
    }

    private fun vaultStore(iterations: Int = 2_000): VaultStore = VaultStore(
        app,
        com.scanwhirl.app.store.ScanRepository(app),
        com.scanwhirl.app.store.vault.FakeVaultKeys(),
        com.scanwhirl.app.store.vault.VaultPasscodeGate(),
        iterations
    )

    @Test
    fun `the last backup timestamp drives the reminder`() {
        assertNull(backup.daysSinceLastBackup())
        backup.markBackedUp(System.currentTimeMillis() - 3 * 86_400_000L)
        assertEquals(3L, backup.daysSinceLastBackup())
    }
}
