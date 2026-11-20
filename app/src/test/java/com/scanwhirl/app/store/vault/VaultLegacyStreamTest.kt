package com.scanwhirl.app.store.vault

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class VaultLegacyStreamTest {
    @Test fun oldOnDiskEntryOpensAndRestoreReencryptsWithNewFormat() {
        val app: Application = ApplicationProvider.getApplicationContext()
        val repo = ScanRepository(app)
        val vault = VaultStore(app, repo, FakeVaultKeys(), VaultPasscodeGate(), iterations = 2000)
        VaultSession.close()
        val archive = File(app.cacheDir, "fictional-old.swvault")
        val legacyEntry = File(app.cacheDir, "fictional-old-entry")
        var oldKey: ByteArray? = null
        try {
            val id = DocFixtures.saved(repo, app, pages = 1, name = "Fictional old Vault file")
            val setup = vault.setup("246810")
            vault.markConfirmationPassed()
            vault.addDocument(id)
            val root = File(app.filesDir, "vault2/$id")
            val key = VaultSession.keyOrNull()!!
            val encrypted = root.listFiles()!!.filter { it.extension == "enc" }
            assertTrue(encrypted.isNotEmpty())
            encrypted.forEach { file ->
                assertEquals(2, file.readBytes()[4].toInt())
                val plain = VaultCrypto.decryptBytes(file.readBytes(), key)
                file.writeBytes(StreamIntegrityFixture.legacy(plain, key))
            }
            assertNotNull(vault.openDocument(id))
            vault.exportBackup(archive)
            root.copyRecursively(legacyEntry)
            oldKey = key.copyOf()
            vault.wipeAll()
            val restored = VaultStore(app, repo, FakeVaultKeys(), VaultPasscodeGate(), iterations = 2000)
            // Portable import adopts the old key and ciphertext without re-encrypting.
            assertEquals(listOf(id), restored.importBackup(archive, setup.recoveryCode).map { it.id })
            assertNotNull(restored.openDocument(id))
            restored.wipeAll()
            restored.setup("135790")
            restored.markConfirmationPassed()
            // Merge into an already set-up Vault actually decrypts and re-encrypts each file.
            assertEquals(id, restored.restoreEntryFromBackup(legacyEntry, oldKey).id)
            assertNotNull(restored.openDocument(id))
            root.listFiles()!!.filter { it.extension == "enc" }.forEach {
                assertEquals("restored payload and manifest use authenticated order", 2, it.readBytes()[4].toInt())
            }
        } finally {
            VaultSession.close()
            oldKey?.fill(0)
            archive.delete()
            legacyEntry.deleteRecursively()
            File(app.filesDir, "vault2").deleteRecursively()
            File(app.cacheDir, "vault_open").deleteRecursively()
            app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
        }
    }
}
