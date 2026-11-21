package com.scanwhirl.app.store.vault

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.store.ScanRepository
import org.junit.After
import org.junit.Assert.assertEquals
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
 * SECURITY_JUDGE 1.3.0 finding 5, behavioral RED anchor using only base APIs:
 * leaving and reopening the Vault must not buy five fresh passcode guesses.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class Sec1VaultGateRegressionTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        VaultSession.close()
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        File(app.filesDir, "vault2").deleteRecursively()
        app.getSharedPreferences("scanwhirl_vault_gate", 0).edit().clear().commit()
    }

    @After
    fun tearDown() {
        VaultSession.close()
        File(app.filesDir, "vault2").deleteRecursively()
        app.getSharedPreferences("scanwhirl_vault_gate", 0).edit().clear().commit()
    }

    private fun VaultStore.setupConfirmed(passcode: String) =
        setup(passcode).also { markConfirmationPassed() }

    private fun store(): VaultStore = VaultStore(
        context = app, repo = repo, keys = FakeVaultKeys(), iterations = 2_000
    )

    @Test(timeout = 30_000)
    fun `reopening the Vault does not restore five fresh passcode guesses`() {
        val first = store()
        first.setupConfirmed("246810")
        repeat(5) { assertFalse(first.unlockWithPasscode("000000").ok) }
        first.lock()

        val reopened = store()
        val result = reopened.unlockWithPasscode("000000")
        assertTrue("the backoff must survive a new VaultStore", result.pauseMillis > 0)
        assertEquals(0, result.triesLeft)
    }
}
