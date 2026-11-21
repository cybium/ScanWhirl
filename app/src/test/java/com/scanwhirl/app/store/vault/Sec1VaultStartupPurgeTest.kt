package com.scanwhirl.app.store.vault

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.ScanWhirlApp
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.ui.shell.ShellActivity
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * SEC1 follow-up: "it will be removed next start" must be true even when the
 * Vault screen is never opened. The off-main launcher recovery pass owns it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class Sec1VaultStartupPurgeTest {

    private val app: ScanWhirlApp get() = ApplicationProvider.getApplicationContext()
    private val repo get() = app.repository

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

    @Test(timeout = 30_000)
    fun `startup recovery purges an original left pending by an interrupted vault add`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Fictional interrupted purge")
        val vault = VaultStore(
            app, repo, FakeVaultKeys(), VaultPasscodeGate(clock = { 0L }), 2_000
        )
        vault.setup("246810").also { vault.markConfirmationPassed() }

        // A failed purge leaves the plaintext original in the private area and
        // the durable .adding marker on disk, exactly like a process that died.
        ScanRepository.vaultPendingDelete = { false }
        val added = vault.addDocumentWithResult(id)
        assertFalse(added.purgeComplete)
        assertTrue(repo.vaultPendingPurgeExists(id))
        assertTrue(File(app.filesDir, "vault2/.adding-$id").isFile)
        vault.lock()
        VaultSession.close()
        ScanRepository.resetTestSeams()

        val controller: ActivityController<ShellActivity> =
            Robolectric.buildActivity(ShellActivity::class.java)
        try {
            controller.setup()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (repo.vaultPendingPurgeExists(id) && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            assertFalse(
                "the launcher's startup recovery must purge a pending Vault original",
                repo.vaultPendingPurgeExists(id)
            )
            assertFalse("the durable marker is cleared only after the purge",
                File(app.filesDir, "vault2/.adding-$id").exists())
            assertTrue("the encrypted Vault entry survives", vault.hasEntry(id))
        } finally {
            controller.destroy()
        }
    }
}
