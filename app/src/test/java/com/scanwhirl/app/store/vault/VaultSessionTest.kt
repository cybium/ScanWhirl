package com.scanwhirl.app.store.vault

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.store.ScanRepository
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * The process-wide Vault session (design §4.1): one key for every screen, dropped after the
 * 2-minute background grace, and usable by a second `VaultStore`/`VaultPasswords` handle.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class VaultSessionTest {

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
        File(app.cacheDir, "vault_open").deleteRecursively()
    }

    @Test
    fun `unlock and lock are process-wide, not per handle`() {
        store.setup("246810")
        assertTrue(store.isUnlocked())

        // Another screen's handle (same process) sees the same unlocked Vault.
        val other = VaultStore(app, repo, FakeVaultKeys(), VaultPasscodeGate(), 2_000)
        assertTrue(other.isUnlocked())
        assertTrue(VaultSession.isUnlocked())

        other.lock()
        assertFalse("locking on one screen locks everywhere", store.isUnlocked())
        assertNull(VaultSession.keyOrNull())
    }

    @Test
    fun `the key is dropped after two minutes in the background`() {
        store.setup("246810")
        val leftAt = 5_000_000L
        VaultSession.onBackground(leftAt)

        assertTrue("inside the grace period", VaultSession.isUnlocked(leftAt + 119_999))
        assertFalse("after two minutes", VaultSession.isUnlocked(leftAt + 120_000))
        assertNull("the key itself is gone", VaultSession.keyOrNull(leftAt + 120_001))
        assertFalse(store.isUnlocked())
    }

    @Test
    fun `coming back to a screen inside the grace period keeps the key`() {
        store.setup("246810")
        VaultSession.onBackground(1_000L)
        VaultSession.onForeground(50_000L)
        assertTrue(VaultSession.isUnlocked(50_000_000L))
        assertNotNull(VaultSession.keyOrNull(50_000_000L))
    }

    @Test
    fun `foreground cannot erase an expired background deadline`() {
        store.setup("246810")
        VaultSession.onBackground(1_000L)
        VaultSession.onForeground(121_000L)
        assertFalse(VaultSession.isUnlocked(121_000L))
        assertNull(VaultSession.keyOrNull(121_000L))
    }

    @Test
    fun `a locked vault has no key for anyone`() {
        store.setup("246810")
        store.lock()
        assertNull(VaultSession.keyOrNull())
        assertFalse(VaultSession.isUnlocked())
        assertThrows(VaultLockedException::class.java) {
            VaultPasswords.of(app).get("doc")
        }
    }
}
