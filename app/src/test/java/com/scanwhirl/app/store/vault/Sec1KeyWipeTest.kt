package com.scanwhirl.app.store.vault

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.scanwhirl.app.store.ScanRepository
import com.scanwhirl.app.store.backup.FullBackup
import com.scanwhirl.app.util.Durable
import com.scanwhirl.app.util.ImportFixtures
import com.scanwhirl.app.util.PdfEncryptor
import com.scanwhirl.app.util.PdfGenerator
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
 * SECURITY_JUDGE 1.3.0 finding 6 (LOW): caller-owned and derived key arrays are
 * wiped in finally. The probes fire exactly at the wipe point; no behaviour
 * changes, so the same suites still prove setup, unlock, backup and protect work.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class Sec1KeyWipeTest {

    private lateinit var app: Application
    private lateinit var repo: ScanRepository

    @Before
    fun setUp() {
        VaultSession.close()
        app = ApplicationProvider.getApplicationContext()
        repo = ScanRepository(app)
        File(app.filesDir, "vault2").deleteRecursively()
    }

    @After
    fun tearDown() {
        VaultSession.close()
        VaultStore.keyWipeProbe = null
        FullBackup.exportKeyWipeProbe = null
        PdfEncryptor.verificationKeyWipeProbe = null
        Durable.resetForTests()
        File(app.filesDir, "vault2").deleteRecursively()
    }

    private fun store(): VaultStore = VaultStore(
        app, repo, FakeVaultKeys(), VaultPasscodeGate(clock = { 0L }), 2_000
    )

    @Test(timeout = 30_000)
    fun `setup wipes its generated key and leaves the session usable`() {
        val wipes = mutableListOf<String>()
        VaultStore.keyWipeProbe = { wipes += it }
        val vault = store()
        vault.setup("246810")
        assertTrue("setup must wipe its generated key", wipes.contains("setup"))
        assertTrue("wiping the local copy must not wipe the session key", vault.isUnlocked())
    }

    @Test(timeout = 30_000)
    fun `setup wipes the key in finally even when a disk write fails`() {
        val wipes = mutableListOf<String>()
        VaultStore.keyWipeProbe = { wipes += it }
        Durable.beforeFileSync = { throw IOException("Fictional disk failure") }
        runCatching { store().setup("246810") }
        assertTrue("a failed setup must still wipe the key", wipes.contains("setup"))
    }

    @Test(timeout = 30_000)
    fun `passcode unlock wipes the unwrapped key`() {
        val vault = store()
        vault.setup("246810").also { vault.markConfirmationPassed() }
        val wipes = mutableListOf<String>()
        VaultStore.keyWipeProbe = { wipes += it }
        vault.lock()
        assertTrue(vault.unlockWithPasscode("246810").ok)
        assertTrue("the unwrapped key must be wiped after the session copies it", wipes.contains("passcode"))
        assertTrue(vault.isUnlocked())
    }

    @Test(timeout = 30_000)
    fun `backup export wipes the derived container key`() {
        var wiped = false
        FullBackup.exportKeyWipeProbe = { wiped = true }
        val target = File(app.cacheDir, "sec1-key-wipe-backup.swbackup")
        FullBackup(app).write(target, "fictional-password-123", FullBackup.Wrap.PASSWORD)
        assertTrue("the derived export key must be wiped", wiped)
        assertTrue("the backup must still be written", target.isFile && target.length() > 0)
    }

    @Test(timeout = 30_000)
    fun `pdf verification key is wiped in finally`() {
        val dir = File(app.cacheDir, "sec1-key-wipe-pdf").apply { mkdirs() }
        val jpg = File(dir, "a.jpg").apply { writeBytes(ImportFixtures.image(width = 200, height = 300)) }
        val src = File(dir, "src.pdf")
        PdfGenerator.imagesToPdf(listOf(jpg), src)
        var wiped = false
        PdfEncryptor.verificationKeyWipeProbe = { wiped = true }
        val dst = File(dir, "protected.pdf")
        PdfEncryptor.encrypt(src, dst, "pw1234")
        assertTrue("the verification key must be wiped", wiped)
        assertTrue("protection must still work", dst.isFile && dst.length() > 0)
    }
}
