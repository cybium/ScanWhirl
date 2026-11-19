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
 * VAULT-F1 (Sol round 2, review F1b): remembered PDF passwords were saved and read with the
 * session key of a Vault whose one-time recovery code had not passed its four-character check.
 * While the setup is unfinished nothing may be written under that key or handed back: the
 * caller gets "none saved" (null / false / 0) and a save is refused, so the Protect screen falls
 * back to asking for the password instead of promising a memory the Vault does not have.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class VaultPasswordsPendingTest {

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
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
    }

    @After
    fun tearDown() {
        VaultSession.close()
        File(app.filesDir, "vault2").deleteRecursively()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
    }

    private fun file(): File = File(File(app.filesDir, "vault2"), VaultPasswords.FILE_NAME)

    @Test
    fun `a pending setup remembers no PDF password`() {
        store.setup("246810")
        assertTrue("the check is still owed", store.isConfirmationPending())
        assertTrue("and the session is open, which is what the leak used", store.isUnlocked())
        val docId = DocFixtures.saved(repo, app, pages = 1, name = "Salary slip")
        val passwords = VaultPasswords.of(app)

        assertThrows(VaultPendingException::class.java) { passwords.save(docId, "pw-1234") }
        assertNull("nothing is handed back while pending", passwords.get(docId))
        assertFalse(passwords.has(docId))
        assertFalse(passwords.remove(docId))
        assertEquals(0, passwords.count())
        assertFalse("nothing is written under the pending key", file().exists())
    }

    @Test
    fun `the same password is remembered once the check passes`() {
        val setup = store.setup("246810")
        val docId = DocFixtures.saved(repo, app, pages = 1, name = "Rent agreement")
        val passwords = VaultPasswords.of(app)
        assertThrows(VaultPendingException::class.java) { passwords.save(docId, "pw-1234") }

        store.markConfirmationPassed()
        assertFalse(store.isConfirmationPending())

        passwords.save(docId, "pw-1234")
        assertEquals("pw-1234", passwords.get(docId))
        assertTrue(file().isFile)
        // The whole existing contract still holds for a finished Vault.
        assertTrue(passwords.has(docId))
        assertEquals(1, passwords.count())
        assertTrue(passwords.remove(docId))
        assertNull(passwords.get(docId))
        assertTrue("the recovery code of this setup is real", setup.recoveryCode.isNotBlank())
    }
}
