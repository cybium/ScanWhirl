package com.scanwhirl.app.store.vault

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * SECURITY_JUDGE R2 N1 (HIGH): removing a Vault entry while its plaintext
 * original was only partially purged must not strand that plaintext forever.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class Sec2VaultRemovalPurgeTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        VaultSession.close()
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        File(app.filesDir, "vault2").deleteRecursively()
        File(app.filesDir, ".vault_pending_purge").deleteRecursively()
        repo.trashDir.deleteRecursively()
    }

    @After
    fun tearDown() {
        VaultSession.close()
        ScanRepository.resetTestSeams()
        VaultPasscodeGate.resetSharedForTests(app)
        File(app.filesDir, "vault2").deleteRecursively()
        File(app.filesDir, ".vault_pending_purge").deleteRecursively()
        repo.trashDir.deleteRecursively()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
    }

    private fun VaultStore.setupConfirmed(passcode: String) =
        setup(passcode).also { markConfirmationPassed() }

    private fun store(): VaultStore = VaultStore(
        app, repo, FakeVaultKeys(), VaultPasscodeGate(clock = { 0L }), 2_000
    )

    private fun marker(id: String) = File(app.filesDir, "vault2/.adding-$id")

    @Test(timeout = 30_000)
    fun `removing an entry after a partial purge still purges the original on restart`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Fictional stranded original")
        val vault = store()
        vault.setupConfirmed("246810")

        ScanRepository.vaultPendingDelete = { false }
        val added = vault.addDocumentWithResult(id)
        assertFalse(added.purgeComplete)
        assertTrue(repo.vaultPendingPurgeExists(id))
        assertTrue(marker(id).isFile)

        // The user removes the encrypted entry while the purge is still pending.
        vault.removeFromVault(id)
        assertFalse("the encrypted entry is gone", vault.hasEntry(id))
        assertTrue("the plaintext original is still on disk", repo.vaultPendingPurgeExists(id))

        // Restart: no encrypted entry exists, so the old marker alone cannot be
        // authority. A locally owned pending-purge sweep must finish the job.
        vault.lock()
        VaultSession.close()
        ScanRepository.resetTestSeams()
        val restarted = store()
        if (!restarted.isSetUp()) restarted.unlockWithPasscode("246810")
        restarted.recover()

        assertFalse(
            "removal must not strand a partially purged plaintext original",
            repo.vaultPendingPurgeExists(id)
        )
        assertFalse(marker(id).exists())
    }

    @Test(timeout = 30_000)
    fun `a pending-purge folder with no live add marker is swept on recovery`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Fictional orphaned purge")
        val vault = store()
        vault.setupConfirmed("246810")
        ScanRepository.vaultPendingDelete = { false }
        vault.addDocumentWithResult(id)
        ScanRepository.resetTestSeams()
        // Simulate a crash that lost the marker but kept the pending plaintext.
        assertTrue(marker(id).delete())

        vault.lock()
        VaultSession.close()
        store().recover()

        assertFalse("an unowned pending-purge folder must be purged", repo.vaultPendingPurgeExists(id))
    }
}
