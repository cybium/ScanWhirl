package com.scanwhirl.app.store.backup

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.store.vault.FakeVaultKeys
import com.scanwhirl.app.store.vault.VaultCrypto
import com.scanwhirl.app.store.vault.VaultPasscodeGate
import com.scanwhirl.app.store.vault.VaultSession
import com.scanwhirl.app.store.vault.VaultStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream

/**
 * VAULT-F1 (Sol round 2, review F1a): a Vault whose one-time recovery code has NOT passed its
 * four-character check ("pending setup") is still unlocked by `setup()`, so Full Backup used to
 * export its encrypted entries **and the key that opens them**. Anyone who can read the archive
 * then needs only the backup password they chose, not the recovery code the owner may not have
 * written down. Full restore had the mirror hole: verified entries were published into a Vault
 * that is not finished.
 *
 * The decided rule: while pending, the full backup omits the Vault (entries, key, remembered
 * passwords) and says so in its report, and a full restore leaves the Vault merge out with a
 * warning. The library part still backs up and restores. A phone with no Vault still adopts the
 * backup's Vault, because the restore types the backup's own recovery code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FullBackupPendingVaultTest {

    private lateinit var app: Application
    private lateinit var out: File
    private val password = "correct horse battery"

    private val docsDir get() = File(app.filesDir, "documents")
    private val vaultDir get() = File(app.filesDir, "vault2")

    @Before
    fun setUp() {
        VaultSession.close()
        app = ApplicationProvider.getApplicationContext()
        out = File(app.cacheDir, "pending-${System.nanoTime()}.swbackup")
        listOf("documents", "vault2", "signatures", "stamps", "drafts").forEach {
            File(app.filesDir, it).deleteRecursively()
        }
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
    }

    @After
    fun tearDown() {
        VaultSession.close()
        out.delete()
        listOf("documents", "vault2", "signatures", "stamps", "drafts").forEach {
            File(app.filesDir, it).deleteRecursively()
        }
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
    }

    private fun vaultStore(): VaultStore = VaultStore(
        app,
        ScanRepository(app),
        FakeVaultKeys(),
        VaultPasscodeGate(),
        2_000
    )

    private fun doc(id: String, name: String): File {
        val dir = File(docsDir, id).apply { mkdirs() }
        File(dir, "meta.txt").writeText("$name\n${System.currentTimeMillis()}\nOther\n")
        File(dir, "scan.pdf").writeBytes(byteArrayOf(37, 80, 68, 70))
        File(dir, "page_000.jpg").writeBytes(ByteArray(512) { it.toByte() })
        return dir
    }

    /** The entry paths inside the encrypted archive, read with the secret the user chose. */
    private fun entryPaths(file: File, secret: String, wrap: FullBackup.Wrap): List<String> {
        val backup = FullBackup(app)
        val header = backup.header(file)
        val normalized = if (wrap == FullBackup.Wrap.RECOVERY_CODE) {
            VaultCrypto.normalizeRecoveryCode(secret)
        } else {
            secret
        }
        val key = VaultCrypto.deriveKey(normalized, header.salt, header.iterations)
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            // magic(8) version(1) wrap(1) iterations(4) saltLength(1) salt(n) hasVaultKey(1)
            var skip = 8L + 1 + 1 + 4 + 1 + header.salt.size + 1
            while (skip > 0) {
                val skipped = input.skip(skip)
                if (skipped <= 0) throw IllegalStateException("short header")
                skip -= skipped
            }
            val plain = ByteArrayOutputStream()
            VaultCrypto.decryptStream(input, plain, key)
            val entries = DataInputStream(ByteArrayInputStream(plain.toByteArray()))
            val paths = mutableListOf<String>()
            while (true) {
                when (entries.readUnsignedByte()) {
                    0 -> return paths
                    1 -> {
                        paths += entries.readUTF()
                        val size = entries.readLong()
                        var left = size
                        while (left > 0) {
                            val n = entries.skip(left)
                            if (n <= 0) throw IllegalStateException("short entry")
                            left -= n
                        }
                    }
                    else -> throw IllegalStateException("bad tag")
                }
            }
        }
    }

    @Test
    fun `a pending setup leaves the Vault and its key out of the full backup and says so`() {
        val vault = vaultStore()
        vault.setup("246810") // the four-character check is still owed: pending
        assertTrue("the marker is pending", vault.isConfirmationPending())
        assertTrue("setup() opens the session, which is what made the leak possible", vault.isUnlocked())
        doc("doc_lib", "Library doc")
        // A pending Vault can already hold files (a restored archive, a half-finished add).
        File(vaultDir, "entry_secret").mkdirs()
        File(vaultDir, "entry_secret/manifest.enc").writeBytes(ByteArray(64) { 7 })
        File(vaultDir, "passwords.bin").writeBytes(ByteArray(32) { 5 })

        val report = FullBackup(app, vault).write(out, password, FullBackup.Wrap.PASSWORD)

        val paths = entryPaths(out, password, FullBackup.Wrap.PASSWORD)
        assertTrue(
            "no Vault file may travel while the setup is unfinished: $paths",
            paths.none { it.startsWith("vault2/") }
        )
        assertFalse(
            "the key that opens the Vault must not travel either",
            paths.contains(FullBackup.VAULT_KEY_ENTRY)
        )
        assertFalse("and the header must not claim a key", FullBackup(app).header(out).hasVaultKey)
        assertTrue(
            "the report says plainly what was left out: ${report.warnings}",
            report.warnings.any { it.contains("Vault is still being set up") }
        )
        assertEquals("the library still backs up", 1, report.documents)
        assertTrue("the Vault itself is untouched", File(vaultDir, "recovery.bin").isFile)
        assertTrue(File(vaultDir, "entry_secret/manifest.enc").isFile)
        assertTrue(
            "the library document is in the archive",
            paths.any { it.startsWith("documents/doc_lib/") }
        )
    }

    @Test
    fun `after the check passes the full backup carries the Vault and its key again`() {
        val vault = vaultStore()
        vault.setup("246810")
        vault.markConfirmationPassed()
        assertFalse(vault.isConfirmationPending())
        doc("doc_vault", "Insurance")
        vault.addDocument("doc_vault")
        File(vaultDir, "passwords.bin").writeBytes(ByteArray(16) { 3 })

        val report = FullBackup(app, vault).write(out, password, FullBackup.Wrap.PASSWORD)

        val paths = entryPaths(out, password, FullBackup.Wrap.PASSWORD)
        assertTrue("the entry travels: $paths", paths.any { it == "vault2/doc_vault/manifest.enc" })
        assertTrue("so do the remembered passwords", paths.contains("vault2/passwords.bin"))
        assertTrue("and the key", paths.contains(FullBackup.VAULT_KEY_ENTRY))
        assertTrue(FullBackup(app).header(out).hasVaultKey)
        assertFalse(
            "no pending warning on a finished Vault: ${report.warnings}",
            report.warnings.any { it.contains("still being set up") }
        )
    }

    @Test
    fun `a restore into a pending Vault defers the Vault merge and still restores the library`() {
        // The backup: a finished Vault with one verified entry, opened by its recovery code.
        val oldVault = vaultStore()
        val setup = oldVault.setup("246810")
        oldVault.markConfirmationPassed()
        doc("doc_merge", "Insurance")
        oldVault.addDocument("doc_merge")
        assertEquals(1, oldVault.listEntries().size)
        // A plain library document, so the restore has a part that must still come through.
        doc("doc_lib", "Library doc")
        FullBackup(app, oldVault).write(out, setup.recoveryCode, FullBackup.Wrap.RECOVERY_CODE)
        assertTrue("the archive carries the key", FullBackup(app).header(out).hasVaultKey)

        // The phone: a Vault whose four-character check is still owed, unlocked by setup().
        File(app.filesDir, "vault2").deleteRecursively()
        File(docsDir, "doc_merge").deleteRecursively()
        File(docsDir, "doc_lib").deleteRecursively()
        val newVault = vaultStore()
        newVault.setup("135790")
        assertTrue(newVault.isConfirmationPending())
        val onPhone = FullBackup(app, newVault)

        val report = onPhone.restore(out, setup.recoveryCode)

        assertTrue("nothing is published into a pending Vault", newVault.listEntries().isEmpty())
        assertFalse(
            "no entry folder was created",
            vaultDir.listFiles().orEmpty().any { it.isDirectory && File(it, "manifest.enc").isFile }
        )
        assertTrue(
            "the report says the Vault part waits: ${report.warnings}",
            report.warnings.any { it.contains("Vault is still being set up") }
        )
        assertFalse("the Vault is not 'locked': it is unfinished", report.vaultLocked)
        assertEquals("the library part is restored", 1, report.documents)
        assertTrue(File(docsDir, "doc_lib/scan.pdf").isFile)
        assertEquals("no Vault entry was counted", 0, report.vaultReencrypted)

        // Once the check passes, the same archive merges as before.
        newVault.markConfirmationPassed()
        val second = onPhone.restore(out, setup.recoveryCode)
        assertEquals(1, second.vaultReencrypted)
        assertEquals("Insurance", newVault.listEntries().single().name)
    }

    @Test
    fun `a phone with no Vault still adopts the backup's Vault`() {
        val oldVault = vaultStore()
        val setup = oldVault.setup("246810")
        oldVault.markConfirmationPassed()
        doc("doc_adopt", "Payslip")
        oldVault.addDocument("doc_adopt")
        FullBackup(app, oldVault).write(out, setup.recoveryCode, FullBackup.Wrap.RECOVERY_CODE)

        File(app.filesDir, "vault2").deleteRecursively()
        val freshPhone = FullBackup(app)

        val report = freshPhone.restore(out, setup.recoveryCode)

        assertEquals("the backup's Vault is adopted", 1, report.vaultEntries)
        assertTrue("its recovery wrap is in place", File(vaultDir, "recovery.bin").isFile)
        assertFalse(
            "an adopted Vault is not left pending: the restore typed the backup's code",
            vaultStore().isConfirmationPending()
        )
    }
}
