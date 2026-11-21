package com.scanwhirl.app.store.vault

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.store.ScanRepository
import org.junit.After
import org.junit.Assert.assertEquals
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
    fun `a stale duplicate close does not wipe a second time`() {
        VaultSession.open(ByteArray(32) { 1 })
        var wipes = 0
        VaultSession.registerOpenCopyWipe { wipes++ }
        VaultSession.close()
        VaultSession.close() // another screen's stale close, after the key is already gone
        assertEquals("one wipe per session, even with a duplicate close", 1, wipes)
    }

    @Test
    fun `a fresh unlock waits for an in-flight close wipe before writing new copies`() {
        // What this proves: close() holds the shared lock across its wipe, so a concurrent open()
        // cannot slip in and have its new plaintext deleted by the old close's wipe. The latch in
        // the wipe hook makes the overlap deterministic: release it from another thread while
        // open() is already blocked on the same lock.
        VaultSession.open(ByteArray(32) { 2 })
        val fresh = File(app.cacheDir, "vault_open/fresh.pdf")
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        VaultSession.registerOpenCopyWipe {
            entered.countDown()
            proceed.await(3, TimeUnit.SECONDS)
            File(app.cacheDir, "vault_open").deleteRecursively()
        }
        val closer = Thread { VaultSession.close() }
        closer.start()
        assertTrue("the old close reached its wipe", entered.await(3, TimeUnit.SECONDS))
        val released = Thread { Thread.sleep(120); proceed.countDown() }
        released.start()
        VaultSession.open(ByteArray(32) { 3 })
        fresh.parentFile?.mkdirs()
        fresh.writeText("fresh plaintext")
        closer.join(3_000)
        released.join(3_000)
        assertTrue("the old wipe finished before the fresh copy was written", fresh.exists())
        assertTrue("the fresh key survives", VaultSession.isUnlocked())
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
