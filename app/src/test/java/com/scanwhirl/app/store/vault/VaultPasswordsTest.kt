package com.scanwhirl.app.store.vault

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The API lane L3 uses for "Remember in Vault" / "Use password saved in Vault" (design
 * §4.2): a PDF password keyed by document id, AES-256-GCM with the Vault key, readable only
 * while the Vault is unlocked.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class VaultPasswordsTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository
    private lateinit var store: VaultStore

    @Before
    fun setUp() {
        VaultSession.close()
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        store = VaultStore(
            context = app,
            repo = repo,
            keys = FakeVaultKeys(),
            gate = VaultPasscodeGate(),
            iterations = 2_000
        )
        File(app.filesDir, "vault2").deleteRecursively()
    }

    @After
    fun tearDown() {
        VaultSession.close()
        File(app.filesDir, "vault2").deleteRecursively()
    }

    private fun file(): File = File(File(app.filesDir, "vault2"), VaultPasswords.FILE_NAME)

    @Test
    fun `save get and remove a password keyed by document id`() {
        store.setup("246810")
        val passwords = VaultPasswords.of(app)
        val docId = DocFixtures.saved(repo, app, pages = 1, name = "Salary slip")
        val other = DocFixtures.saved(repo, app, pages = 1, name = "Rent agreement")

        assertNull("nothing saved yet", passwords.get(docId))
        assertFalse(passwords.has(docId))

        passwords.save(docId, "pw-1234")
        passwords.save(other, "other-9999")
        assertEquals("pw-1234", passwords.get(docId))
        assertEquals("other-9999", passwords.get(other))
        assertTrue(passwords.has(docId))
        assertEquals(2, passwords.count())

        assertTrue(passwords.remove(docId))
        assertFalse("already gone", passwords.remove(docId))
        assertNull(passwords.get(docId))
        assertEquals("the other document is untouched", "other-9999", passwords.get(other))
        assertEquals(1, passwords.count())
    }

    @Test
    fun `unicode passwords survive the round trip`() {
        store.setup("246810")
        val passwords = VaultPasswords.of(app)
        passwords.save("doc-1", "पासवर्ड-١٢٣-€")
        assertEquals("पासवर्ड-١٢٣-€", VaultPasswords.of(app).get("doc-1"))
    }

    @Test
    fun `the password file holds no readable password or document id`() {
        store.setup("246810")
        val passwords = VaultPasswords.of(app)
        passwords.save("aadhaar-doc-id", "SecretPassphrase123")

        val blob = file().readBytes()
        assertTrue("the file exists", blob.isNotEmpty())
        assertFalse("no readable document id", contains(blob, "aadhaar-doc-id".toByteArray()))
        assertFalse("no readable password", contains(blob, "SecretPassphrase123".toByteArray()))
        assertFalse(
            "no plaintext header",
            blob.size >= 2 && blob[0] == 'v'.code.toByte() && blob[1] == '1'.code.toByte()
        )
        // The Vault key is required: a fresh process cannot read it.
        VaultSession.close()
        assertThrows(VaultLockedException::class.java) { passwords.get("aadhaar-doc-id") }
    }

    @Test
    fun `a locked vault refuses to read or write passwords`() {
        store.setup("246810")
        VaultPasswords.of(app).save("doc-1", "pw")
        store.lock()

        assertThrows(VaultLockedException::class.java) { VaultPasswords.of(app).get("doc-1") }
        assertThrows(VaultLockedException::class.java) { VaultPasswords.of(app).has("doc-1") }
        assertThrows(VaultLockedException::class.java) { VaultPasswords.of(app).save("doc-1", "pw2") }
        assertThrows(VaultLockedException::class.java) { VaultPasswords.of(app).remove("doc-1") }
        assertThrows(VaultLockedException::class.java) { VaultPasswords.of(app).count() }
    }

    @Test
    fun `passwords survive lock and unlock with the passcode`() {
        store.setup("246810")
        VaultPasswords.of(app).save("doc-1", "pw-1234")
        store.lock()
        assertTrue(store.unlockWithPasscode("246810").ok)
        assertEquals("pw-1234", VaultPasswords.of(app).get("doc-1"))
    }

    @Test
    fun `another screen's store handle reads the same passwords while unlocked`() {
        store.setup("246810")
        VaultPasswords.of(app).save("doc-1", "pw-1234")

        // The Protect sheet in another screen has no VaultStore of its own: the context is enough.
        val fromOtherScreen = VaultPasswords.of(app)
        assertEquals("pw-1234", fromOtherScreen.get("doc-1"))

        // A second, different VaultStore handle may also lock the Vault (process-wide session).
        val other = VaultStore(app, repo, FakeVaultKeys(), VaultPasscodeGate(), 2_000)
        other.lock()
        assertThrows(VaultLockedException::class.java) { fromOtherScreen.get("doc-1") }
    }

    @Test
    fun `a tampered password file fails the GCM tag instead of returning a wrong password`() {
        store.setup("246810")
        val passwords = VaultPasswords.of(app)
        passwords.save("doc-1", "pw-1234")

        val target = file()
        val bytes = target.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x5A).toByte()
        target.writeBytes(bytes)

        assertThrows(VaultAuthException::class.java) { passwords.get("doc-1") }
    }

    @Test
    fun `removing the vault removes saved passwords`() {
        store.setup("246810")
        VaultPasswords.of(app).save("doc-1", "pw-1234")
        store.wipeAll()
        assertFalse(file().exists())
        assertFalse(VaultSession.isUnlocked())
    }

    @Test
    fun `backup carries remembered passwords and restore returns them`() {
        val id = DocFixtures.saved(repo, app, pages = 2, name = "Insurance policy")
        val setup = store.setup("246810")
        store.addDocument(id)
        VaultPasswords.of(app).save(id, "pw-13579")

        val backup = File(app.cacheDir, "passwords.swvault")
        store.exportBackup(backup)
        val blob = backup.readBytes()
        assertFalse("the backup holds no readable password", contains(blob, "pw-13579".toByteArray()))

        store.wipeAll()
        assertFalse(file().exists())

        val fresh = VaultStore(app, repo, FakeVaultKeys(), VaultPasscodeGate(), 2_000)
        fresh.importBackup(backup, setup.recoveryCode)

        assertEquals("pw-13579", VaultPasswords.of(app).get(id))
        assertEquals(listOf(id), fresh.listEntries().map { it.id })
    }

    @Test
    fun `a version 1 backup still restores its documents`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Old backup")
        val setup = store.setup("246810")
        store.addDocument(id)
        VaultPasswords.of(app).save(id, "pw-24680")

        val backup = File(app.cacheDir, "v1.swvault")
        store.exportBackup(backup)
        // A v1 container has no extras section: patching the version makes the reader stop
        // after the entries, exactly as it did before remembered passwords existed.
        val bytes = backup.readBytes()
        val versionOffset = "SWVAULT1".length
        bytes[versionOffset + 3] = 1
        backup.writeBytes(bytes)

        store.wipeAll()
        val fresh = VaultStore(app, repo, FakeVaultKeys(), VaultPasscodeGate(), 2_000)
        fresh.importBackup(backup, setup.recoveryCode)

        assertEquals("documents still restore", listOf(id), fresh.listEntries().map { it.id })
        assertFalse("a v1 backup has no passwords", file().exists())
    }

    private fun contains(bytes: ByteArray, needle: ByteArray): Boolean {
        outer@ for (i in 0..bytes.size - needle.size) {
            for (j in needle.indices) if (bytes[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}
