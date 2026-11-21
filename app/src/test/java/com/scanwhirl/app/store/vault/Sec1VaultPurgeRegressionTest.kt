package com.scanwhirl.app.store.vault

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.store.DocFixtures
import com.scanwhirl.app.store.ScanRepository
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException

/**
 * SECURITY_JUDGE 1.3.0 finding 2 (HIGH), behavioral RED anchor.
 *
 * Uses only APIs that exist on the base commit, so it fails on the base for the
 * real user-visible reason: a Vault add can leave the plaintext original in the
 * ordinary, listable and restorable Trash. A Vault add must never do that.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class Sec1VaultPurgeRegressionTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        VaultSession.close()
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
    }

    @After
    fun tearDown() {
        VaultSession.close()
        ScanRepository.resetTestSeams()
        File(app.filesDir, "vault2").deleteRecursively()
        File(app.filesDir, ".vault_pending_purge").deleteRecursively()
        repo.trashDir.deleteRecursively()
        app.getSharedPreferences("scanwhirl_prefs", 0).edit().clear().commit()
    }

    private fun VaultStore.setupConfirmed(passcode: String) =
        setup(passcode).also { markConfirmationPassed() }

    private fun store(): VaultStore = VaultStore(
        context = app,
        repo = repo,
        keys = FakeVaultKeys(),
        gate = VaultPasscodeGate(clock = { 0L }),
        iterations = 2_000
    )

    @Test(timeout = 30_000)
    fun `a vault add never leaves the plaintext original in ordinary Trash`() {
        val id = DocFixtures.saved(repo, app, pages = 1, name = "Fictional vault add")
        val vault = store()
        vault.setupConfirmed("246810")

        // Interrupt the ordinary Trash move after the rename: the base code then
        // swallows the failed purge and leaves a FULL plaintext copy in Trash.
        ScanRepository.trashFault = { stage ->
            if (stage == ScanRepository.Companion.TrashStage.MOVED) throw IOException("Fictional purge failure")
        }
        try {
            runCatching { vault.addDocument(id) }
            assertTrue(
                "a Vault add must never leave a plaintext original in the ordinary Trash",
                repo.listTrash().none { it.originalId == id }
            )
        } finally {
            ScanRepository.resetTestSeams()
        }
    }
}
